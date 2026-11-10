package com.gazereel.vr.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.gazereel.vr.core.SourceType
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.SourceEntity
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar

/**
 * PRD FR-10 海报 / 预览图。
 *
 * ## V2.19：为什么重写（用户原话「片库的预览图真是难尽，也太敷衍了」）
 *
 * 上一版对**所有**没有同名图片的片子生成同一张渐变封面，中间放文件名首字母。
 * 而片库里绝大多数是 `VID_20260712_143022.mp4` 这种相机命名 —— 首字母全是 **V**。
 * 于是八格片库看起来是八张一模一样的「V」。
 *
 * 那不是「封面不好看」，是**没有信息**：八个格子长得一样就等于没有格子。
 *
 * 现在分两条路：
 *
 * 1. **本地视频真的抽一帧**（[fetchLocalFrame]）。手机视频在 MediaStore 里是
 *    `content://`，`MediaMetadataRetriever.setDataSource(context, uri)` 可以直接读，
 *    不需要 fd 也不需要把整个文件读进来。这才是「预览图」本来该有的样子。
 * 2. 抽不到（网络片源 / 私有目录无权限 / 损坏文件）时，生成**带身份信息**的封面：
 *    色相由文件名散列决定（相邻两条颜色不同），中间是**时刻**，上面是日期，
 *    下面是时长。仍然是「一眼能认出是哪一段」的信息，而不是一个字母。
 *
 * 网络片源为什么不抽帧：SMB / WebDAV 只有自定义 DataSource，没有可交给
 * MediaMetadataRetriever 的 fd；强行整段拉流会在前台播放时抢带宽。
 * 这条限制写在 [MediaIndexer] 的扫描流程里，不是遗漏。
 */
class PosterRepository(private val appContext: Context) {

    companion object {
        private const val POSTER_W = 320
        private const val POSTER_H = 180
        private const val MAX_FILES = 200
        private const val CACHE_KB = 6 * 1024

        /**
         * 封面版本。改生成规则时必须进位——文件名带版本号，旧文件自动失效重生成。
         * 否则用户手机上那批「一个 V」的旧封面会一直留在库里。
         */
        private const val GENERATION = "g2"

        /**
         * 文件名里的来源标记：`r` = 真画面（抽帧或同名图片），`c` = 生成的兜底封面。
         *
         * 绘制层要知道这件事：兜底封面**自己已经把日期和时刻画在身上了**，
         * 再压一条身份条就是同一句话写两遍。抽出来的真画面才需要补。
         */
        private const val MARK_FRAME = "_${GENERATION}r_"
        private const val MARK_COVER = "_${GENERATION}c_"

        /** 这个封面是不是「画出来的」而不是「拍出来的」。 */
        fun isGeneratedCover(path: String): Boolean = path.contains(MARK_COVER)

        /** 20260712_143022 这类相机 / 手机命名。 */
        private val COMPACT = Regex("(\\d{4})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})(\\d{2})")

        /** 2026-07-12 14.30.22 / 2026-07-12 14:30 这类导出后改名。 */
        private val LOOSE =
            Regex("(\\d{4})-(\\d{2})-(\\d{2})[ _T](\\d{2})[:.](\\d{2})(?:[:.](\\d{2}))?")
    }

    private val dir = File(appContext.filesDir, "posters").apply { mkdirs() }
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** @return mediaId → 封面文件绝对路径；已是最新版本的条目不返回。 */
    fun refresh(
        items: List<MediaItemEntity>,
        source: SourceEntity,
        credentials: SmbCredentials
    ): Map<Long, String> {
        val type = runCatching { SourceType.valueOf(source.type) }.getOrDefault(SourceType.LOCAL)
        val out = HashMap<Long, String>()
        for (item in items) {
            if (isCurrent(item.posterPath)) continue
            val real = when (type) {
                SourceType.WEBDAV, SourceType.HTTP, SourceType.DLNA ->
                    runCatching { fetchNetworkPoster(item.path, credentials) }.getOrNull()
                SourceType.LOCAL ->
                    runCatching { fetchLocalSiblingImage(item.path) }.getOrNull()
                        ?: runCatching { fetchLocalFrame(item.path) }.getOrNull()
                else -> null
            }
            val bitmap = real ?: generateCover(item)

            val file = File(dir, fileNameOf(item, generated = real == null))
            val saved = runCatching {
                FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            }.isSuccess
            if (saved) out[item.id] = file.absolutePath
        }
        trimFiles()
        return out
    }

    fun load(path: String): Bitmap? {
        if (path.isEmpty()) return null
        cache.get(path)?.let { return it }
        val bitmap = runCatching { BitmapFactory.decodeFile(path) }.getOrNull() ?: return null
        cache.put(path, bitmap)
        return bitmap
    }

    /** 路径里带当前代号的才算「已经是新封面」，否则重新生成。 */
    private fun isCurrent(path: String): Boolean =
        path.isNotEmpty() && path.contains("_$GENERATION") && File(path).isFile

    private fun fileNameOf(item: MediaItemEntity, generated: Boolean): String {
        val mark = if (generated) MARK_COVER else MARK_FRAME
        return "${item.sourceId}$mark%08x".format(item.path.hashCode()) + ".png"
    }

    // ── 本地：同名图片 → 真实抽帧 ────────────────────────────────────────

    private fun fetchLocalSiblingImage(path: String): Bitmap? {
        if (path.startsWith("content://")) return null
        val base = path.substringBeforeLast('.')
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val file = File("$base.$ext")
            if (!file.isFile) continue
            BitmapFactory.decodeFile(file.absolutePath)?.let { return centerCrop(it) }
        }
        return null
    }

    /**
     * 抽一帧真画面。
     *
     * 取 1 秒处而不是第 0 帧：相机片段的开头经常是黑场、转场或镜头盖。
     * `OPTION_CLOSEST_SYNC` 允许就近取关键帧，比逐帧解码快一个数量级。
     */
    private fun fetchLocalFrame(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            val uri = Uri.parse(path)
            if (uri.scheme == "content") {
                retriever.setDataSource(appContext, uri)
            } else {
                retriever.setDataSource(path)
            }
            val frame = retriever.getFrameAtTime(1_000_000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: return null
            centerCrop(frame)
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** 等比铺满再居中裁切——直接拉伸会把 16:9 拉成 320×180 之外的比例，人脸会变形。 */
    private fun centerCrop(src: Bitmap): Bitmap {
        if (src.width <= 0 || src.height <= 0) return src
        val scale = maxOf(POSTER_W.toFloat() / src.width, POSTER_H.toFloat() / src.height)
        val matrix = Matrix().apply { postScale(scale, scale) }
        val scaled = Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
        val x = ((scaled.width - POSTER_W) / 2).coerceAtLeast(0)
        val y = ((scaled.height - POSTER_H) / 2).coerceAtLeast(0)
        if (scaled.width <= POSTER_W || scaled.height <= POSTER_H) return scaled
        return Bitmap.createBitmap(scaled, x, y, POSTER_W, POSTER_H)
    }

    // ── 网络：同名图片（与视频同路径换扩展名） ───────────────────────────

    private fun fetchNetworkPoster(url: String, credentials: SmbCredentials): Bitmap? {
        val client = LanHttpClient(credentials)
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val candidate = "${url.substringBefore('?').substringBefore('#')
                .substringBeforeLast('.')}.$ext"
            val response = runCatching { client.request("GET", candidate) }.getOrNull() ?: continue
            if (response.code !in 200..299 || response.body.isEmpty()) continue
            val bitmap = BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
            if (bitmap != null) return centerCrop(bitmap)
        }
        return null
    }

    // ── 兜底封面：带身份信息，不是一个字母 ──────────────────────────────

    /**
     * 抽不到画面时画一张「能认出是哪一段」的封面。
     *
     * 三条信息按重要性排：**时刻**（用户记得住的是几点拍的）> 日期 > 时长。
     * 底色由文件名散列决定色相，所以同一批 `VID_...` 也不会是一片同色 ——
     * 片库里「能一眼分开」比「好看」更重要。
     */
    private fun generateCover(item: MediaItemEntity): Bitmap {
        val bitmap = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        val hue = (item.path.hashCode().let { if (it < 0) -it else it } % 360).toFloat()
        val top = Color.HSVToColor(floatArrayOf(hue, 0.42f, 0.30f))
        val bottom = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.11f))
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, POSTER_H.toFloat(), top, bottom, Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, POSTER_W.toFloat(), POSTER_H.toFloat(), background)

        // 左侧一道同色系的亮边，与片库格子的选中边框同一套视觉语言。
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.95f))
        }
        canvas.drawRect(0f, 0f, 4f, POSTER_H.toFloat(), edge)

        val stamp = stampOf(item.title)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        if (stamp != null) {
            text.textSize = 20f
            text.alpha = 190
            canvas.drawText(stamp.first, POSTER_W / 2f, 64f, text)

            text.textSize = 58f
            text.alpha = 245
            canvas.drawText(stamp.second, POSTER_W / 2f, 124f, text)
        } else {
            // 名字本身就是线索（GOPR0231 / 导出后的中文名），截断成一行。
            text.textSize = 26f
            text.alpha = 240
            val name = item.title.substringBeforeLast('.').ifBlank { item.title }
            canvas.drawText(if (name.length > 12) name.take(11) + "…" else name, POSTER_W / 2f, 100f, text)
        }

        val footer = buildString {
            if (item.durationMs > 0L) append(formatTime(item.durationMs))
            if (item.width > 0 && item.height > 0) {
                if (isNotEmpty()) append(" · ")
                append("${item.width}×${item.height}")
            }
        }
        if (footer.isNotEmpty()) {
            text.textSize = 18f
            text.alpha = 175
            canvas.drawText(footer, POSTER_W / 2f, 158f, text)
        }

        // 右下角一个小播放三角：让「这是视频」这件事不用猜。
        val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            alpha = 120
        }
        canvas.drawPath(
            Path().apply {
                moveTo(POSTER_W - 30f, POSTER_H - 34f)
                lineTo(POSTER_W - 30f, POSTER_H - 14f)
                lineTo(POSTER_W - 13f, POSTER_H - 24f)
                close()
            },
            glyph
        )
        return bitmap
    }

    /** `VID_20260712_143022` → ("7月12日", "14:30:22")。抽不出来返回 null。 */
    private fun stampOf(raw: String): Pair<String, String>? {
        val base = raw.substringBeforeLast('.').ifBlank { raw }
        val groups = COMPACT.find(base)?.groupValues
            ?: LOOSE.find(base)?.groupValues
            ?: return null
        val year = groups[1].toIntOrNull() ?: return null
        val month = groups[2].toIntOrNull() ?: return null
        val day = groups[3].toIntOrNull() ?: return null
        val hour = groups[4].toIntOrNull() ?: return null
        val minute = groups[5].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val second = groups.getOrNull(6)?.toIntOrNull()?.takeIf { it in 0..59 }
        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val date = if (year == thisYear) "${month}月${day}日" else "${year}年${month}月${day}日"
        val time = if (second == null) "%02d:%02d".format(hour, minute)
        else "%02d:%02d:%02d".format(hour, minute, second)
        return date to time
    }

    private fun formatTime(ms: Long): String {
        val total = ms / 1_000L
        return "%02d:%02d".format(total / 60L, total % 60L)
    }

    /** V2.6：片库删掉后，别把属于它的封面留在私有目录里。 */
    fun deleteBySource(sourceId: Long) {
        val prefix = "${sourceId}_"
        dir.listFiles { file -> file.isFile && file.name.startsWith(prefix) }
            ?.forEach { file ->
                cache.remove(file.absolutePath)
                runCatching { file.delete() }
            }
    }

    private fun trimFiles() {
        val files = dir.listFiles { file -> file.isFile && file.name.endsWith(".png") }
            ?.sortedBy { it.lastModified() } ?: return
        if (files.size <= MAX_FILES) return
        files.take(files.size - MAX_FILES).forEach { runCatching { it.delete() } }
    }
}
