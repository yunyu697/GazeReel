package com.gazereel.vr.data

/**
 * 问设备「你叫什么」—— 走 SMB 会话里的 srvsvc，不碰 137 端口。
 *
 * 真机现场：两台 NAS 的名字出来了，**PC 还是显示 IP**。
 * 原因很具体：Windows 默认关着「网络发现」时**不应答 NBSTAT（137/UDP）**，
 * 而它也不在 mDNS 里注册 `_smb._tcp`，家用路由器又通常没有 PTR 记录——
 * 三条路全断，只能显示 IP。但 445 是开着的（不然它不会出现在列表里），
 * 那就顺着已经能连上的那条路问它自己：srvsvc 的 `NetrServerGetInfo` 层级 100，
 * `sv100_name` 就是「网络」里显示的那个名字。
 *
 * V2.29：**凭据输入是相对参考应用的优势，不能因为取名字把它丢了。**
 * 需要账号的 NAS 对匿名 srvsvc 不放行，所以先拿已保存的凭据问一次，
 * guest 匿名只作最后兜底。
 *
 * 协议实现已交给 jcifs-ng（见 [SmbShareLister]），这里只保留**策略**：
 * 先认证、失败再匿名，以及每一次的来源日志。不再自建 smbj 连接 ——
 * 问一句话就走，没必要为它占着片库的会话槽位。
 */
internal object SmbNameProbe {

    /** 旧签名保留：没带凭据时等价于「只走 guest」，既有调用点不受影响。 */
    fun machineName(host: String, timeoutMs: Int = SmbShareLister.NAME_TIMEOUT_MS): String? =
        machineName(host, null, timeoutMs)

    /**
     * 优先用已认证会话取名字；失败或没给凭据时才退回 guest 匿名兜底。
     * 两次尝试各自建连，失败互相隔离，不会把认证失败带进 guest。
     */
    fun machineName(
        host: String,
        credentials: SmbCredentials?,
        timeoutMs: Int = SmbShareLister.NAME_TIMEOUT_MS
    ): String? {
        val credential = credentials?.takeIf { it.user.isNotBlank() }
        if (credential != null) {
            val authed = attempt(host, credential, timeoutMs)
            if (!authed.isNullOrBlank()) {
                RunLog.append("smb", "设备名 · $host · $authed · 来源=已认证会话(srvsvc)")
                return authed
            }
            RunLog.append("smb", "设备名 · $host · 已认证会话没问到名字，退回 guest 兜底")
        }
        val guest = attempt(host, null, timeoutMs)
        if (!guest.isNullOrBlank()) {
            RunLog.append("smb", "设备名 · $host · $guest · 来源=guest(srvsvc) 兜底")
        }
        return guest
    }

    /** 问一次；失败只写一行日志并返回 null —— 名字不是必需信息，不该让扫描失败。 */
    private fun attempt(host: String, credentials: SmbCredentials?, timeoutMs: Int): String? =
        runCatching { SmbShareLister.machineName(host, credentials, timeoutMs) }
            .onFailure { error ->
                val who = credentials?.user?.takeIf { it.isNotBlank() } ?: "guest"
                RunLog.append(
                    "smb",
                    "设备名 · $host · 账号=$who · 没问到 · ${error.message?.take(80)}"
                )
            }
            .getOrNull()
}
