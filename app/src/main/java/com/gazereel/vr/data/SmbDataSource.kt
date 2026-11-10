package com.gazereel.vr.data

import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.RandomAccessSource
import com.hierynomus.msfscc.fileinformation.FileStandardInformation
import com.hierynomus.smbj.share.File
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * PRD 6.3 / NET-02~04 的 SMB2/SMB3 实现：
 * - 文件句柄做随机读，会话复用，seek 不重开连接；
 * - 1MB 分块、最多 12MB 的预读缓存（相当于 8~16MB 环形缓冲的分块实现）；
 * - 顺序播放时后台预取后续 4 块，seek 时按 generation 作废预取队列；
 * - 会话异常按指数退避重连，重建句柄后保持 position，从上次播放位置恢复。
 *
 * 所有 SMB 调用都在 IO 线程；海报/索引使用 MediaIndexer 的独立低优先级调用。
 */
class SmbRandomAccessSource(
    private val ref: MediaRef,
    private val credentials: SmbCredentials,
    private val pool: SmbSessionPool
) : RandomAccessSource {

    companion object {
        const val CHUNK_SIZE = 1 shl 20            // 1 MB
        const val MAX_CACHED_CHUNKS = 12          // 12 MB 上限
        const val PREFETCH_CHUNKS = 4
        const val MAX_RETRY = 3
    }

    private val ioLock = Any()
    private val cacheLock = Any()
    private val cache = HashMap<Long, ByteArray>()
    private val lru = ArrayDeque<Long>()
    // V2.7.5：预取协程里绝不能再让异常逃出去——真机就是这样整台退出 VR 的
    //（SMBRuntimeException: DiskShare has already been closed @ DefaultDispatcher-worker-7）。
    // 即便某处漏了 try/catch，异常处理器也只记一笔，不把进程带走。
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, t ->
            android.util.Log.w("GazeReel", "smb prefetch 已忽略异常: ${t.javaClass.simpleName}")
        }
    )
    private val prefetching = AtomicBoolean(false)

    private var file: File? = null
    private var position = 0L
    private var contentLength = -1L
    @Volatile private var generation = 0
    @Volatile private var closed = false

    /**
     * 读取失败与「文件真的读完了」是两件事，必须分开：
     * 前者要让 ExoPlayer 看到 IOException（界面能提示、能重连），
     * 后者才是 -1。旧实现一律返回 -1，共享一断就静悄悄当成播完。
     */
    private class Loaded(val data: ByteArray?, val error: Throwable? = null)

    override fun open(ref: MediaRef): Long {
        return try {
            withRetry {
                synchronized(ioLock) {
                    file = pool.openFile(ref, credentials)
                    contentLength = fileLength(file)
                    position = 0L
                    contentLength
                }
            }
        } catch (t: Throwable) {
            // open() 跑在 ExoPlayer 的加载线程上，RuntimeException 会直接杀进程。
            // 统一转成 IOException，让播放器按「片源打不开」处理。
            throw IOException("SMB 打开失败：${t.javaClass.simpleName}", t)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (closed) return -1
        if (length == 0) return 0
        val chunkIndex = position / CHUNK_SIZE
        val inChunk = (position % CHUNK_SIZE).toInt()
        val loaded = getChunkInternal(chunkIndex)
        val chunk = loaded.data
        if (chunk == null) {
            loaded.error?.let {
                throw IOException("SMB 读取失败：${it.javaClass.simpleName}", it)
            }
            // 只有真的走到文件末尾才算 EOF。位置还没到长度就断，说明是连接问题，
            // 必须报错——否则播放器会当成「播完了」，于是播几秒自动跳下一个。
            if (contentLength >= 0L && position >= contentLength) return -1
            throw IOException("SMB 提前读到末尾 pos=$position / len=$contentLength")
        }
        val available = chunk.size - inChunk
        if (available <= 0) return -1
        val count = minOf(length, available)
        System.arraycopy(chunk, inChunk, buffer, offset, count)
        position += count
        schedulePrefetch(chunkIndex + 1)
        return count
    }

    override fun seekTo(position: Long) {
        // 拖动进度条：清空预读缓冲并作废在途预取，避免用旧数据喂解码器。
        synchronized(cacheLock) {
            cache.clear()
            lru.clear()
        }
        generation++
        this.position = position.coerceAtLeast(0L)
    }

    override fun close() {
        if (closed) return
        closed = true
        generation++
        scope.cancel()
        synchronized(ioLock) {
            runCatching { file?.close() }
            file = null
        }
        synchronized(cacheLock) { cache.clear(); lru.clear() }
    }

    private fun getChunk(index: Long): ByteArray? = getChunkInternal(index).data

    private fun getChunkInternal(index: Long): Loaded {
        synchronized(cacheLock) {
            cache[index]?.let {
                touch(index)
                return Loaded(it)
            }
        }
        val start = index * CHUNK_SIZE
        if (contentLength >= 0L && start >= contentLength) return Loaded(null)
        if (closed) return Loaded(null)
        val (data, error) = loadChunk(start)
        if (data == null) return Loaded(null, error)
        synchronized(cacheLock) {
            cache[index] = data
            touch(index)
            while (lru.size > MAX_CACHED_CHUNKS) {
                cache.remove(lru.removeFirst())
            }
        }
        return Loaded(data)
    }

    /** @return 数据 + 失败原因；失败原因非空说明这次是「读不到」而不是「读完了」。 */
    private fun loadChunk(start: Long): Pair<ByteArray?, Throwable?> {
        if (closed) return null to IOException("片源已关闭")
        return try {
            withRetry {
                synchronized(ioLock) {
                    // 句柄不可用绝不能当成 EOF：抛出去让 withRetry 重连，
                    // 重连失败就变成 IOException 让播放器报错。
                    // 旧实现在这里 return null，于是「读不到」被上层当成了「播完了」。
                    val handle = file ?: throw IOException("SMB 文件句柄未就绪")
                    val buffer = ByteArray(CHUNK_SIZE)
                    var total = 0
                    var eof = false
                    while (total < CHUNK_SIZE) {
                        // smbj 0.13: File.read(dest, fileOffset, destOffset, length) -> 实际字节数或 -1
                        val n = handle.read(buffer, start + total, total, CHUNK_SIZE - total)
                        if (n < 0) {
                            eof = true
                            break
                        }
                        if (n == 0) throw IOException("SMB 读取返回 0 字节")
                        total += n
                    }
                    when {
                        total == CHUNK_SIZE -> buffer
                        total > 0 -> buffer.copyOf(total)
                        eof -> null
                        else -> null
                    }
                }
            } to null
        } catch (t: Throwable) {
            // V2.7.5：这里必须吞掉。预取跑在独立协程里，异常抛出去会直接带走进程
            //（真机：DiskShare has already been closed @ DefaultDispatcher-worker-7）。
            // 前台读会拿到 error 并转成 IOException，而不是静默结束播放。
            null to t
        }
    }

    private fun schedulePrefetch(fromIndex: Long) {
        // TODO(V1): 预取改用独立 SMB 会话/文件句柄，避免与前台读争用同一把 ioLock。
        if (closed || !prefetching.compareAndSet(false, true)) return
        val gen = generation
        scope.launch {
            try {
                for (i in 0 until PREFETCH_CHUNKS) {
                    if (closed || gen != generation) break
                    val index = fromIndex + i
                    val cached = synchronized(cacheLock) { cache.containsKey(index) }
                    // 预取失败无所谓：下一块读的时候前台会重试并如实报错。
                    // 唯一不能做的是把异常扔出这个协程。
                    if (!cached) runCatching { getChunk(index) }
                }
            } catch (t: Throwable) {
                android.util.Log.w("GazeReel", "预取中断: ${t.javaClass.simpleName}")
            } finally {
                prefetching.set(false)
            }
        }
    }

    private fun touch(index: Long) {
        lru.remove(index)
        lru.addLast(index)
    }

    private fun <T> withRetry(operation: () -> T): T {
        var attempt = 0
        while (true) {
            try {
                return operation()
            } catch (t: Throwable) {
                if (closed || attempt >= MAX_RETRY) throw t
                Thread.sleep((200L shl attempt).coerceAtMost(2_000L))
                attempt++
                recover()
            }
        }
    }

    private fun recover() {
        synchronized(ioLock) {
            runCatching { file?.close() }
            file = null
        }
        // 只丢掉自己那条共享，不要 invalidate() 整个池：
        // 那会把别的还在读的片源的连接一起关掉（V2.7.5 的崩溃正是这么来的）。
        runCatching { pool.evictShare(ref, credentials) }
        synchronized(ioLock) {
            // 位置不重置：重连成功后从上次播放位置继续。
            runCatching {
                file = pool.openFile(ref, credentials)
                contentLength = fileLength(file)
            }
        }
    }

    /**
     * smbj 0.13.0 的 File 没有 getLength()；文件长度来自
     * DiskEntry.getFileInformation(FileStandardInformation.class).getEndOfFile()。
     */
    private fun fileLength(f: File?): Long =
        f?.getFileInformation(FileStandardInformation::class.java)?.endOfFile ?: -1L
}
