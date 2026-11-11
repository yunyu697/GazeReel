package com.gazereel.vr.core

/**
 * V2.20 / C1：抽帧比对左右 / 上下半幅，判 SBS / TAB。
 * V2.36：改为**视差补偿后的分块比对**。
 *
 * ## 为什么不能用「零位移相关系数」
 *
 * 真立体片的左右两幅不是同一张图：同一物体在两眼里横向错开几个到几十个像素（视差）。
 * 零位移相关系数因此随景深一路下滑——KAVR-332（4096×2048、无 st3d/sv3d、名字无 [LR]）
 * 实测只有 0.794，被旧门槛（0.95）判成「MONO」，于是整幅 SBS 铺满球面，正前方正好是接缝。
 *
 * 立体视觉的基本事实是：**把其中一幅横向平移一个视差量，两幅就会重新对齐**。
 * 所以判据不该问「零位移像不像」，而该问「能不能找到一个小位移，让两幅像」：
 *
 *   - 真立体：每个小块都能在 ±{maxShift} 像素内找到自己的视差，对齐后相关 0.9+；
 *   - mono 360：左右两半是相隔 180° 的两块不同场景，哪个位移都对不上；
 *   - mono 平面 2D：左右两半是同一张画面的左右两部分，同样对不上。
 *
 * 分块而不是整幅：视差随景深逐块不同，一个全局位移只能对齐其中一个深度。
 * 分块还能自动忽略黑边、镜头暗角、上下补边的纯色块（没有起伏的块不参与统计）。
 *
 * ## 为什么还要「位移要成团」
 *
 * 纯随机画面里，几千个块 × 十几个位移，总有一些块碰巧相关 0.86 以上
 * （实测 mono 360 也有四成块能碰上）。这些「碰巧」的位移是散开的；
 * 真立体的位移是**成团**的——一整片背景落在同一个视差带里。
 * 所以把证据收成「最宽的那个视差带里，有多少块对上了」：
 * mono 360 散在十几个位移上，最多凑出一成多；真立体通常四到七成。
 * 有了这条，单块门槛才敢降到 0.86，让压缩噪声大、镜头暗角重的真片源也能过。
 *
 * ## 一条片要三个样本
 *
 * 单片误判的代价不可逆（画面直接被切成两半），所以要求三个时刻的结论一致才落地。
 * 抽帧只许把 MONO 升级成 SBS/TAB，不许把已经判对的立体布局降级（见 FormatDetector）。
 *
 * ## 不做的事
 *
 * 不判断投影（360 / 180 / 平面）：那是「每眼画幅比」的事，见 FormatDetector.perEyeFov。
 * 布局与投影混在一个判据里，必然两个都不准。
 */
object StereoFrameJudge {

    /** 零位移相关低于这个值可以断言「两半不是同一场景」（弱证据，只用于兜底 MONO）。 */
    private const val DIFFERENT_CORR = 0.80f

    /** 单个块在某个位移下达到这个相关，算「这一块对上了」。 */
    private const val PAIR_MATCH = 0.86f
    /**
     * 峰值独占度：最佳位移的相关要比「离它 1 px 以外的次佳位移」高出这么多。
     *
     * 这一条专门对付**周期性 / 平稳纹理**（水面、草地、树叶、条纹、噪点）。
     * 这类画面里每个块都能在好几个位移上凑出 0.95 以上的相关——不是立体，是巧合，
     * 而且巧合在 mono 360 的左右两半里同样成立（两半都是「一片水」，统计上没差别）。
     * 真立体的峰值是唯一的：错开一个像素，相关就明显掉下去。
     */
    private const val PEAK_GAP = 0.05f
    /** 最宽的那个视差带里要对上多少块（占所有有起伏块），才认为整幅是立体对。 */
    private const val BAND_RATIO = 0.45f
    /** 视差带内块的平均相关，低于这个值说明「勉强对上」，不算数。 */
    private const val PAIR_MEAN = 0.90f
    /** 视差带的宽度：中位位移 ±[BAND_HALF] 个网格像素。 */
    private const val BAND_HALF = 2
    /**
     * 视差带里的块要**连成片**：有邻居（上下左右）也在带里的块占比。
     *
     * 真立体的一片背景是连着的；纯色块之外剩不下几十个块的时候，
     * 巧合匹配是散落的。这一条把「散落的巧合」和「一片背景」分开，
     * 也顺手挡掉「上下两半各自一片天空」这种对称画面。
     */
    private const val BAND_CLUSTER = 0.55f

    /** 一个块至少要有这么多灰度标准差才算「有起伏」，否则跳过（天空、黑边、暗角）。 */
    private const val MIN_STD = 0.012f
    /** 参与统计的有起伏块少于此数，这一帧没有判别力。 */
    private const val MIN_BLOCKS = 20

    /** 按块比对的分块边长（网格单元）。 */
    private const val BLOCK = 6

    /** 判据网格的上限：抽帧靶是 192×96，再大没有额外信息。 */
    private const val MAX_GRID_W = 192
    private const val MAX_GRID_H = 96

    /** 两个轴要好到这个差距才敢挑一个（避免「地平线在两半同高」这种对称画面）。 */
    private const val MARGIN = 0.04f

    data class Verdict(
        val layout: StereoLayout,
        /** 0 表示这一帧没有判别力（黑场 / 平坦 / 两个轴都像）。 */
        val confidence: Float,
        /** 零位移相关系数（旧判据，保留在日志里方便与历史记录对账）。 */
        val sbsCorr: Float,
        val tabCorr: Float,
        val luma: Float,
        /** 视差带内对上的块占（有起伏的）块的比例。 */
        val sbsBand: Float,
        val tabBand: Float,
        /** 视差带内块的平均相关。 */
        val sbsMean: Float,
        val tabMean: Float,
        /** 视差带中心（判据网格像素）。 */
        val sbsShift: Int,
        val tabShift: Int,
        /** 视差带内「有邻居也在带里」的块占比。 */
        val sbsCluster: Float,
        val tabCluster: Float
    ) {
        fun evidence(): StereoEvidence = StereoEvidence(
            layout = layout,
            confidence = confidence,
            note = "相关 左右 ${fmt(sbsCorr)}/带内 ${fmt(sbsBand)} 上下 ${fmt(tabCorr)}/带内 ${fmt(tabBand)}"
        )

        fun describe(): String =
            "抽帧 ${layout.name} 置信 ${fmt(confidence)} · " +
                "左右 零位移 ${fmt(sbsCorr)} 带内 ${fmt(sbsBand)}@${sbsShift}px " +
                "均值 ${fmt(sbsMean)} 连片 ${fmt(sbsCluster)} · " +
                "上下 零位移 ${fmt(tabCorr)} 带内 ${fmt(tabBand)}@${tabShift}px " +
                "均值 ${fmt(tabMean)} 连片 ${fmt(tabCluster)} · " +
                "亮度 ${fmt(luma)}"
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    private val UNKNOWN = Verdict(StereoLayout.MONO, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0, 0f, 0f)

    /**
     * @param rgba 画面像素，RGBA_8888，行优先。GL 读回来是上下颠倒的，
     *             但这里只比左右 / 上下半幅，颠倒不影响结论。
     */
    fun judge(rgba: ByteArray, width: Int, height: Int): Verdict {
        if (width < 24 || height < 24) return UNKNOWN
        if (rgba.size < width * height * 4) return UNKNOWN

        // 缩到判据网格：压掉噪声，也让一次判定只有十几万次运算。
        val gw = width.coerceAtMost(MAX_GRID_W)
        val gh = height.coerceAtMost(MAX_GRID_H)
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
        if (luma < 0.035f) return Verdict(StereoLayout.MONO, 0f, 0f, 0f, luma, 0f, 0f, 0f, 0f, 0, 0, 0f, 0f)

        val hw = gw / 2
        val hh = gh / 2
        if (hw < BLOCK * 3 || hh < BLOCK * 3) return UNKNOWN

        val left = plane(grid, gw, 0, 0, hw, gh)
        val right = plane(grid, gw, hw, 0, hw, gh)
        val top = plane(grid, gw, 0, 0, gw, hh)
        val bottom = plane(grid, gw, 0, hh, gw, hh)

        val sbs = align(left, right, hw, gh, horizontal = true)
        val tab = align(top, bottom, gw, hh, horizontal = false)

        fun verdict(layout: StereoLayout, confidence: Float) = Verdict(
            layout = layout, confidence = confidence,
            sbsCorr = sbs.corr0, tabCorr = tab.corr0, luma = luma,
            sbsBand = sbs.band, tabBand = tab.band,
            sbsMean = sbs.bandMean, tabMean = tab.bandMean,
            sbsShift = sbs.shift, tabShift = tab.shift,
            sbsCluster = sbs.cluster, tabCluster = tab.cluster
        )

        val sbsStereo = sbs.isStereo
        val tabStereo = tab.isStereo

        return when {
            sbsStereo && (!tabStereo || sbs.bandMean - tab.bandMean >= MARGIN) ->
                verdict(StereoLayout.SBS, confidenceOf(sbs.bandMean))
            tabStereo && (!sbsStereo || tab.bandMean - sbs.bandMean >= MARGIN) ->
                verdict(StereoLayout.TAB, confidenceOf(tab.bandMean))
            // V2.31：「两半不像」是**弱证据**，置信低于 FormatDetector 的 0.70 门槛，
            // 只用来兜底，不许它把文件名 / 容器 / 格式记忆里对的布局改掉。
            sbs.corr0 < DIFFERENT_CORR && tab.corr0 < DIFFERENT_CORR &&
                !sbsStereo && !tabStereo -> verdict(StereoLayout.MONO, 0.55f)
            else -> verdict(StereoLayout.MONO, 0f)
        }
    }

    private fun confidenceOf(corr: Float): Float = when {
        corr >= 0.975f -> 0.95f
        corr >= 0.955f -> 0.88f
        else -> 0.80f
    }

    /** 三个样本投票：一致才落地，分歧就当没看见。 */
    fun combine(samples: List<Verdict>): Verdict? {
        val decided = samples.filter { it.confidence > 0f }
        if (decided.size < samples.size || decided.isEmpty()) return null
        val first = decided.first()
        if (decided.any { it.layout != first.layout }) return null
        // 取最保守的那一条：置信最低的样本。
        return decided.minByOrNull { it.confidence }
    }

    // ---- 分块视差比对 ----

    private class Align(
        /** 零位移整幅相关（旧判据）。 */
        val corr0: Float,
        /** 最宽视差带内块的平均相关。 */
        val bandMean: Float,
        /** 最宽视差带内对上的块 / 有起伏的块。 */
        val band: Float,
        /** 视差带中心（绝对值）。 */
        val shift: Int,
        /** 视差带内「有邻居也在带里」的块占比。 */
        val cluster: Float,
        /** 零位移下就已经对上的块占比（诊断用：视差为 0 的片源会是 1.0）。 */
        val flatRatio: Float,
        val textured: Int
    ) {
        /** 三条同时成立才算「两半是同一场景的立体对」。 */
        val isStereo: Boolean
            get() = textured >= MIN_BLOCKS && band >= BAND_RATIO &&
                bandMean >= PAIR_MEAN && cluster >= BAND_CLUSTER
    }

    /** 从整幅网格里切出一块矩形平面。 */
    private fun plane(grid: FloatArray, gw: Int, x0: Int, y0: Int, w: Int, h: Int): FloatArray {
        val out = FloatArray(w * h)
        for (y in 0 until h) {
            val src = (y0 + y) * gw + x0
            System.arraycopy(grid, src, out, y * w, w)
        }
        return out
    }

    /**
     * 分块求「最佳小位移下的相关」，再找最宽的视差带。
     *
     * 位移搜索范围按半幅宽度的 10% 算：判据网格里半幅 96 像素时是 9 像素，
     * 换成 4096×2048 的片源约 190 px（近景 0.25 m 的视差也就这个量级）。
     * 再放宽就会把「两块不相干的画面」硬凑到一起。
     */
    private fun align(a: FloatArray, b: FloatArray, w: Int, h: Int, horizontal: Boolean): Align {
        val limit = if (horizontal) w else h
        val maxShift = (limit * 0.10f).toInt().coerceIn(3, 12)

        var textured = 0
        var matchedZero = 0
        val shifts = ArrayList<Int>()
        val corrs = ArrayList<Float>()
        val gaps = ArrayList<Float>()
        val blockX = ArrayList<Int>()
        val blockY = ArrayList<Int>()
        val corrBuf = FloatArray(2 * maxShift + 1)

        if (horizontal) {
            var x = maxShift
            while (x + BLOCK + maxShift <= w) {
                var y = 0
                while (y + BLOCK <= h) {
                    // 先看有没有起伏：纯色块（天空、黑边、暗角）不参与统计，也不做搜索。
                    if (stdOf(a, w, x, y, BLOCK) >= MIN_STD) {
                        textured++
                        val m = bestShift(a, b, w, x, y, BLOCK, maxShift, true, corrBuf)
                        corrs.add(m.corr)
                        gaps.add(m.gap)
                        shifts.add(m.shift)
                        blockX.add(x / BLOCK)
                        blockY.add(y / BLOCK)
                        if (m.shift == 0) matchedZero++
                    }
                    y += BLOCK
                }
                x += BLOCK
            }
        } else {
            var y = maxShift
            while (y + BLOCK + maxShift <= h) {
                var x = 0
                while (x + BLOCK <= w) {
                    if (stdOf(a, w, x, y, BLOCK) >= MIN_STD) {
                        textured++
                        val m = bestShift(a, b, w, x, y, BLOCK, maxShift, false, corrBuf)
                        corrs.add(m.corr)
                        gaps.add(m.gap)
                        shifts.add(m.shift)
                        blockX.add(x / BLOCK)
                        blockY.add(y / BLOCK)
                        if (m.shift == 0) matchedZero++
                    }
                    x += BLOCK
                }
                y += BLOCK
            }
        }

        // 找最宽的视差带：以每个出现过的位移为中心，统计「相关 ≥ PAIR_MATCH 且位移落在 ±BAND_HALF」的块。
        var bestCenter = 0
        var bestCount = 0
        var bestCorrSum = 0.0
        var bestCluster = 0f
        if (corrs.isNotEmpty()) {
            for (center in -maxShift..maxShift) {
                var count = 0
                var sum = 0.0
                val inBand = HashSet<Long>()
                for (i in corrs.indices) {
                    if (corrs[i] >= PAIR_MATCH && gaps[i] >= PEAK_GAP &&
                        kotlin.math.abs(shifts[i] - center) <= BAND_HALF
                    ) {
                        count++
                        sum += corrs[i]
                        inBand.add(blockKey(blockX[i], blockY[i]))
                    }
                }
                if (count > bestCount) {
                    bestCount = count
                    bestCenter = center
                    bestCorrSum = sum
                    bestCluster = clusterOf(inBand, count)
                }
            }
        }

        val bandRatio = if (textured > 0) bestCount.toFloat() / textured else 0f
        val bandMean = if (bestCount > 0) (bestCorrSum / bestCount).toFloat() else 0f
        val flatRatio = if (textured > 0) matchedZero.toFloat() / textured else 0f

        return Align(
            corr0 = wholeCorr(a, b),
            bandMean = bandMean,
            band = bandRatio,
            shift = kotlin.math.abs(bestCenter),
            cluster = bestCluster,
            flatRatio = flatRatio,
            textured = textured
        )
    }

    /**
     * 在一个块上搜索最佳位移，返回 (相关, 位移)。
     * A 的块以 (ax, ay) 为左上角；B 的块用同一个左上角再平移 s（各在自己的半幅坐标系里）。
     */
    private class Match(val corr: Float, val shift: Int, val gap: Float)

    private fun blockKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)

    /** 4 邻接连片度：有多少带内块至少有一个上下左右的邻块也在带内。 */
    private fun clusterOf(inBand: Set<Long>, count: Int): Float {
        if (count == 0 || inBand.isEmpty()) return 0f
        var linked = 0
        for (k in inBand) {
            val x = (k shr 32).toInt()
            val y = k.toInt()
            if (inBand.contains(blockKey(x - 1, y)) || inBand.contains(blockKey(x + 1, y)) ||
                inBand.contains(blockKey(x, y - 1)) || inBand.contains(blockKey(x, y + 1))
            ) linked++
        }
        return linked.toFloat() / inBand.size
    }

    private fun bestShift(
        a: FloatArray, b: FloatArray, w: Int,
        ax: Int, ay: Int, size: Int,
        maxShift: Int, horizontal: Boolean,
        buf: FloatArray
    ): Match {
        var best = -2f
        var bestShift = 0
        // buf 是复用数组（长度 2*maxShift+1）：每个块都新建会在这条 GL 线程上留下垃圾。
        var i = 0
        for (s in -maxShift..maxShift) {
            val c = if (horizontal) {
                blockCorr(a, b, w, ax, ay, ax + s, ay, size)
            } else {
                blockCorr(a, b, w, ax, ay, ax, ay + s, size)
            }
            buf[i++] = c
            if (c > best) {
                best = c
                bestShift = s
            }
        }
        // 次佳只统计离最佳 1 个像素以外的位移：相邻位移本来就该跟着高，不算「另一种解释」。
        var second = -2f
        for (k in -maxShift..maxShift) {
            if (kotlin.math.abs(k - bestShift) <= 1) continue
            val c = buf[k + maxShift]
            if (c > second) second = c
        }
        // 只有一个位移可试时，次佳无从谈起，按「独占」处理。
        val gap = if (second <= -1f) 1f else best - second
        return Match(best, bestShift, gap)
    }

    /** 整幅零位移皮尔逊相关（旧判据，只用于日志与「两半不像」这一条）。 */
    private fun wholeCorr(a: FloatArray, b: FloatArray): Float {
        val n = a.size
        if (n == 0 || b.size != n) return 0f
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
        if (va <= 1e-9 || vb <= 1e-9) return 0f
        val cov = sab / n - ma * mb
        return (cov / kotlin.math.sqrt(va * vb)).toFloat().coerceIn(-1f, 1f)
    }

    private fun stdOf(p: FloatArray, w: Int, x0: Int, y0: Int, size: Int): Float {
        var s = 0.0
        var ss = 0.0
        for (y in y0 until y0 + size) {
            var idx = y * w + x0
            for (x in 0 until size) {
                val v = p[idx].toDouble()
                s += v; ss += v * v
                idx++
            }
        }
        val n = size * size
        val mean = s / n
        return kotlin.math.sqrt((ss / n - mean * mean).coerceAtLeast(0.0)).toFloat()
    }

    /** 两块同尺寸方块在各自平面内的零均值归一化相关。 */
    private fun blockCorr(
        a: FloatArray, b: FloatArray, w: Int,
        ax: Int, ay: Int, bx: Int, by: Int, size: Int
    ): Float {
        var sa = 0.0
        var sb = 0.0
        var saa = 0.0
        var sbb = 0.0
        var sab = 0.0
        for (j in 0 until size) {
            var ia = (ay + j) * w + ax
            var ib = (by + j) * w + bx
            for (i in 0 until size) {
                val x = a[ia].toDouble()
                val y = b[ib].toDouble()
                sa += x; sb += y
                saa += x * x; sbb += y * y; sab += x * y
                ia++; ib++
            }
        }
        val n = (size * size).toDouble()
        val ma = sa / n
        val mb = sb / n
        val va = saa / n - ma * ma
        val vb = sbb / n - mb * mb
        // 任一块没有起伏（纯色 / 黑边）→ 这一位移下的相关没有意义。
        if (va <= 1e-9 || vb <= 1e-9) return 0f
        val cov = sab / n - ma * mb
        return (cov / kotlin.math.sqrt(va * vb)).toFloat().coerceIn(-1f, 1f)
    }
}

/** 抽帧得出的布局证据，喂回 [FormatDetector.detect]。 */
data class StereoEvidence(
    val layout: StereoLayout,
    val confidence: Float,
    val note: String = ""
)
