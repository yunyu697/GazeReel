package com.gazereel.vr.core

/**
 * 真机验收用的「指定起播」请求：从 Intent extra 拿一个片名片段，落到片库里的一部片。
 *
 * 为什么要有这一层：九项验收每一项都要求「放**指定的那一部**片」，而手机塞进纸盒之后
 * 没法翻页、也没法确认自己点中的是哪一个格子。让 adb 一条命令点名起播，验收才能被
 * 脚本驱动（见 `tools/verify_device.sh`），截图与运行日志才能按项命名归档，
 * 也不会出现「点错片子、截图记成 V4」这种把结论记错账的事。
 *
 * 这里只做字符串处理、不碰 Android，所以能用 JVM 直接断言
 * （`tools/ClipRequestCheck.kt`，由 `tools/run-format-check.sh` 驱动）。
 */
object ClipRequest {

    /** Intent extra：要播的片名片段（可带路径与扩展名，会被归一）。 */
    const val EXTRA_CLIP = "gazereel.clip"

    /** Intent extra：验收项号（V1…V9），只用于日志里把「哪一项」和「哪部片」对上。 */
    const val EXTRA_ITEM = "gazereel.item"

    /**
     * 片名归一：取最后一段路径、去掉扩展名、转小写。
     *
     * 归一后至少要有 2 个字母数字字符，否则返回 null —— 单字符（比如 `a`）会命中片库里
     * 几乎任何一部片，那比「没命中」更糟：验收人会拿到一部不相干的片子还以为在验 V4。
     */
    fun normalize(raw: String?): String? {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty()) return null
        val cut = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = cut.lastIndexOf('.')
        // dot > 0：`.mp4` 这种整串就是扩展名的形态不切，切完会变成空串。
        val stem = if (dot > 1) cut.substring(0, dot) else cut
        val lower = stem.trim().lowercase()
        return lower.takeIf { loose(it).length >= 2 }
    }

    /**
     * 比对用的松形式：只保留字母与数字。
     *
     * 片名里的分隔符各人各样（`test-180-sbs` / `test_180_sbs` / `test 180 sbs`），
     * 而片库标题来自文件名、可能被索引改写过。忽略分隔符，查的人不必猜是哪一种。
     */
    fun loose(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) if (ch.isLetterOrDigit()) sb.append(ch.lowercaseChar())
        return sb.toString()
    }

    /**
     * 比对键：先按 [normalize] 去掉目录与扩展名，再取 [loose]。
     *
     * 两边都必须走这一步。只归一查询、不归一标题，`xxx.mp4` 的键就会拖着 `mp4` 尾巴，
     * 于是「全等」档永远命中不了，同等前缀的两部片只能靠「包含」档的先后顺序决出——
     * 那是文件名排序，不是语义。（这条是 `ClipRequestCheck` 抓到后改的，
     * 第一版就是这么写的。）
     */
    private fun key(raw: String): String = loose(normalize(raw) ?: raw)

    /** 标题是否是这个查询的候选（子串匹配，分隔符、大小写、扩展名都无关）。 */
    fun matches(title: String, query: String): Boolean {
        val q = normalize(query) ?: return false
        val k = key(title)
        return k.isNotEmpty() && k.contains(loose(q))
    }

    /**
     * 从一组标题里挑最像的一条，返回下标；没有候选返回 -1。
     *
     * 分档而不是「第一个包含就算」：片库里可能同时有 `test-360-equirect-mono` 与
     * `test-360-equirect-mono-4k`，谁排在前面取决于文件名排序，验收要的是**确定性**。
     * 全等 > 前缀 > 包含；同档取靠前的（调用方按文件名升序传进来）。
     */
    fun pick(titles: List<String>, query: String): Int {
        val q = normalize(query) ?: return -1
        val looseQ = loose(q)
        if (looseQ.isEmpty()) return -1

        val keys = titles.map { key(it) }
        keys.indexOfFirst { it == looseQ }.let { if (it >= 0) return it }
        keys.indexOfFirst { it.startsWith(looseQ) }.let { if (it >= 0) return it }
        return keys.indexOfFirst { it.contains(looseQ) }
    }

    /**
     * 项号归一：`v4` / `V4` / ` 4 ` 都给出 `V4`；不是 V + 数字一律 null。
     *
     * 只进日志，不影响起播；写错项号时要立刻看出来，而不是悄悄记成别的项。
     */
    fun item(raw: String?): String? {
        val s = raw?.trim()?.uppercase().orEmpty()
        if (s.isEmpty()) return null
        val digits = s.removePrefix("V")
        val n = digits.toIntOrNull() ?: return null
        if (n <= 0 || n > 99) return null
        return "V$n"
    }

    /** 日志用的一行描述：`item=V4 clip=fisheye-circle` 之类。 */
    fun describe(itemRaw: String?, clipRaw: String?): String {
        val item = item(itemRaw) ?: "?-"
        val clip = normalize(clipRaw) ?: "-"
        return "item=$item clip=$clip"
    }
}
