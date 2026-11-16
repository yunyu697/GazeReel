package com.gazereel.vr.player

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 2.1.0：设备硬解能力快照，给「播放没成功」一句能看懂、且**能照着做**的诊断。
 *
 * ### 为什么需要它
 *
 * 真机上 `AJVR-215`（5400×2700 VP9@59.94）稳定失败：
 *
 * ```
 * format_supported=NO_EXCEEDS_CAPABILITIES
 * Decoder init failed: c2.qti.vp9.decoder
 * Caused by: java.lang.IllegalArgumentException   ← MediaCodec.native_configure
 * ```
 *
 * 4001 是**解码器初始化失败**。旧版给出的提示是「此编码当前设备不支持」，
 * 或者拿「面积最大的那个解码器」的上限去比 —— 这两种做法都对不上真实原因：
 *
 * 1. `Media3` 判 `NO_EXCEEDS_CAPABILITIES` 用的是**平台自己的**
 *    `VideoCapabilities.isSizeSupported / areSizeAndRateSupported`，不是
 *    「宽 > upper || 高 > upper」。上限之外还有**尺寸与帧率的组合上限**
 *    （典型：8K 只到 30fps、4K 才到 60fps）和**宽高对齐要求**（常见 16 / 64）。
 * 2. 面积最大的解码器不一定是 Media3 实际会用的那个。设备上同一个编码常有
 *    `c2.qti.*` 与 `OMX.qcom.*` 两条，能力不同；拿前者比、后者去 configure，
 *    就会得出「应该能播」的错误结论。
 *
 * 所以这里做两件事：**先按 MediaCodecList 的顺序取出 Media3 会先试的那个
 * 解码器**，再用平台的 `isSizeSupported` / `areSizeAndRateSupported` 问它，
 * 最后把「差在哪一项、改成什么能播」算出来。
 *
 * ### 边界（必须如实）
 *
 * 这里**不会让超限的片子变得能播**。Android 上没有内置 VP9 / AV1 软解，
 * Media3 的 FFmpeg 扩展要自己编 aar（几十 MB），不是补丁能解决的事。
 * 这一层的产出是：说清「为什么放不了」+ 给出可执行的一条路
 * （尺寸、帧率、对齐、编码，具体到数字），而不是假装修好了。
 */
object DecoderCaps {

    /** 常见视频 MIME 与给人看的名字。顺序即 `summary()` 的输出顺序。 */
    private val MIMES = listOf(
        "video/avc" to "H.264",
        "video/hevc" to "HEVC",
        "video/x-vnd.on2.vp9" to "VP9",
        "video/av01" to "AV1"
    )

    /**
     * 一个解码器的能力。
     *
     * `maxFps` 是「全尺寸区间里的最大帧率」，**不代表任意尺寸都能跑到它**；
     * 真正的组合上限只能问 [Verdict]，所以这个字段只用于摘要，不用于判断。
     */
    data class Cap(
        val codecName: String,
        val maxWidth: Int,
        val maxHeight: Int,
        val minWidth: Int,
        val minHeight: Int,
        val maxFps: Double,
        val widthAlign: Int,
        val heightAlign: Int,
        val tenBit: Boolean,
        /** 软件解码器：能力通常写 4096 一档，能「配得上」但跑不动 4K 以上。 */
        val software: Boolean
    ) {
        fun sizeLabel(): String = "${maxWidth}×${maxHeight}"
        fun fpsLabel(): String = if (maxFps >= 1.0) "${maxFps.roundToInt()}fps" else "帧率未知"
    }

    /**
     * 诊断结论。
     *
     * [declaredSupported] 是**平台声明**，不是「我们猜的能不能播」：
     * `true` 而仍然失败时，问题就不在尺寸/帧率，而在厂商私有约束。
     */
    data class Verdict(
        val declaredSupported: Boolean,
        /** 一句结论，进错误卡第二行第一句。 */
        val headline: String,
        /** 一句可执行建议，接在分号后面。空串表示没有更好的建议。 */
        val advice: String,
        /** 进 `RunLog` 的一行：把「此片什么规格 / 平台怎么判 / 用的哪个解码器」钉在一起。 */
        val logLine: String
    ) {
        val hint: String get() = if (advice.isBlank()) headline else "$headline；$advice"
    }

    private val codecInfos: Array<MediaCodecInfo> by lazy {
        runCatching { MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos }
            .getOrDefault(emptyArray())
    }

    /** MIME → 给人看的编码名；查不到时原样回一段短名。 */
    fun labelOf(mime: String?): String = when (mime) {
        null -> "这个编码"
        "video/avc" -> "H.264"
        "video/hevc" -> "HEVC"
        "video/x-vnd.on2.vp9" -> "VP9"
        "video/av01" -> "AV1"
        else -> mime.removePrefix("video/")
    }

    /**
     * 取该 MIME 下能力**最大**的硬解；没有硬解才退到软解。
     *
     * 只用于 `summary()` 那句「本机最高能解到多少」——
     * **不要用它判断一条轨道能不能播**，Media3 实际用的可能是另一个解码器，
     * 判断请走 `diagnose()`。软解（`c2.android.*` / `OMX.google.*`）的
     * `VideoCapabilities` 常写 4096×4096 看着很大，实际几帧就掉帧。
     */
    fun best(mime: String): Cap? {
        var bestHard: Cap? = null
        var bestSoft: Cap? = null
        for (info in codecInfos) {
            val cap = capOf(info, mime) ?: continue
            val area = cap.maxWidth.toLong() * cap.maxHeight.toLong()
            if (cap.software) {
                if (area > (bestSoft?.let { it.maxWidth.toLong() * it.maxHeight } ?: -1L)) bestSoft = cap
            } else {
                if (area > (bestHard?.let { it.maxWidth.toLong() * it.maxHeight } ?: -1L)) bestHard = cap
            }
        }
        return bestHard ?: bestSoft
    }

    /**
     * Media3 会**先试**的那个解码器：`MediaCodecList` 顺序里的第一个。
     *
     * 位置必须和 `PlayerController.recordingSelector` 记进日志的候选链一致 ——
     * 那行日志的第一个名字，就是这里返回的名字。两边对不上时，
     * 诊断会指向「另一个解码器」的能力，等于白说。
     */
    fun firstFor(mime: String): Cap? {
        for (info in codecInfos) {
            capOf(info, mime)?.let { return it }
        }
        return null
    }

    /** 该编码的软件解码器（没有则 `null`）。只用于日志里写清楚「软解能到多少」。 */
    fun softwareFor(mime: String): Cap? {
        for (info in codecInfos) {
            val cap = capOf(info, mime) ?: continue
            if (cap.software) return cap
        }
        return null
    }

    private data class Alt(val label: String, val size: String)

    /**
     * 换一个**能解这个尺寸**的编码。
     *
     * 真机实测：VP9 硬解只到 **4096×4096**，而 HEVC / H.264 / AV1 都到 **8192×8192**。
     * 同样是 5400×2700 的片子，转成 H.265 就能播，分辨率一点不用降。
     * 「换个更小的版本」是最后手段，不是第一建议 —— 这个函数就是为把这句话说准而写的。
     *
     * 偏好顺序：H.265（同画质码率最低）→ H.264（兼容性最好）→ AV1 → VP9。
     */
    private fun alternative(mime: String?, width: Int, height: Int): Alt? {
        val order = listOf("video/hevc", "video/avc", "video/av01", "video/x-vnd.on2.vp9")
        for (m in order) {
            if (m == mime) continue
            val cap = firstFor(m) ?: continue
            if (cap.maxWidth >= width && cap.maxHeight >= height) {
                return Alt(labelOf(m), cap.sizeLabel())
            }
        }
        return null
    }

    private fun capOf(info: MediaCodecInfo, mime: String): Cap? {
        if (info.isEncoder) return null
        if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) return null
        // secure 解码器只吃 DRM 内容，普通片源不会走它；排除掉才和 Media3 的排序对齐。
        if (info.name.endsWith(".secure")) return null
        val caps = runCatching { info.getCapabilitiesForType(mime).videoCapabilities }
            .getOrNull() ?: return null
        return Cap(
            codecName = info.name,
            // `Range.supportedWidths` 的 `upper` / `lower` 在 Kotlin 侧是平台类型，
            // 必须显式转，否则泛型推断会把 Double 说成 Number。
            maxWidth = runCatching { caps.supportedWidths.upper.toInt() }.getOrDefault(0),
            maxHeight = runCatching { caps.supportedHeights.upper.toInt() }.getOrDefault(0),
            minWidth = runCatching { caps.supportedWidths.lower.toInt() }.getOrDefault(0),
            minHeight = runCatching { caps.supportedHeights.lower.toInt() }.getOrDefault(0),
            maxFps = runCatching { caps.supportedFrameRates?.upper?.toDouble() ?: 0.0 }
                .getOrDefault(0.0),
            widthAlign = runCatching { caps.widthAlignment }.getOrDefault(1),
            heightAlign = runCatching { caps.heightAlignment }.getOrDefault(1),
            tenBit = supportsTenBit(info, mime),
            software = info.name.startsWith("c2.android.") ||
                info.name.startsWith("OMX.google.") ||
                info.name.startsWith("c2.google.")
        )
    }

    /** 设备有没有 10bit 解码能力（VP9 Profile 2 / HEVC Main10）。 */
    private fun supportsTenBit(info: MediaCodecInfo, mime: String): Boolean {
        val wanted = when (mime) {
            "video/x-vnd.on2.vp9" -> listOf(MediaCodecInfo.CodecProfileLevel.VP9Profile2)
            "video/hevc" -> listOf(
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10,
                MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10
            )
            else -> return false
        }
        return runCatching {
            info.getCapabilitiesForType(mime).profileLevels
                .any { level -> wanted.any { it == level.profile } }
        }.getOrDefault(false)
    }

    /**
     * 一行能力摘要，起播时写进 `RunLog`。
     *
     * 例：`H.264 8192×8192@480fps · HEVC 8192×8192@480fps 10bit · VP9 4096×4096@200fps 10bit（软解 4096×4096）`
     *
     * 末尾的「软解 X×Y」是 2.1.0-alpha3 加的：真机上 `c2.android.vp9.decoder` 确实存在，
     * 但既然它也只到 4096，就不用再幻想「软解能救 5400」——写出来一眼能判。
     */
    fun summary(): String = MIMES.joinToString(" · ") { (mime, label) ->
        val cap = best(mime) ?: return@joinToString "$label 无解"
        val sw = softwareFor(mime)
        val extra = buildString {
            if (cap.tenBit) append(" 10bit")
            if (cap.software) append(" 软解")
            else if (sw != null) append("（软解 ${sw.sizeLabel()}）")
        }
        "$label ${cap.sizeLabel()}@${cap.fpsLabel()}$extra"
    }

    /**
     * 20:2x：片库离线判定「本机解不了」，供片库角标用，不需要起播。
     *
     * 只认平台**明确说不支持**（`declaredSupported == false`）。平台说支持却被
     * 厂商解码器拒配的那一种（`declaredSupported == true`）不能断言「解不了」，
     * 信息不足（`diagnose` 返回 null，例如 WebM 没有 stsd）也不标 —— 宁可漏标，
     * 不要把一部能播的片打成「解不了」。
     *
     * `fps` 默认 0：容器没给帧率时只判尺寸。真机那部 5400×2700 VP9 是纯宽度超限，
     * `isSizeSupported` 直接判否，帧率未知照样标得出来。
     */
    fun isUnsupported(mime: String?, width: Int, height: Int, fps: Float = 0f): Boolean =
        diagnose(mime, width, height, fps)?.declaredSupported == false

    /**
     * 诊断一条视频轨道能不能被本机解出来。
     *
     * 判据完全交给平台（`isSizeSupported` / `areSizeAndRateSupported`），
     * 我们只负责把平台的「不行」翻译成「差哪一项、改成什么」。
     *
     * @param fps 容器里读到的帧率；`<= 0` 表示未知，此时只判尺寸。
     * @return `null` 表示信息不足（编码未知或宽高为 0），调用方退回通用文案。
     */
    fun diagnose(mime: String?, width: Int, height: Int, fps: Float): Verdict? {
        if (mime == null || width <= 0 || height <= 0) return null
        val label = labelOf(mime)
        val cap = firstFor(mime) ?: return Verdict(
            declaredSupported = false,
            headline = "本机没有 $label 解码器",
            advice = "用 H.264 / H.265 重压一遍",
            logLine = "$mime 无解码器候选"
        )
        val vcaps = runCatching {
            codecInfos.first { it.name == cap.codecName }.getCapabilitiesForType(mime).videoCapabilities
        }.getOrNull() ?: return null

        val hasFps = fps > 0f
        val sizeOk = runCatching { vcaps.isSizeSupported(width, height) }.getOrDefault(false)
        val rateOk = hasFps && runCatching {
            vcaps.areSizeAndRateSupported(width, height, fps.toDouble())
        }.getOrDefault(false)
        val declared = sizeOk && (!hasFps || rateOk)

        val fpsText = if (hasFps) "${fps.roundToInt()}fps" else "帧率未知"
        val logLine = "$mime 此片 ${width}×$height $fpsText · " +
            "size=${yesNo(sizeOk)} rate=${if (hasFps) yesNo(rateOk) else "?"} · " +
            "解码器 ${cap.codecName} 上限 ${cap.sizeLabel()} ${cap.fpsLabel()} " +
            "对齐 ${cap.widthAlign}×${cap.heightAlign}"

        if (declared) {
            // 平台说支持，仍然配置失败 —— 这是最需要说清的一类：不要让人再去改分辨率。
            return Verdict(
                declaredSupported = true,
                headline = "平台声明支持这个规格（${cap.sizeLabel()} ${cap.fpsLabel()}），" +
                    "但厂商解码器配置被拒",
                advice = "转成 H.265，或降到 ${cap.maxWidth / 2}×${cap.maxHeight / 2} 一档再试",
                logLine = logLine
            )
        }

        if (!sizeOk) {
            val alignW = cap.widthAlign.coerceAtLeast(1)
            val alignH = cap.heightAlign.coerceAtLeast(1)
            val misaligned = (alignW > 1 && width % alignW != 0) ||
                (alignH > 1 && height % alignH != 0)
            val overSize = width > cap.maxWidth || height > cap.maxHeight ||
                (cap.minWidth > 0 && width < cap.minWidth) ||
                (cap.minHeight > 0 && height < cap.minHeight)
            if (misaligned && !overSize) {
                val aw = floorTo(width, alignW)
                val ah = floorTo(height, alignH)
                val loss = 100.0 - 100.0 * (aw.toLong() * ah) / (width.toLong() * height)
                return Verdict(
                    declaredSupported = false,
                    headline = "本机 $label 解码器要求宽高按 ${alignW}×${alignH} 对齐，" +
                        "$width×$height 不满足",
                    advice = "改成 ${aw}×$ah 就能播（少 ${oneDecimal(loss)}% 画面）",
                    logLine = logLine
                )
            }
            val alt = alternative(mime, width, height)
            return Verdict(
                declaredSupported = false,
                headline = "画面 $width×$height 超过本机 $label 上限 ${cap.sizeLabel()}",
                advice = if (alt != null) {
                    // 真机上这条才是最优解：宽超了 4096，但 HEVC/H.264 到 8192，
                    // 换编码即可，分辨率一点不用降。
                    "转 ${alt.label} 可保持 $width×$height（本机上限 ${alt.size}），分辨率不用降"
                } else {
                    "换 ≤${cap.sizeLabel()} 的版本，或转成 H.265"
                },
                logLine = logLine
            )
        }

        // 尺寸可以、帧率超：这是最容易被误判成「设备不支持这个编码」的一类。
        val maxFpsHere = runCatching { vcaps.getSupportedFrameRatesFor(width, height).upper }
            .getOrNull()
        val capFpsText = maxFpsHere?.let { "${it.roundToInt()}fps" } ?: cap.fpsLabel()
        val target = maxFpsHere?.let { roundDownFps(it) }
        return Verdict(
            declaredSupported = false,
            headline = "这个尺寸下本机 $label 最高 $capFpsText，此片 $fpsText",
            advice = if (target != null && target < (fps.roundToInt())) {
                "转成 ${target}fps 就能播，分辨率不用降"
            } else {
                "转 30fps 版本，或用 H.264 / H.265 重压一遍"
            },
            logLine = logLine
        )
    }

    private fun yesNo(value: Boolean) = if (value) "Y" else "N"

    private fun floorTo(value: Int, align: Int): Int {
        if (align <= 1) return value
        val v = (value / align) * align
        return if (v > 0) v else value
    }

    /** 帧率上限取整到 5 的倍数并留一档（设备常报 60.000030，落到 55 反而播不了）。 */
    private fun roundDownFps(upper: Double): Int {
        val step = floor(upper / 5.0) * 5.0
        return step.toInt().coerceAtLeast(15)
    }

    private fun oneDecimal(value: Double): String {
        val scaled = (value * 10).roundToInt()
        return "${scaled / 10}.${scaled % 10}"
    }
}
