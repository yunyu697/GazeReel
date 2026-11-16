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
import com.gazereel.vr.core.Optics
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.StereoLayout
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.core.eyeTextureRegion
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.input.SettingsRepository
import com.gazereel.vr.ui.VrUiState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * PRD 6.1：ExoPlayer → SurfaceTexture → GL_TEXTURE_EXTERNAL_OES → 投影几何 → 左右眼。
 * - 每眼用不同的 uEyeRegion 采样同一纹理，实现 Mono / SBS / TAB；
 * - UI 作为独立 GL 层画在 2 米虚拟距离，世界锁定（model 固定，不跟随头部）；
 * - 亮度通过 uBrightness 只作用于视频层。
 * - alpha39 纯复刻：每眼 view / projection 全部来自官方 `LensDistortion`，两眼先渲进
 *   [DistortionComposer] 的全屏离屏纹理，再由 SDK 畸变网格合成到屏幕。
 *   **没有左右半屏直出分支，也没有开关。** 这是唯一渲染路径。
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
    // alpha35 / 02：头锁定 UI 用「去掉姿态旋转」的 MVP；类字段复用，避免每帧分配。
    private val headLockedMvp = FloatArray(16)
    private val eyeViewTmp = FloatArray(16)
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
    /**
     * 17:6x：唤出徽标。热区在 ±38°，主画布（播放页 ±16.7°）画不到，所以单开
     * 一张 512×160 纹理 + 一个独立 quad。几何只有上和下两个，按角度缓存。
     */
    private var wakeTextureId = 0
    private val wakeGeometries = HashMap<Float, ProjectionMesh.Geometry>()
    private var lastWakeDeg: Float? = null
    private var lastWakeStep = -1
    /**
     * alpha3 / 15:0x：场景层。只有 2D 银幕时参与绘制 —— 360/180 是相机中心的内翻球面，
     * 房间会把它整个挡住。渲染顺序固定为 **场景 → 视频 → UI**，复用同一个 mvp。
     */
    private val scene = SceneRenderer()
    private var lastProjection: Projection? = null
    private var lastFovDegrees: Int? = null
    private var lastScreenWidthPercent: Int = -1
    private var lastFisheyeFov: Int = -1
    private var lastFisheyeCircle: Int = -1
    private var lastFisheyeAspectPermille: Int = -1
    // 19:2x：全画幅开关换的是**另一种网格**（矩形 vs 圆盘），漏进重建判定就不是「差一点」，
    // 而是整幅几何不换 —— 圆盘把全画幅片子的四角留黑，且边缘被拉成条纹。
    private var lastFisheyeFullFrame: Boolean? = null
    private var lastLayout: com.gazereel.vr.core.StereoLayout? = null
    private var loggedStMatrix = false
    private var width = 1
    private var height = 1
    @Volatile private var frameAvailable = false

    // V2.31：UI 位图的脏标记与上次上传时间，见 [uiSignature]。
    private var lastUiKey = 0
    private var lastUiUploadMs = 0L

    // 惰性解析放在 GL 线程上：Provider 的 distortionComposer() 只是造一个不碰 GL
    // 的薄对象，真正的 FBO 与 SDK 畸变渲染器在它自己的 beginFrame 里分配。
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
            // 15:0x：纹理按 2 倍分辨率分配，与 VrUiLayer 的 TEX_SCALE 对齐。
            // 分配尺寸写死小一点的话，texSubImage2D 传更大的位图会直接失败（GL_INVALID_VALUE），
            // 表现是整块 UI 变成上一帧残留 —— 这条必须和位图尺寸一起改。
            uiTextureId = GlUtils.createUiTexture(
                VrUiLayer.W * VrUiLayer.TEX_SCALE,
                VrUiLayer.H * VrUiLayer.TEX_SCALE
            )
            videoProgram = GlProgram.video()
            uiProgram = GlProgram(Shaders.UI_VERTEX, Shaders.UI_FRAGMENT)
            wakeTextureId = GlUtils.createUiTexture(VrUiLayer.WAKE_W, VrUiLayer.WAKE_H)
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
            // 若异常发生在 SDK 链的 beginFrame 与 endFrame 之间，离屏 FBO 还绑着，
            // 不先解绑，清屏就清进纹理、屏幕停在上一帧。
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
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

    /**
     * 把瞳距换算成投影矩阵上的水平光轴位移（离轴视锥）。
     *
     * ## 为什么改 `proj[8]`
     *
     * 标准透视投影里 `clip.x = m[0]·x + m[8]·z + m[12]·w`、`clip.w = -z`，于是
     * `ndc.x = -m[0]·x/z - m[8]`。取视轴上的点（x=0）就是 `ndc.x = -m[8]`，
     * 也就是说 **`m[8]` 本身就是光轴在近平面上的归一化偏移**。给它加一个量，
     * 整幅画面就在视口里整体平移，而视场角、透视关系、深度全都不变 —— 这正是
     * 「光轴转一点」的等价写法，也是纸盒里瞳距偏大/偏小时该发生的事。
     *
     * ## 为什么不用相机平移
     *
     * 旧实现是每眼相机沿 x 挪 ±IPD/2。片源铺在半径 10 的球面上，62→75mm 只挪
     * 6.5mm，对应视差 `atan(0.0065/10) ≈ 0.04°` —— 数学上正确、肉眼上等于没有。
     * 这也是 `Optics` KDoc 里记着的那次事故。
     *
     * ## 符号与幅度
     *
     * `Optics.eyeShiftDeg` 约定「正 = 光轴朝屏幕右侧转 = 画面内容向左移」，左眼取正、
     * 右眼取负，于是调大瞳距时两幅画面各自向外分开。
     *
     * 视轴上的点 `x=0` 有 `ndc.x = -m[8]`，所以 **`m[8]` 增大 = ndc.x 减小 = 内容左移**，
     * 与 `Optics` 的正号约定同向 —— 这也是这里用 `+=` 而不是 `-=` 的原因。
     * （第一版写成 `-=`，被 `tools/check_ipd_shift.py` 抓出来：那样左眼内容会右移，
     * 两幅向内聚拢，正好与「调大瞳距要分开」相反。）
     *
     * `m[0] = 1 / tan(halfFovX)`，反解出 `tan(halfFovX)` 后，把角度偏移折算成
     * 近平面上的 ndc 偏移量。偏移上限由 `Optics.MAX_SHIFT_DEG`（9°）夹住。
     */
    private fun applyIpdShift(eye: Eye, projection: FloatArray) {
        val deg = Optics.eyeShiftDeg(eye == Eye.LEFT, settings.current.ipdMm)
        if (deg == 0f) return
        val m0 = projection[0]
        // m[0] 必为正且有限；SDK 换过参数导致异常时宁可不偏移，也不要画出歪画面。
        if (m0 <= 1e-6f) return
        val tanHalfFovX = 1f / m0
        val ndcShift = (Math.tan(Math.toRadians(deg.toDouble())) / tanHalfFovX).toFloat()
        projection[8] += ndcShift
    }

    private fun drawFrame() {
        val state = stateProvider()
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

        // 18:2x：fov 与银幕宽度也参与几何重建。少了这两条，220/270 手选后
        // 网格仍是旧跨度、调幕宽后前墙洞口仍是旧尺寸 —— 参数「存了没生效」。
        val fovDeg = state.format.fov.degrees
        val screenWidthPercent = settings.current.screenWidthPercent
        // 19:0x：鱼眼张角与画面圆也算几何输入 —— 它们改的是 UV 反算，不重建网格就不生效。
        val fisheyeFov = settings.current.fisheyeFovDeg
        val fisheyeCircle = settings.current.fisheyeCirclePercent
        // 19:1x：圆度补偿要按**该眼区域**的像素宽高比算（SBS 取半幅、TAB 取半高）。
        // 尺寸来自片库条目，可能比 format 晚到，所以它也必须进重建判定。
        val fisheyeAspect = state.eyeAspect()
        val aspectPermille = (fisheyeAspect * 1000f).roundToInt()
        val fisheyeFullFrame = settings.current.fisheyeFullFrame
        if (lastProjection != state.format.projection || lastLayout != state.format.layout ||
            lastFovDegrees != fovDeg || lastScreenWidthPercent != screenWidthPercent ||
            lastFisheyeFov != fisheyeFov || lastFisheyeCircle != fisheyeCircle ||
            lastFisheyeAspectPermille != aspectPermille ||
            lastFisheyeFullFrame != fisheyeFullFrame) {
            videoGeometry = mesh.build(
                state.format.projection, fovDeg, screenWidthPercent,
                fisheyeFov, fisheyeCircle, fisheyeAspect, fisheyeFullFrame
            )
            lastProjection = state.format.projection
            lastLayout = state.format.layout
            lastFovDegrees = fovDeg
            lastScreenWidthPercent = screenWidthPercent
            lastFisheyeFov = fisheyeFov
            lastFisheyeCircle = fisheyeCircle
            lastFisheyeAspectPermille = aspectPermille
            lastFisheyeFullFrame = fisheyeFullFrame
            // 真机排查用：正前方（网格 u=0.5, v=0.5）落在纹理的哪个坐标。
            // SBS 应该是左眼 (0.25, 0.5)、右眼 (0.75, 0.5)——即各取半幅的中心；
            // 如果是 (0.5, 0.5)，说明每眼取半幅根本没生效，视点就落在两幅的接缝上。
            val left = state.format.eyeTextureRegion(Eye.LEFT)
            val right = state.format.eyeTextureRegion(Eye.RIGHT)
            RunLog.append(
                "gl",
                "几何 ${state.format.projection}/${state.format.layout}/${state.format.fov.name} " +
                    "跨度 ${fovDeg ?: 0}° 幕宽 ${screenWidthPercent}% " +
                    "鱼眼 ${fisheyeFov}°/圆 ${fisheyeCircle}%/眼画幅 " +
                    "${"%.2f".format(fisheyeAspect)}/" +
                    (if (fisheyeFullFrame) "全画幅" else "圆形") + " " +
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

        // beginFrame 必须在 glClear 之前——清的是离屏纹理，不是屏幕；
        // 顺序反了会拿上一帧的合成结果当底。
        val activeComposer = distortion()
        val targets = activeComposer.beginFrame(width, height)
        if (targets == null) {
            // SDK 畸变链这一帧没起来：解绑可能已绑定的离屏 FBO，跳过本帧。
            // alpha39 没有备用渲染路径，失败原因由 SdkDistortionComposer 记进日志。
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            return
        }
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val panel = uiLayer.panelSpec(state)
        // 缓动期间朝向每帧都在变；量化到 1 毫弧度（约 0.06°），避免 24 帧各建一次 FloatBuffer。
        // V2.19c：姿态确认卡会把整块平面转到候选方向，所以 yaw 也要参与比较。
        // alpha35 / 02：头锁定层的朝向恒为 (0,0)，几何只建一次，不再随姿态重建。
        val specKey = if (panel.headLocked) {
            VrUiLayer.PanelSpec(panel.width, panel.height, 0f, 0f, headLocked = true)
        } else {
            VrUiLayer.PanelSpec(
                panel.width, panel.height,
                Math.round(panel.yaw * 1000f) / 1000f,
                Math.round(panel.pitch * 1000f) / 1000f
            )
        }
        if (uiSpec != specKey) {
            uiSpec = specKey
            uiGeometry = mesh.uiQuad(specKey.yaw, specKey.pitch, specKey.width, specKey.height)
        }

        // 17:6x：唤出徽标。热区在 ±75°~±81°（中心 ±78°），主画布画不到那里，
        // 所以单独一个 quad，中心就挂在热区方向、距眼 2 米。角度全部取
        // `VrUiLayer.WAKE_*`，这里不写死数字。
        // 纹理在循环外上传一次（两只眼共用），进度量化到 2% 再上传 ——
        // 1024×64×4B = 256KB，每帧都传会白烧带宽。
        val wakeDeg = when (gazeId) {
            "wakeConsoleUp" -> VrUiLayer.WAKE_UP_DEG
            "wakeConsoleDown" -> VrUiLayer.WAKE_DOWN_DEG
            else -> null
        }
        val wakeGeometry: ProjectionMesh.Geometry?
        if (wakeDeg != null) {
            wakeGeometry = wakeGeometries.getOrPut(wakeDeg) {
                mesh.uiQuad(
                    0f,
                    Math.toRadians(wakeDeg.toDouble()).toFloat(),
                    VrUiLayer.WAKE_QUAD_W,
                    VrUiLayer.WAKE_QUAD_H
                )
            }
            val step = (gazeProgress.coerceIn(0f, 1f) * 50f).toInt()
            if (wakeDeg != lastWakeDeg || step != lastWakeStep) {
                lastWakeDeg = wakeDeg
                lastWakeStep = step
                GlUtils.uploadBitmap(wakeTextureId, uiLayer.renderWakeBadge(gazeProgress))
            }
        } else {
            wakeGeometry = null
            lastWakeDeg = null
            lastWakeStep = -1
        }

        for (eye in listOf(Eye.LEFT, Eye.RIGHT)) {
            val viewport = targets.viewportOf(eye)
            GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
            val params = headset.eyeParams(eye)
            // 15:5x：把用户瞳距接回渲染，这是「瞳距能拖、画面不动」的正解。
            //
            // 接入 Cardboard SDK 时，Provider 的 eyeParams 注释写明「眼位、FOV、IPD 全由
            // LensDistortion 按纸盒参数给出，接口上连 aspect / ipdMeters 都不再传」——
            // 于是 Optics 里那套离轴视锥（连同注释「上一版为什么界面能拖、画面一点变化都没有」）
            // 写完就再没被调用过。用户设置里的 ipdHalfMm 只能改 UI 数字和日志文本，
            // 渲染端一次都没读，所以「有 UI、调了没效果」。
            //
            // 修法不动三件套：SDK 给的投影矩阵照样用，只在它上面叠一个水平 skew，
            // 等价于把光轴转一个角（这正是纸盒里「画面要落到镜片中心」的几何含义，
            // 与物体远近无关，所以对 180° 球面和 2D 银幕都成立）。
            applyIpdShift(eye, params.projectionMatrix)
            Matrix.multiplyMM(mvp, 0, params.projectionMatrix, 0, params.viewMatrix, 0)
            // alpha35 / 02：头锁定 UI 去掉姿态旋转，只留眼位平移（IPD 视差不变）；
            // 世界锁定 UI 与视频共用 mvp。
            val uiMvpMatrix: FloatArray = if (panel.headLocked) {
                val eyeX = if (eye == Eye.LEFT) -ipdMeters / 2f else ipdMeters / 2f
                Matrix.setLookAtM(eyeViewTmp, 0, eyeX, 0f, 0f, eyeX, 0f, -1f, 0f, 1f, 0f)
                Matrix.multiplyMM(headLockedMvp, 0, params.projectionMatrix, 0, eyeViewTmp, 0)
                headLockedMvp
            } else {
                mvp
            }

            // 16:5x：影院形态。场景层插在视频之前，只在 2D 银幕时画 ——
            // 180°/360° 是相机中心的内翻球面，半径远大于房间，房间会被它整个挡住。
            // `screenLuma` 用亮度设置当代理：画面调亮，厅里的间接光跟着亮，
            // 这是「坐在厅里看幕」和「贴脸看一块板」之间最容易察觉的差别。
            if (state.format.projection == Projection.PLANE_2D) {
                val screenLuma = (state.settings.brightness / 70f).coerceIn(0.14f, 1.30f)
                scene.draw(mvp, screenLuma, settings.current.screenWidthPercent)
            }

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
                        // 18:2x：内容远近感只在球面生效。2D 银幕已经由「银幕宽度」
                        // 调张角，再叠一层 UV 缩放会变成裁画面，两个设置互相打架。
                        // 19:0x：鱼眼同理，而且更硬 —— 鱼眼的 UV 是投影反算出来的，
                        // 再乘一次缩放会把采样点推到画面圆的**外面**，那圈黑角会被卷进来，
                        // 边缘出现一个月牙形黑洞。鱼眼的取景只由「张角 + 画面圆」决定。
                        GLES20.glUniform1f(
                            program.uniform("uContentZoom"),
                            if (state.format.projection == Projection.PLANE_2D ||
                                state.format.projection == Projection.FISHEYE) 1f
                            else settings.current.contentScalePercent / 100f
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
                    samplerName = "uUi",
                    mvpMatrix = uiMvpMatrix
                )
            }

            // 17:6x：唤出徽标压在 UI 之上（它只在控制台淡出时出现，此时主画布上没有
            // 别的可点元素）。用同一个 UI 程序与同一份 mvp，只是另一张纹理。
            wakeGeometry?.let { geometry ->
                val program = uiProgram ?: return@let
                drawGeometry(
                    program = program,
                    geometry = geometry,
                    samplerUnit = GLES20.GL_TEXTURE0,
                    bindTexture = {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, wakeTextureId)
                    },
                    samplerName = "uUi",
                    mvpMatrix = uiMvpMatrix
                )
            }
        }
        // 两眼都画完了才合成；只画一只眼就合成会在另一侧读到旧像素。
        activeComposer.endFrame()
        GlUtils.checkError("onDrawFrame")
    }

    /**
     * 惰性装配 SDK 畸变链（GL 线程）。Provider 造的是不碰 GL 的薄对象，
     * 真正的 FBO / DistortionRenderer 在首次 beginFrame 里分配。
     * alpha39 起没有「装配失败就直出」这条旁路：失败直接抛给 onDrawFrame 的 failGl。
     */
    private fun distortion(): DistortionComposer =
        composer ?: headset.distortionComposer().also { composer = it }

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
        // 18:2x：设置页第 2 页与两个新数值都必须进签名，否则改了值纹理不重绘。
        h = 31 * h + state.settingsPage
        h = 31 * h + state.settings.screenWidthPercent
        h = 31 * h + state.settings.contentScalePercent
        // 19:0x：鱼眼张角与画面圆也会画在第 2 页卡片上（还有预设高亮），
        // 不进签名就会「点了 + 图像不动、数字要等退出重进才变」。
        h = 31 * h + state.settings.fisheyeFovDeg
        h = 31 * h + state.settings.fisheyeCirclePercent
        // 19:2x：全画幅开关会改第 2 页的画面圆数值显示（改成「不计」）与说明句。
        h = 31 * h + (if (state.settings.fisheyeFullFrame) 1 else 0)
        h = 31 * h + (state.refusal?.key?.hashCode() ?: 0)
        // 19:1x：第 2 页要显示「眼画幅 2.00:1 · 已按短边补偿」，尺寸是入库后才有的，
        // 不进签名就会出现「补偿生效了、那行字还写着 1.00:1」。
        h = 31 * h + (state.eyeAspect() * 1000f).toInt()
        h = 31 * h + state.page
        h = 31 * h + state.selectedIndex
        h = 31 * h + state.format.hashCode()
        h = 31 * h + state.suggestedFormat.hashCode()
        h = 31 * h + (state.message?.hashCode() ?: 0)
        // 2.1.0：错误卡第二行按错误类型变（解码超限 / 网络 / 文件损坏），
        // 不进签名就会出现「换了错误类型、卡片还是上一句建议」。
        h = 31 * h + (state.errorHint?.hashCode() ?: 0)
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
        h = 31 * h + state.items.size
        // 20:2x：「本机解不了」集合是后台探测后才填的，items.size 那时早就不变了。
        // 不进签名，纹理不会重传，角标会一直不出现（17:6x 踩过同类的坑）。
        h = 31 * h + state.unsupportedKeys.hashCode()
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
        // 19:2x：键形看 wantPlay、板顶多一行「缓冲中」、进度条多一段缓冲 ——
        // 三者都必须进签名，否则纹理停在旧帧上，改了半天看不见。
        // 缓冲位置按 1% 量化：它每 500ms 都在长，逐毫秒入签名会让纹理白白重传。
        h = 31 * h + state.wantPlay.hashCode()
        h = 31 * h + state.buffering.hashCode()
        h = 31 * h + if (state.durationMs > 0L) {
            (state.bufferedMs * 100L / state.durationMs).toInt()
        } else 0
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
     * 置空 composer，下一帧由 distortion() 重新装配（GL 上下文重建时必须）。
     */
    fun releaseGl() {
        runCatching { composer?.release() }
            .onFailure {
                RunLog.append("gl", "[SDK] GL 资源释放异常：${it.message}")
            }
        // 场景层的程序与顶点缓冲随 GL 上下文一起丢弃，重建时 ensureXxx 会重新生成。
        scene.release()
        composer = null
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

        /** UI 位图的兜底刷新间隔：render 内部有淡出 / 超时动画，不能完全停更。 */
        private const val UI_MAX_INTERVAL_MS = 200L
    }
}
