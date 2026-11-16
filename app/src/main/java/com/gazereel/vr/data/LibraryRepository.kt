package com.gazereel.vr.data

import com.gazereel.vr.core.FieldOfView
import com.gazereel.vr.core.FormatDetector
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.Refusal
import com.gazereel.vr.core.SphericalMetadata
import com.gazereel.vr.core.StereoLayout
import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.db.AppDatabase
import com.gazereel.vr.data.db.FileNameOrder
import com.gazereel.vr.data.db.FormatOverrideEntity
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.PlayProgressEntity

/** 片库 / 格式覆盖 / 观看进度 / 自动连播的统一读写入口。 */
class LibraryRepository(private val db: AppDatabase) {

    companion object {
        /**
         * 格式记忆的规则版本。识别规则换代时 +1，启动时清空旧覆盖表。
         * 覆盖表里存的是「旧规则 + 用户当时那个错误建议」的产物，留着只会一直错下去。
         *
         * V2.20 → 3：判据顺序换了（先定布局再算视场），并引入抽帧。
         * 之前那批「SBS 被记成 Mono」的结论必须清掉，否则新规则一条都用不上——
         * 这是 A4 那条最贵的教训：**自动记住的结论比 bug 更顽固**。
         *
         * V2.31 → 4：抽帧曾经可以单方面把 SBS 降级成 MONO 并写回这张表，
         * 于是「打开就正对接缝」被记忆固化、几乎每部片都中招。规则已改成
         * 「抽帧只许升级、不许降级」，但被污染的旧记录不会自己消失，升版清一次。
         *
         * V2.40 → 5：抽帧那条链路已整条删除，识别只剩「文件名 > 元数据 > 几何」三条
         * 确定性判据。表里那些「4096×2048 被判成 360 Mono」的记录全是抽帧时代的产物，
         * 留着会继续让画面正对接缝；清空后按新规则重判一遍，同一部片在旧规则下
         * 手选过的 180 SBS，新规则算出来还是 180 SBS，结论一致，不会白清。
         */
        const val MEMORY_VERSION = 5
        const val MEMORY_VERSION_KEY = "format_memory_version"
    }

    data class FormatResolution(
        val format: VrFormat,
        val needsConfirmation: Boolean,
        /** 19:2x：明确拒绝的片源（未缝合双鱼眼 360）。非 null 时 format 已是 2D 银幕。 */
        val refusal: Refusal? = null
    )

    /** 已经确认过的格式：命中就不必再去文件里探测，播放可以立刻开始。 */
    suspend fun cachedFormat(ref: MediaRef): VrFormat? {
        val override = db.formatOverrideDao().get(ref.mediaKey) ?: return null
        return parse(override.projection, override.layout, override.fov)
    }

    /**
     * PRD 6.2：覆盖表优先；没有覆盖时按当前证据链现场判定。
     *
     * V2.7.6：多了一个 [probe] 参数——播放前从容器里读出来的事实（真实宽高、
     * 立体排布、球面声明、裁剪比）。有它就不用猜文件名了。
     * 判定足够确定时顺手写进覆盖表，同一个文件下次直接命中，不再探测。
     */
    suspend fun resolveFormat(
        ref: MediaRef,
        item: MediaItemEntity,
        probe: SphericalMetadata.Probe? = null
    ): FormatResolution {
        val override = db.formatOverrideDao().get(ref.mediaKey)
        if (override != null) {
            return FormatResolution(parse(override.projection, override.layout, override.fov), false)
        }
        // V2.5：旧版扫描把「无宽高 → DEFAULT 360」固化进 detected_format，
        // 直接读它会让普通 2D 视频永远进不了世界锁定银幕。一律用当前规则现场重判；
        // detected_format 保留为兼容字段，不再作为判据。
        val width = probe?.width?.takeIf { it > 0 } ?: item.width
        val height = probe?.height?.takeIf { it > 0 } ?: item.height
        val detected = FormatDetector().detect(item.path, width, height, probe)
        // 一条日志说清「靠什么判的」：真机报格式问题时，先看这一行，
        // 就知道是文件名、元数据还是几何定的，不用再猜。
        RunLog.append(
            "fmt",
            "${item.title} → ${detected.format.label} · 依据 ${detected.source} · " +
                "${width}x$height · ${probe?.describe() ?: "无元数据"}"
        )
        // 高置信度结论直接记住：少弹一次确认，也省掉下次的探测开销。
        //
        // 19:2x：**拒绝的结论不写进记忆**。记忆的语义是「用户/证据确认过这部片是什么」，
        // 而拒绝是我们这一版算不出来 —— 将来支持双鱼眼了，旧记忆会把片子永久钉在 2D 上，
        // 用户还得手动清一次格式记忆。每次打开现判一次，代价只有一个字符串匹配。
        if (!detected.needsConfirmation && detected.format.confidence >= 0.85f &&
            detected.refusal == null
        ) {
            rememberFormat(ref, detected.format)
        }
        return FormatResolution(detected.format, detected.needsConfirmation, detected.refusal)
    }

    /** 用户六选一确认后，按文件记住，下次播放不再弹确认。 */
    suspend fun rememberFormat(ref: MediaRef, format: VrFormat) {
        db.formatOverrideDao().put(
            FormatOverrideEntity(
                mediaKey = ref.mediaKey,
                projection = format.projection.name,
                layout = format.layout.name,
                fov = format.fov.name,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun clearFormatMemory() = db.formatOverrideDao().clear()

    /** V2.18：断点挂在 `sourceId:path` 上，不再挂在会变的自增 id 上。 */
    suspend fun resumePosition(mediaKey: String): Long =
        db.progressDao().get(mediaKey)?.positionMs ?: 0L

    suspend fun saveProgress(mediaKey: String, positionMs: Long, durationMs: Long) {
        val finished = durationMs > 0 && positionMs >= durationMs - 1_000L
        db.progressDao().put(
            PlayProgressEntity(
                mediaKey = mediaKey,
                positionMs = positionMs,
                updatedAt = System.currentTimeMillis(),
                finished = finished
            )
        )
    }

    /**
     * alpha35 + 数据层：自动连播的顺序只有这一份 —— **未隐藏片源 + 文件名升序**。
     *
     * 原来 `nextOf()/previousOf()` 直接 `all()` 查全表：隐藏掉的片源照样会被
     * 连播出来（用户藏了它，过一会儿它自己又播上了），而且 DAO 无序、片库页按
     * 另一套规则排，「下一部」和屏幕上看到的顺序对不上。
     */
    private suspend fun playbackOrder(): List<MediaItemEntity> =
        FileNameOrder.sorted(db.mediaDao().allVisible())

    private fun neighbourOf(
        list: List<MediaItemEntity>,
        mediaId: Long,
        step: Int
    ): MediaItemEntity? {
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == mediaId }
        // 当前片来自已隐藏 / 已删除的片源：不猜位置，从可见列表第一部重新开始。
        if (index < 0) return list.first()
        return list[(index + step + list.size) % list.size]
    }

    /** 自动连播：按文件名序取下一项，末尾回到第一项。 */
    suspend fun nextOf(mediaId: Long): MediaItemEntity? =
        neighbourOf(playbackOrder(), mediaId, 1)

    suspend fun previousOf(mediaId: Long): MediaItemEntity? =
        neighbourOf(playbackOrder(), mediaId, -1)

    private fun parse(parts: String): VrFormat {
        val split = parts.split("/")
        return if (split.size == 3) parse(split[0], split[1], split[2]) else VrFormat.DEFAULT
    }

    private fun parse(p: String, l: String, f: String): VrFormat = runCatching {
        VrFormat(Projection.valueOf(p), StereoLayout.valueOf(l), FieldOfView.valueOf(f))
    }.getOrDefault(VrFormat.DEFAULT)
}
