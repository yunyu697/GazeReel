package com.gazereel.vr.data

import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
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
 * 用一次性连接，不进 [SmbSessionPool]：这里只问一句话就走，
 * 不该占着片库用的会话槽位，也不该把用户已经连好的会话挤掉。
 */
internal object SmbNameProbe {

    fun machineName(host: String, timeoutMs: Int = 1_400): String? {
        val config = SmbConfig.builder()
            .withSoTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .withTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        val client = SMBClient(config)
        val connection = runCatching { client.connect(host) }.getOrNull() ?: return null
        return try {
            // 先试访客；很多设备对 srvsvc 的匿名 bind 是放行的。
            val session = runCatching {
                connection.authenticate(AuthenticationContext.guest())
            }.getOrNull() ?: return null
            runCatching { SrvsvcShareEnum.machineName(session, host) }.getOrNull()
        } finally {
            runCatching { connection.close() }
        }
    }
}
