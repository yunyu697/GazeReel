package com.gazereel.vr.core

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V2.19 / B5：两个纯旋转矩阵之间的球面插值。
 *
 * ## 为什么需要它
 *
 * 「把正前方挪到这里」不能瞬跳。瞬跳在头显里不只是难看——整个视界在一帧里
 * 平移几十度，是典型的晕动触发源。设计第 09 章写的是 **0.8 秒缓转**。
 *
 * 而重定基准的实现是换参考系：`inverseOffset` 从旧值直接变成新值。
 * 要让这个过程连续，就得在**两个参考系之间插值**，也就是对旋转做 slerp。
 *
 * ## 为什么不用矩阵线性插值
 *
 * 两个旋转矩阵逐元素线性插值得到的矩阵一般**不再是旋转矩阵**（不正交），
 * 用它去乘姿态会让画面被拉伸、剪切。必须转成四元数、球面插值、再转回来。
 *
 * 矩阵为 OpenGL 列主序 4×4，只取左上 3×3，平移列忽略。
 */
object RotationBlend {

    /**
     * @param from 起始旋转（列主序 4×4）
     * @param to 目标旋转（列主序 4×4）
     * @param t 0..1，超出范围会被夹住
     * @param out 结果写入这里（可与 from / to 相同）
     */
    fun slerp(from: FloatArray, to: FloatArray, t: Float, out: FloatArray) {
        val k = t.coerceIn(0f, 1f)
        if (k <= 0f) {
            System.arraycopy(from, 0, out, 0, 16)
            return
        }
        if (k >= 1f) {
            System.arraycopy(to, 0, out, 0, 16)
            return
        }

        val a = toQuaternion(from)
        val b = toQuaternion(to)

        var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        // 四元数 q 与 -q 表示同一个旋转。取同号那条短弧，否则会绕远路转 360°。
        var bx = b[0]; var by = b[1]; var bz = b[2]; var bw = b[3]
        if (dot < 0f) {
            dot = -dot
            bx = -bx; by = -by; bz = -bz; bw = -bw
        }

        val x: Float
        val y: Float
        val z: Float
        val w: Float
        if (dot > 0.9995f) {
            // 几乎重合：线性插值 + 归一化足够，避免 acos 在 1 附近丢精度。
            x = a[0] + (bx - a[0]) * k
            y = a[1] + (by - a[1]) * k
            z = a[2] + (bz - a[2]) * k
            w = a[3] + (bw - a[3]) * k
        } else {
            val theta = acos(dot)
            val sinTheta = sin(theta)
            val wa = sin((1f - k) * theta) / sinTheta
            val wb = sin(k * theta) / sinTheta
            x = a[0] * wa + bx * wb
            y = a[1] * wa + by * wb
            z = a[2] * wa + bz * wb
            w = a[3] * wa + bw * wb
        }

        val len = sqrt(x * x + y * y + z * z + w * w)
        if (len <= 1e-6f) {
            System.arraycopy(from, 0, out, 0, 16)
            return
        }
        quaternionToMatrix(x / len, y / len, z / len, w / len, out)
    }

    /** 列主序 4×4 旋转矩阵 → 四元数 (x, y, z, w)。 */
    private fun toQuaternion(m: FloatArray): FloatArray {
        val m00 = m[0]; val m10 = m[1]; val m20 = m[2]
        val m01 = m[4]; val m11 = m[5]; val m21 = m[6]
        val m02 = m[8]; val m12 = m[9]; val m22 = m[10]
        val trace = m00 + m11 + m22
        return if (trace > 0f) {
            val s = sqrt(trace + 1f) * 2f
            floatArrayOf((m21 - m12) / s, (m02 - m20) / s, (m10 - m01) / s, 0.25f * s)
        } else if (m00 > m11 && m00 > m22) {
            val s = sqrt(1f + m00 - m11 - m22) * 2f
            floatArrayOf(0.25f * s, (m01 + m10) / s, (m02 + m20) / s, (m21 - m12) / s)
        } else if (m11 > m22) {
            val s = sqrt(1f + m11 - m00 - m22) * 2f
            floatArrayOf((m01 + m10) / s, 0.25f * s, (m12 + m21) / s, (m02 - m20) / s)
        } else {
            val s = sqrt(1f + m22 - m00 - m11) * 2f
            floatArrayOf((m02 + m20) / s, (m12 + m21) / s, 0.25f * s, (m10 - m01) / s)
        }
    }

    /** 四元数 → 列主序 4×4，平移列填 0。 */
    private fun quaternionToMatrix(x: Float, y: Float, z: Float, w: Float, out: FloatArray) {
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy + wz);      out[2] = 2f * (xz - wy);      out[3] = 0f
        out[4] = 2f * (xy - wz);      out[5] = 1f - 2f * (xx + zz); out[6] = 2f * (yz + wx);      out[7] = 0f
        out[8] = 2f * (xz + wy);      out[9] = 2f * (yz - wx);      out[10] = 1f - 2f * (xx + yy); out[11] = 0f
        out[12] = 0f;                 out[13] = 0f;                  out[14] = 0f;                  out[15] = 1f
    }
}
