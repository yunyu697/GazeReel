package com.gazereel.vr.data

import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.dcerpc.DcerpcException
import jcifs.dcerpc.DcerpcHandle
import jcifs.dcerpc.msrpc.MsrpcShareEnum
import jcifs.dcerpc.msrpc.srvsvc
import jcifs.smb.NtlmPasswordAuthenticator
import java.io.IOException
import java.util.Properties

/**
 * 设备上有哪些共享 / 设备叫什么 —— 交给 jcifs-ng 的 srvsvc 实现，不再自己拼报文。
 *
 * ## 为什么换掉手写实现
 *
 * 旧版（`SmbShareEnum.kt`，404 行）自己拼 DCE/RPC 与 NDR32：bind PDU、两种入参排布、
 * 分帧拼接、按偏移扫应答。它能跑，但代价是**每个字节都是我猜出来的**：
 * 上一版因为「变长内容没有按 NDR 规则延后」真机直接判成畸形请求，表现成
 * 「设备没有返回共享列表」——一个字段顺序错误就整条链失效，且没法在本机复现。
 *
 * jcifs-ng 2.1.10 自带 `jcifs.dcerpc.msrpc.MsrpcShareEnum`（srvsvc NetrShareEnum，
 * 层级 1，直接返回 `FileEntry[]`）与 `srvsvc.ServerGetInfo`（层级 100，`sv100_name`
 * 就是「网络」里显示的那台机器名）。协议细节由库负责，出问题也由库的社区维护。
 *
 * 这次是**整段替换**，不是加旁路：手写实现与「双跑对照」层都已删除，
 * 默认路径只跑这里。R10 的理由照旧成立 —— 留着两套分支，等于新方案永远没有
 * 真机验证，历史问题必然从旧路径复发。
 *
 * ## 失败不吞
 *
 * [listShares] 拿不到列表时抛 [IOException]，由 [SmbSessionPool.listShares] 回退到
 * 「并发试连常见共享名」并如实写进 `ShareListing.reason`；[machineName] 抛错由
 * [SmbNameProbe] 决定是否退回 guest 匿名再问一次。这里不做「静默返回空表」，
 * 空表必须真的表示设备没有共享。
 */
internal object SmbShareLister {

    /** 管道名；srvsvc 挂在 `IPC$` 上。 */
    private const val PIPE = "\\pipe\\srvsvc"

    /** 列共享的预算：比建连 + 一次 RPC 略宽，设备慢时不至于白等，也不会拖死「加片库」。 */
    const val LIST_TIMEOUT_MS = 4_000

    /** 取设备名的预算：[LanSmbScanner] 的名字阶段整体只有 2600ms，单台必须更短。 */
    const val NAME_TIMEOUT_MS = 1_400

    // Windows 侧返回的 NetrShareEnum / NetrServerGetInfo 结果码。
    private const val NERR_Success = 0
    private const val ERROR_ACCESS_DENIED = 5
    private const val ERROR_BAD_NETPATH = 53
    private const val ERROR_BAD_NET_NAME = 67
    private const val ERROR_LOGON_FAILURE = 1326
    private const val ERROR_ACCOUNT_DISABLED = 1331
    private const val ERROR_ACCOUNT_LOCKED_OUT = 1909

    /**
     * 设备自己报的共享名（原样，含 `IPC$` 之类以 `$` 结尾的管理共享）。
     *
     * 过滤交给调用方：`SmbSessionPool.isHiddenShare` 是唯一的判断处，
     * 这里再滤一遍就成了两套口径。[credentials] 留空表示 guest 匿名，
     * 但列共享通常需要账号 —— 匿名失败是预期结果，不是异常路径。
     */
    fun listShares(
        host: String,
        credentials: SmbCredentials,
        timeoutMs: Int = LIST_TIMEOUT_MS
    ): List<String> = withHandle(host, credentials, timeoutMs) { handle ->
        val request = MsrpcShareEnum(host)
        handle.sendrecv(request)
        requireOk(request.getResult(), request.retval, host, "共享枚举")
        request.entries
            ?.mapNotNull { it.name?.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
    }

    /**
     * 问设备「你叫什么」：srvsvc 的 `NetrServerGetInfo` 层级 100。
     *
     * 这一条**不依赖 137 端口**，所以 Windows 关着「网络发现」时也答得上来 ——
     * 真机现场「两台 NAS 名字对了、PC 只显示 IP」就是靠它修的。jcifs 走同一个接口，
     * 行为与旧实现一致，只是不再由我拼 NDR。
     *
     * 解码后的对象在 `request.info` 里（不是构造时传进去的那个），这是
     * `ServerGetInfo.decode_out` 的实现决定的：它自己 new 一个 `ServerInfo100`
     * 再填值。问不到名字返回 null，协议错误照常抛。
     */
    fun machineName(
        host: String,
        credentials: SmbCredentials?,
        timeoutMs: Int = NAME_TIMEOUT_MS
    ): String? = withHandle(host, credentials, timeoutMs) { handle ->
        val request = srvsvc.ServerGetInfo("\\\\$host", 100, srvsvc.ServerInfo100())
        handle.sendrecv(request)
        requireOk(request.getResult(), request.retval, host, "取设备名")
        (request.info as? srvsvc.ServerInfo100)
            ?.name
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    /** 建一条到 `srvsvc` 的 DCE/RPC 管道，用完连上下文一起关。 */
    private fun <T> withHandle(
        host: String,
        credentials: SmbCredentials?,
        timeoutMs: Int,
        block: (DcerpcHandle) -> T
    ): T {
        val context = context(credentials, timeoutMs)
        try {
            val handle = DcerpcHandle.getHandle("ncacn_np:$host[$PIPE]", context)
            try {
                // sendrecv 内部会在未绑定时先 bind，不必显式调 bind()。
                return block(handle)
            } finally {
                runCatching { handle.close() }
            }
        } finally {
            // 上下文持有自己的传输池与名字服务，不关就是连接泄漏。
            runCatching { context.close() }
        }
    }

    /**
     * 每次一条独立上下文，不复用 `SingletonContext`：连接池按 host 复用是好事，
     * 但进程级单例在「重开就好」这类故障里反而会把坏连接一直留着。
     */
    private fun context(credentials: SmbCredentials?, timeoutMs: Int): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.responseTimeout", timeoutMs.toString())
            setProperty("jcifs.smb.client.soTimeout", timeoutMs.toString())
            setProperty("jcifs.smb.client.connTimeout", timeoutMs.toString())
            setProperty("jcifs.netbios.soTimeout", timeoutMs.toString())
            setProperty("jcifs.netbios.retryTimeout", timeoutMs.toString())
            // 不做 WINS / 广播解析：这里连的是已经拿到 IP 的设备，不该往局域网多撒包。
            setProperty("jcifs.resolveOrder", "DNS")
            // DFS 会让 jcifs 追加一次 referral 查询；家用 NAS 基本不用，关掉省一次往返。
            setProperty("jcifs.smb.client.dfs.disabled", "true")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val credential = credentials?.takeIf { it.user.isNotBlank() }
        return if (credential != null) {
            base.withCredentials(
                NtlmPasswordAuthenticator(credential.domain, credential.user, credential.password)
            )
        } else {
            // 官方 API 就是这个拼写（Crendentials），不是 Credentials。
            base.withGuestCrendentials()
        }
    }

    /**
     * DCE 层故障与应用层结果码分开看：前者是「管道没通 / 绑定被拒」，
     * 后者是「通了但这台设备不让你列」——两者的提示语完全不同。
     */
    private fun requireOk(result: DcerpcException?, retval: Int, host: String, what: String) {
        if (result != null) {
            throw IOException("$what 失败 · $host · DCE ${result.message}", result)
        }
        if (retval != NERR_Success) {
            throw IOException("$what 失败 · $host · ${win32(retval)}（$retval）")
        }
    }

    /**
     * 结果码翻成 [SmbSessionPool.describe] 认得的关键词。
     *
     * 不直接给中文：提示语只该有一处（`describe`），这里只提供机器可读的名字，
     * 否则同一个错误在不同层各写一句中文，迟早对不上。
     */
    private fun win32(code: Int): String = when (code) {
        ERROR_ACCESS_DENIED -> "ACCESS_DENIED"
        ERROR_BAD_NETPATH -> "BAD_NETWORK_PATH"
        ERROR_BAD_NET_NAME -> "BAD_NETWORK_NAME"
        ERROR_LOGON_FAILURE -> "LOGON_FAILURE"
        ERROR_ACCOUNT_DISABLED -> "ACCOUNT_DISABLED"
        ERROR_ACCOUNT_LOCKED_OUT -> "ACCOUNT_LOCKED_OUT"
        else -> "WIN32_$code"
    }
}
