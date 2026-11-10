package com.gazereel.vr.ui

import com.gazereel.vr.data.db.MediaItemEntity

/**
 * V2.16：一叠 = 一次拍摄，或一个文件夹。
 *
 * 设计 08 章的三层是「柜子 → 叠 → 片」：柜子是一台设备或一个片源，叠是同一批素材，
 * 片才是单个文件。之前只做了柜子和片，于是 832 部平铺成 104 页 —— 用户原话是「噩梦」。
 *
 * 叠的判据按内容形态取，不按协议取：
 * - 网络共享里的片子天然分文件夹（`Movies\2024`、`DCIM\100GOPRO`），按文件夹成叠；
 * - 手机与相机拍的片子常常全堆在一个目录里，文件名里带着拍摄时间，按日期成叠。
 *
 * 两种情况都不成立时（比如只有十来个文件、或者名字完全看不出规律）**不给叠**，
 * 老老实实平铺——多一层没意义的点击比多翻两页更烦。
 */
data class MediaStack(
    val key: String,
    val label: String,
    val detail: String,
    val items: List<MediaItemEntity>
)

object StackBuilder {

    /** 少于这么多部就不值得成叠：一屏就装下了。 */
    private const val MIN_ITEMS = VrUiState.PAGE_SIZE * 3

    /** 至少要能分成这么多叠才值得成叠，否则还是平铺更好用。 */
    private const val MIN_STACKS = 3

    private val STAMP_COMPACT = Regex("(\\d{4})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})(\\d{2})")
    private val STAMP_LOOSE =
        Regex("(\\d{4})-(\\d{2})-(\\d{2})[ _T](\\d{2})[:.](\\d{2})(?:[:.](\\d{2}))?")

    fun build(items: List<MediaItemEntity>): List<MediaStack> {
        if (items.size < MIN_ITEMS) return emptyList()

        val byFolder = groupByFolder(items)
        if (byFolder.size >= MIN_STACKS) return byFolder

        val byDate = groupByDate(items)
        if (byDate.size >= MIN_STACKS) return byDate

        return emptyList()
    }

    /** `Movies\2024\a.mp4` → `Movies\2024`；`content://` 这种没有目录概念，返回 null。 */
    private fun folderOf(item: MediaItemEntity): String? {
        val path = item.path
        if (path.startsWith("content://", true)) return null
        if (path.startsWith("http://", true) || path.startsWith("https://", true)) {
            val clean = path.substringBefore('?').substringBefore('#').trimEnd('/')
            return clean.substringBeforeLast('/', "").ifBlank { null }
        }
        val cut = path.lastIndexOfAny(charArrayOf('\\', '/'))
        if (cut <= 0) return null
        return path.substring(0, cut).trim('\\', '/').ifBlank { null }
    }

    private fun groupByFolder(items: List<MediaItemEntity>): List<MediaStack> {
        val groups = LinkedHashMap<String, MutableList<MediaItemEntity>>()
        for (item in items) {
            val folder = folderOf(item) ?: continue
            groups.getOrPut(folder) { mutableListOf() } += item
        }
        if (groups.size < MIN_STACKS) return emptyList()
        return groups.map { (folder, list) ->
            // 一叠只显示最后一级目录名，太长会把格子撑爆。
            val name = folder.substringAfterLast('\\').substringAfterLast('/')
            MediaStack(
                key = "dir:$folder",
                label = name.ifBlank { folder },
                detail = "${list.size} 部",
                items = list.sortedByDescending { it.title }
            )
        }.sortedBy { it.label.lowercase() }
    }

    /**
     * 按拍摄日期成叠。日期只从文件名里读——扫描期拿不到真实拍摄时间，
     * 而 `mtime` 记的是「入库时间」，用它分叠等于全部落在今天，反而更误导。
     */
    private fun groupByDate(items: List<MediaItemEntity>): List<MediaStack> {
        val groups = LinkedHashMap<String, MutableList<MediaItemEntity>>()
        for (item in items) {
            val stamp = stampOf(item.title) ?: continue
            groups.getOrPut(stamp) { mutableListOf() } += item
        }
        if (groups.size < MIN_STACKS) return emptyList()
        return groups.map { (stamp, list) ->
            val parts = stamp.split('-')
            MediaStack(
                key = "day:$stamp",
                label = if (parts.size == 3) {
                    "${parts[1].trimStart('0')} 月 ${parts[2].trimStart('0')} 日"
                } else stamp,
                detail = "${list.size} 部",
                items = list.sortedByDescending { it.title }
            )
        }.sortedByDescending { it.key }
    }

    private fun stampOf(title: String): String? {
        STAMP_COMPACT.find(title)?.let { m ->
            return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
        }
        STAMP_LOOSE.find(title)?.let { m ->
            return "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}"
        }
        return null
    }
}
