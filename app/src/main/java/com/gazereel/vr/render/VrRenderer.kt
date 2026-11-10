package com.gazereel.vr.render

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.Surface
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.StereoFrameJudge
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.core.eyeTextureRegion
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.input.SettingsRepository
import com.gazereel.vr.ui.VrUiState
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    /**
     * V2.20 / C1：抽帧判立体布局的结论。
     *
     * 回调在 **GL 线程**上触发，落地要自己切回主线程。
     */
    var onFrameSample: ((StereoFrameJudge.Verdict) -> Unit)? = null

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
    // V2.19 / B5：UI 平面不再只有「居中 / 下移 / 片库」三块固定几何。
    // 舒适俯角是连续量，缓动期间每帧都在变，所以按 panelSpec 现算并按需重建。
    private var uiGeometry: ProjectionMesh.Geometry? = null
    private var uiSpec: VrUiLayer.PanelSpec? = null
    private var lastProjection: Projection? = null
    private var lastLayout: com.gazereel.vr.core.StereoLayout? = null
    private var loggedStMatrix = false
    private var width = 1
    private var height = 1
    @Volatile private var frameAvailable = false

    // V2.20 / C1：抽帧判立体布局的离屏靶。
    private var sampleFbo = 0
    private var sampleTex = 0
    private var sampleQuad: ProjectionMesh.Geometry? = null
    private var judgedMediaId = -1L
    private var judged = false
    private var sampleAttempts = 0
    private var framesSinceOpen = 0
    private val samples = ArrayList<StereoFrameJudge.Verdict>(3)

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

        // V2.20 / C1：画面刚出来时抽一帧判立体布局。放在换眼绘制之前，
        // 因为它要绑自己的 FBO 与视口，后面的每眼循环会把两者重新设好。
        maybeJudgeStereo(state)

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
        // V2.14：光标画在真实视线落点上，由这里算好交给绘制层——
        // 绘制层拿不到头部姿态，而准星钉在画布正中正是「定位不准」的根源。
        val cursor = uiLayer.gazePoint(headset.headRotation(), state)
        GlUtils.uploadBitmap(uiTextureId, uiLayer.render(state, gazeId, gazeProgress, cursor))

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val panel = uiLayer.panelSpec(state)
        // 缓动期间朝向每帧都在变；量化到 1 毫弧度（约 0.06°），避免 24 帧各建一次 FloatBuffer。
        // V2.19c：姿态确认卡会把整块平面转到候选方向，所以 yaw 也要参与比较。
        val specKey = VrUiLayer.PanelSpec(
            panel.width, panel.height,
            Math.round(panel.yaw * 1000f) / 1000f,
            Math.round(panel.pitch * 1000f) / 1000f
        )
        if (uiSpec != specKey) {
            uiSpec = specKey
            uiGeometry = mesh.uiQuad(specKey.yaw, specKey.pitch, specKey.width, specKey.height)
        }

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
        mvpMatrix: FloatArray = mvp,
        beforeDraw: (GlProgram) -> Unit = {}
    ) {
        program.use()
        GLES20.glActiveTexture(samplerUnit)
        bindTexture()
        GLES20.glUniform1i(program.uniform(samplerName), 0)
        GLES20.glUniformMatrix4fv(program.uniform("uMvp"), 1, false, mvpMatrix, 0)
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

    // ---- V2.20 / C1：抽帧判立体布局 ----

    /**
     * 画面开始播之后的第 10 帧左右抽一次，判这张片到底是 Mono / SBS / TAB。
     *
     * 为什么不等用户确认：**证据迟到，结论可以先放**。先按现有判据起播，
     * 抽帧一到位就当场改成对的——只换 `uEyeRegion`，不动投影，片子照常往下走，
     * 用户最多觉得画面「对上了」一下。反过来要求用户先回答一个他自己也看不出答案的问题，
     * 才是这一版最该去掉的东西。
     *
     * 抽到黑场（片头淡入）或平坦画面（大片天空 / 死黑）不算数：
     * `StereoFrameJudge` 会给 0 置信，这里再等十帧重试，凑满三个一致样本才落地。
     */
    private fun maybeJudgeStereo(state: VrUiState) {
        val mediaId = state.currentMediaId
        if (mediaId != judgedMediaId) {
            judgedMediaId = mediaId
            judged = false
            sampleAttempts = 0
            framesSinceOpen = 0
            samples.clear()
        }
        if (judged || mediaId == 0L) return
        framesSinceOpen++
        if (framesSinceOpen < FIRST_SAMPLE_FRAMES) return
        framesSinceOpen = 0
        sampleAttempts++

        val verdict = runCatching { captureStereoFrame() }.getOrNull()
        if (verdict != null && verdict.confidence > 0f) samples.add(verdict)

        if (samples.size >= SAMPLE_COUNT) {
            judged = true
            val combined = StereoFrameJudge.combine(samples)
            if (combined != null) {
                RunLog.append("fmt", combined.describe())
                onFrameSample?.invoke(combined)
            } else {
                // 三个时刻说法不一：当作看不出。宁可维持现状，也不要把对的改成错的。
                RunLog.append(
                    "fmt",
                    "抽帧三帧不一致，沿用现有判据（" +
                        samples.joinToString(" | ") { it.layout.name } + "）"
                )
            }
            return
        }
        if (sampleAttempts >= MAX_SAMPLE_ATTEMPTS) {
            judged = true
            RunLog.append(
                "fmt",
                "抽帧没凑够样本（${samples.size}/$SAMPLE_COUNT，多是黑场或平坦画面）· 沿用现有判据"
            )
        }
    }

    /**
     * 把视频纹理原样画进一块 192×96 的 FBO 再读回像素。
     *
     * 走这条路的理由：它**绕开所有片源差异**。本地 content://、SMB、HTTP
     * 最终都是同一张 external 纹理，抽帧不用为每种片源再写一遍解码逻辑，
     * 也不会和正在播放的解码器抢文件句柄。
     */
    private fun captureStereoFrame(): StereoFrameJudge.Verdict? {
        val program = videoProgram ?: return null
        ensureSampleTarget()
        if (sampleFbo == 0) return null
        val quad = sampleQuad ?: mesh.screenQuad().also { sampleQuad = it }
        val identity = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFbo)
        GLES20.glViewport(0, 0, SAMPLE_W, SAMPLE_H)
        // uEyeRegion 取整幅：判布局必须看**完整的一帧**，
        // 若照当前布局取半幅，SBS 被误判成 Mono 时这里也只会看到一半，永远自洽。
        drawGeometry(
            program = program,
            geometry = quad,
            samplerUnit = GLES20.GL_TEXTURE0,
            bindTexture = {
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTextureId)
            },
            samplerName = "uVideo",
            mvpMatrix = identity,
            beforeDraw = { p ->
                GLES20.glUniformMatrix4fv(p.uniform("uSTMatrix"), 1, false, stMatrix, 0)
                GLES20.glUniform4fv(p.uniform("uEyeRegion"), 1, FULL_REGION, 0)
                GLES20.glUniform1f(p.uniform("uBrightness"), 1f)
            }
        )
        val buffer = ByteBuffer.allocateDirect(SAMPLE_W * SAMPLE_H * 4)
            .order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(
            0, 0, SAMPLE_W, SAMPLE_H,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        buffer.position(0)
        val bytes = ByteArray(SAMPLE_W * SAMPLE_H * 4)
        buffer.get(bytes)
        return StereoFrameJudge.judge(bytes, SAMPLE_W, SAMPLE_H)
    }

    private fun ensureSampleTarget() {
        if (sampleFbo != 0) return
        sampleTex = GlUtils.createUiTexture(SAMPLE_W, SAMPLE_H)
        val ids = IntArray(1)
        GLES20.glGenFramebuffers(1, ids, 0)
        sampleFbo = ids[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sampleFbo)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, sampleTex, 0
        )
        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            // 读不回来就退回现有判据，不能因为一个可选能力把播放弄挂。
            RunLog.append("fmt", "抽帧用的 FBO 不可用（0x${status.toString(16)}）")
            sampleFbo = 0
        }
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
        if (sampleFbo != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(sampleFbo), 0)
            sampleFbo = 0
        }
        if (sampleTex != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(sampleTex), 0)
            sampleTex = 0
        }
    }

    companion object {
        private const val STRIDE = 5 * 4   // pos(3) + uv(2) floats

        /** 抽帧靶的尺寸：96×48 的判据网格刚好每格 2×2 像素，读回只有 72KB。 */
        private const val SAMPLE_W = 192
        private const val SAMPLE_H = 96

        /** 等十来帧再抽：片头常有黑场，第一帧不能代表整部片。 */
        private const val FIRST_SAMPLE_FRAMES = 10

        /** 三个样本一致才落地：单帧误判会把对的画面切成两半，代价比慢一秒大得多。 */
        private const val SAMPLE_COUNT = 3

        /** 黑场 / 平坦画面会一直给不出结论，抽这么多次就放弃（不能每帧都读像素）。 */
        private const val MAX_SAMPLE_ATTEMPTS = 12

        /** 整幅纹理区域（不切半幅）。 */
        private val FULL_REGION = floatArrayOf(1f, 1f, 0f, 0f)
    }
}
