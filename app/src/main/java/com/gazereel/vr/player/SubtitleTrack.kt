package com.gazereel.vr.player

import com.gazereel.vr.core.MediaRef
import com.gazereel.vr.core.SourceType
import com.gazereel.vr.data.RandomAccessSourceFactory
import java.io.ByteArrayOutputStream

data class SubtitleCue(val startMs: Long, val endMs: Long, val text: String)

/**
 * SRT / WebVTT 共用的轻量解析器。
 *
 * 不接入 Media3 的 SubtitleParser：1.5.1 的解析入口是「Format + 异步
 * Consumer<CuesWithTiming>」或 parseToLegacySubtitle，产出 androidx Cue /
 * CuesWithTiming，与本类对上层承诺的扁平 (startMs, endMs, text) 列表并不一致；
 * 工厂按 sampleMimeType 分派、需要从字节里嗅探 srt/vtt，且属于 @UnstableApi，
 * 又不能改动 PlayerController（本轮禁止）。接入收益低、牵动面大，故保留手写
 * 并增强健壮性（见下）。
 *
 * 覆盖：UTF-8 BOM、CRLF/CR、可选序号、时间轴含逗号或点号、WebVTT 无小时的
 * MM:SS.mmm、行尾 cue settings、<c>/<v>/<b> 等内联标签与 HTML 实体、以及
 * 「没空行分隔」的紧凑 cue。cueAt 对重叠时间轴合并同屏多行文本。
 */
object SubtitleParser {
    /** 时间戳：H:M:S,ms / H:M:S.ms 或 M:S.ms（可含 1~3 位毫秒）。 */
    private val TS = """\d{1,3}:\d{1,3}:\d{2}[,.]\d{1,3}|\d{1,3}:\d{2}[,.]\d{1,3}"""
    /** 时间轴行：允许行首空白、两端逗号/点号、行尾 cue settings（忽略）。 */
    private val TIME = Regex("""^\s*($TS)\s*-->\s*($TS)""")
    private val TAG = Regex("""<[^>]*>""")
    private val ENTITY = Regex(
        """&(nbsp|amp|lt|gt|quot|apos|lrm|rlm|#\d+|#x[0-9a-fA-F]+);""",
        RegexOption.IGNORE_CASE
    )

    fun parse(bytes: ByteArray): List<SubtitleCue> {
        val lines = decode(bytes).split('\n')
        val cues = mutableListOf<SubtitleCue>()
        var i = 0
        while (i < lines.size) {
            val match = TIME.find(lines[i].trim())
            if (match == null) {
                i++
                continue
            }
            val start = toMs(match.groupValues[1])
            val end = toMs(match.groupValues[2])
            val textLines = mutableListOf<String>()
            var j = i + 1
            while (j < lines.size) {
                val line = lines[j]
                if (line.isBlank() || TIME.find(line.trim()) != null) break
                textLines += cleanLine(line)
                j++
            }
            val text = textLines.joinToString("\n").trim()
            if (text.isNotEmpty() && end > start) {
                cues += SubtitleCue(start, end, text)
            }
            i = j
        }
        return cues.sortedWith(compareBy<SubtitleCue> { it.startMs }.thenBy { it.endMs })
    }

    /** 同一时刻命中多条 cue（重叠时间轴）时合并为多行文本，而不是只取第一条。 */
    fun cueAt(cues: List<SubtitleCue>, positionMs: Long): String? =
        cues.filter { positionMs >= it.startMs && positionMs < it.endMs }
            .map { it.text }
            .distinct()
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

    private fun decode(bytes: ByteArray): String {
        // 去掉开头的 UTF-8 BOM（EF BB BF），只去开头，避免误删正文里的 U+FEFF。
        var start = 0
        if (bytes.size >= 3 &&
            (bytes[0].toInt() and 0xFF) == 0xEF &&
            (bytes[1].toInt() and 0xFF) == 0xBB &&
            (bytes[2].toInt() and 0xFF) == 0xBF
        ) start = 3
        return String(bytes, start, bytes.size - start, Charsets.UTF_8)
            .trimStart('\uFEFF')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
    }

    private fun cleanLine(line: String): String =
        decodeEntities(TAG.replace(line, "")).trim()

    private fun decodeEntities(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1].lowercase()) {
            "nbsp" -> "\u00A0"
            "amp" -> "&"
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "lrm" -> "\u200E"
            "rlm" -> "\u200F"
            else -> {
                val n = when {
                    e.startsWith("#x") -> e.drop(2).toIntOrNull(16)
                    e.startsWith("#") -> e.drop(1).toIntOrNull()
                    else -> null
                }
                n?.takeIf { it in 1..0xFFFF }?.toChar()?.toString() ?: m.value
            }
        }
    }

    /** 同时接受 HH:MM:SS,mmm / HH:MM:SS.mmm 与 WebVTT 的 MM:SS.mmm。 */
    private fun toMs(ts: String): Long {
        val parts = ts.trim().split(Regex("""[:,.]"""))
        if (parts.size < 3) return 0L
        val millis = parts.last().padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        val sec = parts[parts.size - 2].toLongOrNull() ?: 0L
        val min = parts[parts.size - 3].toLongOrNull() ?: 0L
        val hour = if (parts.size >= 4) parts[parts.size - 4].toLongOrNull() ?: 0L else 0L
        return (hour * 3_600L + min * 60L + sec) * 1_000L + millis
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
