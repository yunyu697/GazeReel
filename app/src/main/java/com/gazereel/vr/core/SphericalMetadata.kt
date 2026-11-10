package com.gazereel.vr.core

/**
 * V2.7.6：格式判断不再主要靠文件名。
 *
 * 文件名是第三方发布组的私人习惯：Doink 写 `[180][3dh][LR]`，有人写 `_LR_180`，
 * 有人写 `VR180`，相机 App 干脆写 `VID_20260712_143022.mp4` 什么都不标。
 * 靠猜命名规则永远补不完，而且每换一个来源就再崩一次。
 *
 * 但视频文件本身就写着它是什么：
 *   - Spatial Media V2（Google 标准，YouTube / Insta360 / GoPro / ffmpeg 都会写）：
 *     moov 里的 `sv3d` → `proj` → `prhd` + `projh`(`equi`)，立体排布在 `st3d`；
 *   - Spatial Media V1（老设备、老转码器）：mp4 里的 XMP，
 *     `GSpherical:StereoMode` / `GSpherical:ProjectionType` /
 *     `GSpherical:CroppedAreaImageWidthPixels` + `FullPanoWidthPixels`；
 *   - 编码尺寸在 `stsd` 的 VisualSampleEntry 里（宽 2 字节、高 2 字节）。
 *
 * 这些是拍摄/转码方自己写进去的声明，不是我们猜的。
 * 裁剪比还能直接算出 180 还是 360：`CroppedAreaImageWidthPixels / FullPanoWidthPixels`
 * 约 0.5 就是半全景。
 *
 * 解析只需要文件开头和结尾的若干字节，不依赖 Android 解码器，SMB / HTTP 一样能用。
 */
object SphericalMetadata {

    /**
     * 一次探测拿到的全部事实。哪一项拿不到就是默认值，调用方据此降级，
     * 而不是把「拿不到」当成「是 360 球面」。
     */
    data class Probe(
        val width: Int = 0,
        val height: Int = 0,
        /** 容器声明的立体排布；null 表示容器没说。 */
        val layout: StereoLayout? = null,
        /** 容器声明自己是球面/全景。 */
        val spherical: Boolean = false,
        /** 容器声明的视场；null 表示只能靠裁剪比或宽高比推。 */
        val fov: FieldOfView? = null,
        /** 立方体投影：本期渲染不了，不谎报成球面。 */
        val cubemap: Boolean = false,
        /** 证据来源，写进运行日志方便对账。 */
        val source: String = ""
    ) {
        fun describe(): String = buildString {
            append("w=").append(width).append(" h=").append(height)
            append(" 球面=").append(if (spherical) "是" else "否")
            append(" 立体=").append(layout?.name ?: "-")
            append(" 视场=").append(fov?.name ?: "-")
            if (cubemap) append(" 立方体=是")
            append(" 来源=").append(source.ifEmpty { "none" })
        }
    }

    fun parse(head: ByteArray, tail: ByteArray? = null): Probe {
        val buffers = if (tail != null && tail.isNotEmpty()) listOf(head, tail) else listOf(head)

        val dims = dimensionsOf(head) ?: tail?.let { dimensionsOf(it) }
        var layout: StereoLayout? = null
        var fov: FieldOfView? = null
        var spherical = false
        var cubemap = false

        for (buf in buffers) {
            if (layout == null) layout = stereoBoxOf(buf) ?: xmpStereoOf(buf)
            if (fov == null) fov = xmpFovOf(buf)
            if (!spherical && declaresSpherical(buf)) spherical = true
            if (!cubemap && declaresCubemap(buf)) cubemap = true
        }

        val evidence = buildList {
            if (dims != null) add("stsd")
            if (layout != null) add("stereo")
            if (spherical) add("sv3d")
            if (fov != null) add("xmp-crop")
            if (cubemap) add("cubemap")
        }.joinToString("+")

        return Probe(
            width = dims?.first ?: 0,
            height = dims?.second ?: 0,
            layout = layout,
            spherical = spherical && !cubemap,
            fov = if (spherical && !cubemap) fov else null,
            cubemap = cubemap,
            source = evidence
        )
    }

    /** 头部是否已经含 moov：不含说明 moov 在文件尾部，需要再读一段尾巴。 */
    fun hasMoov(head: ByteArray): Boolean = head.find("moov") >= 0

    // ---- 证据提取 ----

    /**
     * `st3d` 是 FullBox：4 字节 size + 4 字节 type + 4 字节 version/flags + 1 字节 stereo_mode。
     * 找到 type 的位置后，stereo_mode 在 +8。
     */
    private fun stereoBoxOf(buf: ByteArray): StereoLayout? {
        val idx = buf.find("st3d")
        if (idx < 0 || idx + 8 >= buf.size) return null
        return when (buf[idx + 8].toInt() and 0xFF) {
            0 -> StereoLayout.MONO
            1 -> StereoLayout.TAB
            2 -> StereoLayout.SBS
            else -> null
        }
    }

    private fun xmpStereoOf(buf: ByteArray): StereoLayout? {
        val idx = buf.findIgnoreCase("GSpherical:StereoMode").takeIf { it >= 0 }
            ?: buf.findIgnoreCase("StereoFormat").takeIf { it >= 0 }
            ?: return null
        val window = buf.text(idx, 72).lowercase()
        return when {
            window.contains("left-right") || window.contains("left_right") ||
                window.contains("side-by-side") || window.contains("side_by_side") -> StereoLayout.SBS
            window.contains("top-bottom") || window.contains("top_bottom") ||
                window.contains("over-under") || window.contains("over_under") -> StereoLayout.TAB
            window.contains("mono") -> StereoLayout.MONO
            else -> null
        }
    }

    /**
     * 裁剪比直接决定 180 还是 360：半全景只铺了整球的一半。
     * 这是全篇唯一不用猜的「180 vs 360」判据。
     */
    private fun xmpFovOf(buf: ByteArray): FieldOfView? {
        val full = intAfterKey(buf, "FullPanoWidthPixels") ?: return null
        if (full <= 0) return null
        val cropped = intAfterKey(buf, "CroppedAreaImageWidthPixels") ?: full
        return if (cropped.toFloat() / full < 0.8f) FieldOfView.FOV_180 else FieldOfView.FOV_360
    }

    private fun declaresSpherical(buf: ByteArray): Boolean =
        buf.find("sv3d") >= 0 ||
            buf.findIgnoreCase("GSpherical:") >= 0 ||
            buf.findIgnoreCase("SphericalVideo") >= 0 ||
            buf.findIgnoreCase("equirectangular") >= 0

    private fun declaresCubemap(buf: ByteArray): Boolean =
        buf.find("cbmp") >= 0 || buf.findIgnoreCase("cubemap") >= 0

    /**
     * 从 `stsd` 里取编码尺寸。
     * 偏移：stsd 类型(4) + version/flags(4) + entry_count(4) + entry 头(8) +
     * SampleEntry(8) + VisualSampleEntry 前 16 字节 = 宽在类型后第 44 字节。
     *
     * 音轨也有自己的 stsd，那里第 44 字节不是宽高，所以先看 entry 的 4CC 是不是音频。
     */
    private fun dimensionsOf(buf: ByteArray): Pair<Int, Int>? {
        val idx = buf.find("stsd")
        if (idx < 0 || idx + 47 >= buf.size) return null
        val entry = String(buf, idx + 16, 4, Charsets.ISO_8859_1).lowercase()
        if (entry in NON_VIDEO_ENTRIES) return null
        val w = readU16(buf, idx + 44)
        val h = readU16(buf, idx + 46)
        return if (w in 16..20000 && h in 16..20000) w to h else null
    }

    private val NON_VIDEO_ENTRIES = setOf(
        "mp4a", "ac-3", "ec-3", "opus", "alac", "lpcm", "sowt", "twos",
        "flac", "dtsc", "samr", "sawb", "mp3 ", "mp3."
    )

    private fun readU16(buf: ByteArray, at: Int): Int =
        ((buf[at].toInt() and 0xFF) shl 8) or (buf[at + 1].toInt() and 0xFF)

    private fun intAfterKey(buf: ByteArray, key: String): Int? {
        val idx = buf.findIgnoreCase(key)
        if (idx < 0) return null
        var i = idx + key.length
        // 跳过 =" ' > 空格 这类分隔符，最多 12 个字节，防止在别处乱抓数字。
        while (i < buf.size && i - idx <= key.length + 12 && !buf[i].isDigit()) i++
        var value = 0
        var digits = 0
        while (i < buf.size && digits < 7) {
            val c = buf[i].toInt().toChar()
            if (!c.isDigit()) break
            value = value * 10 + (c - '0')
            i++
            digits++
        }
        return if (digits > 0) value else null
    }

    private fun Byte.isDigit(): Boolean {
        val v = toInt()
        return v >= '0'.code && v <= '9'.code
    }

    private fun ByteArray.text(at: Int, length: Int): String {
        val end = minOf(at + length, size)
        if (end <= at) return ""
        return String(this, at, end - at, Charsets.ISO_8859_1)
    }

    private fun ByteArray.find(needle: String, from: Int = 0): Int {
        val n = needle.length
        if (n == 0 || size < n) return -1
        val limit = size - n
        var i = from.coerceAtLeast(0)
        while (i <= limit) {
            var j = 0
            while (j < n && this[i + j] == needle[j].code.toByte()) j++
            if (j == n) return i
            i++
        }
        return -1
    }

    private fun ByteArray.findIgnoreCase(needle: String, from: Int = 0): Int {
        val n = needle.length
        if (n == 0 || size < n) return -1
        val limit = size - n
        var i = from.coerceAtLeast(0)
        while (i <= limit) {
            var j = 0
            while (j < n && lower(this[i + j]) == lower(needle[j].code.toByte())) j++
            if (j == n) return i
            i++
        }
        return -1
    }

    private fun lower(b: Byte): Int {
        val v = b.toInt() and 0xFF
        return if (v in 'A'.code..'Z'.code) v + 32 else v
    }
}
