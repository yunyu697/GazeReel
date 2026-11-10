package com.gazereel.vr.data

/**
 * V2.15：一次「找设备」的完整结果。
 *
 * 分三类而不是一句「找到 / 没找到」，因为用户看到的三种情况要给出三种不同的下一步：
 * - servers：开了媒体服务器的设备，点一下直接进，不用账号；
 * - smbHosts：只开了共享的设备（用户那两台 NAS 就属于这种），点一下进选共享，要账号；
 * - responders：回了 SSDP 的台数。一台都没找到时，用它把「网络不通」和
 *   「设备开了广播但没开共享」分开讲。
 */
data class LanScan(
    val servers: List<DlnaScanner.Server> = emptyList(),
    val smbHosts: List<LanSmbScanner.Host> = emptyList(),
    val responders: Int = 0
) {
    val total: Int get() = servers.size + smbHosts.size

    /**
     * 共享设备在列表里的写法。两台 NAS 都叫「NAS」时，光看名字分不出是哪一台，
     * 重名才补 IP —— 名字本身有价值，不能每台后面都拖一串数字。
     */
    fun smbLabels(): List<String> {
        val counts = smbHosts.groupingBy { it.label }.eachCount()
        return smbHosts.map { host ->
            val base = if ((counts[host.label] ?: 0) > 1) {
                "${host.label} · ${host.address}"
            } else host.label
            "$base · 共享文件夹"
        }
    }
}
