package com.gazereel.vr.core

import android.net.Uri

enum class SourceType { SMB, LOCAL, WEBDAV, FTP, DLNA, HTTP }

/** 上层只传这个引用，不感知片源物理位置（PRD 5.1）。 */
data class MediaRef(
    val sourceId: Long,
    val type: SourceType,
    val host: String = "",
    val share: String = "",
    val path: String,
    val credentialRef: String = "",
    val mediaId: Long = 0L
) {
    /** 供 Media3 DataSpec / 日志使用的自定义 scheme URI。 */
    fun toUri(): Uri = Uri.Builder()
        .scheme("vrplayer")
        .authority(type.name.lowercase())
        .appendQueryParameter("source", sourceId.toString())
        .appendQueryParameter("path", path)
        .build()

    /** format_override / play_progress 的稳定键（PRD 6.2）。 */
    val mediaKey: String get() = "$sourceId:$path"
}

/**
 * PRD 8.1「MediaDataSource」：open / read / close / seek / range。
 * SMB 与本地/OTG 各实现一份，Media3 DataSource 只做桥接。
 */
interface RandomAccessSource {
    /** @return 内容总长度（字节）；未知时返回 -1。 */
    fun open(ref: MediaRef): Long

    /** 顺序读；@return 实际字节数，-1 表示 EOF。 */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    /** 随机定位；实现方应清空预读缓冲。 */
    fun seekTo(position: Long)

    fun close()
}
