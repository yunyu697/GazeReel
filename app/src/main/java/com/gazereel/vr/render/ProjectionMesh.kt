package com.gazereel.vr.render

import com.gazereel.vr.core.Projection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** PRD 6.1：按投影模式选择内翻球面 / 半球 / 虚拟影院平面。 */
class ProjectionMesh {

    data class Geometry(val vertices: FloatBuffer, val vertexCount: Int)

    fun build(projection: Projection): Geometry = when (projection) {
        Projection.SPHERE_360 -> curved(fullSphere = true)
        Projection.HALF_SPHERE_180 -> curved(fullSphere = false)
        // 视频用平面与 UI 用平面的 v 轴方向相反：外部纹理（SurfaceTexture）的
        // v=0 是画面**底部**，而 Bitmap 上传的 2D 纹理 v=0 是**顶部**。
        Projection.PLANE_2D -> plane(distance = 2f, width = 2.4f, height = 1.35f, centerY = 0f, flipV = true)
    }

    /** 世界锁定 UI：约 2 米虚拟距离；播放控制台下移到视野下方约 13°。 */
    fun uiQuad(centerY: Float = 0f): Geometry =
        plane(distance = 2f, width = 1.8f, height = 0.9f, centerY = centerY)

    private fun curved(fullSphere: Boolean): Geometry {
        val latBands = 32
        val lonBands = if (fullSphere) 64 else 40
        val radius = 10f
        // V2.7.4：等距柱状投影的 u=0.5 才是画面中心，且「向左看」必须对应 u 变小。
        // 旧实现让正前方（theta=0）落在 u=0，于是打开 360 视频第一眼看到的是接缝。
        val startTheta = if (fullSphere) -PI else -PI / 2
        val endTheta = if (fullSphere) PI else PI / 2
        val span = endTheta - startTheta
        val out = mutableListOf<Float>()

        fun point(phi: Double, theta: Double): FloatArray = floatArrayOf(
            (-radius * cos(phi) * sin(theta)).toFloat(),
            (radius * sin(phi)).toFloat(),
            (-radius * cos(phi) * cos(theta)).toFloat()
        )

        fun emit(phi: Double, theta: Double) {
            val p = point(phi, theta)
            val u = (0.5 - theta / span).toFloat()
            val v = (phi / PI + 0.5).toFloat()
            out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(u); out.add(v)
        }

        for (lat in 0 until latBands) {
            val phi0 = -PI / 2 + PI * lat / latBands
            val phi1 = -PI / 2 + PI * (lat + 1) / latBands
            for (lon in 0 until lonBands) {
                val t0 = startTheta + (endTheta - startTheta) * lon / lonBands
                val t1 = startTheta + (endTheta - startTheta) * (lon + 1) / lonBands
                emit(phi0, t0); emit(phi1, t0); emit(phi0, t1)
                emit(phi0, t1); emit(phi1, t0); emit(phi1, t1)
            }
        }
        return toGeometry(out)
    }

    private fun plane(
        distance: Float,
        width: Float,
        height: Float,
        centerY: Float,
        flipV: Boolean = false
    ): Geometry {
        val left = -width / 2
        val right = width / 2
        val bottom = centerY - height / 2
        val top = centerY + height / 2
        val z = -distance
        val out = mutableListOf<Float>()
        fun emit(x: Float, y: Float, u: Float, v: Float) {
            out.add(x); out.add(y); out.add(z); out.add(u); out.add(v)
        }
        val vTop = if (flipV) 1f else 0f
        val vBottom = if (flipV) 0f else 1f
        emit(left, bottom, 0f, vBottom); emit(right, bottom, 1f, vBottom); emit(left, top, 0f, vTop)
        emit(left, top, 0f, vTop); emit(right, bottom, 1f, vBottom); emit(right, top, 1f, vTop)
        return toGeometry(out)
    }

    private fun toGeometry(values: List<Float>): Geometry {
        val buffer = ByteBuffer.allocateDirect(values.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(values.toFloatArray())
        buffer.position(0)
        return Geometry(buffer, values.size / 5)
    }
}
