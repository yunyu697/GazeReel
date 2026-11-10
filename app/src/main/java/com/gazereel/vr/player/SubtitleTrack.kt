package com.gazereel.vr.player

import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.SourceType
import com.gazereel.vr.data.RandomAccessSourceFactory
import java.io.ByteArrayOutputStream

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/** SRT 与 WebVTT 共用一套时间轴解析，忽略 VTT cue settings 与内联标签。 */
object SubtitleParser {
    private val TIME = Regex(
        """(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})"""
    )
    private val TAG = Regex("""<[^>]*>|&nbsp;|&amp;|&lt;|&gt;""")

    fun parse(bytes: ByteArray): List<SubtitleCue> {
        val raw = String(bytes, Charsets.UTF_8)
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val cues = mutableListOf<SubtitleCue>()
        for (block in raw.split(Regex("""\n{2,}"""))) {
            val lines = block.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            val timeIndex = lines.indexOfFirst { TIME.containsMatchIn(it) }
            if (timeIndex < 0) continue
            val match = TIME.find(lines[timeIndex]) ?: continue
            val (h1, m1, s1, ms1, h2, m2, s2, ms2) = match.destructured
            val start = toMs(h1, m1, s1, ms1)
            val end = toMs(h2, m2, s2, ms2)
            val text = lines.drop(timeIndex + 1)
                .joinToString("\n") { TAG.replace(it, "") }
                .trim()
            if (text.isNotEmpty() && end > start) cues += SubtitleCue(start, end, text)
        }
        return cues.sortedBy { it.startMs }
    }

    fun cueAt(cues: List<SubtitleCue>, positionMs: Long): String? =
        cues.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs }?.text

    private fun toMs(h: String, m: String, s: String, ms: String): Long {
        val millis = ms.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return (h.toLong() * 3_600L + m.toLong() * 60L + s.toLong()) * 1_000L + millis
    }
}

/**
 * PRD FR-12 字幕：优先同目录同名 .srt，其次 .vtt。
 * 复用 RandomAccessSourceFactory，因此 SMB / WebDAV / HTTP / DLNA / 本地
 * 都能用同一条链路读取字幕，不需要为字幕单独写一套网络访问代码。
 */
class SubtitleRepository(private val sourceFactory: RandomAccessSourceFactory) {
    companion object {
        private const val MAX_BYTES = 512 * 1024
    }

    fun load(ref: MediaRef): List<SubtitleCue> {
        for (ext in listOf("srt", "vtt")) {
            val cues = runCatching { readCues(sidecar(ref, ext)) }.getOrDefault(emptyList())
            if (cues.isNotEmpty()) return cues
        }
        return emptyList()
    }

    private fun readCues(ref: MediaRef): List<SubtitleCue> {
        val source = sourceFactory.create(ref)
        try {
            source.open(ref)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8 * 1024)
            while (out.size() < MAX_BYTES) {
                val count = source.read(buffer, 0, buffer.size)
                if (count <= 0) break
                out.write(buffer, 0, count)
            }
            return SubtitleParser.parse(out.toByteArray())
        } finally {
            runCatching { source.close() }
        }
    }

    private fun sidecar(ref: MediaRef, extension: String): MediaRef {
        val candidate = when (ref.type) {
            SourceType.WEBDAV, SourceType.HTTP, SourceType.DLNA ->
                ref.path.substringBefore('?').substringBefore('#')
                    .substringBeforeLast('.') + ".$extension"
            else -> ref.path.substringBeforeLast('.') + ".$extension"
        }
        return ref.copy(path = candidate)
    }
}
