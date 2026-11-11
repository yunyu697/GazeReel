package com.gazereel.vr.data

import com.gazereel.vr.core.FieldOfView
import com.gazereel.vr.core.FormatDetector
import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.SphericalMetadata
import com.gazereel.vr.core.StereoLayout
import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.db.AppDatabase
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
         */
        const val MEMORY_VERSION = 4
        const val MEMORY_VERSION_KEY = "format_memory_version"
    }

    data class FormatResolution(val format: VrFormat, val needsConfirmation: Boolean)

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
        // 高置信度结论直接记住：少弹一次确认，也省掉下次的探测开销。
        if (!detected.needsConfirmation && detected.format.confidence >= 0.85f) {
            rememberFormat(ref, detected.format)
        }
        return FormatResolution(detected.format, detected.needsConfirmation)
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

    /** 目录内自动连播：按标题序取下一项，末尾回到第一项。 */
    suspend fun nextOf(mediaId: Long): MediaItemEntity? {
        val list = db.mediaDao().all()
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == mediaId }
        return list[(index + 1).coerceAtLeast(0) % list.size]
    }

    suspend fun previousOf(mediaId: Long): MediaItemEntity? {
        val list = db.mediaDao().all()
        if (list.isEmpty()) return null
        val index = list.indexOfFirst { it.id == mediaId }
        return list[(index - 1 + list.size) % list.size]
    }

    private fun parse(parts: String): VrFormat {
        val split = parts.split("/")
        return if (split.size == 3) parse(split[0], split[1], split[2]) else VrFormat.DEFAULT
    }

    private fun parse(p: String, l: String, f: String): VrFormat = runCatching {
        VrFormat(Projection.valueOf(p), StereoLayout.valueOf(l), FieldOfView.valueOf(f))
    }.getOrDefault(VrFormat.DEFAULT)
}
