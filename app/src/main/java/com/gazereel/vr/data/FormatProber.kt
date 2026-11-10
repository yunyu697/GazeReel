package com.gazereel.vr.data

import android.content.Context
import android.os.SystemClock
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.RandomAccessSource
import com.gazereel.vr.core.SphericalMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * V2.7.6：播放前先去文件里问一句「你到底是什么」，而不是看名字猜。
 *
 * 走的是播放用的同一套 RandomAccessSource，所以本地 / SMB / WebDAV / HTTP 全都适用。
 * 只读文件头 512KB；如果 moov 不在头部（非 faststart 的 mp4），
 * 再沿着顶层 box 链跳到 moov 的位置读它的前 768KB ——
 * 旧版直接读「最后 1MB」，遇到 moov 很大（4K/7K 的 stco 表能有好几 MB）时
 * 正好错过 moov 开头的 stsd 与 sv3d，真机日志里那条 `w=0 h=0 来源=none` 就是这么来的。
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
    }

    suspend fun probe(ref: MediaRef): SphericalMetadata.Probe? = withContext(Dispatchers.IO) {
        var source: RandomAccessSource? = null
        val started = SystemClock.elapsedRealtime()
        try {
            val active = factory.create(ref)
            source = active
            val total = active.open(ref)

            val head = readAt(active, 0L, HEAD_BYTES)
            if (head.isEmpty()) return@withContext null

            val moov = if (SphericalMetadata.hasMoov(head)) null else readMoov(active, head, total)
            val probe = SphericalMetadata.parse(head, moov)
            RunLog.append(
                context, "fmt",
                "探测 ${ref.path.takeLast(40)} → ${probe.describe()} " +
                    "${SystemClock.elapsedRealtime() - started}ms"
            )
            probe
        } catch (t: Throwable) {
            RunLog.append(context, "fmt", "探测失败 ${t.javaClass.simpleName}: ${t.message?.take(80)}")
            null
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
     * mdat 可能有几十 GB，所以只能按 box 头里的 size 跳，不能顺序读。
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
