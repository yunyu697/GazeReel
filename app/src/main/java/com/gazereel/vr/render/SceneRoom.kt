package com.gazereel.vr.render

import android.opengl.GLES20
import java.nio.ByteBuffer
import java.nio.ByteOrder
import com.gazereel.vr.data.RunLog
import java.nio.FloatBuffer

/** 一块几何：顶点缓冲 + 顶点数。 */
class SceneGeometry(val vertices: FloatBuffer, val vertexCount: Int)

/**
 * 16:5x 影院形态：用户坐在一个厅里，正前方 5 米处是一块 5.4×3.04 米的银幕。
 *
 * ## 与 15:0x 那版「房间盒」的区别
 *
 * 上一版是六面封闭的盒子，银幕贴在盒子里、还比盒子小得多，所以从任何角度看
 * 都像一个灰暗的立方体内部。这一版是**影院**：前墙按银幕尺寸开洞，洞口四周
 * 有丝绒幕框，房间尺度按「厅」来（12 米宽 × 17 米深 × 4.15 米高），
 * 地面有木纹、后墙有吸音布纹。
 *
 * ## 顶点格式
 *
 * `pos(3) + normal(3) + mat(1)`，stride 7 floats。`mat` 是材质编号，
 * 具体反照率 / 粗糙度在片元里按编号程序化生成 —— 所以**不引任何贴图资产**。
 * 0 木地板 / 1 墙面布纹 / 2 天花 / 3 幕框丝绒。
 *
 * ## 边界（必须写清）
 *
 * 只在 `Projection.PLANE_2D` 参与绘制。180°/360° 是相机中心的内翻球面，
 * 半径远大于房间，房间会被它整个挡住。这是几何事实，不是没做。
 */
object SceneRoom {

    const val STRIDE_FLOATS = 7
    const val STRIDE_BYTES = STRIDE_FLOATS * 4

    // ── 房间尺度（与 ProjectionMesh 的银幕参数必须对齐）──────────────────
    private const val HALF_W = 6.0f
    const val FLOOR_Y = -1.25f
    private const val CEIL_Y = 2.9f
    private const val BACK_Z = 12.0f
    /** 前墙略在银幕之后，避免与银幕共面打架。 */
    private const val FRONT_Z = -5.06f
    /** 幕框丝绒故意比银幕靠前 6cm，形成一圈「布包着幕」的层次。 */
    private const val FRAME_Z = -4.94f
    private const val FRAME_W = 0.28f
    /** 下幕框故意窄：要留出 0.25 米的墙裙，否则银幕看着还像插在地上。 */
    private const val FRAME_BOTTOM = 0.10f
    /** 侧幕帘宽度。从幕框外沿再往外 0.75 米，落地垂坠。 */
    private const val SIDE_CURTAIN_W = 0.75f

    // 18:2x：银幕尺寸改成按档位现算，不再从常量推 —— ProjectionMesh 里那三个
    // const 只代表 100% 基准，供 tools/check_cinema_geometry.py 核对用。

    private const val MAT_FLOOR = 0f
    private const val MAT_WALL = 1f
    private const val MAT_CEIL = 2f
    private const val MAT_VELVET = 3f

    fun build(screenWidthPercent: Int = 100): SceneGeometry {
        val out = ArrayList<Float>(1024)
        val screenHalfW = ProjectionMesh.screenWidth(screenWidthPercent) / 2f
        val screenHalfH = ProjectionMesh.screenHeight(screenWidthPercent) / 2f

        /**
         * 一个四边形。四点按逆时针给（从法线那一侧看），拆两个三角形。
         * 法线显式给，不用叉积反推 —— 房间的面法线全部朝**房间内**，方向唯一。
         */
        fun quad(
            a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray,
            n: FloatArray, mat: Float
        ) {
            fun emit(p: FloatArray) {
                out.add(p[0]); out.add(p[1]); out.add(p[2])
                out.add(n[0]); out.add(n[1]); out.add(n[2])
                out.add(mat)
            }
            emit(a); emit(b); emit(c)
            emit(a); emit(c); emit(d)
        }

        fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

        val up = floatArrayOf(0f, 1f, 0f)
        val down = floatArrayOf(0f, -1f, 0f)
        val front = floatArrayOf(0f, 0f, 1f)

        // 地面（法线朝上）。整块，从银幕下沿一直到后墙。
        quad(
            v(-HALF_W, FLOOR_Y, FRONT_Z), v(HALF_W, FLOOR_Y, FRONT_Z),
            v(HALF_W, FLOOR_Y, BACK_Z), v(-HALF_W, FLOOR_Y, BACK_Z),
            up, MAT_FLOOR
        )
        // 天花（法线朝下）。
        quad(
            v(-HALF_W, CEIL_Y, BACK_Z), v(HALF_W, CEIL_Y, BACK_Z),
            v(HALF_W, CEIL_Y, FRONT_Z), v(-HALF_W, CEIL_Y, FRONT_Z),
            down, MAT_CEIL
        )
        // 后墙（法线朝 −z，朝向观众）。
        quad(
            v(HALF_W, FLOOR_Y, BACK_Z), v(-HALF_W, FLOOR_Y, BACK_Z),
            v(-HALF_W, CEIL_Y, BACK_Z), v(HALF_W, CEIL_Y, BACK_Z),
            floatArrayOf(0f, 0f, -1f), MAT_WALL
        )
        // 左墙（法线朝 +x）。
        quad(
            v(-HALF_W, FLOOR_Y, FRONT_Z), v(-HALF_W, FLOOR_Y, BACK_Z),
            v(-HALF_W, CEIL_Y, BACK_Z), v(-HALF_W, CEIL_Y, FRONT_Z),
            floatArrayOf(1f, 0f, 0f), MAT_WALL
        )
        // 右墙（法线朝 −x）。
        quad(
            v(HALF_W, FLOOR_Y, BACK_Z), v(HALF_W, FLOOR_Y, FRONT_Z),
            v(HALF_W, CEIL_Y, FRONT_Z), v(HALF_W, CEIL_Y, BACK_Z),
            floatArrayOf(-1f, 0f, 0f), MAT_WALL
        )

        // 前墙：按银幕尺寸开洞，四块拼出一个矩形缺口。
        // 中心高度随幕宽推导（地板 + 半高 + 墙裙）。写死 0.62 会让放大的幕
        // 重新陷进地板 —— 17:2x 的「银幕立在地上」就是这么来的。
        val sy = ProjectionMesh.screenCenterY(screenWidthPercent)
        val oL = -screenHalfW
        val oR = screenHalfW
        val oB = sy - screenHalfH
        val oT = sy + screenHalfH
        // 洞下方
        quad(
            v(-HALF_W, FLOOR_Y, FRONT_Z), v(HALF_W, FLOOR_Y, FRONT_Z),
            v(HALF_W, oB, FRONT_Z), v(-HALF_W, oB, FRONT_Z),
            front, MAT_WALL
        )
        // 洞上方
        quad(
            v(-HALF_W, oT, FRONT_Z), v(HALF_W, oT, FRONT_Z),
            v(HALF_W, CEIL_Y, FRONT_Z), v(-HALF_W, CEIL_Y, FRONT_Z),
            front, MAT_WALL
        )
        // 洞左侧
        quad(
            v(-HALF_W, oB, FRONT_Z), v(oL, oB, FRONT_Z),
            v(oL, oT, FRONT_Z), v(-HALF_W, oT, FRONT_Z),
            front, MAT_WALL
        )
        // 洞右侧
        quad(
            v(oR, oB, FRONT_Z), v(HALF_W, oB, FRONT_Z),
            v(HALF_W, oT, FRONT_Z), v(oR, oT, FRONT_Z),
            front, MAT_WALL
        )

        // 幕框：上 / 左 / 右三边 0.28 米，**下边单独做窄**。
        //
        // 17:2x：原来四边都是 0.28。银幕抬到 0.62 之后底边离地只有 0.35 米，
        // 一条 0.28 的下幕框会把整条墙裙盖掉 —— 看上去仍然「贴着地面」。
        // 影院银幕下沿本来就是一条细窄的压边（masking），这里给 0.10，
        // 于是幕下方留出 0.25 米的真墙，银幕才有「挂在墙上」的落点。
        val fL = oL - FRAME_W
        val fR = oR + FRAME_W
        val fB = oB - FRAME_BOTTOM
        val fT = oT + FRAME_W
        quad(
            v(fL, oT, FRAME_Z), v(fR, oT, FRAME_Z),
            v(fR, fT, FRAME_Z), v(fL, fT, FRAME_Z), front, MAT_VELVET
        )
        quad(
            v(fL, fB, FRAME_Z), v(fR, fB, FRAME_Z),
            v(fR, oB, FRAME_Z), v(fL, oB, FRAME_Z), front, MAT_VELVET
        )
        quad(
            v(fL, fB, FRAME_Z), v(oL, fB, FRAME_Z),
            v(oL, fT, FRAME_Z), v(fL, fT, FRAME_Z), front, MAT_VELVET
        )
        quad(
            v(oR, fB, FRAME_Z), v(fR, fB, FRAME_Z),
            v(fR, fT, FRAME_Z), v(oR, fT, FRAME_Z), front, MAT_VELVET
        )

        // 侧幕帘：从幕框顶一直垂到地面。
        //
        // 这是「银幕挂在墙上」最直接的视觉证据 —— 两侧有垂坠的幕布落地，
        // 原来只有一圈 28cm 的框，幕布外面直接是墙，任何高度都会像一块立牌。
        // z 再往前 3cm，压住幕框外沿，避免共面打架。
        val cz = FRAME_Z - 0.03f
        quad(
            v(fL - SIDE_CURTAIN_W, FLOOR_Y, cz), v(fL, FLOOR_Y, cz),
            v(fL, fT, cz), v(fL - SIDE_CURTAIN_W, fT, cz), front, MAT_VELVET
        )
        quad(
            v(fR, FLOOR_Y, cz), v(fR + SIDE_CURTAIN_W, FLOOR_Y, cz),
            v(fR + SIDE_CURTAIN_W, fT, cz), v(fR, fT, cz), front, MAT_VELVET
        )

        // 幕头（valance）：幕框顶到天花的一条丝绒，把银幕「吊」起来。
        // 宽度收到两侧幕帘外沿，正好把前墙洞口的上半段封死，不再露出一圈墙。
        quad(
            v(fL - SIDE_CURTAIN_W, fT, cz), v(fR + SIDE_CURTAIN_W, fT, cz),
            v(fR + SIDE_CURTAIN_W, CEIL_Y, cz), v(fL - SIDE_CURTAIN_W, CEIL_Y, cz),
            front, MAT_VELVET
        )

        val data = out.toFloatArray()
        val buffer = ByteBuffer.allocateDirect(data.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
        buffer.put(data).position(0)
        return SceneGeometry(buffer, data.size / STRIDE_FLOATS)
    }
}

/** 影院场景的绘制器：程序化几何 + Cook-Torrance。 */
class SceneRenderer {

    private var program: GlProgram? = null
    private var geometry: SceneGeometry? = null
    private var geometryWidth: Int = -1

    /** 编译失败就不要再每帧重试 —— 每帧编译一次会把主循环拖垮。 */
    @Volatile
    private var disabled = false

    /**
     * @param screenLuma 当前画面亮度（0..1），房间的间接光跟着它走。
     *   影院里最直观的一条：画面亮，整个厅也跟着亮。
     */
    fun draw(mvp: FloatArray, screenLuma: Float, screenWidthPercent: Int = 100) {
        if (disabled) return
        val p = ensureProgram() ?: return
        val g = ensureGeometry(screenWidthPercent) ?: return

        // 只有这一层要前后关系：从角落看时地面、前墙、幕框会互相遮挡。
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDepthMask(true)

        p.use()
        GLES20.glUniformMatrix4fv(p.uniform("uMvp"), 1, false, mvp, 0)
        // 主光从银幕打过来，带一点俯角。方向是世界空间的，不随头动 —— 光钉在房间里。
        GLES20.glUniform3f(p.uniform("uLightDir"), 0.10f, 0.26f, 1.0f)
        GLES20.glUniform1f(p.uniform("uLuma"), screenLuma.coerceIn(0f, 1.5f))

        val posLoc = p.attrib("aPosition")
        val nrmLoc = p.attrib("aNormal")
        val matLoc = p.attrib("aMat")
        val buffer = g.vertices

        buffer.position(0)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glVertexAttribPointer(
            posLoc, 3, GLES20.GL_FLOAT, false, SceneRoom.STRIDE_BYTES, buffer
        )
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(nrmLoc)
        GLES20.glVertexAttribPointer(
            nrmLoc, 3, GLES20.GL_FLOAT, false, SceneRoom.STRIDE_BYTES, buffer
        )
        buffer.position(6)
        GLES20.glEnableVertexAttribArray(matLoc)
        GLES20.glVertexAttribPointer(
            matLoc, 1, GLES20.GL_FLOAT, false, SceneRoom.STRIDE_BYTES, buffer
        )

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, g.vertexCount)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(nrmLoc)
        GLES20.glDisableVertexAttribArray(matLoc)
        // 立刻关掉：视频与 UI 不该继承这里的深度状态。
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun ensureProgram(): GlProgram? {
        program?.let { return it }
        return runCatching {
            GlProgram(Shaders.SCENE_VERTEX, Shaders.SCENE_FRAGMENT).also { program = it }
        }.getOrElse {
            // 必须留痕：GLSL 是运行时编译的，失败时画面表现只是「场景没出现」，
            // 没有这条日志就只能猜是几何问题、深度问题还是着色器问题。
            disabled = true
            RunLog.append("scene", "[场景] 着色器编译失败，本批场景不画：${it.message}")
            null
        }
    }

    /** 档位变了只重建几何，不重编译着色器。 */
    private fun ensureGeometry(screenWidthPercent: Int): SceneGeometry? {
        geometry?.takeIf { geometryWidth == screenWidthPercent }?.let { return it }
        return runCatching {
            SceneRoom.build(screenWidthPercent).also {
                geometry = it
                geometryWidth = screenWidthPercent
            }
        }.getOrElse {
            disabled = true
            RunLog.append("scene", "[场景] 几何构建失败：${it.message}")
            null
        }
    }

    /** GL 上下文丢了就整体重来，`disabled` 也一并复位。 */
    fun release() {
        program = null
        geometry = null
        geometryWidth = -1
        disabled = false
    }
}
