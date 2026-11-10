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
 * 而 smbj 0.13 没有公开的 SRVSVC NetShareEnum 高层 API，故用同一会话逐个
 * 试连常见共享名，把连得上的作为可见列表交给用户点选（不是让用户输入）。
 */
class SmbSessionPool {
    companion object {
        /** 常见 NAS / Windows / 路由器的共享名；命中即可见，未命中不打扰用户。 */
        private val COMMON_SHARES = listOf(
            "VR", "vr", "Videos", "videos", "Video", "video", "Movies", "movies",
            "Media", "media", "Share", "share", "Public", "public", "Data", "data",
            "NAS", "nas", "volume1", "Volume1", "homes", "Download", "Downloads",
            "Multimedia", "multimedia", "disk1", "share1"
        )
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
        val newSession = newConnection.authenticate(authentication(credentials))
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
     * 只填 IP 后的第一层「共享」。逐个 TREE_CONNECT，成功的才出现在列表里。
     * 已知边界：非常规命名的共享可能探测不到——界面在这种失败情况下允许用户粘贴
     * 完整 UNC，而不是要求所有用户都去记共享名；完整 NetShareEnum 留待后续专项。
     */
    @Synchronized
    fun probeShares(host: String, credentials: SmbCredentials): List<String> {
        val active = session(host, credentials)
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
