package com.gazereel.vr.data

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.nio.charset.Charset
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * V2.15（C8b）：只开了共享、没开媒体服务器的设备，SSDP 一辈子发现不了。
 *
 * 用户的真机反馈是「这个 Wi-Fi 下没找到设备，但局域网内有两台 NAS」——两台 NAS 都在，
 * 只是它们没应答 M-SEARCH。SSDP 找的是 DLNA 服务，SMB 共享根本不在它的广播范围里，
 * 所以「没找到设备」这句话在只开 SMB 的网络里永远是错的。
 *
 * 这里换成直接问网络：本机所在的 /24 网段里，谁在 445 端口上等连接。
 * 扫到之后再问一次 NetBIOS 名称，界面上就能写「客厅的群晖」而不是「192.168.1.10」。
 */
class LanSmbScanner {

    data class Host(val address: String, val name: String = "") {
        /** 有名字就用名字；没问到就老老实实显示 IP，不要编一个假名字。 */
        val label: String get() = name.ifBlank { address }
    }

    companion object {
        private const val SMB_PORT = 445
        /** 局域网内没开 445 的地址会在 SYN 阶段就没回应，260ms 足够判死。 */
        private const val CONNECT_TIMEOUT_MS = 260
        /** 并发 48 路：254 个地址最坏 6 轮，总耗时约 1.6 秒。 */
        private const val WORKERS = 48
        private const val MAX_HOSTS = 254
        private const val NBNS_PORT = 137
        private const val NBNS_TIMEOUT_MS = 350
    }

    /** 反向 DNS 专用：缓存池，被卡住的线程不会拖住后面的查询。 */
    private val dnsPool = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "lan-reverse-dns").apply { isDaemon = true }
    }

    /**
     * 扫本机所在网段，返回在 445 端口上应答的地址。
     * 只扫自己所在的那一段（最多 254 个），不会为了找两台 NAS 把整栋楼的地址都连一遍。
     */
    fun scan(): List<Host> {
        val targets = candidates()
        if (targets.isEmpty()) return emptyList()
        val pool = Executors.newFixedThreadPool(WORKERS) { runnable ->
            Thread(runnable, "lan-smb-probe").apply { isDaemon = true }
        }
        val alive = try {
            val tasks = targets.map { ip ->
                Callable {
                    if (!reachable(ip)) return@Callable null
                    // 先问 NetBIOS（最快、最像用户在电脑上看到的那个名字），
                    // 问不到再试反向 DNS；都没有就显示 IP，不编名字。
                    Host(ip, netbiosName(ip) ?: reverseName(ip).orEmpty())
                }
            }
            pool.invokeAll(tasks).mapNotNull { future ->
                runCatching { future.get() }.getOrNull()
            }
        } finally {
            pool.shutdownNow()
        }
        return alive.sortedBy { numeric(it.address) }
    }

    private fun reachable(ip: String): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(ip, SMB_PORT), CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    /**
     * 家用路由基本都是 /24；遇到 /16 这种大网段也只取自己那 254 个。
     * 前缀比 /30 还窄时没有可用主机地址，直接给空表。
     */
    private fun candidates(): List<String> {
        val local = localIpv4() ?: return emptyList()
        val octets = local.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return emptyList()
        val value = octets.fold(0) { acc, part -> (acc shl 8) or (part and 0xFF) }
        val prefix = localPrefix().coerceIn(24, 30)
        val network = value and (-1 shl (32 - prefix))
        val size = 1 shl (32 - prefix)
        val result = mutableListOf<String>()
        for (offset in 1 until size - 1) {
            val ip = network + offset
            if (ip == value) continue
            result += "${(ip ushr 24) and 0xFF}.${(ip ushr 16) and 0xFF}." +
                "${(ip ushr 8) and 0xFF}.${ip and 0xFF}"
            if (result.size >= MAX_HOSTS) break
        }
        return result
    }

    /** wlan 优先，避免选到热点、VPN 或者蜂窝上的地址。 */
    private fun localIpv4(): String? = runCatching {
        interfaces().firstNotNullOfOrNull { nif ->
            nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .firstOrNull { it.isSiteLocalAddress }?.hostAddress
        }
    }.getOrNull()

    private fun localPrefix(): Int = runCatching {
        interfaces().firstNotNullOfOrNull { nif ->
            nif.interfaceAddresses
                .firstOrNull { it.address is Inet4Address }?.networkPrefixLength
        }?.toInt()
    }.getOrNull() ?: 24

    private fun interfaces(): List<NetworkInterface> =
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan", true)) 0 else 1 }

    /**
     * NetBIOS 名称查询。反向 DNS 在家用路由上时有时无，NBSTAT 则是 NAS 与 Windows
     * 一直在答的东西，问到的就是用户在电脑「网络」里看到的那个名字。
     * 任何一步失败都返回 null —— 名字是锦上添花，不能因为它让设备从列表里消失。
     */
    private fun netbiosName(ip: String): String? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = NBNS_TIMEOUT_MS
            val query = nbstatQuery()
            socket.send(DatagramPacket(query, query.size, InetAddress.getByName(ip), NBNS_PORT))
            val buffer = ByteArray(1024)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            parseNbstat(buffer, packet.length)
        }
    }.getOrNull()

    /**
     * NBSTAT 查询包：12 字节头 + 编码后的通配名「*」+ QTYPE 0x0021 / QCLASS 0x0001。
     * NetBIOS 名字按「每字符拆成两个 nibble 再加 'A'」编码，'*' = 0x2B → "CK"，
     * 其余 15 个字符补零 → "AA"，所以编码结果是 "CK" + 30 个 'A'。
     */
    private fun nbstatQuery(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x12, 0x34, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        out.write(0x20)
        out.write(("CK" + "A".repeat(30)).toByteArray(Charsets.US_ASCII))
        out.write(0)
        out.write(byteArrayOf(0, 0x21, 0, 1))
        return out.toByteArray()
    }

    /** 应答里每个名字条目 18 字节：15 字节名字（空格补齐）+ 1 字节后缀 + 2 字节标志位。 */
    private fun parseNbstat(data: ByteArray, length: Int): String? {
        var offset = 12
        // 问题段：长度字节 + 名字 + 结尾 0，然后 QTYPE / QCLASS 各 2 字节。
        while (offset < length) {
            val len = data[offset].toInt() and 0xFF
            offset += 1
            if (len == 0) break
            offset += len
        }
        offset += 4
        // 答案段的名字可能是压缩指针（0xC0），也可能是完整名字，两种都吃。
        if (offset < length && (data[offset].toInt() and 0xC0) == 0xC0) {
            offset += 2
        } else {
            while (offset < length) {
                val len = data[offset].toInt() and 0xFF
                offset += 1
                if (len == 0) break
                offset += len
            }
        }
        offset += 2 + 2 + 4
        if (offset + 2 > length) return null
        val rdLength = readShort(data, offset)
        offset += 2
        if (rdLength <= 0 || offset >= length) return null
        val count = data[offset].toInt() and 0xFF
        offset += 1

        var workstation: String? = null
        var fileServer: String? = null
        for (index in 0 until count) {
            if (offset + 18 > length) break
            val suffix = data[offset + 15].toInt() and 0xFF
            val group = (readShort(data, offset + 16) and 0x8000) != 0
            val clean = decodeName(data, offset, 15)
            offset += 18
            if (clean == null) continue
            // 0x00 = 工作站名，0x20 = 文件服务器名；组名（workgroup）不要。
            if (!group && suffix == 0x00 && workstation == null) workstation = clean
            if (!group && suffix == 0x20 && fileServer == null) fileServer = clean
        }
        return workstation ?: fileServer
    }

    /**
     * V2.16：NetBIOS 名字是 **OEM 编码**，中文名在中文 NAS / Windows 上是 GBK。
     * 原来按 US-ASCII 解，一个中文名变成一串问号，被 [sanitize] 一拒就只剩 IP——
     * 真机反馈「2 台 NAS 已经能发现，但没有显示名称」就是这么来的。
     * GBK 能正确解出纯 ASCII 名，所以先试它；解出乱码就换下一个。
     */
    private fun decodeName(data: ByteArray, offset: Int, length: Int): String? {
        val raw = data.copyOfRange(offset, offset + length)
        for (charset in listOf("GBK", "UTF-8")) {
            val text = runCatching { String(raw, Charset.forName(charset)) }.getOrNull() ?: continue
            sanitize(text)?.let { return it }
        }
        return null
    }

    private fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    /** 名字里有控制字符或长得不像主机名，就别往界面上贴。 */
    private fun sanitize(raw: String): String? {
        val clean = raw.trim()
        if (clean.length < 2) return null
        // '\uFFFD' 表示解码失败；'?' 是 ASCII 硬解中文名的产物，两种都不要。
        if (clean.any { it == '\uFFFD' || it == '?' }) return null
        if (!clean.all { it.isLetterOrDigit() || it in " -_." }) return null
        return clean.take(32)
    }

    /**
     * 反向 DNS 兜底。`canonicalHostName` 没有超时参数，在没配 DNS 的网络上会挂很久，
     * 所以丢进独立线程并只等 400ms——拿不到就当没有，绝不让「找设备」卡住。
     */
    private fun reverseName(ip: String): String? = runCatching {
        val task = dnsPool.submit(Callable { InetAddress.getByName(ip).canonicalHostName })
        val name = task.get(400, TimeUnit.MILLISECONDS)
        if (name.isNullOrBlank() || name == ip) return@runCatching null
        sanitize(name.substringBefore('.'))
    }.getOrNull()

    private fun numeric(ip: String): Long = ip.split('.')
        .fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull() ?: 0L) }
}
