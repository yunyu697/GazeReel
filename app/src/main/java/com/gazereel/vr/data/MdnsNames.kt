package com.gazereel.vr.data

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo

/**
 * V2.17：用系统的 mDNS（DNS-SD / Bonjour）把「服务名」对上 IP。
 *
 * 真机上飞牛、极空间、连 Windows PC 都只显示 IP —— 说明 NBSTAT 那条根本没拿到回包
 * （PC 一定会应答 NBSTAT，所以不是设备的问题）。除了改扫描节奏，再补一条独立的路：
 * `_smb._tcp` 的实例名通常就是设备主机名，而 Android 自带 [NsdManager] 就能问，
 * 不必自己拼 mDNS 报文。
 *
 * 三条路各自可能失效，所以都留着：mDNS → NBSTAT → 反向 DNS，都没有就显示 IP。
 */
class MdnsNames(private val context: Context) {

    companion object {
        private const val SERVICE_TYPE = "_smb._tcp"
    }

    /** 返回 IP → 设备名。拿不到就返回空表，绝不抛。 */
    fun discover(timeoutMs: Int = 2_500): Map<String, String> {
        val nsd = context.applicationContext
            .getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return emptyMap()
        val found = HashMap<String, String>()
        val pending = ArrayDeque<NsdServiceInfo>()
        val lock = Any()
        var resolving = false

        // 老版本 NsdManager 不允许并发 resolveService，只能一个一个来。
        fun resolveNext() {
            synchronized(lock) {
                if (resolving) return
                val next = pending.removeFirstOrNull() ?: return
                resolving = true
                val listener = object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                        synchronized(lock) { resolving = false }
                        resolveNext()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo) {
                        val ip = info.host?.hostAddress
                        val name = clean(info.serviceName)
                        if (ip != null && name != null) {
                            synchronized(found) { found[ip] = name }
                        }
                        synchronized(lock) { resolving = false }
                        resolveNext()
                    }
                }
                @Suppress("DEPRECATION")
                runCatching { nsd.resolveService(next, listener) }
                    .onFailure { synchronized(lock) { resolving = false } }
            }
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onServiceFound(info: NsdServiceInfo) {
                synchronized(lock) { pending.addLast(info) }
                resolveNext()
            }

            override fun onServiceLost(info: NsdServiceInfo) = Unit
        }

        if (runCatching { nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discovery) }
                .isFailure
        ) return emptyMap()

        // 回调在主线程，这里只是阻塞 IO 线程，不会互相卡住。
        // 窗口内来多少收多少：它和端口扫描并行跑，等满不会让用户多等。
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) Thread.sleep(60)
        runCatching { nsd.stopServiceDiscovery(discovery) }
        Thread.sleep(200)
        return synchronized(found) { HashMap(found) }
    }

    /** "客厅的群晖 (SMB)" / "DiskStation._smb._tcp.local" 之类，只留名字本身。 */
    private fun clean(raw: String): String? {
        var name = raw.substringBefore("._").trim()
        name = name.substringBefore(" (SMB)").substringBefore("(SMB)").trim()
        if (name.length < 2) return null
        if (name.any { it == '\uFFFD' || it == '?' }) return null
        return name.take(32)
    }
}
