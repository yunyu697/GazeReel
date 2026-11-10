package com.gazereel.vr.core

/**
 * V2.20 / C1：抽帧比对左右 / 上下半幅，判 SBS / TAB。
 *
 * ## 为什么用相关系数，而不是「两半差多少」
 *
 * 最直觉的写法是归一化平均绝对差（nmae）。它在一类画面上会骗人：
 * **低对比度、大片平坦的画面**（海面、夜空、白墙、雾）。两半本来就没多少起伏，
 * 差值的分母也小，算出来「很像」——于是一部 mono 的 360 片会被判成 SBS，
 * 每眼只剩半幅，画面被拉成两倍宽，比原来的 bug 更糟。
 *
 * 相关系数只看**结构是否同形**，与整体明暗、对比度无关：
 * 真立体片的左右两幅是同一场景、只差一点水平视差，相关系数 0.97–0.99；
 * 而 mono 360 的左右两半是相隔 180° 的两块不同场景，通常 0.3 以下。
 * 平坦画面则**两边都没有结构**，方差太小直接判「看不出」，不作结论。
 *
 * ## 一条片要三个样本
 *
 * 单片误判的代价是不可逆的（画面直接被切成两半），所以要求三个时刻的结论一致才落地。
 * 三帧都说是 SBS，那基本就是 SBS；一帧说 SBS、一帧说 MONO，就当作看不出，
 * 沿用容器 / 文件名的判断——**宁可维持现状，也不要把对的改成错的**。
 *
 * ## 不做的事
 *
 * 不判断投影（360 / 180 / 平面）：球面接缝、鱼眼暗角都不在判据里。
 * 布局与投影是两件事，混在一起必然两个都不准。
 */
object StereoFrameJudge {

    /** 相关系数高于此值才认为「两半是同一场景」。 */
    private const val SAME_CORR = 0.95f
    /** 低于此值可以断言「两半不是同一场景」。 */
    private const val DIFFERENT_CORR = 0.80f
    /** 两半的对比度至少要有这么多（灰度标准差），否则这帧没有判别力。 */
    private const val MIN_STD = 0.010f
    /** 两个轴要好到这个差距才敢挑一个。 */
    private const val MARGIN = 0.05f

    data class Verdict(
        val layout: StereoLayout,
        /** 0 表示这一帧没有判别力（黑场 / 平坦 / 两个轴都像）。 */
        val confidence: Float,
        val sbsCorr: Float,
        val tabCorr: Float,
        val luma: Float
    ) {
        fun evidence(): StereoEvidence = StereoEvidence(
            layout = layout,
            confidence = confidence,
            note = "相关 左右 ${fmt(sbsCorr)} / 上下 ${fmt(tabCorr)}"
        )

        fun describe(): String =
            "抽帧 ${layout.name} 置信 ${fmt(confidence)} · 相关 左右 ${fmt(sbsCorr)} " +
                "上下 ${fmt(tabCorr)} · 亮度 ${fmt(luma)}"
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    private val UNKNOWN = Verdict(StereoLayout.MONO, 0f, 0f, 0f, 0f)

    /**
     * @param rgba 画面像素，RGBA_8888，行优先。GL 读回来是上下颠倒的，
     *             但这里只比左右 / 上下半幅，颠倒不影响结论。
     */
    fun judge(rgba: ByteArray, width: Int, height: Int): Verdict {
        if (width < 16 || height < 16) return UNKNOWN
        if (rgba.size < width * height * 4) return UNKNOWN

        // 缩到 96×48 的灰度网格：压掉噪声，也让一次判定只有几千次运算。
        val gw = 96
        val gh = 48
        val grid = FloatArray(gw * gh)
        for (gy in 0 until gh) {
            val y0 = gy * height / gh
            val y1 = ((gy + 1) * height / gh).coerceAtLeast(y0 + 1)
            for (gx in 0 until gw) {
                val x0 = gx * width / gw
                val x1 = ((gx + 1) * width / gw).coerceAtLeast(x0 + 1)
                var sum = 0.0
                var count = 0
                for (y in y0 until y1) {
                    var idx = (y * width + x0) * 4
                    for (x in x0 until x1) {
                        val r = rgba[idx].toInt() and 0xFF
                        val g = rgba[idx + 1].toInt() and 0xFF
                        val b = rgba[idx + 2].toInt() and 0xFF
                        sum += (r * 77 + g * 150 + b * 29) shr 8
                        idx += 4
                        count++
                    }
                }
                grid[gy * gw + gx] = if (count > 0) (sum / count / 255.0).toFloat() else 0f
            }
        }

        var total = 0.0
        for (v in grid) total += v
        val luma = (total / grid.size).toFloat()
        // 黑场（片头 / 切场）没有结构，比什么都「像」。
        if (luma < 0.035f) return Verdict(StereoLayout.MONO, 0f, 0f, 0f, luma)

        val sbs = correlation(grid, gw, gh, vertical = true)
        val tab = correlation(grid, gw, gh, vertical = false)
        // 任一半幅几乎没有起伏（大片天空 / 死黑），相关系数不可信。
        if (sbs.std < MIN_STD || tab.std < MIN_STD) {
            return Verdict(StereoLayout.MONO, 0f, sbs.corr, tab.corr, luma)
        }

        return when {
            sbs.corr >= SAME_CORR && sbs.corr - tab.corr >= MARGIN ->
                Verdict(StereoLayout.SBS, confidenceOf(sbs.corr), sbs.corr, tab.corr, luma)
            tab.corr >= SAME_CORR && tab.corr - sbs.corr >= MARGIN ->
                Verdict(StereoLayout.TAB, confidenceOf(tab.corr), sbs.corr, tab.corr, luma)
            sbs.corr < DIFFERENT_CORR && tab.corr < DIFFERENT_CORR ->
                Verdict(StereoLayout.MONO, 0.85f, sbs.corr, tab.corr, luma)
            else -> Verdict(StereoLayout.MONO, 0f, sbs.corr, tab.corr, luma)
        }
    }

    private fun confidenceOf(corr: Float): Float = when {
        corr >= 0.985f -> 0.95f
        corr >= 0.970f -> 0.88f
        else -> 0.78f
    }

    /** 三个样本投票：一致才落地，分歧就当没看见。 */
    fun combine(samples: List<Verdict>): Verdict? {
        val decided = samples.filter { it.confidence > 0f }
        if (decided.size < samples.size || decided.isEmpty()) return null
        val first = decided.first()
        if (decided.any { it.layout != first.layout }) return null
        // 取最保守的那一条：置信最低、相关系数也最低。
        return decided.minByOrNull { it.confidence }
    }

    private class HalfStat(val corr: Float, val std: Float)

    /**
     * 沿某条轴把画面切成两半，求两半的皮尔逊相关系数与较小那半的标准差。
     *
     * 方差近似用 `Σx² / n − mean²`，避免为了一个判据再扫一遍。
     */
    private fun correlation(grid: FloatArray, gw: Int, gh: Int, vertical: Boolean): HalfStat {
        val n: Int
        val a = FloatArray(if (vertical) (gw / 2) * gh else gw * (gh / 2))
        val b = FloatArray(a.size)
        var k = 0
        if (vertical) {
            val half = gw / 2
            for (y in 0 until gh) {
                val row = y * gw
                for (x in 0 until half) {
                    a[k] = grid[row + x]
                    b[k] = grid[row + x + half]
                    k++
                }
            }
        } else {
            val half = gh / 2
            for (y in 0 until half) {
                val top = y * gw
                val bottom = (y + half) * gw
                for (x in 0 until gw) {
                    a[k] = grid[top + x]
                    b[k] = grid[bottom + x]
                    k++
                }
            }
        }
        n = k
        if (n == 0) return HalfStat(0f, 0f)

        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        for (i in 0 until n) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            sa += x; sb += y
            saa += x * x; sbb += y * y; sab += x * y
        }
        val ma = sa / n
        val mb = sb / n
        val va = saa / n - ma * ma
        val vb = sbb / n - mb * mb
        val cov = sab / n - ma * mb
        val corr = if (va <= 1e-9 || vb <= 1e-9) 0f
        else (cov / kotlin.math.sqrt(va * vb)).toFloat().coerceIn(-1f, 1f)
        val std = kotlin.math.sqrt(kotlin.math.min(va, vb).coerceAtLeast(0.0)).toFloat()
        return HalfStat(corr, std)
    }
}

/** 抽帧得出的布局证据，喂回 [FormatDetector.detect]。 */
data class StereoEvidence(
    val layout: StereoLayout,
    val confidence: Float,
    val note: String = ""
)
