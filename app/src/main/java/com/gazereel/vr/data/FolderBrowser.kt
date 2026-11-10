package com.gazereel.vr.data

import com.gazereel.vr.core.SourceType
import com.gazereel.vr.data.db.SourceEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL

/** 一行可见的文件夹；cursor 是该协议下「进入这一层」所需的定位串。 */
data class FolderChoice(val name: String, val cursor: String, val detail: String)

/**
 * 浏览会话的当前状态。
 * - address：选「就用这一层」时真正写进 source.address 的规范地址；
 * - root：只允许在它下面上移，避免退到站外；
 * - cursor：协议内部定位（SMB 是 share 内相对路径，HTTP/WebDAV 是 URL，LOCAL 是绝对路径）。
 */
data class BrowseState(
    val type: SourceType,
    val address: String,
    val root: String,
    val cursor: String,
    val crumb: String,
    val displayName: String,
    val folders: List<FolderChoice>,
    val videoCount: Int,
    val canChoose: Boolean,
    val canGoUp: Boolean,
    val shareLevel: Boolean = false,
    val note: String = ""
)

/**
 * V2.6：把「连上之后让用户选文件夹」做成与协议无关的单一入口。
 *
 * 关键约束：浏览与扫描共用 MediaIndexer.list()，因此界面里看得见的那一层，
 * 就是保存之后会扫描的那一层；用户不需要也不能够再手敲路径。
 */
class FolderBrowser(
    private val indexer: MediaIndexer,
    private val pool: SmbSessionPool
) {

    /** 新增：按用户填的设备地址打开第一层；WebDAV / HTTP 会自动试常见挂载点。 */
    suspend fun root(
        type: SourceType,
        rawAddress: String,
        credentials: SmbCredentials
    ): BrowseState = withContext(Dispatchers.IO) {
        val normalized = SourceAddress.normalize(type, rawAddress)
        if (type != SourceType.DLNA && type != SourceType.LOCAL && normalized.isBlank()) {
            throw IOException("先填设备地址，例如 192.168.1.10")
        }
        when (type) {
            SourceType.WEBDAV, SourceType.HTTP -> {
                var lastError: String? = null
                var emptyState: BrowseState? = null
                for (candidate in SourceAddress.httpCandidates(normalized, type)) {
                    val state = runCatching {
                        build(type, candidate, candidate, candidate, credentials)
                    }.getOrElse { error ->
                        lastError = error.message
                        null
                    }
                    if (state != null) {
                        if (state.videoCount > 0 || state.folders.isNotEmpty()) return@withContext state
                        if (emptyState == null) emptyState = state
                    }
                }
                emptyState?.copy(note = "这一层没有可直接浏览的文件夹或视频，可返回改填完整地址。")
                    ?: throw IOException(
                        lastError ?: "连不上这个地址：检查 IP、端口，以及手机与设备是否在同一个 Wi-Fi"
                    )
            }
            SourceType.FTP -> throw IOException("FTP / SFTP 的随机读与列举排在阶段三")
            else -> at(type, normalized, credentials)
        }
    }

    /** 编辑：从已保存的地址原样打开，用户可以从当前层往上退回设备 / 共享层。 */
    suspend fun at(
        type: SourceType,
        savedAddress: String,
        credentials: SmbCredentials
    ): BrowseState = withContext(Dispatchers.IO) {
        when (type) {
            SourceType.SMB -> {
                val (host, share, folder) = parseUncFull(savedAddress)
                if (share.isBlank()) atShares("\\\\$host\\", credentials)
                else build(
                    SourceType.SMB, savedAddress, "\\\\$host\\$share", folder, credentials
                )
            }
            SourceType.DLNA -> if (savedAddress.equals("auto", true) ||
                !savedAddress.startsWith("http", true)
            ) atDlnaServers()
            else build(
                SourceType.DLNA, savedAddress, savedAddress, savedAddress, credentials
            ).copy(canGoUp = true)
            else -> build(type, savedAddress, savedAddress, savedAddress, credentials)
        }
    }

    /** 点进一个文件夹。 */
    suspend fun enter(
        state: BrowseState,
        choice: FolderChoice,
        credentials: SmbCredentials
    ): BrowseState = withContext(Dispatchers.IO) {
        when (state.type) {
            SourceType.DLNA -> build(
                SourceType.DLNA, choice.cursor, choice.cursor, choice.cursor, credentials
            ).copy(displayName = choice.name, crumb = choice.name, canGoUp = true)
            SourceType.SMB -> {
                if (state.shareLevel) {
                    val host = parseUncFull(state.address).first
                    val address = "\\\\$host\\${choice.cursor}"
                    build(SourceType.SMB, address, address, "", credentials)
                } else {
                    val (host, share, _) = parseUncFull(state.address)
                    val cursor = choice.cursor
                    val address = "\\\\$host\\$share" +
                        if (cursor.isBlank()) "" else "\\$cursor"
                    build(SourceType.SMB, address, state.root, cursor, credentials)
                }
            }
            else -> build(
                state.type, choice.cursor, state.root, choice.cursor, credentials
            )
        }
    }

    /** 返回上一层；SMB 从共享根再上一层会回到共享列表。 */
    suspend fun up(
        state: BrowseState,
        credentials: SmbCredentials
    ): BrowseState = withContext(Dispatchers.IO) {
        when (state.type) {
            SourceType.DLNA -> atDlnaServers()
            SourceType.SMB -> {
                val (host, share, folder) = parseUncFull(state.address)
                if (folder.isBlank()) atShares("\\\\$host\\", credentials)
                else {
                    val parent = folder.substringBeforeLast('\\', "")
                    val address = "\\\\$host\\$share" +
                        if (parent.isBlank()) "" else "\\$parent"
                    build(SourceType.SMB, address, state.root, parent, credentials)
                }
            }
            // V2.7：手机视频没有上级目录，原地不动。
            SourceType.LOCAL -> state
            else -> {
                val url = runCatching { URL(state.cursor) }.getOrNull()
                    ?: return@withContext state
                val path = url.path.trimEnd('/')
                if (path.isEmpty() || path == "/") return@withContext state
                val parentPath = path.substringBeforeLast('/', "").ifEmpty { "/" }
                    .trimEnd('/') + "/"
                val rootPath = runCatching { URL(state.root).path.trimEnd('/') }
                    .getOrDefault("")
                if (parentPath.trimEnd('/').length < rootPath.length) return@withContext state
                val parent = "${url.protocol}://${url.authority}$parentPath"
                build(state.type, parent, state.root, parent, credentials)
            }
        }
    }

    /** 列目录并组装一层浏览状态；失败给的是能照着做的错误，不是协议栈异常。 */
    private fun build(
        type: SourceType,
        address: String,
        root: String,
        cursor: String,
        credentials: SmbCredentials
    ): BrowseState {
        val source = SourceEntity(type = type.name, address = address, displayName = "")
        val entries = try {
            indexer.list(source, credentials)
        } catch (error: Exception) {
            val reason = when (type) {
                SourceType.SMB -> "连不上这台设备：检查 IP、Wi-Fi 和账号密码"
                SourceType.WEBDAV -> "这个地址没有响应 WebDAV：可能需要端口或挂载路径"
                SourceType.HTTP -> "这个地址没有返回目录列表：可试填完整 URL"
                SourceType.DLNA -> "媒体服务器没有响应：确认服务已开启"
                else -> "打不开这个文件夹"
            }
            throw IOException("$reason（${error.message?.take(90).orEmpty()}）", error)
        }
        val folders = entries.filter { it.isDirectory }.map { entry ->
            FolderChoice(
                name = entry.title.ifBlank { entry.path.substringAfterLast('/') },
                cursor = entry.path,
                detail = "文件夹"
            )
        }.sortedBy { it.name.lowercase() }
        val videos = entries.count {
            // V2.7：手机视频的 path 是 content:// URI，没有扩展名可判。
            !it.isDirectory && (it.path.startsWith("content://") || hasVideoExtension(it.path))
        }
        val canGoUp = when (type) {
            SourceType.SMB -> cursor.isNotBlank() || parseUncFull(address).second.isNotBlank()
            // V2.7：手机视频是一个整体索引，没有「上一层」可退。
            SourceType.LOCAL -> false
            SourceType.DLNA -> cursor.isNotBlank()
            else -> cursor.trimEnd('/') != root.trimEnd('/')
        }
        return BrowseState(
            type = type,
            address = address,
            root = root,
            cursor = cursor,
            crumb = SourceAddress.displayName(type, address),
            displayName = SourceAddress.displayName(type, address),
            folders = folders,
            videoCount = videos,
            canChoose = true,
            canGoUp = canGoUp
        )
    }

    private fun atShares(address: String, credentials: SmbCredentials): BrowseState {
        val host = parseUncFull(address).first
        if (host.isBlank()) throw IOException("设备地址不能为空")
        val shares = runCatching { pool.probeShares(host, credentials) }
            .getOrElse { throw IOException("连不上 $host：检查 IP、Wi-Fi 和账号密码", it) }
        return BrowseState(
            type = SourceType.SMB,
            address = "\\\\$host\\",
            root = "\\\\$host\\",
            cursor = "",
            crumb = "$host · 选择共享",
            displayName = host,
            folders = shares.map { FolderChoice(it, it, "共享") },
            videoCount = 0,
            canChoose = false,
            canGoUp = false,
            shareLevel = true,
            note = if (shares.isEmpty()) {
                "没找到共享名。可以回到地址栏填 主机\\共享名（例如 192.168.1.10\\VR）再连一次。"
            } else "这是设备上的共享，点进去再选文件夹。"
        )
    }

    private fun atDlnaServers(): BrowseState {
        val servers = runCatching { indexer.dlnaServers() }.getOrDefault(emptyList())
        return BrowseState(
            type = SourceType.DLNA,
            address = "auto",
            root = "auto",
            cursor = "",
            crumb = "局域网 · 选择媒体服务器",
            displayName = "DLNA / UPnP（自动发现）",
            folders = servers.map { FolderChoice(it.label, it.controlUrl, "媒体服务器") },
            videoCount = 0,
            canChoose = false,
            canGoUp = false,
            note = if (servers.isEmpty()) {
                "没发现服务器：确认媒体服务器开着、手机与它在同一 Wi-Fi，再试一次。"
            } else "选一台设备，就能看到它提供的全部视频。"
        )
    }
}
