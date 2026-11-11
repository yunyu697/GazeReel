package com.gazereel.vr.render

import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import com.gazereel.vr.core.DistortionComposer
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
 * - C5 / V2.32：桶形畸变已有可接线链路，但默认关。开关打开时两眼先渲进
 *   [DistortionComposer] 提供的全屏离屏纹理，再由 SDK 畸变网格合成到屏幕；
 *   关着时仍是原来那套左右半屏 glViewport 直出，画面几何零变化。
 *   EyeParams.distortion 仍是预留字段，不参与任何一条链路。
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
     * V2.31：第一个参数是**产生这帧的 mediaId**。结论回到主线程时可能已经切了片，
     * 带上 id 才能把过期结论丢掉，避免上一部片的像素改掉这一部片的格式。
     */
    var onFrameSample: ((Long, StereoFrameJudge.Verdict) -> Unit)? = null

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

    // V2.31：UI 位图的脏标记与上次上传时间，见 [uiSignature]。
    private var lastUiKey = 0
    private var lastUiUploadMs = 0L

    // V2.20 / C1：抽帧判立体布局的离屏靶。
    private var sampleFbo = 0
    private var sampleTex = 0
    private var sampleQuad: ProjectionMesh.Geometry? = null
    private var judgedMediaId = -1L
    private var judged = false
    private var sampleAttempts = 0
    private var totalSampleAttempts = 0
    private var framesSinceOpen = 0
    private val samples = ArrayList<StereoFrameJudge.Verdict>(3)

    // V2.32 / C5：默认关时这里是 null，drawFrame 里连一次额外判断都不会做。
    // 惰性解析放在 GL 线程上：Provider 的 distortionComposer() 只是造一个不碰 GL
    // 的薄对象，真正的 FBO 与 SDK 畸变渲染器在它自己的 beginFrame 里分配。
    private var composerResolved = false
    private var composer: DistortionComposer? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            // C5：GL 上下文重建时，上一轮的 FBO / 纹理 / SDK DistortionRenderer
            // 全部作废。这里已经在 GL 线程上，正好按约定释放，下一帧按需重建。
            composer?.release()
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
            // 若异常发生在 C5 的 beginFrame 与 endFrame 之间，离屏 FBO 还绑着，
            // 不先解绑，清屏就清进纹理、屏幕停在上一帧。默认路径 composer 为 null，不变。
            if (composer != null) GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
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
                    "每眼区域 左=(${fmt(left.xScale)},${fmt(left.xOffset)}) " +
                    "右=(${fmt(right.xScale)},${fmt(right.xOffset)}) " +
                    "正前方采样 左=(${fmt(0.5f * left.xScale + left.xOffset)}," +
                    "${fmt(0.5f * left.yScale + left.yOffset)}) " +
                    "右=(${fmt(0.5f * right.xScale + right.xOffset)}," +
                    "${fmt(0.5f * right.yScale + right.yOffset)}) · " +
                    "STx=${fmt(stMatrix[0])} STdx=${fmt(stMatrix[12])}"
            )
        }

        val (gazeId, gazeProgress) = gazeProvider()
        // V2.14：光标画在真实视线落点上，由这里算好交给绘制层——
        // 绘制层拿不到头部姿态，而准星钉在画布正中正是「定位不准」的根源。
        val cursor = uiLayer.gazePoint(headset.headRotation(), state)
        // V2.31：UI 是 1024×512 的 ARGB_8888，全量 render + texSubImage2D 每帧要
        // 重画并上传 2MB。看片时画面几乎不变，这笔开销却每帧都在，直接挤占解码
        // 与 SMB 读取的时间。改成「内容签名 + 静止时最多 10fps」按需上传：签名一变
        // 立刻更新（按钮、格式、消息、准星都不延迟），静止时靠 100ms 量化时间
        // 推动淡出动画，200ms 兜底刷新。
        val nowMs = SystemClock.elapsedRealtime()
        val uiKey = uiSignature(state, gazeId, gazeProgress, cursor, nowMs)
        if (uiKey != lastUiKey || nowMs - lastUiUploadMs >= UI_MAX_INTERVAL_MS) {
            lastUiKey = uiKey
            lastUiUploadMs = nowMs
            GlUtils.uploadBitmap(uiTextureId, uiLayer.render(state, gazeId, gazeProgress, cursor))
        }

        // C5 / V2.32：开关关着（或链初始化失败）时 targets 为 null，
        // 下面整段与改动前逐行一致。beginFrame 必须在 glClear 之前——
        // 清的是离屏纹理，不是屏幕；顺序反了会拿上一帧的合成结果当底。
        val activeComposer = distortion()
        val targets = activeComposer?.beginFrame(width, height)
        // 只有「链在、但这一帧初始化失败退回直出」才需要解绑：beginFrame 可能已经
        // 绑过离屏 FBO。composer 为 null 时根本没人绑过，默认路径一帧里不许多一次 GL 调用。
        if (activeComposer != null && targets == null) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }
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
            // 离屏链的左右半区与直出的左右半屏数值相同，只有落点不同。
            val viewport = targets?.viewportOf(eye)
            if (viewport != null) {
                GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            } else {
                val viewportX = if (eye == Eye.LEFT) 0 else width / 2
                GLES20.glViewport(viewportX, 0, width / 2, height)
            }
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
        // 两眼都画完了才合成；只画一只眼就合成会在另一侧读到旧像素。
        if (targets != null) activeComposer?.endFrame()
        GlUtils.checkError("onDrawFrame")
    }

    /**
     * 惰性装配畸变链。默认 Provider 返回 null，所以直出路径只有一次布尔判断。
     * 装配失败只记一行日志并保持直出，绝不让缺 AAR / 缺 native 变成闪退。
     */
    private fun distortion(): DistortionComposer? {
        if (!composerResolved) {
            composerResolved = true
            composer = runCatching { headset.distortionComposer() }
                .onFailure {
                    RunLog.append("gl", "[C5] 畸变链装配失败，保持直出：${it.message}")
                }
                .getOrNull()
        }
        return composer
    }

    /**
     * V2.31：只取真正会出现在 UI 位图里的字段，并把高频量量化：
     * 播放进度按秒、倒计时/淡出按 50ms、准星按 1/10 画布、凝视进度按 5%。
     *
     * 不能直接用 `state.hashCode()`：主线程 tick 会更新 positionMs / lastActivityMs，
     * 那样签名每帧都变，脏标记等于没做。反过来也不能只做纯时间节流——
     * 用户选中一个按钮、弹出一条消息都必须当帧生效，等 100ms 会被感觉成「不跟手」。
     */
    private fun uiSignature(
        state: VrUiState,
        gazeId: String?,
        progress: Float,
        cursor: PointF?,
        nowMs: Long
    ): Int {
        var h = state.screen.ordinal
        h = 31 * h + state.overlay.ordinal
        h = 31 * h + state.page
        h = 31 * h + state.selectedIndex
        h = 31 * h + state.format.hashCode()
        h = 31 * h + state.suggestedFormat.hashCode()
        h = 31 * h + (state.message?.hashCode() ?: 0)
        h = 31 * h + (state.feedback?.hashCode() ?: 0)
        h = 31 * h + (state.postureCandidate?.hashCode() ?: 0)
        h = 31 * h + (state.postureRemainingMs / 100L).hashCode()
        // V2.38：倒计时按 100ms 量化（秒数每 1 秒变一次，弧每 100ms 走一格），
        // 「稳住了 / 还在动」与「已调整 N°」也必须进签名，否则文案要等兜底刷新才变。
        h = 31 * h + (state.alignCountdown?.let {
            listOf(it.remainingMs / 100L, it.movedDeg.toLong(), if (it.steady) 1L else 0L)
                .hashCode()
        } ?: 0)
        h = 31 * h + state.consoleVisible.hashCode()
        h = 31 * h + state.gazeArmed.hashCode()
        h = 31 * h + state.pitchCheck.hashCode()
        h = 31 * h + state.items.size
        h = 31 * h + state.stacks.size
        h = 31 * h + (state.activeStack?.hashCode() ?: 0)
        h = 31 * h + state.cabinetsOpen.hashCode()
        h = 31 * h + state.cabinet.hashCode()
        h = 31 * h + state.settings.hashCode()
        h = 31 * h + state.subtitles.size
        h = 31 * h + state.currentTitle.hashCode()
        h = 31 * h + (state.positionMs / 1000L).hashCode()
        h = 31 * h + state.durationMs.hashCode()
        h = 31 * h + state.playing.hashCode()
        h = 31 * h + state.progress.size
        h = 31 * h + (gazeId?.hashCode() ?: 0)
        h = 31 * h + (progress * 20f).toInt()
        h = 31 * h + ((cursor?.x ?: 0f) * 0.1f).toInt()
        h = 31 * h + ((cursor?.y ?: 0f) * 0.1f).toInt()
        h = 31 * h + (nowMs / 100L).toInt()
        return h
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
     * 画面开始播之后持续低频抽帧，判这张片到底是 Mono / SBS / TAB。
     *
     * 为什么不等用户确认：**证据迟到，结论可以先放**。先按现有判据起播，
     * 抽帧一到位就当场改成对的——只换 `uEyeRegion`，不动投影，片子照常往下走，
     * 用户最多觉得画面「对上了」一下。反过来要求用户先回答一个他自己也看不出答案的问题，
     * 才是这一版最该去掉的东西。
     *
     * V2.27：旧版只在开播后 0.17–2 秒里试 12 次，没凑够三个样本就 **永久判死**
     * （`judged = true`），而很多片子的片头黑场 / Logo 就长达 2–5 秒。这类片永远
     * 退回 MONO：整幅 SBS 铺满球面，正前方 u=0.5 正好压在两幅的接缝上。
     * 现在起点约 0.5 秒、之后每 0.33 秒一次、持续观察最多约 60 秒；
     * 三帧不一致也只是清空本轮样本再观察，不再把「暂时看不出」当成「判死了」。
     * 判据本身（相关系数、三帧一致）一个字没改，只放宽了放弃条件。
     */
    private fun maybeJudgeStereo(state: VrUiState) {
        val mediaId = state.currentMediaId
        if (mediaId != judgedMediaId) {
            judgedMediaId = mediaId
            judged = false
            sampleAttempts = 0
            totalSampleAttempts = 0
            framesSinceOpen = 0
            samples.clear()
        }
        if (judged || mediaId == 0L) return
        framesSinceOpen++
        // 第一帧等久一点避开片头，之后按固定间隔低频重试。
        val need = if (totalSampleAttempts == 0) FIRST_SAMPLE_FRAMES else SAMPLE_INTERVAL_FRAMES
        if (framesSinceOpen < need) return
        framesSinceOpen = 0
        sampleAttempts++
        totalSampleAttempts++

        val verdict = runCatching { captureStereoFrame() }.getOrNull()
        if (verdict != null && verdict.confidence > 0f) samples.add(verdict)

        if (samples.size >= SAMPLE_COUNT) {
            val combined = StereoFrameJudge.combine(samples)
            if (combined != null) {
                judged = true
                RunLog.append("fmt", combined.describe())
                onFrameSample?.invoke(mediaId, combined)
                return
            }
            // 三帧说法不一，只是这一轮运气不好（片头渐入 / 切场），不是结论。
            // 旧版在这里就把 judged 置 true，等于永远放弃。
            RunLog.append(
                "fmt",
                "抽帧三帧不一致，继续观察（" +
                    samples.joinToString(" | ") { it.layout.name } + "）"
            )
            samples.clear()
        }
        if (sampleAttempts >= MAX_SAMPLE_ATTEMPTS) {
            RunLog.append(
                "fmt",
                "抽帧本轮只拿到 ${samples.size}/$SAMPLE_COUNT 个有效样本（黑场 / 平坦）· 继续观察"
            )
            sampleAttempts = 0
            samples.clear()
        }
        if (totalSampleAttempts >= MAX_TOTAL_ATTEMPTS) {
            judged = true
            RunLog.append(
                "fmt",
                "抽帧持续 $totalSampleAttempts 次仍无一致结论 · 沿用现有判据"
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

    /**
     * C5 / V2.32：**必须在 GL 线程调用**（VrActivity 用 `queueEvent` 投递）。
     * SDK 的 DistortionRenderer.close() 与 FBO / 纹理删除都要求上下文有效，
     * 挂到主线程那条 [release] 上会在部分机型上留下 GL 资源或直接报错。
     * 默认关时 composer 为 null，这个方法是个空操作。
     */
    fun releaseGl() {
        runCatching { composer?.release() }
            .onFailure {
                RunLog.append("gl", "[C5] GL 资源释放异常：${it.message}")
            }
        composer = null
        composerResolved = false
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

        /** 起播后先等约 0.5 秒：躲开解码器吐出的第一帧黑屏，又别让接缝停太久。 */
        private const val FIRST_SAMPLE_FRAMES = 30

        /** 之后约每 0.33 秒抽一次，不每帧读像素。 */
        private const val SAMPLE_INTERVAL_FRAMES = 20

        /** 三个样本一致才落地：单帧误判会把对的画面切成两半，代价比慢一秒大得多。 */
        private const val SAMPLE_COUNT = 3

        /** 一轮最多抽 40 次（约 20 秒）；到点只记一行日志并开新一轮，不判死。 */
        private const val MAX_SAMPLE_ATTEMPTS = 40

        /** 全程约 60 秒仍拿不到一致结论才真正放弃，避免一直读像素。 */
        private const val MAX_TOTAL_ATTEMPTS = 120

        /** 整幅纹理区域（不切半幅）。 */
        private val FULL_REGION = floatArrayOf(1f, 1f, 0f, 0f)

        /** UI 位图的兜底刷新间隔：render 内部有淡出 / 超时动画，不能完全停更。 */
        private const val UI_MAX_INTERVAL_MS = 200L
    }
}
