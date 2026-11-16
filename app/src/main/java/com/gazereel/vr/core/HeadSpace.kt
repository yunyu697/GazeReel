package com.gazereel.vr.core

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin

/**
 * alpha38：头追姿态的**唯一真相**。
 *
 * ## 为什么要有这个文件
 *
 * 到 alpha37 为止，「姿态怎么变成视图矩阵」这件事在工程里有两个实现
 * （`SensorHeadsetProvider` / `CardboardHeadsetProvider`），转置出现在两个地方
 * （Provider 的 `refreshRotation` 与 `viewRotationOf`）。只要两处对「上游给的是什么」
 * 的假设不一致，就会出现「抬头低头、左右转头画面方向全反，而画面本身是正的」——
 * 这正是 alpha35 真机反馈。而那个假设**无法从本地 AAR 核实**（AAR 里只有
 * `classes.jar` 和 `.so`，没有源码），于是每修一次只能靠装机看一眼。
 *
 * 现在的规则只有一条，且全工程只在这里定义：
 *
 * ## 约定
 *
 * - **pose**（姿态）P：列主序 4×4 纯旋转，第 0/1/2 列 = 屏幕的 **右 / 上 / 后**
 *   在世界中的方向。等价说法：`v_world = P · v_screen`。
 * - **视线** = `−P 的第 3 列`（因为相机看 −Z）。见 [forward]。
 * - **view**（相机视图）= `Pᵀ`，再按眼位平移。见 [viewMatrix]。**全工程只有这一处转置。**
 * - `det(P) = +1`，永远是合法旋转，不会镜像、不会上下颠倒。
 *
 * ## 为什么「少做/多做一次转置」会双轴全反
 *
 * 戴稳时 `P ≈ I`，`P` 与 `Pᵀ` 几乎相同，所以画面看着是正的。
 * 一旦转头，`P` 是「设备 → 世界」，直接当 view 用等于把物理转角 α 又叠了一次，
 * 屏幕上得到 2α —— 头往哪边转画面就往哪边跑；俯仰同理。两个轴一起反、
 * 内容不镜像，与真机反馈逐条吻合。
 *
 * 本文件的正确性由 `tools/PoseMathCheck.kt` 第 7 组断言在 JVM 上离线证明
 * （`tools/run-pose-check.sh`），不需要装机。
 */
object HeadSpace {

    /** 单位姿态。 */
    fun identity(): FloatArray = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }

    /** 列主序 4×4 乘法：`a · b`（先 b 后 a）。 */
    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    /**
     * 纯旋转的逆 = 转置。**这是全工程唯一一处转置姿态的地方。**
     *
     * 平移列清零、`[15] = 1`：姿态本身不含平移，混进平移会让 MVP 把画面推走。
     */
    fun transpose(m: FloatArray): FloatArray {
        val out = FloatArray(16)
        out[0] = m[0]; out[1] = m[4]; out[2] = m[8]; out[3] = 0f
        out[4] = m[1]; out[5] = m[5]; out[6] = m[9]; out[7] = 0f
        out[8] = m[2]; out[9] = m[6]; out[10] = m[10]; out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return out
    }

    /** 视线方向（单位向量，世界系）：`−` 第 3 列。 */
    fun forward(pose: FloatArray): FloatArray =
        floatArrayOf(-pose[8], -pose[9], -pose[10])

    /**
     * 相机视图矩阵 = `Pᵀ` 加眼位平移。
     *
     * 推导：眼睛在世界里的位置是 `P · e`（`e = (eyeX, 0, 0)` 是眼位在**屏幕系**的偏移），
     * 于是 `v_cam = Pᵀ · (v_world − P·e) = Pᵀ·v_world − e`。
     * 列主序下就是「转置后的旋转 + 平移列 = −e」。
     *
     * 注意这里**没有**第二次转置，也不该有。上游给的必须是 pose，不是 view。
     */
    fun viewMatrix(pose: FloatArray, eyeX: Float = 0f): FloatArray {
        val out = transpose(pose)
        out[12] = -eyeX
        return out
    }

    /**
     * 两个姿态之间的夹角（度）。用于「手机是不是还在动」的判定与 SDK 方向自检。
     *
     * 对纯旋转 `a`、`b`，`trace(aᵀb) = Σ a_rc·b_rc`（3×3 逐元素内积），
     * 所以不需要真的做矩阵乘法：`θ = acos((dot − 1) / 2)`。
     */
    fun rotationAngleDeg(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        for (c in 0 until 3) for (r in 0 until 3) dot += a[c * 4 + r] * b[c * 4 + r]
        val cosine = ((dot - 1f) / 2f).coerceIn(-1f, 1f)
        return Math.toDegrees(acos(cosine).toDouble()).toFloat()
    }

    /**
     * 绕世界 Y 轴的旋转（弧度），列主序。
     *
     * 正角把 `+X` 转向 `−Z`，也就是**向左**转；`recenterYaw` 里传的是
     * 「向右为正」的视线方位角，所以那里直接传 `atan2(fx, −fz)` 的结果，
     * 左乘之后水平分量归零（推导见 `HeadRig.recenterYaw`）。
     */
    fun rotYRad(angle: Float): FloatArray {
        val c = cos(angle)
        val s = sin(angle)
        val out = identity()
        out[0] = c; out[2] = -s
        out[8] = s; out[10] = c
        return out
    }

    /**
     * 与 `android.opengl.Matrix.frustumM` 完全等价的透视矩阵（列主序）。
     *
     * 自己写是为了让投影也能进 JVM 离线自检——`android.opengl.Matrix` 在
     * `tools/run-pose-check.sh` 里不存在，而「视锥对不对」恰恰需要可断言。
     */
    fun frustum(
        left: Float, right: Float, bottom: Float, top: Float,
        near: Float, far: Float
    ): FloatArray {
        val out = FloatArray(16)
        out[0] = 2f * near / (right - left)
        out[5] = 2f * near / (top - bottom)
        out[8] = (right + left) / (right - left)
        out[9] = (top + bottom) / (top - bottom)
        out[10] = -(far + near) / (far - near)
        out[11] = -1f
        out[14] = -2f * far * near / (far - near)
        return out
    }

    /** 是否正交且右手（det = +1）。上游给的东西必须先过这一关。 */
    fun isValid(pose: FloatArray): Boolean = PoseMath.isProperRotation(pose)
}
