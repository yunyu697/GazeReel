package com.gazereel.vr.data

import android.content.Context
import android.os.SystemClock
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.RandomAccessSource
import com.gazereel.vr.core.SphericalMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V2.7.6：播放前先去文件里问一句「你到底是什么」，而不是看名字猜。
 *
 * 走的是播放用的同一套 RandomAccessSource，所以本地 / SMB / WebDAV / HTTP 全都适用。
 * 只读文件头 512KB；如果 moov 不在头部（非 faststart 的 mp4），
 * 再沿顶层 box 链跳到 moov 的位置读它的前 768KB ——
 * 旧版直接读「最后 1MB」，遇到 moov 很大时正好错过 moov 开头的 stsd 与 sv3d。
 *
 * V2.7.9 起播卡顿优化（调用方在 VrCoordinator，未改动）：
 * - 按 mediaKey 做内存 LRU 缓存，**含失败结果**，坏片不会被每次重探；
 * - 探测放到独立守护线程池，头与 moov 各开一个数据源**并发**读，
 *   总时延 ≈ max(头, moov) 而非两者相加；
 * - 用 invokeAll(timeout) 做**真正的墙钟上限**（2.5s，原调用方的 8s 太长）：
 *   withTimeoutOrNull 只能取消可挂起协程，对 smbj 这种同步阻塞 read 无效，
 *   这里到点直接 cancel 掉没跑完的任务，调用方最多等 2.5s 且拿到 null 快速退化。
 *
 * 探测失败一律返回 null，让上层退回宽高比与文件名，绝不因此拦住播放。
 */
class FormatProber(
    private val factory: RandomAccessSourceFactory,
    private val context: Context
) {
    companion object {
        private const val HEAD_BYTES = 512 * 1024
        private const val MOOV_BYTES = 768 * 1024
        private const val MAX_BOX_HOPS = 24
        /** 起播前探测的墙钟上限：2.5s（原调用方给到 8s，起播会被拖住）。 */
        private const val PROBE_TIMEOUT_MS = 2_500L
        /** 内存 LRU 容量：按 mediaKey 缓存探测结果，含失败(null)。 */
        private const val CACHE_MAX = 64

        // 探测独占的守护线程池：卡住的 SMB 同步 read 只占后台线程，
        // 不会占住调用方的加载线程；invokeAll 到点 cancel 未完成任务。
        private val io = Executors.newCachedThreadPool { r ->
            Thread(r, "fmt-probe").apply { setDaemon(true) }
        }
    }

    // accessOrder=true：get() 会把命中的 key 挪到最新，实现 LRU。
    private val cacheLock = Any()
    private val cache = LinkedHashMap<String, SphericalMetadata.Probe?>(CACHE_MAX, 0.75f, true)

    suspend fun probe(ref: MediaRef): SphericalMetadata.Probe? {
        val key = ref.mediaKey
        // 命中缓存（含失败结果）立刻返回，连探测任务都不提交，调用方不被阻塞。
        var hit = false
        var cached: SphericalMetadata.Probe? = null
        synchronized(cacheLock) {
            if (cache.containsKey(key)) {
                hit = true
                cached = cache[key]      // get() 顺带刷新 LRU 顺序
            }
        }
        if (hit) {
            RunLog.append(
                context, "fmt",
                "探测命中缓存 $key → ${cached?.describe() ?: "失败(null)"}"
            )
            return cached
        }

        val result = probeBounded(ref, key)
        synchronized(cacheLock) {
            cache[key] = result
            while (cache.size > CACHE_MAX) {
                cache.remove(cache.keys.iterator().next())
            }
        }
        return result
    }

    /** 带墙钟上限的探测：超时/异常一律快速退化成 null，绝不长时间阻塞调用方。 */
    private suspend fun probeBounded(ref: MediaRef, key: String): SphericalMetadata.Probe? =
        withContext(Dispatchers.IO) {
            try {
                val started = SystemClock.elapsedRealtime()
                // 头与 moov 各开一个独立数据源并发读；两个任务都由 invokeAll 限时看管。
                val headTask: Callable<ByteArray?> = Callable { probeHead(ref) }
                val moovTask: Callable<ByteArray?> = Callable { probeMoov(ref) }
                val futures = io.invokeAll(
                    listOf(headTask, moovTask),
                    PROBE_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS
                )
                // 被 cancel 的任务 get() 会抛 CancellationException，runCatching 兜住当失败。
                val head = futures.getOrNull(0)?.let { runCatching { it.get() }.getOrNull() }
                val moov = futures.getOrNull(1)?.let { runCatching { it.get() }.getOrNull() }
                if (head == null || head.isEmpty()) {
                    RunLog.append(context, "fmt", "探测超时/无数据，退化 $key")
                    return@withContext null
                }
                // head 里已含 moov（faststart）就别用并发读回的 moov，避免把 mdat 尾巴当证据。
                val tail = if (SphericalMetadata.hasMoov(head)) null else moov
                val result = SphericalMetadata.parse(head, tail)
                RunLog.append(
                    context, "fmt",
                    "探测 ${ref.path.takeLast(40)} → ${result.describe()} " +
                        "${SystemClock.elapsedRealtime() - started}ms"
                )
                result
            } catch (t: Throwable) {
                RunLog.append(
                    context, "fmt",
                    "探测失败 ${t.javaClass.simpleName}: ${t.message?.take(80)}"
                )
                null
            }
        }

    /** 头任务：自己的数据源，读 512KB 文件头。 */
    private fun probeHead(ref: MediaRef): ByteArray? {
        var source: RandomAccessSource? = null
        return try {
            val active = factory.create(ref)
            source = active
            active.open(ref)
            readAt(active, 0L, HEAD_BYTES)
        } finally {
            runCatching { source?.close() }
        }
    }

    /**
     * moov 任务：自己的数据源，先读一份头供 box 链复用（沿用原来的 box 链逻辑，
     * 不改成「读文件尾」，避免大 moov 时错过 stsd/sv3d），再跳到 moov 读前段。
     */
    private fun probeMoov(ref: MediaRef): ByteArray? {
        var source: RandomAccessSource? = null
        return try {
            val active = factory.create(ref)
            source = active
            val total = active.open(ref)
            val head = readAt(active, 0L, HEAD_BYTES)
            if (head.isEmpty()) null else readMoov(active, head, total)
        } finally {
            runCatching { source?.close() }
        }
    }

    private fun readAt(source: RandomAccessSource, position: Long, size: Int): ByteArray {
        if (position > 0L) runCatching { source.seekTo(position) }
        val buffer = ByteArray(size)
        var total = 0
        while (total < size) {
            val n = source.read(buffer, total, size - total)
            if (n <= 0) break
            total += n
        }
        return if (total == size) buffer else buffer.copyOf(total)
    }

    /**
     * 沿顶层 box 链（ftyp → free/mdat → moov）跳到 moov 并读它的前段。
     * mdat 可能有几十 GB，所以只能按 box 头里的 size 跳，不能顺序读；
     * offset 落在已知 head 内时直接用 head，避免每跳一次都触发 1MB 网络读。
     */
    private fun readMoov(source: RandomAccessSource, head: ByteArray, total: Long): ByteArray? {
        var offset = 0L
        var hops = 0
        while (hops++ < MAX_BOX_HOPS) {
            if (total > 0L && offset >= total) return null
            val header = if (offset + 16 <= head.size) {
                head.copyOfRange(offset.toInt(), offset.toInt() + 16)
            } else {
                readAt(source, offset, 16)
            }
            if (header.size < 8) return null

            val type = String(header, 4, 4, Charsets.ISO_8859_1)
            val size = boxSize(header)
            if (type == "moov") {
                val want = if (size in 8L..MOOV_BYTES.toLong()) size.toInt() else MOOV_BYTES
                return readAt(source, offset, want)
            }
            // size == 0 表示这个 box 一直延伸到文件末尾，后面不会再有别的 box。
            if (size < 8L) return null
            offset += size
        }
        return null
    }

    private fun boxSize(header: ByteArray): Long {
        var size = ((header[0].toLong() and 0xFF) shl 24) or
            ((header[1].toLong() and 0xFF) shl 16) or
            ((header[2].toLong() and 0xFF) shl 8) or
            (header[3].toLong() and 0xFF)
        // size == 1 表示真正的长度在紧随其后的 8 字节里（largesize）。
        if (size == 1L && header.size >= 16) {
            size = 0L
            for (i in 8..15) size = (size shl 8) or (header[i].toLong() and 0xFF)
        }
        return size
    }
}
