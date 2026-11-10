package com.gazereel.vr.data

import com.gazereel.vr.core.MediaRef
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.hierynomus.smbj.share.Share
import java.util.EnumSet
import java.util.concurrent.TimeUnit

data class SmbCredentials(val user: String, val password: String, val domain: String = "")

/**
 * 共享层的结论。
 * - names：能给用户点选的共享名（已滤掉 `IPC$` 与以 `$` 结尾的管理共享）；
 * - authoritative：true 表示这是设备自己报的完整列表 —— 空就是真的空；
 * - reason：拿不到完整列表时的原因，界面要照实写出来，不能让用户对着
 *   「这一层没有可再展开的文件夹」猜。
 */
data class ShareListing(
    val names: List<String>,
    val authoritative: Boolean,
    val reason: String = ""
)

/**
 * V2.6：\\host\share\Movies\a.mp4 → host / share / 相对文件夹。
 * 旧版 parseUnc 丢掉了 share 之后的层级，导致「选了子文件夹」也无法扫描到那一层。
 */
fun parseUncFull(address: String): Triple<String, String, String> {
    val clean = address.trim().replace('/', '\\').trimStart('\\')
    val parts = clean.split('\\').filter { it.isNotEmpty() }
    return Triple(
        parts.getOrElse(0) { "" },
        parts.getOrElse(1) { "" },
        parts.drop(2).joinToString("\\")
    )
}

/** 保留两段式调用点（VrCoordinator 构造 MediaRef 只需要 host / share）。 */
fun parseUnc(address: String): Pair<String, String> {
    val (host, share, _) = parseUncFull(address)
    return host to share
}

/**
 * PRD 6.3：会话复用，避免每次 seek 重新打开连接。
 * 断线时由 SmbRandomAccessSource 调用 invalidate() 后按指数退避重建。
 *
 * V2.6：把「会话」与「树连接」拆开缓存。只填 IP 时需要先列出设备上的共享，
 * 而 smbj 0.13 没有公开的 SRVSVC NetShareEnum 高层 API，当时用同一会话逐个
 * 试连常见共享名凑出一个列表。
 *
 * V2.12：试连名单永远凑不全（用户 NAS 上叫「影片」的共享根本试不到），
 * 改成先走 IPC$ + srvsvc 的 NetrShareEnum 让设备自己报；只有设备不支持时才
 * 退回试连，并把「为什么不是完整列表」写进 ShareListing.reason。
 */
class SmbSessionPool {
    companion object {
        /** 设备不支持枚举时的兜底名单；命中即可见，未命中不打扰用户。 */
        private val COMMON_SHARES = listOf(
            "VR", "vr", "Videos", "videos", "Video", "video", "Movies", "movies",
            "Media", "media", "Share", "share", "Public", "public", "Data", "data",
            "NAS", "nas", "volume1", "Volume1", "volume2", "homes", "home", "Home",
            "Download", "Downloads", "Multimedia", "multimedia", "disk1", "share1",
            "video2", "Videos2", "USB", "usbshare1", "usbshare2", "影视", "视频",
            "影片", "电影", "共享", "家庭", "照片"
        )

        /** Windows 自己也不显示的隐藏共享（IPC$ / C$ / ADMIN$ / print$…）。 */
        fun isHiddenShare(name: String): Boolean = name.endsWith("$")
    }

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    // V2.7.5：以前只有一个 share 槽位，换个共享就把旧 DiskShare 关掉，
    // 而另一个还在读的 SmbRandomAccessSource 手里正握着它 —— 真机崩溃
    // 「DiskShare has already been closed」就是这么来的。改成按 key 各留一个。
    private val shares = HashMap<String, DiskShare>()
    private var currentSessionKey: String = ""

    private fun authentication(credentials: SmbCredentials): AuthenticationContext =
        // 空凭证走 guest 匿名登录；填了账号才做 NTLM。
        if (credentials.user.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(
            credentials.user,
            credentials.password.toCharArray(),
            credentials.domain
        )

    @Synchronized
    private fun session(host: String, credentials: SmbCredentials): Session {
        val key = "$host|${credentials.user}|${credentials.domain}"
        session?.let { if (currentSessionKey == key) return it }
        closeQuietly()
        val config = SmbConfig.builder()
            .withSoTimeout(30, TimeUnit.SECONDS)
            .withTimeout(30, TimeUnit.SECONDS)
            .build()
        val newClient = SMBClient(config)
        val newConnection = newClient.connect(host)
        // V2.15：登录失败时这条连接还没挂到字段上，不主动关就是每次失败漏一个 socket。
        // 「点一台共享设备先用空账号试一次」会让登录失败变成常态，必须收拾干净。
        val newSession = runCatching { newConnection.authenticate(authentication(credentials)) }
            .getOrElse { error ->
                runCatching { newConnection.close() }
                throw error
            }
        client = newClient
        connection = newConnection
        session = newSession
        currentSessionKey = key
        return newSession
    }

    @Synchronized
    fun diskShare(ref: MediaRef, credentials: SmbCredentials): DiskShare {
        val key = "${ref.host}|${ref.share}|${credentials.user}"
        shares[key]?.let { return it }
        val newShare = runCatching {
            session(ref.host, credentials).connectShare(ref.share) as DiskShare
        }.getOrElse {
            // 会话可能已经半死（连接还在、树连接已断）。整条连接重建一次再试。
            closeQuietly()
            session(ref.host, credentials).connectShare(ref.share) as DiskShare
        }
        shares[key] = newShare
        return newShare
    }

    /** 只丢弃指定共享，其余片源的连接不受影响。重连时用它，不要用 invalidate()。 */
    @Synchronized
    fun evictShare(ref: MediaRef, credentials: SmbCredentials) {
        val key = "${ref.host}|${ref.share}|${credentials.user}"
        shares.remove(key)?.let { runCatching { it.close() } }
    }

    /**
     * 只填 IP 后的第一层「共享」。
     *
     * 先让设备自己报（IPC$ + srvsvc），这一步给出的是完整列表；
     * 设备不支持或账号没权限时才退回试连常见共享名，并带上原因。
     */
    @Synchronized
    fun listShares(host: String, credentials: SmbCredentials): ShareListing {
        val active = session(host, credentials)
        val viaRpc = runCatching { SrvsvcShareEnum.list(active, host) }
        val names = viaRpc.getOrNull()
        if (names != null) {
            return ShareListing(
                names = names.filterNot { isHiddenShare(it) }.distinct(),
                authoritative = true
            )
        }
        return ShareListing(
            names = probeCommon(active),
            authoritative = false,
            reason = describe(viaRpc.exceptionOrNull())
        )
    }

    /** 设备不支持枚举时的兜底：逐个 TREE_CONNECT 试连常见共享名。 */
    private fun probeCommon(active: Session): List<String> {
        val found = LinkedHashSet<String>()
        for (name in COMMON_SHARES) {
            val candidate: Share = runCatching { active.connectShare(name) }.getOrNull() ?: continue
            if (candidate is DiskShare) found += name
            runCatching { candidate.close() }
        }
        // 试连失败可能让会话处于半断开状态，清掉缓存让下一次重新建连。
        invalidate()
        return found.toList()
    }

    /** 把协议栈的异常翻成用户能照着做的一句话。 */
    private fun describe(error: Throwable?): String {
        val text = error?.message.orEmpty()
        return when {
            text.contains("LOGON_FAILURE", true) || text.contains("WRONG_PASSWORD", true) ->
                "账号或密码不对，设备不肯列共享"
            text.contains("ACCOUNT_DISABLED", true) ->
                "这个账号被停用了"
            text.contains("ACCESS_DENIED", true) ->
                "这个账号没有列共享的权限"
            text.contains("IPC", true) || text.contains("BAD_NETWORK_NAME", true) ->
                "设备没有开放管理管道"
            text.contains("OBJECT_NAME_NOT_FOUND", true) ||
                text.contains("srvsvc", true) ->
                "设备不支持共享列表查询"
            error == null -> "设备没有返回共享列表"
            else -> "设备没有返回共享列表（${text.take(60)}）"
        }
    }

    @Synchronized
    fun openFile(ref: MediaRef, credentials: SmbCredentials): File {
        val relativePath = ref.path.trim().replace('/', '\\').trimStart('\\')
            .removePrefix("${ref.share}\\")
        return diskShare(ref, credentials).openFile(
            relativePath,
            EnumSet.of(AccessMask.FILE_READ_DATA),
            null,
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
            SMB2CreateDisposition.FILE_OPEN,
            null
        )
    }

    @Synchronized
    fun list(ref: MediaRef, credentials: SmbCredentials, folder: String): List<SmbEntry> {
        return diskShare(ref, credentials).list(folder).mapNotNull { info ->
            val name = info.fileName
            if (name == "." || name == "..") return@mapNotNull null
            // FILE_ATTRIBUTE_DIRECTORY = 0x10（MS-FSCC）
            SmbEntry(
                name = name,
                sizeBytes = info.endOfFile,
                isDirectory = (info.fileAttributes and 0x10L) != 0L
            )
        }
    }

    @Synchronized
    fun invalidate() {
        closeQuietly()
    }

    private fun closeQuietly() {
        // README 官方示例以 try-with-resources 关闭的正是这两个对象；
        // 其余引用随 Connection 一起释放，避免依赖未公开的 close() 签名。
        shares.values.forEach { runCatching { it.close() } }
        shares.clear()
        runCatching { connection?.close() }
        session = null
        connection = null
        client = null
        currentSessionKey = ""
    }
}

data class SmbEntry(val name: String, val sizeBytes: Long, val isDirectory: Boolean)
