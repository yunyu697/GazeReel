package com.gazereel.vr.core

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * V2.39：姿态矩阵的纯数学工具。
 *
 * 为什么从 `android.opengl.Matrix` 搬出来：上一版（V2.37）用 `Matrix.rotateM`
 * 拼 `Ry·Rx·Rz`，真机上「开关打开后没有任何变化」。那条路径没法在开发机上跑，
 * 只能靠推演——而推演已经错过一次了。这里改成不依赖 Android 的显式 3×3 运算，
 * 于是可以用 `tools/run-pose-check.sh` 在 JVM 上直接验证姿态数学。
 *
 * V2.43：**把俯仰反向加回来，并更正 V2.41 的错误诊断。**
 *
 * V2.41 说「只反俯仰是镜像（det = −1），不可能实现」——这句话是错的。
 * 它描述的是「对姿态矩阵逐元素做 Y 取反」；而我们要做的不是变换矩阵，
 * 是**用同一组欧拉角、把 pitch 换成 −pitch 重新构造一个姿态**：
 * `Ry(y)·Rx(−p)·Rz(r)` 是三个旋转的乘积，det 恒为 +1，是货真价实的合法旋转。
 * 数值验证（16 组 y/p/r，含 89° 俯仰、20° 横滚、120° 偏航）：
 * yaw 与 roll 逐度不变，pitch 精确取反，正交性不变。
 *
 * V2.37/alpha26 真机「点一下之后画面在转」的真因不是镜像，是 `yawPitchRollDeg`
 * 里 yaw 多了一个负号：分解出的 y 与重建用的 `rotY(−yaw)` 差一整套符号，
 * 残余横滚把 2 倍偏航吸了进去，头一动 roll 就乱跳。那个负号已在 V2.41 修掉，
 * 所以现在重建是安全的。
 *
 * 同时 V2.41 的 `flipVertical`（把球面 v 取补）整段删除：那等于把画面上下
 * **镜像贴到球面上**，真机 2026-11-12 确认「整个画面都倒过来了」。
 * 方向问题只能改姿态响应，不能改内容——内容翻转必然带来倒像。
 *
 * 约定与 [VrHeadsetProvider.headRotation] 一致：列主序 4×4，
 * 第 0/1/2 列 = 屏幕的右 / 上 / 后在世界中的方向，`-第 2 列` 即视线。
 */
object PoseMath {

    /**
     * 分解成 `[yaw, pitch, roll]`（度），只用于日志与自检。
     *
     * 日志用它记「用户实际看到的朝向」；自检用它验证翻转之后 roll 没被动过。
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

    /**
     * V2.43：把姿态的**俯仰响应**取反——yaw 与 roll 原样，pitch 换成 −pitch 后重建。
     *
     * 症状对应：抬头本该看到天花板（画面内容向下滚），却看到地板；左右正常、
     * 画面本身也不倒。这就是俯仰响应的符号，不是内容方向。
     *
     * 为什么这是合法旋转而不是镜像：这里没有去「改」矩阵的某几项，而是拿
     * 分解出来的三个角重新乘一遍 `Ry·Rx·Rz`。三个旋转相乘仍是旋转（det=+1），
     * 它的 up 向量始终朝上，所以**画面不会倒、也不会 roll**。
     *
     * 为什么不用「四元数把 x 分量取反」那种无分解的捷径：数值验证里它在
     * (yaw=0, pitch=80°, roll=10°) 会凭空造出 44.6° 偏航和 45.4° 横滚，
     * 正是「抬头低头画面在转」的来源。分解重建在同样输入下 yaw/roll 精确不变。
     *
     * 调用位置必须是**姿态源头**（`onSensorChanged` 写 `rotation` 之前），
     * 不能放在 `headRotation()` 出口：recenter 用 `rotation` 算参考系，
     * 出口翻转会让「看到的」和「被对齐的」不是同一个姿态，形成反馈回路。
     */
    fun withInvertedPitch(pose: FloatArray): FloatArray {
        val a = yawPitchRollDeg(pose)
        return poseOf(a[0], -a[1], a[2])
    }

    /** 视线俯仰角（度）：抬头为正。日志与自检用。 */    fun gazePitchDeg(pose: FloatArray): Float {
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
