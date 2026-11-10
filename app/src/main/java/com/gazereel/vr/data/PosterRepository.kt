package com.gazereel.vr.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.util.LruCache
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.SourceEntity
import com.gazereel.vr.core.SourceType
import java.io.File
import java.io.FileOutputStream

/**
 * PRD FR-10 海报：优先取与视频同名的 jpg/png/webp，取不到时生成带标题的封面。
 *
 * 刻意不调用 MediaMetadataRetriever 抽帧：网络片源只有自定义 DataSource，
 * 没有可供 MediaMetadataRetriever 使用的 fd，强行抽帧会在高码率播放时抢带宽。
 * 生成结果落到应用私有缓存目录并做数量上限，VrUiLayer 通过 LruCache 读取，
 * GL 线程每帧只读内存，不触碰磁盘。
 */
class PosterRepository(context: Context) {

    companion object {
        private const val POSTER_W = 320
        private const val POSTER_H = 180
        private const val MAX_FILES = 200
        private const val CACHE_KB = 6 * 1024
    }

    private val dir = File(context.filesDir, "posters").apply { mkdirs() }
    private val cache = object : LruCache<String, Bitmap>(CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    /** @return mediaId → 海报文件绝对路径；未变化的条目不返回。 */
    fun refresh(
        items: List<MediaItemEntity>,
        source: SourceEntity,
        credentials: SmbCredentials
    ): Map<Long, String> {
        val type = runCatching { SourceType.valueOf(source.type) }.getOrDefault(SourceType.LOCAL)
        val out = HashMap<Long, String>()
        for (item in items) {
            if (item.posterPath.isNotEmpty() && File(item.posterPath).isFile) continue
            // SMB 不在此处读同名图片：阶段一 SmbRandomAccessSource 与播放共用会话锁，
            // 扫描期并发读会与前台串流争用，统一走文字封面回退。
            val bitmap = when (type) {
                SourceType.WEBDAV, SourceType.HTTP, SourceType.DLNA ->
                    runCatching { fetchNetworkPoster(item.path, credentials) }.getOrNull()
                SourceType.LOCAL -> runCatching { fetchLocalPoster(item.path) }.getOrNull()
                else -> null
            } ?: generatePoster(item.title)

            val file = File(dir, "${item.sourceId}_%08x".format(item.path.hashCode()) + ".png")
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

    private fun fetchNetworkPoster(url: String, credentials: SmbCredentials): Bitmap? {
        val client = LanHttpClient(credentials)
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val candidate = "${url.substringBefore('?').substringBefore('#')
                .substringBeforeLast('.')}.$ext"
            val response = runCatching { client.request("GET", candidate) }.getOrNull() ?: continue
            if (response.code !in 200..299 || response.body.isEmpty()) continue
            val bitmap = BitmapFactory.decodeByteArray(response.body, 0, response.body.size)
            if (bitmap != null) return bitmap
        }
        return null
    }

    private fun fetchLocalPoster(path: String): Bitmap? {
        val base = path.substringBeforeLast('.')
        for (ext in listOf("jpg", "jpeg", "png", "webp")) {
            val file = File("$base.$ext")
            if (!file.isFile) continue
            BitmapFactory.decodeFile(file.absolutePath)?.let { return it }
        }
        return null
    }

    private fun generatePoster(title: String): Bitmap {
        val bitmap = Bitmap.createBitmap(POSTER_W, POSTER_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, POSTER_W.toFloat(), POSTER_H.toFloat(),
                0xFF1C3552.toInt(), 0xFF0B1626.toInt(), Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, POSTER_W.toFloat(), POSTER_H.toFloat(), background)

        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
        }
        val initial = title.trim().take(1).ifEmpty { "VR" }
        text.textSize = 68f
        text.alpha = 210
        canvas.drawText(initial, POSTER_W / 2f, 92f, text)

        text.textSize = 20f
        text.alpha = 235
        val label = if (title.length > 14) title.take(13) + "…" else title
        canvas.drawText(label, POSTER_W / 2f, 142f, text)
        return bitmap
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
