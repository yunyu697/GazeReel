package com.gazereel.vr.data

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

/**
 * E7 / V2.32：jcifs-ng 与手写 srvsvc 的**双跑对照**。
 *
 * ## 这条链只做一件事：往日志里多写一行
 *
 * 手写 DCE/RPC（[SrvsvcShareEnum]）走 445 的 srvsvc，是「设备叫什么 / 有哪些共享」
 * 的唯一权威来源；jcifs-ng 走自己的 CIFSContext（默认 NBNS 137 + SMB1 协商），
 * 在「Windows 关掉网络发现」这类网络里本来就问不到名字。两者**不是等价替代**，
 * 而是一个对照证据：日志里同时看到两条结果，才能判断手写链解析得对不对。
 *
 * 所以这里的结果**不参与任何返回值**：播放、目录遍历、共享列表、设备显示名
 * 全部照旧走 smbj + 手写 srvsvc，手写链失败时的 fallback 一个字没动。
 *
 * ## 为什么不塞进「找设备」的名字解析热路径
 *
 * [LanSmbScanner] 的名字阶段是 48 路并发、整体 2600ms 预算；在 [SmbNameProbe]
 * 里直接加一次 jcifs 网络往返，会把每台设备的超时叠加到扫描里，正是 V2.29
 * 刚修掉的毛病。这里改成**单线程后台 fire-and-forget**：
 * - 只在「共享枚举」（用户点进文件夹，低频）和 srvsvc [SmbNameProbe] 返回之后
 *   各挂一次**非阻塞**提交；提交动作只有一个 ConcurrentHashMap 读写，不进网络；
 * - 同一 host + 同一动作 60 秒内只提交一次，反复进出「找设备」不会刷请求；
 * - 队列满了直接丢，绝不排队堆积；
 * - 每次调用自建 BaseContext，用完在 finally 里 close()（连带关掉它自己的
 *   transport pool / name service client），不复用 SingletonContext 这个进程级单例，
 *   免得「重开就好」又变回连接泄漏。
 *
 * 所有 jcifs 调用整体包在 Throwable 隔离里：Android 缺 javax.security.auth、
 * 缺 BouncyCastle、SMB1 被设备拒绝 —— 都只写一行日志，不会冒到调用方，
 * 更不会把手写链的结果改掉。**双跑跑不起来本身也是一条有用的真机结论。**
 */
internal object JcifsCrossCheck {

    /** 双跑总开关。真机上一旦发现 jcifs 拖慢或报错，改这一处即可整体停掉。 */
    private const val ENABLED = true

    private const val THROTTLE_MS = 60_000L

    /** 单路网络预算：比手写链的 1400ms 更保守，避免后台任务长期占着无线电。 */
    private const val TIMEOUT_MS = 1_200

    private val lastSubmit = ConcurrentHashMap<String, Long>()

    private val worker = Executors.newSingleThreadExecutor(ThreadFactory { runnable ->
        Thread(runnable, "smb-jcifs-crosscheck").apply { isDaemon = true }
    })

    private val submitted = AtomicInteger(0)

    /**
     * 对照「设备名」。手写链已经得出 [handwritten]（可能为 null）之后才调用，
     * 只把 jcifs 的结果并排写进日志，不改写 [handwritten]。
     */
    fun compareName(host: String, credentials: SmbCredentials?, handwritten: String?) {
        submit("name|$host") {
            val viaJcifs = runCatching { nameViaJcifs(host, credentials) }.getOrNull()
            RunLog.append(
                "smb",
                "[双跑·取名] $host · 手写srvsvc=${handwritten ?: "没问到"} · " +
                    "jcifs=${viaJcifs ?: "没问到"}"
            )
        }
    }

    /**
     * 对照「共享列表」。手写 srvsvc 得出 [srvsvc]（失败为 null）之后调用，
     * [effective] 是最终交给 UI 的列表（失败时是试连兜底的结果）。
     * 同样只写日志，[SmbSessionPool.ShareListing] 的内容一个字不改。
     */
    fun compareShares(
        host: String,
        credentials: SmbCredentials,
        srvsvc: List<String>?,
        effective: List<String>,
        authoritative: Boolean
    ) {
        submit("shares|$host") {
            val viaJcifs = runCatching { sharesViaJcifs(host, credentials) }.getOrNull()
            RunLog.append(
                "smb",
                "[双跑·共享] $host · 手写srvsvc=${srvsvc?.joinToString(",") ?: "枚举失败"} · " +
                    "最终=${effective.joinToString(",").ifEmpty { "空" }}" +
                    "（${if (authoritative) "srvsvc" else "试连兜底"}） · " +
                    "jcifs=${viaJcifs?.joinToString(",") ?: "枚举失败"}"
            )
        }
    }

    private fun submit(key: String, block: () -> Unit) {
        if (!ENABLED) return
        val now = System.currentTimeMillis()
        val previous = lastSubmit[key] ?: 0L
        if (now - previous < THROTTLE_MS) return
        // 单线程 + 有界：后台最多压一个任务，慢设备不会把队列拉成一条长龙。
        if (submitted.get() >= 2) return
        lastSubmit[key] = now
        submitted.incrementAndGet()
        runCatching {
            worker.execute {
                try {
                    block()
                } catch (t: Throwable) {
                    // Kotlin 的 runCatching 吃的是 Throwable，但 jcifs 的类加载失败
                    // 可能发生在 block 之外；后台线程上任何漏网的 Error 都会杀进程，
                    // 所以这里再兜一层，绝不让它冒出 Runnable。
                    RunLog.append(
                        "smb",
                        "[双跑] $key · jcifs 不可用：${t.javaClass.simpleName}: " +
                            t.message?.take(120)
                    )
                } finally {
                    submitted.decrementAndGet()
                }
            }
        }.onFailure { submitted.decrementAndGet() }
    }

    /** 每次一条独立上下文；close() 会关掉它自己的传输池与名字服务。 */
    private fun context(credentials: SmbCredentials?): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.soTimeout", TIMEOUT_MS.toString())
            setProperty("jcifs.smb.client.connTimeout", TIMEOUT_MS.toString())
            setProperty("jcifs.netbios.soTimeout", TIMEOUT_MS.toString())
            setProperty("jcifs.netbios.retryTimeout", TIMEOUT_MS.toString())
            // 不做 WINS / 广播解析：双跑只是对照，不该往局域网里多撒包。
            setProperty("jcifs.resolveOrder", "DNS")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val cred = credentials?.takeIf { it.user.isNotBlank() }
        return if (cred != null) {
            base.withCredentials(
                NtlmPasswordAuthenticator(cred.domain, cred.user, cred.password)
            )
        } else {
            base.withGuestCrendentials()   // 官方 API 就是这个拼写，不是 Credentials
        }
    }

    /** 列一台设备上的共享；与手写 srvsvc 的 NetrShareEnum 对照。 */
    private fun sharesViaJcifs(host: String, credentials: SmbCredentials): List<String> {
        val ctx = context(credentials)
        try {
            // 不能写 SmbFile.use{}：SmbFile 只实现 jcifs.SmbResource → AutoCloseable，
            // 而 kotlin.io.use 的接收者是 java.io.Closeable，不同 Kotlin 版本行为不一致。
            // 显式 try/finally 最稳。
            val root = SmbFile("smb://$host/", ctx)
            try {
                return root.list()
                    ?.map { it.trimEnd('/') }
                    ?.filter { it.isNotBlank() && !it.endsWith('$') }
                    ?.distinct()
                    ?: emptyList()
            } finally {
                runCatching { root.close() }
            }
        } finally {
            runCatching { ctx.close() }
        }
    }

    /** 问设备名；jcifs 走 NBNS，手写链走 445 srvsvc，两者结果本来就可能不同。 */
    private fun nameViaJcifs(host: String, credentials: SmbCredentials?): String? {
        val ctx = context(credentials)
        try {
            val addresses = ctx.nameServiceClient.getNbtAllByAddress(host)
            return addresses.firstOrNull()?.hostName
                ?.takeIf { it.isNotBlank() && !it.equals(host, ignoreCase = true) }
        } finally {
            runCatching { ctx.close() }
        }
    }
}
