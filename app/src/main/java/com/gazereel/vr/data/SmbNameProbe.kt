package com.gazereel.vr.data

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import java.util.concurrent.TimeUnit

/**
 * V2.18：问设备「你叫什么」的第四条路 —— 走 SMB 会话里的 srvsvc，不碰 137 端口。
 *
 * 真机现场：两台 NAS 的名字出来了，**PC 还是显示 IP**。
 * 原因很具体：Windows 默认关着「网络发现」时**不应答 NBSTAT（137/UDP）**，
 * 而它也不在 mDNS 里注册 `_smb._tcp`，家用路由器又通常没有 PTR 记录——
 * 三条路全断，只能显示 IP。但 445 是开着的（不然它不会出现在列表里），
 * 那就顺着已经能连上的那条路问它自己：`NetrWkstaGetInfo` 的
 * `wki100_computername` 就是「网络」里显示的那个名字。
 *
 * V2.29：**凭据输入是我们相对参考应用的优势，不能因为取名字把它丢了。**
 * 旧版只做 guest 匿名 bind，需要账号的 NAS 对匿名 srvsvc 不放行，
 * 于是「设了账号的 NAS 反而只显示 IP」。现在优先复用用户已保存的凭据
 * 建一条**已认证会话**去问名字，匿名 guest 只作为最后兜底。
 *
 * 仍然用一次性连接，不进 [SmbSessionPool]：这里只问一句话就走，
 * 不该占着片库用的会话槽位，也不该把用户已经连好的会话挤掉。
 */
internal object SmbNameProbe {

    /** 旧签名保留：没带凭据时等价于「只走 guest」，既有调用点不受影响。 */
    fun machineName(host: String, timeoutMs: Int = 1_400): String? =
        machineName(host, null, timeoutMs)

    /**
     * 优先用已认证会话取名字；失败或没给凭据时才退回 guest 匿名兜底。
     * 每次尝试各用一条一次性连接，失败互相隔离，不会把认证失败带进 guest。
     */
    fun machineName(host: String, credentials: SmbCredentials?, timeoutMs: Int = 1_400): String? {
        val cred = credentials?.takeIf { it.user.isNotBlank() }
        if (cred != null) {
            val authed = ask(host, timeoutMs) { conn ->
                conn.authenticate(
                    AuthenticationContext(cred.user, cred.password.toCharArray(), cred.domain)
                )
            }
            if (!authed.isNullOrBlank()) {
                RunLog.append("smb", "设备名 · $host · $authed · 来源=已认证会话(srvsvc)")
                return authed
            }
            RunLog.append("smb", "设备名 · $host · 已认证会话没问到名字，退回 guest 兜底")
        }
        val guest = ask(host, timeoutMs) { conn -> conn.authenticate(AuthenticationContext.guest()) }
        if (!guest.isNullOrBlank()) {
            RunLog.append("smb", "设备名 · $host · $guest · 来源=guest(srvsvc) 兜底")
        }
        return guest
    }

    /** 一条连接 → 认证 → 走 srvsvc 问 computername；任何一步失败返回 null。 */
    private fun ask(host: String, timeoutMs: Int, auth: (Connection) -> Session): String? {
        val config = SmbConfig.builder()
            .withSoTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val client = SMBClient(config)
        val connection = runCatching { client.connect(host) }.getOrNull() ?: return null
        return try {
            val session = runCatching { auth(connection) }.getOrNull() ?: return null
            runCatching { SrvsvcShareEnum.machineName(session, host) }.getOrNull()
        } finally {
            runCatching { connection.close() }
        }
    }
}
