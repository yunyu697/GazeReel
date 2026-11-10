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

    /**
     * 世界锁定 UI：一块 2 米外、**正对眼睛**的平面，可以整体转向 (yaw, pitch)。
     *
     * V2.13：尺寸变成参数。片库要在一屏里放下 8 个能读清的格子，
     * 1.8×0.9 米（约 48.5°×25.4°）摊不开，片库单独用 2.4×1.2 米（约 62°×33°）。
     * 尺寸必须与 VrUiLayer.panelSpec() 一致，否则凝视落点会整体偏移。
     *
     * V2.19c：**是「转向」不是「平移」**，这条差点写错。
     *
     * 姿态确认卡要出现在候选方向的视线正前方，最直觉的做法是把平面平移过去
     * （`y = 2·tan(pitch)`）。但平面若保持与 XY 平行，视线与它的法线夹角就等于
     * 候选角度本身——抬头 60° 时卡片被压扁到 `cos 60° = 0.5`，还带强透视，字读不了。
     * 控制台只偏 13°（`cos 13° = 0.97`）时看不出来，所以这个错误只在大角度下暴露，
     * 而姿态调整恰恰全是大角度。
     *
     * 正确做法：中心 `C = 2f`，两个轴取 `r`（右）与 `u = r × f`（上），
     * 平面与视线垂直。任何角度下都不变形，凝视映射也与 `VrUiLayer.gazePoint` 对齐。
     */
    fun uiQuad(
        yaw: Float = 0f,
        pitch: Float = 0f,
        width: Float = 1.8f,
        height: Float = 0.9f
    ): Geometry {
        val cy = cos(yaw)
        val sy = sin(yaw)
        val cp = cos(pitch)
        val sp = sin(pitch)

        // 视线方向 f（y 向上，−z 为正前方）
        val fx = sy * cp
        val fy = sp
        val fz = -cy * cp
        val cx = 2f * fx
        val cyy = 2f * fy
        val cz = 2f * fz

        // 右轴与上轴：r 与俯仰无关，u = r × f
        val rx = cy
        val rz = sy
        val ux = -sy * sp
        val uy = cp
        val uz = cy * sp

        val hw = width / 2f
        val hh = height / 2f
        fun corner(sx: Float, sy2: Float): FloatArray = floatArrayOf(
            cx + sx * hw * rx + sy2 * hh * ux,
            cyy + sy2 * hh * uy,
            cz + sx * hw * rz + sy2 * hh * uz
        )

        val bl = corner(-1f, -1f)
        val br = corner(1f, -1f)
        val tl = corner(-1f, 1f)
        val tr = corner(1f, 1f)

        val out = mutableListOf<Float>()
        fun emit(p: FloatArray, u: Float, v: Float) {
            out.add(p[0]); out.add(p[1]); out.add(p[2]); out.add(u); out.add(v)
        }
        // Bitmap 2D 纹理的 v=0 是顶部，所以底边 v=1、顶边 v=0。
        emit(bl, 0f, 1f); emit(br, 1f, 1f); emit(tl, 0f, 0f)
        emit(tl, 0f, 0f); emit(br, 1f, 1f); emit(tr, 1f, 0f)
        return toGeometry(out)
    }

    /**
     * V2.20：整屏四边形（裁剪空间 −1..1），只给「离屏读回」用。
     *
     * 抽帧判立体布局要把同一张视频纹理原样画进一块小 FBO 再读像素，
     * 这条路径不需要任何投影，单位 MVP + 铺满视口就是我们要的东西。
     */
    fun screenQuad(): Geometry {
        val out = mutableListOf<Float>()
        fun emit(x: Float, y: Float, u: Float, v: Float) {
            out.add(x); out.add(y); out.add(0f); out.add(u); out.add(v)
        }
        emit(-1f, -1f, 0f, 0f); emit(1f, -1f, 1f, 0f); emit(-1f, 1f, 0f, 1f)
        emit(-1f, 1f, 0f, 1f); emit(1f, -1f, 1f, 0f); emit(1f, 1f, 1f, 1f)
        return toGeometry(out)
    }

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
