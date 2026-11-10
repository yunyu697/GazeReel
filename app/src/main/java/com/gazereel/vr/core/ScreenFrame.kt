package com.gazereel.vr.core

import android.opengl.Matrix
import android.view.Surface
import com.gazereel.vr.data.RunLog

/**
 * 「屏幕的上边是手机的哪条轴」—— 整条姿态链里唯一一个**猜一次就全盘皆错**的量。
 *
 * ## 它为什么必须只有一个来源
 *
 * 姿态矩阵 `R = 设备姿态 × 屏幕映射`。设备姿态来自旋转矢量，是物理量；屏幕映射
 * 来自「屏幕轴 ↔ 设备轴」的对应关系，不是物理量——横屏时它是 90° 或 270°，
 * 竖屏是 0°，谁都不能靠算得出，只能**观测**。
 *
 * 它一旦错，`R` 就绕视轴整错一个 90° 的倍数：地平线竖着、UI 卡片跟着倒、
 * 为转向弹出的确认卡歪到视野之外。更糟的是它**不会被任何自动摆正修好**——
 * 自动摆正看的是「视线偏离正前方多少」，而绕视轴转一下不改变视线方向，
 * 偏移量仍然是 0。所以这个值必须一次定准，且全线共用同一个。
 *
 * ## 怎么观测：问重力
 *
 * 横握手机时，指向天空的那条设备轴就是「屏幕上」。四个候选（±x / ±y 当屏幕上）
 * 一一对应 `0/90/180/270` 四种映射，取「指天分量」最大的那个即可。
 *
 * 两个阈值缺一不可：
 * - 置信 ≥ 0.62：手机接近平放时重力落在屏幕法线上，面内没有信息，不能定；
 * - 领先次优 ≥ 0.30：手机从竖屏往横屏转的途中（约 45°）两个候选打平，
 *   那一瞬间选谁都是掷骰子。**等下一帧**，不要落地。
 *
 * 不落地时返回 [UNKNOWN]，调用方继续沿用上一帧的姿态即可——这不是错误，
 * 只是「这一帧还不知道」。**宁可慢半秒，也不要定错一整个会话。**
 *
 * ## 为什么不问 Display.getRotation()
 *
 * 试过，错了两版。窗口刚建好时它会返回转屏前的 0°，而页面已经是横屏；
 * 冻结下来就永久固化，每秒重读又会在中途翻 180°。它不是「读得到读不到」的问题，
 * 而是「同一时刻可能有两个值」的问题——参与坐标变换的量不能有这种性质。
 *
 * 重力没有这个歧义，只取决于手机此刻怎么拿。所以这里也顺带提供 [poseOf]，
 * 让两个页面与渲染层**共用同一份实现**，而不是各写一遍。
 */
object ScreenFrame {

    /** 还没定下来。调用方需要自己找兜底，并知道自己在兜底。 */
    const val UNKNOWN = Int.MIN_VALUE

    /** 指天分量（余弦）的下限。0.62 ≈ 与竖直方向夹角 52° 以内。 */
    private const val MIN_SCORE = 0.62f

    /** 第一名必须领先第二名这么多。0.30 ≈ 离 45° 分界线 17.5°。 */
    private const val MIN_MARGIN = 0.30f

    private val CANDIDATES = intArrayOf(
        Surface.ROTATION_0, Surface.ROTATION_90,
        Surface.ROTATION_180, Surface.ROTATION_270
    )

    @Volatile
    private var frame = UNKNOWN

    /** 已经定下来的值；[UNKNOWN] 表示还没定。 */
    fun cached(): Int = frame

    /** 是否已经定下来。还没定的时候调用方是在兜底，日志里要写清楚。 */
    fun isKnown(): Boolean = frame != UNKNOWN

    fun name(rotation: Int): String = when (rotation) {
        Surface.ROTATION_0 -> "0°"
        Surface.ROTATION_90 -> "90°"
        Surface.ROTATION_180 -> "180°"
        Surface.ROTATION_270 -> "270°"
        else -> "未知"
    }

    /**
     * 用一帧旋转矢量尝试定下屏上方向。
     *
     * 已经定过就直接返回，不会中途改——中途换一次就是画面跳 90°。
     *
     * @param d2w TYPE_ROTATION_VECTOR 推出的 3×3，**行主序** 9 元（world = R · device）
     * @param source 日志里写清是谁定的（「入盒引导页」/「VR 页」），出问题时一眼能追
     * @return 当前已知的屏上方向，仍未知时 [UNKNOWN]
     */
    fun offer(d2w: FloatArray, source: String): Int {
        val known = frame
        if (known != UNKNOWN) return known
        if (d2w.size < 9) return UNKNOWN

        // 行主序第 3 列是设备 z 轴，而我们要的是「设备 x / y 分别指天多少」：
        // 设备 x 在世界里的 z 分量 = d2w[6]，设备 y 同理 = d2w[7]。
        val upX = d2w[6]
        val upY = d2w[7]
        val scores = floatArrayOf(upY, -upX, -upY, upX)

        var best = 0
        for (i in 1..3) if (scores[i] > scores[best]) best = i
        var second = -2f
        for (i in 0..3) if (i != best && scores[i] > second) second = scores[i]
        val score = scores[best]
        val margin = score - second

        if (score < MIN_SCORE || margin < MIN_MARGIN) return UNKNOWN

        synchronized(this) {
            if (frame != UNKNOWN) return frame
            frame = CANDIDATES[best]
            RunLog.append(
                "frame",
                "屏上方向=${name(frame)}（重力 置信 ${round2(score)} 领先 ${round2(margin)} · $source）"
            )
            return frame
        }
    }

    /** 只有测试与「用户手动纠正」会用到。正常路径一律走 [offer]。 */
    fun force(rotation: Int, source: String) {
        if (rotation != Surface.ROTATION_0 && rotation != Surface.ROTATION_90 &&
            rotation != Surface.ROTATION_180 && rotation != Surface.ROTATION_270
        ) {
            return
        }
        frame = rotation
        RunLog.append("frame", "屏上方向=${name(rotation)}（$source）")
    }

    /**
     * 设备姿态 → **屏幕姿态**（4×4 列主序）。
     *
     * 输出列依次是 (right, up, back)，第三列取负即视线方向——与
     * [VrHeadsetProvider.headRotation] 的约定一致，准星与手势都基于它。
     */
    fun poseOf(d2w: FloatArray, rotation: Int): FloatArray {
        // 行主序 3×3 → 列主序 4×4（转置即为求逆，这里是布局转换，不是求逆）
        val deviceToWorld = FloatArray(16)
        for (col in 0..2) {
            for (row in 0..2) {
                deviceToWorld[col * 4 + row] = d2w[row * 3 + col]
            }
        }
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, deviceToWorld, 0, screenToDevice(rotation), 0)
        return out
    }

    /**
     * 屏幕轴 → 设备轴。列 0 = 屏幕右，列 1 = 屏幕上，列 2 = 屏幕法线（朝用户）。
     *
     * 与 `SensorManager.remapCoordinateSystem` 的约定一致：ROTATION_90 时
     * 屏幕右 = 设备 +y、屏幕上 = 设备 −x（等价于 `AXIS_Y, AXIS_MINUS_X`）。
     */
    fun screenToDevice(rotation: Int): FloatArray {
        val (x, y) = when (rotation) {
            Surface.ROTATION_90 -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(-1f, 0f, 0f)
            Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f) to floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(0f, -1f, 0f) to floatArrayOf(1f, 0f, 0f)
            else -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f)
        }
        return floatArrayOf(
            x[0], x[1], x[2], 0f,
            y[0], y[1], y[2], 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    private fun round2(v: Float): Float = (v * 100).toInt() / 100f
}
