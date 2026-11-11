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
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

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
 * V2.29：进程内扫描缓存（共享列表 + 已枚举目录）。
 *
 * 配置页与 VR 各 new 一个 [SmbSessionPool]，跨界面不共享连接，但扫描结果
 * 理应共享：反复进出 / 反复「重扫」不该把同一棵目录树重新枚举一遍。
 * 做成进程级单例，缓存 key 含 host / share / 凭据标识（见 [credKey]），
 * 不同账号拿到的列表互不串味。
 *
 * 失效走两条：TTL 到期惰性丢弃；[bump] 递增版本号，让所有旧条目一次性失效，
 * 供用户显式「重扫」强制刷新，不必逐条删，也不会误删别人的缓存。
 */
internal object SmbScanCache {
    private const val TTL_MS = 60_000L
    private val version = AtomicLong(0)
    private val map = ConcurrentHashMap<String, Entry>()

    private data class Entry(val version: Long, val at: Long, val value: Any)

    /** 用户显式「重扫」时调用：版本号一变，旧条目全部过期。 */
    fun bump() {
        version.incrementAndGet()
    }

    private fun alive(entry: Entry): Boolean =
        entry.version == version.get() && System.currentTimeMillis() - entry.at < TTL_MS

    @Suppress("UNCHECKED_CAST")
    fun <T> get(key: String): T? {
        val entry = map[key] ?: return null
        if (!alive(entry)) {
            map.remove(key)
            return null
        }
        return entry.value as? T
    }

    fun put(key: String, value: Any) {
        map[key] = Entry(version.get(), System.currentTimeMillis(), value)
    }
}

/** 缓存 key 里的「凭据标识」：不落明文密码，又能把不同账号区分开，避免串味。 */
private fun credKey(credentials: SmbCredentials): String =
    "${credentials.user}|${credentials.domain}|${credentials.password.hashCode()}"

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
 *
 * V2.29：不再整方法 @Synchronized。只在「建连 / 取 share」的临界区用
 * synchronized(this)（与 [close] 的 @Synchronized 是同一把锁），
 * 真正的 list / openFile IO 在 [withShare] 里、锁外执行，不同共享因此能并行。
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

        /** V2.29：兜底共享名并发试连的上限；别把 NAS 打爆，也别串行试 38 次。 */
        private const val PROBE_WORKERS = 8
        /** 试连整体预算；设备不应答时不让「加片库」无限等。 */
        private const val PROBE_TIMEOUT_MS = 6_000L

        /** Windows 自己也不显示的隐藏共享（IPC$ / C$ / ADMIN$ / print$…）。 */
        fun isHiddenShare(name: String): Boolean = name.endsWith("$")
    }

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    // V2.7.5：以前只有一个 share 槽位，换个共享就把旧 DiskShare 关掉，
    // 而另一个还在读的 SmbRandomAccessSource 手里正握着它 —— 真机崩溃
    // 「DiskShare has already been closed」就是这么来的。改成按 key 各留一个。
    // V2.29：换成 ConcurrentHashMap，配合只在建连处持锁的策略。
    private val shares = ConcurrentHashMap<String, DiskShare>()
    private var currentSessionKey: String = ""

    private fun authentication(credentials: SmbCredentials): AuthenticationContext =
        // 空凭证走 guest 匿名登录；填了账号才做 NTLM。
        if (credentials.user.isBlank()) AuthenticationContext.guest()
        else AuthenticationContext(
            credentials.user,
            credentials.password.toCharArray(),
            credentials.domain
        )

    /**
     * V2.29：只在「建连」这一小段临界区加锁。全局只维持一条到 host 的连接，
     * 并发进来不能各自 connect() 再互相覆盖 client / connection / session 字段。
     * 锁与 [close] 的 @Synchronized、[diskShare]、[evictShare] 共用同一个 this 监视器。
     */
    private fun session(host: String, credentials: SmbCredentials): Session = synchronized(this) {
        val key = "$host|${credentials.user}|${credentials.domain}"
        val cached = session
        if (cached != null && currentSessionKey == key) {
            cached
        } else {
            val started = System.currentTimeMillis()
            closeQuietly()
            val config = SmbConfig.builder()
                .withSoTimeout(30, TimeUnit.SECONDS)
                .withTimeout(30, TimeUnit.SECONDS)
                .build()
            val newClient = SMBClient(config)
            val newConnection = newClient.connect(host)
            // V2.15：登录失败时这条连接还没挂到字段上，不主动关就是每次失败漏一个 socket。
            val newSession = runCatching { newConnection.authenticate(authentication(credentials)) }
                .getOrElse { error ->
                    runCatching { newConnection.close() }
                    throw error
                }
            client = newClient
            connection = newConnection
            session = newSession
            currentSessionKey = key
            val account = if (credentials.user.isBlank()) "guest" else credentials.user
            RunLog.append(
                "smb",
                "建连 · $host · 账号=$account · 耗时 ${System.currentTimeMillis() - started}ms"
            )
            newSession
        }
    }

    /**
     * 只在「取 / 建 share」的临界区加锁；真正的 list / openFile IO 在 [withShare]
     * 里、锁外执行，所以不同共享的读写能并行。缓存命中时这里只做一次查表。
     */
    fun diskShare(ref: MediaRef, credentials: SmbCredentials): DiskShare = synchronized(this) {
        val key = "${ref.host}|${ref.share}|${credentials.user}"
        val cached = shares[key]
        if (cached != null) {
            cached
        } else {
            val newShare = runCatching {
                session(ref.host, credentials).connectShare(ref.share) as DiskShare
            }.getOrElse {
                // 会话可能已经半死（连接还在、树连接已断）。整条连接重建一次再试。
                closeQuietly()
                session(ref.host, credentials).connectShare(ref.share) as DiskShare
            }
            shares[key] = newShare
            newShare
        }
    }

    /** 只丢弃指定共享，其余片源的连接不受影响。重连时用它，不要用 invalidate()。 */
    fun evictShare(ref: MediaRef, credentials: SmbCredentials) {
        synchronized(this) {
            val key = "${ref.host}|${ref.share}|${credentials.user}"
            shares.remove(key)?.let { runCatching { it.close() } }
        }
    }

    /**
     * V2.28：所有 SMB 操作都从这里过一道（**自愈逻辑原样保留**）。
     *
     * `shares` 里可能躺着一个「已经关掉」的 DiskShare：服务端单方面断开、
     * 别的路径 evict 过、或者退出界面时 close() 与 diskShare() 撞在一起。
     * smbj 0.13 没有公开的 isClosed()，与其猜，不如让第一次失败自己说话：
     * 丢掉这一条、重建一次，再把同一个操作重做一遍。
     *
     * 只重试一次，且只针对「连接已死」这一类异常。权限不足、路径不存在等
     * 真实错误原样抛出，不会被吞掉，也不会反复重连。
     * V2.29：diskShare() 只在临界区持锁，operation（真正的网络 IO）在锁外，
     * 因此列文件夹、补封面、打开片源和扫描四条路径能并行跑。
     */
    private inline fun <T> withShare(
        ref: MediaRef,
        credentials: SmbCredentials,
        operation: (DiskShare) -> T
    ): T = try {
        operation(diskShare(ref, credentials))
    } catch (t: RuntimeException) {
        if (!isDeadShare(t)) throw t
        RunLog.append("smb", "共享连接已失效，剔除后重建 · ${ref.share}")
        evictShare(ref, credentials)
        operation(diskShare(ref, credentials))
    }

    /** 只看「连接死了」这一类，找不到就当普通错误交给上层。 */
    private fun isDeadShare(t: Throwable): Boolean {
        var cur: Throwable? = t
        for (depth in 0 until 4) {
            if (cur == null) break
            val message = cur.message.orEmpty().lowercase()
            if (message.contains("closed") || message.contains("disconnect") ||
                message.contains("transport") || message.contains("not connected")
            ) return true
            cur = cur.cause
        }
        return false
    }

    /**
     * 只填 IP 后的第一层「共享」。
     *
     * 先让设备自己报（IPC$ + srvsvc），这一步给出的是完整列表；
     * 设备不支持或账号没权限时才退回并发试连常见共享名，并带上原因。
     * V2.29：加缓存（key 含 host + 凭据标识）；RPC 与试连都放在锁外，
     * 避免一次慢枚举把其它 SMB 操作全堵住。失败 / 空探测结果不缓存，
     * 免得把一次网络抖动固化成 60 秒的「设备没共享」。
     */
    fun listShares(host: String, credentials: SmbCredentials): ShareListing {
        val cacheKey = "shares|$host|${credKey(credentials)}"
        SmbScanCache.get<ShareListing>(cacheKey)?.let {
            RunLog.append("smb", "共享列表命中缓存 · $host · ${it.names.size} 个")
            return it
        }
        val started = System.currentTimeMillis()
        val active = session(host, credentials)
        val viaRpc = runCatching { SrvsvcShareEnum.list(active, host) }
        val names = viaRpc.getOrNull()
        val result = if (names != null) {
            RunLog.append(
                "smb",
                "共享枚举成功 · $host · ${names.size} 个 · 耗时 ${System.currentTimeMillis() - started}ms"
            )
            ShareListing(names.filterNot { isHiddenShare(it) }.distinct(), true)
        } else {
            val probed = probeCommon(active, host)
            ShareListing(probed, false, describe(viaRpc.exceptionOrNull()))
        }
        // E7 / V2.32：jcifs-ng 双跑对照。放在 result 算完之后，srvsvc 成功和
        // 试连兜底两条路径都能拿到对照证据；提交只是往后台队列丢一个 Runnable，
        // 不占这次会话、不进网络、不改 result，失败也只多写一行日志。
        JcifsCrossCheck.compareShares(
            host, credentials, names, result.names, result.authoritative
        )
        // 枚举成功（哪怕真的是空列表）或试连有命中才缓存；整条失败不缓存。
        if (result.authoritative || result.names.isNotEmpty()) {
            SmbScanCache.put(cacheKey, result)
        }
        return result
    }

    /**
     * 设备不支持枚举时的兜底：V2.29 改为有上限的并发 TREE_CONNECT 试连。
     * 末尾不再 invalidate() —— 试连失败是「这个共享名不存在」的正常结果，
     * 探测用的临时 Share 也从没进 shares 缓存，没有「失败的那条」要 evict；
     * 真把会话弄坏了，后续操作由 [withShare] 自愈重建即可。
     */
    private fun probeCommon(active: Session, host: String): List<String> {
        val started = System.currentTimeMillis()
        val workers = Executors.newFixedThreadPool(PROBE_WORKERS) { runnable ->
            Thread(runnable, "smb-probe-common").apply { isDaemon = true }
        }
        val found = HashSet<String>()
        try {
            val tasks = COMMON_SHARES.map { name ->
                Callable {
                    val candidate: Share? = runCatching { active.connectShare(name) }.getOrNull()
                    if (candidate == null) {
                        null
                    } else {
                        val isDisk = candidate is DiskShare
                        runCatching { candidate.close() }
                        if (isDisk) name else null
                    }
                }
            }
            // 整体限时：设备不应答时不让 38 个试连把整次扫描拖死。
            workers.invokeAll(tasks, PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS).forEach { future ->
                runCatching { future.get() }.getOrNull()?.let { found += it }
            }
        } finally {
            workers.shutdownNow()
        }
        // 按名单原序输出，保序比 HashSet 的迭代顺序稳定。
        val ordered = COMMON_SHARES.filter { it in found }
        RunLog.append(
            "smb",
            "共享探测 · $host · 并发 $PROBE_WORKERS · 命中 ${ordered.size}/${COMMON_SHARES.size} · " +
                "耗时 ${System.currentTimeMillis() - started}ms"
        )
        return ordered
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

    fun openFile(ref: MediaRef, credentials: SmbCredentials): File {
        val relativePath = ref.path.trim().replace('/', '\\').trimStart('\\')
            .removePrefix("${ref.share}\\")
        return withShare(ref, credentials) {
            it.openFile(
                relativePath,
                EnumSet.of(AccessMask.FILE_READ_DATA),
                null,
                EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                SMB2CreateDisposition.FILE_OPEN,
                null
            )
        }
    }

    fun list(ref: MediaRef, credentials: SmbCredentials, folder: String): List<SmbEntry> {
        val cacheKey = "dir|${ref.host}|${ref.share}|${credKey(credentials)}|$folder"
        SmbScanCache.get<List<SmbEntry>>(cacheKey)?.let {
            RunLog.append("smb", "目录命中缓存 · ${ref.share}/$folder · ${it.size} 项")
            return it
        }
        val result = withShare(ref, credentials) { share ->
            share.list(folder).mapNotNull { info ->
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
        SmbScanCache.put(cacheKey, result)
        return result
    }

    fun invalidate() {
        closeQuietly()
    }

    /**
     * V2.18：把会话还给设备；V2.28：补上同步。
     *
     * 以前只有 [invalidate] 能走到这里，而它只在「连接看起来坏了」的时候被调用。
     * 于是退出界面时连接根本没关——每进一次 VR 就在 NAS 上留一条。
     * 进出几次之后设备那边的并发连接被占满，下一次扫描就连不上，
     * 用户看到的是「必须完全退出 App 重开才能扫描」。
     *
     * 必须和 [diskShare] / [list] / [openFile] 的临界区用同一把锁：否则退出时的
     * `closeQuietly()` 可能正好夹在别人「新建 share 放进 map」与「关连接」
     * 之间，留下一个挂在已关闭连接上、却仍被缓存复用的僵尸 DiskShare。
     * V2.29：**保留 @Synchronized**，其它临界区统一改用 synchronized(this)，
     * 仍是同一个 this 监视器，互斥语义不变。
     */
    @Synchronized
    fun close() {
        closeQuietly()
    }

    /** 自身带锁，供 [close] / [invalidate] / [session] / [diskShare] 重入调用。 */
    private fun closeQuietly() {
        synchronized(this) {
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
}

data class SmbEntry(val name: String, val sizeBytes: Long, val isDirectory: Boolean)
