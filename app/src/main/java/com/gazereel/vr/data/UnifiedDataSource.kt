@file:Suppress("UnsafeOptInUsageError")

package com.gazereel.vr.data

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.gazereel.vr.core.RandomAccessSource
import java.io.IOException

/**
 * PRD 6.3：把内部 RandomAccessSource 桥接成 Media3 DataSource。
 * ExoPlayer 通过 ProgressiveMediaSource 打开 vrplayer:// URI，
 * open/read/close 与按区间 seek 都由具体实现承担，解码层不感知 SMB 或本地。
 */
class UnifiedDataSource(
    private val sourceFactory: RandomAccessSourceFactory
) : BaseDataSource(true) {

    private var source: RandomAccessSource? = null
    private var uri: Uri? = null
    private var opened = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val ref = MediaRefRegistry.resolve(dataSpec.uri)
            ?: throw IOException("未注册的片源：${dataSpec.uri}")
        // V2.35：同一个 DataSource 可能被再次 open（读失败重试、换区间）。
        // 旧版直接覆盖字段，上一个 source 的预读缓存与两份 SMB 句柄就没人关了。
        source?.let { runCatching { it.close() } }
        source = null
        val newSource = sourceFactory.create(ref)
        val total = newSource.open(ref)
        if (dataSpec.position > 0) newSource.seekTo(dataSpec.position)
        source = newSource
        uri = dataSpec.uri
        opened = true
        transferStarted(dataSpec)
        // DataSource.open 需返回「从当前位置起还可读取的字节数」。
        val remaining = if (total >= 0L) (total - dataSpec.position).coerceAtLeast(0L) else total
        return if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val active = source ?: throw IOException("DataSource 未打开")
        val count = active.read(buffer, offset, length)
        if (count > 0) {
            bytesTransferred(count)
            return count
        }
        return C.RESULT_END_OF_INPUT
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (!opened) return
        try {
            source?.close()
        } finally {
            source = null
            uri = null
            opened = false
            transferEnded()
        }
    }
}

class UnifiedDataSourceFactory(
    private val sourceFactory: RandomAccessSourceFactory
) : DataSource.Factory {
    override fun createDataSource(): DataSource = UnifiedDataSource(sourceFactory)
}
