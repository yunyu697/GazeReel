package com.gazereel.vr.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.core.eyeTextureRegion
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.input.SettingsRepository
import com.gazereel.vr.ui.VrUiState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * PRD 6.1：ExoPlayer → SurfaceTexture → GL_TEXTURE_EXTERNAL_OES → 投影几何 → 左右眼视口。
 * - 每眼用不同的 uEyeRegion 采样同一纹理，实现 Mono / SBS / TAB；
 * - UI 作为独立 GL 层画在 2 米虚拟距离，世界锁定（model 固定，不跟随头部）；
 * - 亮度通过 uBrightness 只作用于视频层；IPD 由 VrHeadsetProvider 消费。
 * - 桶形畸变列为独立专项：正确实现需改为离屏 FBO 后处理并按纸盒参数真机校准，
 *   EyeParams.distortion 目前仅是预留字段，不参与已验证的直出渲染链路。
 */
class VrRenderer(
    private val headset: VrHeadsetProvider,
    private val uiLayer: VrUiLayer,
    private val settings: SettingsRepository,
    private val stateProvider: () -> VrUiState,
    private val gazeProvider: () -> Pair<String?, Float>
) : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    /** GL 线程就绪后把 Surface 交给 PlayerController.setVideoSurface()。 */
    var onGlReady: ((Surface) -> Unit)? = null

    /**
     * V2.7：GL 线程上抛出的异常会直接杀掉进程，外层 try-catch 兜不住。
     * 这里把失败收成一个回调，让 Activity 能显示原因，而不是让用户看到闪退。
     */
    var onGlError: ((Throwable) -> Unit)? = null
    @Volatile private var glFailed = false

    private val mesh = ProjectionMesh()
    private val stMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val mvp = FloatArray(16)
    private val frameLock = Any()

    private var videoProgram: GlProgram? = null
    private var uiProgram: GlProgram? = null
    private var videoTextureId = 0
    private var uiTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var videoGeometry: ProjectionMesh.Geometry? = null
    private var uiCentered: ProjectionMesh.Geometry? = null
    private var uiLowered: ProjectionMesh.Geometry? = null
    // V2.13：片库用更大的平面（2.4×1.2 米）。尺寸必须与 VrUiLayer.panelSpec() 一致。
    private var uiLibrary: ProjectionMesh.Geometry? = null
    private var lastProjection: Projection? = null
    private var lastLayout: com.gazereel.vr.core.StereoLayout? = null
    private var loggedStMatrix = false
    private var width = 1
    private var height = 1
    @Volatile private var frameAvailable = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            GLES20.glDisable(GLES20.GL_CULL_FACE)       // 内翻球面需要双面可见
            GLES20.glEnable(GLES20.GL_BLEND)
            // Canvas 的 ARGB_8888 是预乘 alpha，混合因子用 ONE 避免边缘二次变暗。
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glClearColor(0.031f, 0.067f, 0.118f, 1f)

            videoTextureId = GlUtils.createExternalTexture()
            uiTextureId = GlUtils.createUiTexture(VrUiLayer.W, VrUiLayer.H)
            videoProgram = GlProgram.video()
            uiProgram = GlProgram(Shaders.UI_VERTEX, Shaders.UI_FRAGMENT)
            surfaceTexture = SurfaceTexture(videoTextureId).apply {
                setOnFrameAvailableListener(this@VrRenderer)
            }
            videoSurface = Surface(surfaceTexture)
            uiCentered = mesh.uiQuad(0f)
            uiLowered = mesh.uiQuad(-0.46f)
            uiLibrary = mesh.uiQuad(0f, width = 2.4f, height = 1.2f)
            onGlReady?.invoke(videoSurface!!)
        } catch (t: Throwable) {
            failGl(t)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    override fun onDrawFrame(gl: GL10?) {
        if (glFailed) {
            // 已经失败过就只清屏，不再每帧重复抛异常。
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            return
        }
        try {
            drawFrame()
        } catch (t: Throwable) {
            failGl(t)
        }
    }

    private fun fmt(v: Float): String = String.format(java.util.Locale.US, "%.3f", v)

    private fun drawFrame() {
        val state = stateProvider()
        val aspect = (width / 2f) / height.coerceAtLeast(1)
        val ipdMeters = settings.current.ipdMm / 1_000f

        if (frameAvailable) {
            synchronized(frameLock) { frameAvailable = false }
            surfaceTexture?.updateTexImage()
            surfaceTexture?.getTransformMatrix(stMatrix)
            // 真机排查用：ST 矩阵若不是单位矩阵，说明解码器把画面放进了更大的纹理缓冲，
            // 那么「在哪一层切半幅」就会影响结果。只记一次。
            if (!loggedStMatrix) {
                loggedStMatrix = true
                RunLog.append(
                    "gl",
                    "ST矩阵 x=(${fmt(stMatrix[0])},${fmt(stMatrix[1])}) " +
                        "y=(${fmt(stMatrix[4])},${fmt(stMatrix[5])}) " +
                        "平移=(${fmt(stMatrix[12])},${fmt(stMatrix[13])})"
                )
            }
        }

        if (lastProjection != state.format.projection || lastLayout != state.format.layout) {
            videoGeometry = mesh.build(state.format.projection)
            lastProjection = state.format.projection
            lastLayout = state.format.layout
            // 真机排查用：正前方（网格 u=0.5, v=0.5）落在纹理的哪个坐标。
            // SBS 应该是左眼 (0.25, 0.5)、右眼 (0.75, 0.5)——即各取半幅的中心；
            // 如果是 (0.5, 0.5)，说明每眼取半幅根本没生效，视点就落在两幅的接缝上。
            val left = state.format.eyeTextureRegion(Eye.LEFT)
            val right = state.format.eyeTextureRegion(Eye.RIGHT)
            RunLog.append(
                "gl",
                "几何 ${state.format.projection}/${state.format.layout} " +
                    "正前方采样 左=(${fmt(0.5f * left.xScale + left.xOffset)}," +
                    "${fmt(0.5f * left.yScale + left.yOffset)}) " +
                    "右=(${fmt(0.5f * right.xScale + right.xOffset)}," +
                    "${fmt(0.5f * right.yScale + right.yOffset)})"
            )
        }

        val (gazeId, gazeProgress) = gazeProvider()
        GlUtils.uploadBitmap(uiTextureId, uiLayer.render(state, gazeId, gazeProgress))

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val panel = uiLayer.panelSpec(state)

        for (eye in listOf(Eye.LEFT, Eye.RIGHT)) {
            val viewportX = if (eye == Eye.LEFT) 0 else width / 2
            GLES20.glViewport(viewportX, 0, width / 2, height)
            val params = headset.eyeParams(eye, aspect, ipdMeters)
            Matrix.multiplyMM(mvp, 0, params.projectionMatrix, 0, params.viewMatrix, 0)

            videoGeometry?.let { geometry ->
                drawGeometry(
                    program = videoProgram ?: return@let,
                    geometry = geometry,
                    samplerUnit = GLES20.GL_TEXTURE0,
                    bindTexture = { GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId) },
                    samplerName = "uVideo",
                    beforeDraw = { program ->
                        GLES20.glUniformMatrix4fv(program.uniform("uSTMatrix"), 1, false, stMatrix, 0)
                        GLES20.glUniform4fv(
                            program.uniform("uEyeRegion"), 1,
                            state.format.eyeTextureRegion(eye).toFloatArray(), 0
                        )
                        // 70% 为 1.0 基准；10~100% 映射到约 0.14~1.43 倍，UI 层不受影响。
                        GLES20.glUniform1f(
                            program.uniform("uBrightness"),
                            (settings.current.brightness / 70f).coerceIn(0.14f, 1.45f)
                        )
                    }
                )
            }

            val uiGeometry = when {
                panel.width > 2f -> uiLibrary
                panel.centerY < 0f -> uiLowered
                else -> uiCentered
            }
            uiGeometry?.let { geometry ->
                drawGeometry(
                    program = uiProgram ?: return@let,
                    geometry = geometry,
                    samplerUnit = GLES20.GL_TEXTURE0,
                    bindTexture = { GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, uiTextureId) },
                    samplerName = "uUi"
                )
            }
        }
        GlUtils.checkError("onDrawFrame")
    }

    private fun drawGeometry(
        program: GlProgram,
        geometry: ProjectionMesh.Geometry,
        samplerUnit: Int,
        bindTexture: () -> Unit,
        samplerName: String,
        beforeDraw: (GlProgram) -> Unit = {}
    ) {
        program.use()
        GLES20.glActiveTexture(samplerUnit)
        bindTexture()
        GLES20.glUniform1i(program.uniform(samplerName), 0)
        GLES20.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvp, 0)
        beforeDraw(program)

        val positionLoc = program.attrib("aPosition")
        val uvLoc = program.attrib("aTexCoord")
        val buffer = geometry.vertices
        buffer.position(0)
        GLES20.glEnableVertexAttribArray(positionLoc)
        GLES20.glVertexAttribPointer(positionLoc, 3, GLES20.GL_FLOAT, false, STRIDE, buffer)
        buffer.position(3)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, STRIDE, buffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, geometry.vertexCount)
        GLES20.glDisableVertexAttribArray(positionLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
    }

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        synchronized(frameLock) { frameAvailable = true }
    }

    /** GL 线程上的失败只报一次，之后停止绘制，避免每帧刷同一条错误。 */
    private fun failGl(t: Throwable) {
        if (glFailed) return
        glFailed = true
        android.util.Log.e("CardboardVr.GL", "GL 渲染失败", t)
        onGlError?.invoke(t)
    }

    fun release() {
        surfaceTexture?.setOnFrameAvailableListener(null)
        videoSurface?.release()
        surfaceTexture?.release()
        videoSurface = null
        surfaceTexture = null
    }

    companion object {
        private const val STRIDE = 5 * 4   // pos(3) + uv(2) floats
    }
}
