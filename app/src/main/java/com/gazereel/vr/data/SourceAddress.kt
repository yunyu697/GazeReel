package com.gazereel.vr.data

import android.os.Environment
import com.gazereel.vr.core.SourceType
import java.io.File
import java.net.URL
import java.net.URLDecoder

/**
 * V2.6「机器的语法不该让用户输入」。
 *
 * 旧的添加片库要求用户自己写 `\\192.168.1.10\VR` 或 `http://192.168.1.10:8080/dav/`：
 * 协议头、正反斜杠、共享名、文件夹名都是程序内部约定，却全靠用户回忆和手敲。
 * 这里把输入收敛成「设备地址」一个概念：用户只填 IP / 主机名（可带端口），
 * 协议头、分隔符、默认端口、常见挂载点由程序补齐与探测；同时仍接受粘贴完整 UNC / URL。
 */
object SourceAddress {

    private const val UNC = "\\\\"
    private const val SEP = "\\"

    /** 把用户填的任何一种写法归一成可存进 Room 的 canonical address。 */
    fun normalize(type: SourceType, raw: String): String = when (type) {
        SourceType.SMB -> smb(raw)
        SourceType.WEBDAV, SourceType.HTTP -> http(raw)
        SourceType.DLNA -> "auto"
        // V2.7：空字符串表示「整机全部视频」；填了就是 MediaStore 里的相对目录。
        SourceType.LOCAL -> raw.trim()
        SourceType.FTP -> raw.trim()
    }

    /** 192.168.1.10 / 192.168.1.10\VR\Movies / \\host\share / smb://host/share → \\host\share\Movies */
    fun smb(raw: String): String {
        val clean = raw.trim()
            .removePrefix("smb://")
            .replace('/', '\\')
            .trim('\\', ' ', '\t')
        if (clean.isBlank()) return ""
        val parts = clean.split(SEP).filter { it.isNotBlank() }
        return UNC + parts.joinToString(SEP)
    }

    /** 192.168.1.10:8080/dav → http://192.168.1.10:8080/dav/；完整 URL 原样保留并补尾斜杠。 */
    fun http(raw: String): String {
        var value = raw.trim()
        if (value.isBlank()) return ""
        if (value.startsWith("smb://", true)) value = "http://" + value.substring(6)
        if (!value.contains("://")) value = "http://$value"
        val queryAt = value.indexOfFirst { it == '?' || it == '#' }
        val path = if (queryAt >= 0) value.substring(0, queryAt) else value
        val tail = if (queryAt >= 0) value.substring(queryAt) else ""
        if (path.isBlank()) return ""
        return path.trimEnd('/') + "/" + tail
    }

    fun localDefault(): String = runCatching {
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES).absolutePath
    }.getOrDefault("/sdcard/Movies")

    /**
     * 只填 IP 时，程序替用户试常见挂载点。第一个能列目录的就作为浏览起点；
     * 全部失败时在界面上明确告诉用户「可以粘贴完整地址」，而不是让他猜路径。
     */
    fun httpCandidates(normalized: String, type: SourceType): List<String> {
        if (normalized.isBlank()) return emptyList()
        val origin = runCatching {
            val url = URL(normalized)
            "${url.protocol}://${url.authority}"
        }.getOrDefault(normalized.trimEnd('/'))
        val names = if (type == SourceType.WEBDAV) {
            listOf("dav", "webdav", "remote.php/dav/files", "share", "remote.php/webdav", "Dav")
        } else {
            listOf("videos", "video", "media", "Movies", "share", "VR")
        }
        return (listOf(normalized) + names.map { "$origin/$it/" }).distinct()
    }

    /** 片库卡片的默认名字；用户仍可在「改」里重命名。 */
    fun displayName(type: SourceType, address: String): String = when (type) {
        SourceType.SMB -> {
            // V2.29：旧版在层级 >= 3 时只留「首 · 尾」，把共享名和中间层级
            // 全丢了 —— 用户在同一台 NAS 的两个文件夹建片库，卡片会撞名。
            // 现在按原顺序把 host / share / 各层文件夹都写出来（中文照原样保留）。
            val parts = address.trimStart('\\').split(SEP).filter { it.isNotBlank() }
            parts.joinToString(" · ").ifBlank { "网络共享" }
        }
        SourceType.WEBDAV, SourceType.HTTP -> runCatching {
            val url = URL(address)
            val last = url.path.trimEnd('/').substringAfterLast('/')
            val decoded = runCatching { URLDecoder.decode(last, "UTF-8") }.getOrDefault(last)
            if (decoded.isBlank()) url.host else "${url.host} · $decoded"
        }.getOrDefault(address)
        SourceType.DLNA -> "DLNA / UPnP（自动发现）"
        SourceType.LOCAL -> if (address.isBlank()) "手机里的视频"
        else File(address).name.ifBlank { "手机里的视频" }
        SourceType.FTP -> address
    }

    /** 输入框 hint：只说「设备地址」，不再展示协议语法。 */
    fun hint(type: SourceType?): String = when (type) {
        SourceType.SMB -> "例如 192.168.1.10 或 NAS 的名称"
        SourceType.WEBDAV -> "例如 192.168.1.10:5005"
        SourceType.HTTP -> "例如 192.168.1.10:8080"
        else -> "例如 192.168.1.10"
    }

    /** 输入框下方的一句人话：把「不用写什么」说清楚。 */
    fun help(type: SourceType?): String = when (type) {
        SourceType.SMB ->
            "只填设备地址就行：反斜杠、共享名和文件夹名都不用写，连上后从列表里点选。也可以直接粘贴电脑上的 \\\\主机\\共享。"
        SourceType.WEBDAV, SourceType.HTTP ->
            "只填设备地址就行：http:// 和后面的路径都不用写，连上后从列表里点选。服务器需要特殊挂载路径时，直接粘贴完整 URL。"
        SourceType.DLNA -> "DLNA 会自动在局域网里找媒体服务器，不需要填地址。"
        SourceType.LOCAL -> "不用填地址：程序直接读系统里的视频索引，点一下就能用。"
        else -> "FTP / SFTP 的随机读与列举排在阶段三，本期请用 SMB / WebDAV / HTTP / DLNA。"
    }
}
