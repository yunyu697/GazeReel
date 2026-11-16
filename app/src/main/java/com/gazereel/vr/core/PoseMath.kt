package com.gazereel.vr.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * V2.39：姿态矩阵的纯数学工具（不依赖 Android，可在 JVM 上直接自检）。
 *
 * V2.44：**整段「俯仰反向」已删除，不再回来。**
 *
 * 连续四轮（alpha24 / 26 / 28 / 30）都在做「把 pitch 取反」这件事，全部失败。
 * 离线数值复现给出的真因不是符号，而是坐标系：Android `SensorManager` 的旋转矢量在
 * **ENU（X 东、Y 北、Z 天）**，而本工程全部按 **Y 朝天、-Z 朝前** 解读。
 * alpha39 起生产链路的姿态直接来自官方 Cardboard SDK 的 HeadTracker，
 * 坐标系转换由 SDK 内部完成（[HeadPose] 仅作离线自检夹具）。此前在姿态源头
 * 照 `EkfToHeadTrackerRotations` 语义补上 ENU → Y-up 那一层之后：
 *
 * - 正常戴镜时 [yawPitchRollDeg] 得到 (±方位角, 0, 0)，不再恒为 ±90° 的万向节死锁；
 * - 抬头看天花板、低头看地板，四个方位一致（见 `tools/PoseMathCheck.kt`）；
 * - `recenterYaw` 的 rotY 也终于绕真正的竖直轴，不再把地平线拧歪。
 *
 * 为什么不能用「分解 → pitch 取反 → 重建」伪造：在死锁点附近分解出的 yaw/roll
 * 会跳 180°，真机表现就是「开关一拨画面左右翻转」。而坐标转换是 det=+1 的
 * 固定旋转，既不倒像也不镜像，更没有开关。成熟 App（VRTV / SourceVR）也都没有
 * 这种开关——方向由坐标约定保证，不是用户拨出来的。
 *
 * 约定与 [VrHeadsetProvider.headRotation] 一致：列主序 4×4，
 * 第 0/1/2 列 = 屏幕的右 / 上 / 后在世界中的方向，`-第 2 列` 即视线。
 */
object PoseMath {

    /**
     * 分解成 `[yaw, pitch, roll]`（度），只用于日志与自检。
     *
     * V2.44：现在输入的是 Y-up 姿态（生产链路由 SDK 给出），戴稳时 pitch/roll 恒为 0，
     * 不再踩万向节死锁；自检用它验证四个方位下 roll 都不会漂。
     */
    fun yawPitchRollDeg(pose: FloatArray): FloatArray {
        val gx = -pose[8].toDouble()
        val gy = (-pose[9]).toDouble().coerceIn(-1.0, 1.0)
        val gz = -pose[10].toDouble()
        // V2.41：这里的符号必须与 [gazeYawDeg] **完全一致**（向右为正）。
        // 旧版这里多了个负号，而重建用的是 `rotY(yaw)`——一个符号对不上，
        // 残余横滚就把这 2 倍偏航吸进去，于是「点一下开关，抬头低头画面在转」。
        val yaw = atan2(gx, -gz)
        val pitch = asin(gy)
        val m = mul(transpose3(mul(rotY(-yaw), rotX(pitch))), toMat3(pose))
        val roll = atan2(m[3], m[0])
        return floatArrayOf(
            Math.toDegrees(yaw).toFloat(),
            Math.toDegrees(pitch).toFloat(),
            Math.toDegrees(roll).toFloat()
        )
    }

    /** 视线俯仰角（度）：抬头为正。日志与自检用。 */
    fun gazePitchDeg(pose: FloatArray): Float {
        val gy = (-pose[9]).toDouble().coerceIn(-1.0, 1.0)
        return Math.toDegrees(asin(gy)).toFloat()
    }

    /** 视线偏航角（度）：向右为正。 */
    fun gazeYawDeg(pose: FloatArray): Float {
        val gx = -pose[8].toDouble()
        val gz = -pose[10].toDouble()
        return Math.toDegrees(atan2(gx, -gz)).toFloat()
    }

    /** 是否正交且右手（det = +1）。自检用：翻转之后必须仍然是一个合法姿态。 */
    fun isProperRotation(pose: FloatArray): Boolean {
        val m = toMat3(pose)
        val r0 = doubleArrayOf(m[0].toDouble(), m[1].toDouble(), m[2].toDouble())
        val r1 = doubleArrayOf(m[3].toDouble(), m[4].toDouble(), m[5].toDouble())
        val r2 = doubleArrayOf(m[6].toDouble(), m[7].toDouble(), m[8].toDouble())
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        if (abs(dot(r0, r0) - 1.0) > 1e-4 || abs(dot(r1, r1) - 1.0) > 1e-4 ||
            abs(dot(r2, r2) - 1.0) > 1e-4
        ) return false
        if (abs(dot(r0, r1)) > 1e-4 || abs(dot(r0, r2)) > 1e-4 || abs(dot(r1, r2)) > 1e-4) return false
        val cross = doubleArrayOf(
            r0[1] * r1[2] - r0[2] * r1[1],
            r0[2] * r1[0] - r0[0] * r1[2],
            r0[0] * r1[1] - r0[1] * r1[0]
        )
        return dot(cross, r2) > 0.99
    }

    // ── 3×3（行主序，Double，够精度的同时不依赖 Android）────────────────

    private fun rotX(rad: Double): DoubleArray {
        val c = cos(rad); val s = sin(rad)
        return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
    }

    private fun rotY(rad: Double): DoubleArray {
        val c = cos(rad); val s = sin(rad)
        return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
    }

    private fun rotZ(rad: Double): DoubleArray {
        val c = cos(rad); val s = sin(rad)
        return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
    }

    private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) {
                var sum = 0.0
                for (k in 0 until 3) sum += a[r * 3 + k] * b[k * 3 + c]
                out[r * 3 + c] = sum
            }
        }
        return out
    }

    private fun transpose3(a: DoubleArray): DoubleArray =
        doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

    /** 列主序 4×4 → 行主序 3×3（丢掉平移）。 */
    private fun toMat3(pose: FloatArray): DoubleArray {
        val out = DoubleArray(9)
        for (r in 0 until 3) {
            for (c in 0 until 3) out[r * 3 + c] = pose[c * 4 + r].toDouble()
        }
        return out
    }

    /** 行主序 3×3 → 列主序 4×4（平移归零）。 */
    private fun toPose(m: DoubleArray): FloatArray {
        val out = FloatArray(16)
        for (r in 0 until 3) {
            for (c in 0 until 3) out[c * 4 + r] = m[r * 3 + c].toFloat()
        }
        out[15] = 1f
        return out
    }

    /**
     * 自检用：按 `Ry(−yaw)·Rx(pitch)·Rz(roll)` 造一个姿态（列主序 4×4）。
     *
     * 为什么是 −yaw：`rotY(θ)` 在右手系里把 +X 转向 −Z，正角是**向左**转；
     * 而全工程（[gazeYawDeg]、准星、日志）用的是「向右为正」。
     * 这里对齐到后者，否则自检会拿一套符号去验另一套符号，永远验不出真问题。
     */
    fun poseOf(yawDeg: Float, pitchDeg: Float, rollDeg: Float): FloatArray =
        toPose(
            mul(
                mul(
                    rotY(-Math.toRadians(yawDeg.toDouble())),
                    rotX(Math.toRadians(pitchDeg.toDouble()))
                ),
                rotZ(Math.toRadians(rollDeg.toDouble()))
            )
        )
}
