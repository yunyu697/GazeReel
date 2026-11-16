package com.gazereel.vr.render

import com.gazereel.vr.core.Projection
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** PRD 6.1：按投影模式选择内翻球面 / 半球 / 虚拟影院平面。 */
class ProjectionMesh {

    data class Geometry(val vertices: FloatBuffer, val vertexCount: Int)

    /**
     * V2.43：不再有 `flipV` 参数。把球面 v 取补等于把画面上下镜像贴上去，
     * 真机 2026-11-12 确认「整个画面都倒过来了」——方向问题必须在姿态侧解决。
     * 2D 银幕那一层仍用自己的 `flipV = true` 对齐外部纹理，两条路径互不相关。
     */
    /**
     * 18:2x：张角真正参与几何。以前只按 projection 建网格，`fov` 写进 DB 却从不生效
     * （11-10 记的「FOV 是死的」）。现在球面跨度直接取 fov.degrees，
     * 180/220/270/360 共用同一套 u 映射；2D 银幕尺寸按档位现算。
     */
    fun build(
        projection: Projection,
        fovDegrees: Int?,
        screenWidthPercent: Int = 100,
        fisheyeFovDeg: Int = 200,
        fisheyeCirclePercent: Int = 100,
        fisheyeAspect: Float = 1f,
        fisheyeFullFrame: Boolean = false
    ): Geometry {
        if (projection == Projection.PLANE_2D) {
            return plane(
                distance = SCREEN_DISTANCE,
                width = screenWidth(screenWidthPercent),
                height = screenHeight(screenWidthPercent),
                centerY = screenCenterY(screenWidthPercent),
                flipV = true
            )
        }
        // 19:0x：鱼眼不是「等距柱状换一个张角」，而是另一套投影：网格是圆盘、
        // UV 由镜头投影反算。张角属于镜头（全局设置），不属于片子。
        if (projection == Projection.FISHEYE) {
            return fisheye(
                fisheyeFovDeg.toFloat(), fisheyeCirclePercent, fisheyeAspect, fisheyeFullFrame
            )
        }
        return curved((fovDegrees ?: 180).toFloat())
    }

    companion object {
        /** 银幕到眼睛的距离（米）。 */
        const val SCREEN_DISTANCE = 5.0f

        /** 银幕宽度：5 米处的 16:9 幕，横向张角 2·atan(2.7/5) ≈ 56.6°。 */
        const val SCREEN_WIDTH = 5.4f

        /** 银幕高度 = 宽 × 9/16 = 3.0375。 */
        const val SCREEN_HEIGHT = SCREEN_WIDTH * 9f / 16f

        /**
         * 银幕中心高度。
         *
         * 17:2x：原来是 `0f`（与眼睛齐平），这在几何上是错的 —— 银幕半高 1.519 米，
         * 中心取 0 时底边落在 **−1.519**，而 `SceneRoom.FLOOR_Y` 是 **−1.25**，
         * 也就是说**银幕下沿陷进地板 27 厘米**。前墙「洞口下方」那块墙的 y 范围
         * 因此是 −1.25 → −1.519（反向），等于那块墙根本不存在。用户看到的就是
         * 「银幕从地里长出来 / 立在地上」。
         *
         * 抬到 0.62 之后：底边 −0.899，离地 **0.35 米**，前墙有了一条真正的墙裙，
         * 银幕是「挂在墙上」的。代价是画面中心比视线高 7.1°（atan(0.62/5)），
         * 这是影院前排的量级，可接受；再高就会变成仰着脖子看两小时。
         *
         * 改这个值必须同步 `SceneRoom`（洞口、幕框按它算），否则墙会开错洞。
         */
        const val SCREEN_CENTER_Y = 0.62f

        /** 与 SceneRoom.FLOOR_Y 必须相等；tools/check_format_and_view.py 会核对。 */
        const val ROOM_FLOOR_Y = -1.25f

        /**
         * 18:2x：银幕宽度档位（80~110%，默认 100）。墙不动、只改幕宽，
         * 张角 2·atan(W/2d) 随之变化，等效于「坐第几排」但不改厅进深。
         * 上限 110% 是**天花约束**：幕头顶 = FLOOR_Y + H + SKIRT + FRAME_W，
         * 110% 时约 2.722 m < 天花 2.9 m；再放大幕头会穿顶。
         */
        fun screenWidth(percent: Int): Float = SCREEN_WIDTH * percent / 100f
        fun screenHeight(percent: Int): Float = screenWidth(percent) * 9f / 16f

        /**
         * 中心高度必须由「地板 + 半高 + 墙裙」推导，**不能写死**。
         * 17:2x 那个「银幕立在地上」的 bug，根因就是写死 0.62 而半高会随幕宽变：
         * 幕放到 120% 时半高 1.82，底边就落到 −1.20、几乎贴地。
         * 推导后底边恒为 FLOOR_Y + SKIRT = −0.89875，墙裙恒定 0.35125 m，
         * 各档都不会再陷进地板。100% 时正好等于基准 SCREEN_CENTER_Y = 0.62。
         */
        private const val SCREEN_SKIRT = 0.35125f
        fun screenCenterY(percent: Int): Float =
            ROOM_FLOOR_Y + screenHeight(percent) / 2f + SCREEN_SKIRT
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

    /**
     * 19:0x：**单镜头鱼眼**的反畸变网格。
     *
     * 为什么不能沿用 `curved()`：那条路径把 UV 当等距柱状图线性铺上去
     * （`u = 0.5 - θ/span`），鱼眼图的半径与角度是 `ρ = f·α` 的关系 ——
     * 拿线性 UV 去采样，画面中心被拉大、边缘被挤成一圈，越靠边越夸张，
     * 正是「认成 180° 会看到的扭曲」。
     *
     * 做法：**网格用极坐标圆盘，UV 用鱼眼投影反算**。
     * - 半径 r ∈ [0,1] 对应「离画面圆边缘还有多远」，α = r·FOV/2 是它离光轴的角度；
     * - 顶点位置 = 绕光轴转 α、方位角 ψ 的单位方向 × 10 m（与球面同半径，深度排序一致）；
     * - 纹理坐标 = `(0.5 + 0.5·circle·r·cosψ, 0.5 + 0.5·circle·r·sinψ)`；
     *   `circle` 是「画面圆占整幅的比例」，圆没占满时按比例缩，免得把圆外黑边卷进来。
     *
     * 只实现**等距投影**（equidistant，VR180 规范与 MKX / VRCA / RF5.2 的默认）。
     * 等立体角与立体投影会在边缘留一点形变，但不会像不反畸变那样整幅扭曲 —— 如实记录。
     *
     * 圆盘边缘之外没有几何：片源圆外的黑边不会被采样，也不会出现「采样越界拉出条纹」。
     *
     * `aspect` 是该眼区域的像素宽高比（SBS 取半幅、TAB 取半高后算），只影响 UV 的 u/v 倍率，
     * 不动顶点角度 —— 它保证「圆在屏幕上还是圆的」，不改变张角与视场。
     * `uEyeRegion` 仍由顶点着色器负责（SBS 时每眼取半幅，半幅内就是一个圆）。
     */
    private fun fisheye(
        fovDeg: Float,
        circlePercent: Int,
        aspect: Float,
        fullFrame: Boolean = false
    ): Geometry {
        val safeAspect = if (aspect.isFinite() && aspect > 0.05f) aspect else 1f
        // 19:2x：全画幅是**另一种画幅形态**，不是「圆小一点」。它必须换成矩形网格：
        // 圆盘最多铺到「半径 = 短边一半」那条边，四角天生没有几何，
        // 而全画幅的片子四角恰恰是有效像素 —— 用圆盘会在四角留黑。
        if (fullFrame) return fisheyeFullFrame(fovDeg, safeAspect)

        val radialBands = 28
        val segments = 96
        val radius = 10f
        val halfFov = Math.toRadians(fovDeg.toDouble()) / 2.0
        val circle = (circlePercent.coerceIn(50, 100)) / 100f
        // 19:1x：**圆度补偿**。纹理坐标的 u、v 是各自归一化的，而画面圆在**像素**里是正圆。
        // 单眼画幅不是正方形时（最典型：单镜头鱼眼存成 2:1，圆只占中间），
        // 若仍按 u、v 同倍率铺，圆会被横向拉成椭圆 —— 转一圈看，四周的直线都在弯。
        // 按「圆的直径永远只受**短边**限制」换算：宽画面只在 u 上收窄，高画面只在 v 上收窄。
        // 正方形（VR180 规范的 SBS，每眼 1:1）时 au = av = 1，与旧版逐位相同。
        val scaleU = if (safeAspect >= 1f) 1f / safeAspect else 1f
        val scaleV = if (safeAspect <= 1f) safeAspect else 1f
        val out = mutableListOf<Float>()

        fun vertex(r: Double, psi: Double): FloatArray {
            val alpha = r * halfFov
            val sa = sin(alpha)
            val ca = cos(alpha)
            val u = (0.5 + 0.5 * circle * r * cos(psi) * scaleU).toFloat()
            val v = (0.5 + 0.5 * circle * r * sin(psi) * scaleV).toFloat()
            return floatArrayOf(
                (radius * sa * cos(psi)).toFloat(),
                (radius * sa * sin(psi)).toFloat(),
                (-radius * ca).toFloat(),
                u, v
            )
        }

        fun emit(p: FloatArray) {
            for (value in p) out.add(value)
        }

        for (band in 0 until radialBands) {
            val r0 = band.toDouble() / radialBands
            val r1 = (band + 1).toDouble() / radialBands
            for (seg in 0 until segments) {
                val p0 = 2.0 * PI * seg / segments
                val p1 = 2.0 * PI * (seg + 1) / segments
                // 两个三角形拼一格；r0=0 时第一圈会退化成零面积三角形，不影响绘制。
                emit(vertex(r0, p0)); emit(vertex(r1, p0)); emit(vertex(r0, p1))
                emit(vertex(r1, p0)); emit(vertex(r1, p1)); emit(vertex(r0, p1))
            }
        }
        return toGeometry(out)
    }

    /**
     * 19:2x：**全画幅鱼眼**的反畸变网格。
     *
     * 与圆盘版的区别只在两处，但两处都不能省：
     *
     * 1. **网格是铺满的矩形**，不是圆盘。全画幅的意思是「镜头影像比画幅大」，
     *    画面里没有黑角，四角也是有效像素。圆盘最多铺到短边，四角天生没有几何 ——
     *    那样会在四角留黑，还会把边缘像素拉成放射状条纹。
     * 2. **半径按半对角线归一**：`r = ρ / √(A² + 1)`（以半高为 1 计），
     *    于是 r=1 正好落在**四角**，整幅都在 [0, 1] 内，一个像素都不浪费。
     *    这也是「全画幅鱼眼标称 180° 是**对角线**」这个行业口径对应的算法：
     *    用户给的张角就是对角线张角，边中点对应的是 `2·atan(1/1)` 那一类更小的角。
     *
     * UV 这里**直接就是画幅坐标**（`u = 0.5 ± px/(2A)`、`v = 0.5 ± py/2`）：
     * 画幅铺满时每一点都对应片源上的同一点，没有任何裁剪，也就不需要圆度补偿 ——
     * 补偿是给「圆 + 黑边」那种画幅用的。
     *
     * 顶点角度仍按等距投影 `α = r·FOV/2`、方位角 `ψ = atan2(py, px)` 反算，
     * 半径恒 10 m（与球面同深度层），右 = +X、上 = +Y、正前方 = −Z，与圆盘版逐位同向。
     */
    private fun fisheyeFullFrame(fovDeg: Float, aspect: Float): Geometry {
        val radius = 10f
        val halfFov = Math.toRadians(fovDeg.toDouble()) / 2.0
        // 半对角线长度，单位是「半高 = 1」。
        val halfDiag = sqrt(1.0 + aspect.toDouble() * aspect.toDouble())
        // 横向格数固定，纵向按画幅比缩：窄画面少几行、宽画面多几行，格子接近正方，
        // 顶点数与圆盘版同量级（~4k），不会因为全画幅把每帧开销翻倍。
        val cols = 72
        val rows = (72 / aspect).toInt().coerceIn(24, 144)
        val out = mutableListOf<Float>()

        fun vertex(px: Double, py: Double): FloatArray {
            val r = (sqrt(px * px + py * py) / halfDiag).coerceIn(0.0, 1.0)
            val alpha = r * halfFov
            val sa = sin(alpha)
            val ca = cos(alpha)
            val psi = atan2(py, px)
            return floatArrayOf(
                (radius * sa * cos(psi)).toFloat(),
                (radius * sa * sin(psi)).toFloat(),
                (-radius * ca).toFloat(),
                (0.5 + px / (2.0 * aspect)).toFloat(),
                (0.5 + py / 2.0).toFloat()
            )
        }

        fun emit(px: Double, py: Double) {
            for (value in vertex(px, py)) out.add(value)
        }

        for (row in 0 until rows) {
            // py 从下往上：−1 是画幅底边，+1 是顶边（v 也随之从 0 到 1）。
            val y0 = -1.0 + 2.0 * row / rows
            val y1 = -1.0 + 2.0 * (row + 1) / rows
            for (col in 0 until cols) {
                val x0 = -aspect + 2.0 * aspect * col / cols
                val x1 = -aspect + 2.0 * aspect * (col + 1) / cols
                emit(x0, y0); emit(x1, y0); emit(x0, y1)
                emit(x1, y0); emit(x1, y1); emit(x0, y1)
            }
        }
        return toGeometry(out)
    }

    private fun curved(spanDeg: Float): Geometry {
        val latBands = 32
        // 180° 原来 40 段、360° 64 段；按跨度线性插值并钳在 24~64。
        val lonBands = (40f * spanDeg / 180f).toInt().coerceIn(24, 64)
        val radius = 10f
        // V2.7.4：等距柱状投影的 u=0.5 才是画面中心，且「向左看」必须对应 u 变小。
        // 旧实现让正前方（theta=0）落在 u=0，于是打开 360 视频第一眼看到的是接缝。
        // 18:2x：跨度从写死的两档改成参数。180/360 的取值与旧版逐位相同
        // （±π/2 与 ±π），所以参数化对既有片源零回归；220/270 只是多两个跨度。
        val span = Math.toRadians(spanDeg.toDouble())
        val startTheta = -span / 2
        val endTheta = span / 2
        val out = mutableListOf<Float>()

        fun point(phi: Double, theta: Double): FloatArray = floatArrayOf(
            (-radius * cos(phi) * sin(theta)).toFloat(),
            (radius * sin(phi)).toFloat(),
            (-radius * cos(phi) * cos(theta)).toFloat()
        )

        fun emit(phi: Double, theta: Double) {
            val p = point(phi, theta)
            val u = (0.5 - theta / span).toFloat()
            // 球顶 = v 1、球底 = v 0。方向反不要动这一行：动它就是内容倒像。
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
