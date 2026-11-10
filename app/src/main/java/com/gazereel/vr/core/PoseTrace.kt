package com.gazereel.vr.core

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

/**
 * V2.24 / 第 0 步：姿态诊断记录。
 *
 * **只读**。不参与任何姿态解算，不改变视图矩阵，不写渲染路径。
 * 它只做一件事：把每一帧的姿态结果和几个「本该看得见却一直没人看见」的量记下来，
 * 让真机上的现象从推理变成数据。
 *
 * ## 为什么需要它
 *
 * 上一轮在电脑上把矩阵数学复刻了一遍：全姿态球面扫描最大跳变 2.000°（等于采样步长），
 * 重定向落点 0.000000°——数学全过，真机 45°。这说明错的不是公式，是**喂给公式的输入**。
 * 而输入里有几个量靠推理永远猜不准：
 *
 * 1. `Display.getRotation()` 在锁定横屏的 Activity 里到底返回什么（上一版冻死在这里）；
 * 2. 视线与重力轴的夹角 θ——退化的唯一判据，此前从未被记录；
 * 3. `screenPoseOf` 里 `cross(forward, 参考轴)` 的**原始模长**，退化到了什么程度；
 * 4. 走了哪个参考轴分支（天顶 / 设备 y / 设备 x），切换时横滚跳了多少；
 * 5. 用户实际看到的横滚——它来自重定向偏移，不是屏幕姿态的中间结果。
 *
 * ## 两套横滚指标，因为一个不够
 *
 * 屏幕姿态的中间矩阵在正常分支下用 `cross(forward, 天顶)` 重建 right，
 * **横滚被强制归零**，拿它量横滚永远是 0。所以绝对横滚必须从最终姿态 `h = O·R` 量，
 * 而且参考的「上」不是世界天顶 ẑ，是 `O·ẑ`——h 的坐标轴是重定那一刻的手机姿态。
 * 参考用错，横滚会重新读成 0。
 *
 * 但绝对横滚在视线贴近重力轴时本身无定义（投影长度趋零）。所以抖动另用一个指标：
 * **up 轴的帧间夹角**。它对退化不敏感，横滚乱跳时它会直接变大。
 */
object PoseTrace {

    private const val SAMPLE_MS = 500L
    private const val MAX_MS = 300_000L
    private const val MAX_SAMPLES = 600
    private const val REPORT_SAMPLES = 120

    private data class Sample(
        val tMs: Long,
        val thetaDeg: Float,
        val crossLen: Float,
        val branch: Int,
        val rollDeg: Float,
        val offsetDeg: Float
    )

    private val samples = ArrayList<Sample>(MAX_SAMPLES)

    private var running = false
    private var startMs = 0L
    private var lastSampleMs = 0L
    private var panelRotation = -1
    private var screenOrientation = -1

    // ── 抖动：up 轴帧间夹角 ────────────────────────────────────────────
    private var frames = 0
    private val lastUp = FloatArray(3)
    private var hasLastUp = false
    private var sumJitter = 0f
    private var maxJitter = 0f
    private var secondStartMs = 0L
    private var secondJitter = 0f
    private var maxSecondJitter = 0f

    // ── 退化与分支 ────────────────────────────────────────────────────
    private var lastBranch = 0
    private var branchSwitches = 0
    private var maxSwitchJump = 0f
    private val thetaBuckets = IntArray(5)   // <2, 2-5, 5-10, 10-20, >20
    private val branchCount = IntArray(4)    // 0 未用, 1 天顶, 2 设备 y, 3 设备 x
    private var crossMin = Float.MAX_VALUE
    private var crossMax = 0f

    // ── 绝对横滚 ──────────────────────────────────────────────────────
    private var rollMin = 0f
    private var rollMax = 0f
    private var hasRollRange = false
    private var lastRoll = 0f
    private var hasLastRoll = false

    @Synchronized
    fun begin(nowMs: Long, panelRotation: Int, screenOrientation: Int) {
        samples.clear()
        running = true
        startMs = nowMs
        lastSampleMs = nowMs
        this.panelRotation = panelRotation
        this.screenOrientation = screenOrientation

        frames = 0
        hasLastUp = false
        sumJitter = 0f
        maxJitter = 0f
        secondStartMs = nowMs
        secondJitter = 0f
        maxSecondJitter = 0f

        lastBranch = 0
        branchSwitches = 0
        maxSwitchJump = 0f
        thetaBuckets.fill(0)
        branchCount.fill(0)
        crossMin = Float.MAX_VALUE
        crossMax = 0f

        hasRollRange = false
        hasLastRoll = false
    }

    @Synchronized
    fun finish() {
        running = false
    }

    /**
     * 每帧调用（传感器线程，约 50Hz）。
     *
     * 两个矩阵都要传，因为它们量的东西在**不同的坐标系**里：
     *
     * - [c] 是 `screenPoseOf` 的输出（设备 → 世界）。只有它还在世界坐标里，
     *   视线与重力轴的夹角 θ 才是有意义的；换成 [h] 会因为参考系已经转过而失真。
     * - [h] 是 `O · R`（设备 → 参考系），也就是 `headRotation()` 返回的那个。
     *   用户看到的横滚与抖动只有在这里才量得到。
     *
     * @param refUp 参考系里的「上」= `O · ẑ`，即偏移矩阵的第三列
     * @param crossLen `cross(forward, 参考轴)` 归一化前的模长（来自原始设备姿态）
     * @param branch 1=天顶 2=设备 y 3=设备 x
     */
    @Synchronized
    fun onFrame(
        nowMs: Long,
        c: FloatArray,
        h: FloatArray,
        refUp: FloatArray,
        crossLen: Float,
        branch: Int
    ) {
        if (!running) return
        if (nowMs - startMs > MAX_MS) {
            running = false
            return
        }
        frames++

        val rawTheta = Math.toDegrees(acos((-c[10]).coerceIn(-1f, 1f)).toDouble()).toFloat()
        val thetaDeg = minOf(rawTheta, 180f - rawTheta)
        val rollDeg = rollOf(h, refUp)

        val ux = h[4]; val uy = h[5]; val uz = h[6]

        thetaBuckets[when {
            thetaDeg < 2f -> 0
            thetaDeg < 5f -> 1
            thetaDeg < 10f -> 2
            thetaDeg < 20f -> 3
            else -> 4
        }]++

        if (crossLen < crossMin) crossMin = crossLen
        if (crossLen > crossMax) crossMax = crossLen

        branchCount[branch.coerceIn(0, 3)]++
        val switched = lastBranch != 0 && branch != lastBranch
        if (switched) {
            branchSwitches++
            if (hasLastRoll) {
                val jump = abs(shortestDelta(rollDeg, lastRoll))
                if (jump > maxSwitchJump) maxSwitchJump = jump
            }
        }
        lastBranch = branch

        // 抖动：up 轴转过的角度。退化时横滚乱跳，这个量会直接变大。
        if (hasLastUp) {
            val cos = (lastUp[0] * ux + lastUp[1] * uy + lastUp[2] * uz).coerceIn(-1f, 1f)
            val jitter = Math.toDegrees(acos(cos).toDouble()).toFloat()
            sumJitter += jitter
            if (jitter > maxJitter) maxJitter = jitter
            secondJitter += jitter
        }
        lastUp[0] = ux; lastUp[1] = uy; lastUp[2] = uz
        hasLastUp = true

        // 每秒结算一次抖动累计：这是「抖得有多凶」最直观的一个数
        if (nowMs - secondStartMs >= 1_000L) {
            if (secondJitter > maxSecondJitter) maxSecondJitter = secondJitter
            secondJitter = 0f
            secondStartMs = nowMs
        }

        if (!hasRollRange) {
            rollMin = rollDeg; rollMax = rollDeg; hasRollRange = true
        } else {
            if (rollDeg < rollMin) rollMin = rollDeg
            if (rollDeg > rollMax) rollMax = rollDeg
        }
        lastRoll = rollDeg
        hasLastRoll = true

        if (nowMs - lastSampleMs >= SAMPLE_MS && samples.size < MAX_SAMPLES) {
            lastSampleMs = nowMs
            samples.add(
                Sample(
                    tMs = nowMs - startMs,
                    thetaDeg = thetaDeg,
                    crossLen = crossLen,
                    branch = branch,
                    rollDeg = rollDeg,
                    offsetDeg = rawTheta
                )
            )
        }
    }

    /**
     * 画面相对重力的横滚角。
     *
     * @param refUp 参考系的「上」（已归一化的方向，允许与视线不垂直）
     *
     * 把 refUp 投影到垂直于视线的平面，得到参考「上」；叉出参考「右」；
     * 再看画面 up 相对这一对基准转了多少。视线与重力轴平行时投影趋零，
     * 此时返回 0——但那种姿态下横滚本来就无定义，靠抖动指标看。
     */
    private fun rollOf(h: FloatArray, refUp: FloatArray): Float {
        val ux = h[4]; val uy = h[5]; val uz = h[6]
        val bx = h[8]; val by = h[9]; val bz = h[10]

        val d = refUp[0] * bx + refUp[1] * by + refUp[2] * bz
        var px = refUp[0] - d * bx
        var py = refUp[1] - d * by
        var pz = refUp[2] - d * bz
        val len = sqrt(px * px + py * py + pz * pz)
        if (len < 1e-4f) return 0f
        px /= len; py /= len; pz /= len

        // 参考右 = 参考上 × back，与 right/up/back 同手性
        val rx = py * bz - pz * by
        val ry = pz * bx - px * bz
        val rz = px * by - py * bx

        val sinPart = ux * rx + uy * ry + uz * rz
        val cosPart = ux * px + uy * py + uz * pz
        return Math.toDegrees(atan2(sinPart, cosPart).toDouble()).toFloat()
    }

    private fun shortestDelta(a: Float, b: Float): Float {
        var d = a - b
        while (d > 180f) d -= 360f
        while (d < -180f) d += 360f
        return d
    }

    private fun pct(count: Int, total: Int): String =
        if (total <= 0) "0%" else String.format(Locale.US, "%.0f%%", count * 100f / total)

    @Synchronized
    fun summary(): String {
        if (frames <= 0) {
            return "（还没有姿态数据。进一次 VR，按「竖直 → 横放 → 举头顶平放 → 躺下」做一遍，再回这里看）"
        }

        val seconds = (lastSampleMs - startMs) / 1000f
        val total = frames
        val avgJitter = if (total > 1) sumJitter / (total - 1) else 0f
        val avgRoll = if (total > 1) {
            (rollMax - rollMin)
        } else 0f

        return buildString {
            appendLine("记录 ${fmt(seconds)}s / $total 帧 / ${samples.size} 样本")
            appendLine("面板 rotation=$panelRotation  orientation=$screenOrientation")
            appendLine(
                "抖动(up 帧间) 均 ${fmt(avgJitter)}° 峰 ${fmt(maxJitter)}° " +
                    "秒累计峰 ${fmt(maxSecondJitter)}°"
            )
            appendLine(
                "θ(视线与重力轴) <2°:${pct(thetaBuckets[0], total)} 2-5°:${pct(thetaBuckets[1], total)} " +
                    "5-10°:${pct(thetaBuckets[2], total)} 10-20°:${pct(thetaBuckets[3], total)} " +
                    ">20°:${pct(thetaBuckets[4], total)}"
            )
            appendLine("叉积模长(所选分支) min ${fmt(crossMin)} max ${fmt(crossMax)}  <0.2 即退化")
            appendLine(
                "分支 天顶:${pct(branchCount[1], total)} 设备y:${pct(branchCount[2], total)} " +
                    "设备x:${pct(branchCount[3], total)} 切换 $branchSwitches 次 最大跳 ${fmt(maxSwitchJump)}°"
            )
            if (hasRollRange) {
                appendLine("绝对横滚 范围 ${fmt(rollMin)}° ~ ${fmt(rollMax)}°（跨度 ${fmt(avgRoll)}°）")
            }
            appendLine("样本 t / θ / 叉积 / 分支 / 横滚 / 偏离")
            val from = (samples.size - REPORT_SAMPLES).coerceAtLeast(0)
            for (i in from until samples.size) {
                val s = samples[i]
                appendLine(
                    "  ${fmt(s.tMs / 1000f)}s θ=${fmt(s.thetaDeg)} c=${fmt3(s.crossLen)} " +
                        "b=${s.branch} roll=${fmt(s.rollDeg)} off=${fmt(s.offsetDeg)}"
                )
            }
        }
    }

    private fun fmt(v: Float): String = String.format(Locale.US, "%.1f", v)

    private fun fmt3(v: Float): String = String.format(Locale.US, "%.3f", v)
}
