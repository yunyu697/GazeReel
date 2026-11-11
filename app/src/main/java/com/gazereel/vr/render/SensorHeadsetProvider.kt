package com.gazereel.vr.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.EyeParams
import com.gazereel.vr.core.PoseTrace
import com.gazereel.vr.core.RotationBlend
import com.gazereel.vr.core.TriggerEvent
import com.gazereel.vr.core.TriggerKind
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.core.viewRotationOf
import com.gazereel.vr.data.RunLog

/**
 * 无 Cardboard AAR 时的可运行实现：Android 旋转矢量传感器 + 自绘左右眼视口。
 * 通过 VrHeadsetProvider 与 SDK 实现完全隔离，替换时 UI / 播放 / 输入层零改动。
 *
 * 注意：这里的畸变参数为占位（不做预畸变），真机需由 Cardboard SDK 的
 * LensDistortion / DistortionRenderer 提供每镜片参数；见 src/cardboard/java。
 */
class SensorHeadsetProvider(context: Context) : VrHeadsetProvider, SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager =
        appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /** V2.24 诊断：`screenPoseOf` 最后一次用的叉积模长与分支，只读记录。 */
    @Volatile
    private var lastCrossLen = 0f
    @Volatile
    private var lastBranch = 0
    private val diagRotation = FloatArray(16)

    /** V2.25：面板朝向。锁 landscape 后恒为 90 或 270，启动时先读一次。 */
    @Volatile
    private var lastPanelRotation = Surface.ROTATION_0

    private val rotation = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val inverseOffset = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val lock = Any()
    private var triggerListener: ((TriggerEvent) -> Unit)? = null

    @Volatile
    private var viewLocked = false
    private val frozen = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    // V2.19 / B5：缓转重定基准。起点/终点都是「参考系偏移」，中间走球面插值。
    private var easing = false
    private var easeStartMs = 0L
    private var easeDurationMs = 800L
    private val easeFrom = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val easeTo = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val easeScratch = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    /**
     * V2.28：正前方只在进 VR 时定**一次**，而且要等姿态稳下来再定。
     *
     * 旧版在第一帧传感器数据到达时就 recenterLocked()：那一刻用户还举着手机往
     * 纸盒里塞，手机往往是竖直的。等他戴稳、把手机后仰到舒服的 10°，正前方
     * 已经钉在那个竖直姿态上了——这就是真机「要摆回 0° 才正对」的十来度偏差。
     * alpha14 试过事后静默补偿，结果每次动头都重定一次，反而更乱（见
     * VrCoordinator.updatePosture 的注释）。
     *
     * 现在改成：连续若干帧视线几乎不动，就认为「戴稳了」，用那一刻的姿态定基准。
     * 最长等 4 秒兜底，避免用户一直在动时永远不定。定完即关，之后不再自动改。
     */
    @Volatile
    private var pendingInitialRecenter = false
    private var initialDeadlineMs = 0L
    private var stillFrames = 0
    private var hasLastRawPose = false
    private val lastRawPose = FloatArray(16)

    companion object {
        /** 帧间视线夹角小于它算「没动」，约 0.6°。 */
        private const val STILL_RAD = 0.010f

        /** 连续静止多少帧才定基准：SENSOR_DELAY_GAME 约 50Hz，25 帧 ≈ 0.5 秒。 */
        private const val STILL_FRAMES = 25

        /** 最长等待时间；超时就按当前姿态定，不能让用户一直没有正前方。 */
        private const val INITIAL_MAX_WAIT_MS = 4_000L

        /** V2.35：视线水平分量的下限，低于它不做方位角对齐（见 recenterYaw）。 */
        private const val YAW_MIN_HORIZONTAL = 0.15f
    }

    override val supportsHardwareTrigger: Boolean = false

    override fun start() {
        synchronized(lock) {
            pendingInitialRecenter = true
            initialDeadlineMs = SystemClock.elapsedRealtime() + INITIAL_MAX_WAIT_MS
            stillFrames = 0
            hasLastRawPose = false
        }
        // V2.25：面板朝向在进入时就确定，之后每帧只做比对，避免窗口刚建好时读错。
        lastPanelRotation = panelRotation().takeIf { it in 0..3 } ?: Surface.ROTATION_0
        // V2.24 诊断：每次进 VR 重新开始记录，只读。
        PoseTrace.begin(
            nowMs = SystemClock.elapsedRealtime(),
            panelRotation = panelRotation(),
            screenOrientation = appContext.resources.configuration.orientation
        )
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    override fun stop() {
        PoseTrace.finish()
        sensorManager.unregisterListener(this)
    }

    /** 面板朝向。诊断用：验证「锁定横屏后它到底返回什么」这个一直靠猜的量。 */
    @Suppress("DEPRECATION")
    private fun panelRotation(): Int = runCatching {
        windowManager.defaultDisplay.rotation
    }.getOrDefault(-1)

    override fun attachTriggerListener(listener: (TriggerEvent) -> Unit) {
        triggerListener = listener
    }

    /** 无硬件触发键时，由 VrActivity 把屏幕触摸/音量键事件归一化后注入。 */
    fun injectTrigger(kind: TriggerKind) {
        triggerListener?.invoke(TriggerEvent(kind, SystemClock.elapsedRealtime()))
    }

    override fun headRotation(): FloatArray {
        if (viewLocked) return synchronized(lock) { frozen.copyOf() }
        val result = FloatArray(16)
        synchronized(lock) {
            Matrix.multiplyMM(result, 0, offsetNow(), 0, rotation, 0)
        }
        return result
    }

    /**
     * V2.19 / B5：当前生效的参考系偏移。
     *
     * 缓转期间它是 `easeFrom → easeTo` 的球面插值，其余时候就是 `inverseOffset`。
     * 所有读姿态的路径（视图矩阵、凝视落点、手势）都必须经过这里，
     * 否则缓转的 0.8 秒里画面在动、准星却已经跳到终点。
     */
    private fun offsetNow(): FloatArray {
        if (!easing) return inverseOffset
        val now = SystemClock.elapsedRealtime()
        val t = ((now - easeStartMs).toFloat() / easeDurationMs.coerceAtLeast(1L)).coerceIn(0f, 1f)
        if (t >= 1f) {
            easing = false
            System.arraycopy(easeTo, 0, inverseOffset, 0, 16)
            return inverseOffset
        }
        RotationBlend.slerp(easeFrom, easeTo, t, easeScratch)
        return easeScratch
    }

    override fun setViewLocked(locked: Boolean) {
        if (locked && !viewLocked) {
            // 先按未锁定状态取一次当前朝向，再冻结，避免把锁定动作本身算进去。
            val snapshot = headRotation()
            synchronized(lock) { System.arraycopy(snapshot, 0, frozen, 0, 16) }
        }
        viewLocked = locked
    }

    /**
     * V2.4：锁定视角时 headRotation() 直接返回 frozen；旧实现只改 inverseOffset，
     * 「重置正前方」在锁定态因此完全无效。这里把冻结帧一并更新——新基准下
     * 当前姿态就是正前方，所以 frozen 设为单位矩阵，锁定状态保持不变。
     */
    override fun recenter() {
        synchronized(lock) {
            easing = false
            recenterLocked()
        }
    }

    /**
     * V2.19 / B5：0.8 秒缓转。
     *
     * 起点是**当前生效**的偏移（可能正处在上一段缓转中间），终点是新参考系。
     * 直接拿 `inverseOffset` 当起点会在连续确认时跳一下。
     */
    override fun recenterSmooth(durationMs: Long) {
        recenterTo(null, durationMs)
    }

    /**
     * V2.19c：缓转到指定姿态。
     *
     * `pose == null` 表示「就用此刻的朝向」，等价于旧的 [recenterSmooth]。
     * 姿态确认卡用得到非 null 的那种：用户为点到卡片上的按钮会把视线移开几度，
     * 那几度不该被写进新的正前方。
     *
     * `pose` 的语义是 **[headRotation] 那种「参考系里的姿态」**，不是设备原始姿态。
     * 要让 H 变成新的正前方，需要 `O'·R = I` 且 `O·R = H`，解得 `O' = H⁻¹·O`。
     * 直接写 `O' = pose⁻¹` 会多乘一次 O，落点偏出去几十度——这正是这个接口容易写错的地方。
     */
    override fun recenterTo(pose: FloatArray?, durationMs: Long) {
        synchronized(lock) {
            val offset = offsetNow()
            val h = FloatArray(16)
            if (pose != null) {
                System.arraycopy(pose, 0, h, 0, 16)
            } else {
                Matrix.multiplyMM(h, 0, offset, 0, rotation, 0)
            }
            val inv = FloatArray(16)
            if (!Matrix.invertM(inv, 0, h, 0)) return
            val target = FloatArray(16)
            Matrix.multiplyMM(target, 0, inv, 0, offset, 0)
            System.arraycopy(offset, 0, easeFrom, 0, 16)
            System.arraycopy(target, 0, easeTo, 0, 16)
            easeStartMs = SystemClock.elapsedRealtime()
            easeDurationMs = durationMs.coerceAtLeast(1L)
            easing = true
            if (viewLocked) Matrix.setIdentityM(frozen, 0)
        }
    }

    /** 把当前姿态设为「正前方」。调用方需持有 lock。 */
    private fun recenterLocked() {
        easing = false
        val inverse = FloatArray(16)
        if (Matrix.invertM(inverse, 0, rotation, 0)) {
            System.arraycopy(inverse, 0, inverseOffset, 0, 16)
            if (viewLocked) Matrix.setIdentityM(frozen, 0)
        }
    }

    /**
     * V2.35：只把方位角对齐到现在看的方向，俯仰/横滚不动。
     *
     * 数学上就是对参考系偏移**左乘**一个世界 Y 轴旋转：
     * `O' = R_y(ψ)·O`，于是 `H' = R_y(ψ)·H`，水平分量被转到 0，
     * 而俯仰与横滚（重力对齐）原样保留 —— 画面回到正中，地平线还是平的。
     *
     * 唯一要防的是奇点：视线接近正上/正下时水平投影趋零，方位角没有意义。
     * 旧实现的「平放抖动 / 画面翻过去」就是在那附近把噪声放大成了 90° 跳变，
     * 这里直接不动（宁可这次不对齐，也不制造一次翻转）。
     */
    override fun recenterYaw() {
        synchronized(lock) {
            // 锁视角时 headRotation() 返回 frozen，参考系就必须按 frozen 算，
            // 否则「看到的方向」和「被对齐的方向」不是同一个，画面会莫名转一下。
            val h = if (viewLocked) {
                frozen.copyOf()
            } else {
                FloatArray(16).also { Matrix.multiplyMM(it, 0, inverseOffset, 0, rotation, 0) }
            }
            val fx = -h[8]
            val fz = -h[10]
            if (kotlin.math.hypot(fx, fz) < YAW_MIN_HORIZONTAL) return
            val psi = kotlin.math.atan2(fx.toDouble(), (-fz).toDouble()).toFloat()
            val c = kotlin.math.cos(psi)
            val s = kotlin.math.sin(psi)
            // R_y(ψ) 列主序；作用于世界坐标，所以左乘
            val rotY = FloatArray(16)
            Matrix.setIdentityM(rotY, 0)
            rotY[0] = c; rotY[2] = -s
            rotY[8] = s; rotY[10] = c
            val updated = FloatArray(16)
            Matrix.multiplyMM(updated, 0, rotY, 0, inverseOffset, 0)
            System.arraycopy(updated, 0, inverseOffset, 0, 16)
            easing = false
            // 锁定视角时 frozen 里存的是「参考系里的姿态」，新参考系下它就是 H'
            if (viewLocked) Matrix.multiplyMM(frozen, 0, rotY, 0, h, 0)
        }
    }

    override fun eyeParams(eye: Eye, aspect: Float, ipdMeters: Float): EyeParams {
        val eyeX = if (eye == Eye.LEFT) -ipdMeters / 2f else ipdMeters / 2f
        val translated = FloatArray(16)
        Matrix.setLookAtM(translated, 0, eyeX, 0f, 0f, eyeX, 0f, -1f, 0f, 1f, 0f)
        // 近似的相机视图 = 眼位平移 × 姿态的逆。姿态是「设备 → 世界」，
        // 视图要「世界 → 设备」，必须转置（V2.7.2：此前直接用姿态，
        // 导致头向左转画面也向左转，方向反且幅度加倍）。
        val view = FloatArray(16)
        Matrix.multiplyMM(view, 0, translated, 0, viewRotationOf(headRotation()), 0)
        val projection = FloatArray(16)
        val near = 0.1f
        Matrix.frustumM(projection, 0, -near * aspect, near * aspect, -near, near, near, 50f)
        return EyeParams(view, projection)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val deviceToWorld = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(deviceToWorld, event.values)
        val matrix = screenPoseOf(deviceToWorld)
        val crossLen = lastCrossLen
        val branch = lastBranch
        // 参考系的「上」= 偏移矩阵作用在世界天顶上，即它的第三列。
        val refUp = FloatArray(3)
        synchronized(lock) {
            System.arraycopy(matrix, 0, rotation, 0, 16)
            if (pendingInitialRecenter) {
                val now = SystemClock.elapsedRealtime()
                val still = hasLastRawPose && poseDelta(matrix, lastRawPose) < STILL_RAD
                stillFrames = if (still) stillFrames + 1 else 0
                System.arraycopy(matrix, 0, lastRawPose, 0, 16)
                hasLastRawPose = true
                if (stillFrames >= STILL_FRAMES || now >= initialDeadlineMs) {
                    pendingInitialRecenter = false
                    recenterLocked()
                    RunLog.append(
                        "posture",
                        "首次基准 · 姿态稳定后定一次（静止 $stillFrames 帧）"
                    )
                }
            }
            // 诊断用：当前参考系里的姿态，供记录「用户实际看到的横滚与偏离」。
            Matrix.multiplyMM(diagRotation, 0, inverseOffset, 0, rotation, 0)
            refUp[0] = inverseOffset[8]
            refUp[1] = inverseOffset[9]
            refUp[2] = inverseOffset[10]
        }
        // V2.24 诊断：只读记录。上面写 rotation 的那条路径一行没动。
        // 传 diagRotation（偏移 × 姿态），不是 screenPoseOf 的中间结果——
        // 那个中间矩阵的横滚被强制归零，拿它量横滚永远是 0。
        PoseTrace.onFrame(
            nowMs = SystemClock.elapsedRealtime(),
            c = matrix,
            h = diagRotation,
            refUp = refUp,
            crossLen = crossLen,
            branch = branch
        )
    }

    /** 两个姿态的视线夹角（弧度）：只用来判断「手机是不是还被握着在动」。 */
    private fun poseDelta(a: FloatArray, b: FloatArray): Float {
        val dot = (a[8] * b[8] + a[9] * b[9] + a[10] * b[10]).coerceIn(-1f, 1f)
        return Math.acos(dot.toDouble()).toFloat()
    }

    /**
     * V2.25 第 2 步：屏幕姿态 = 设备姿态 × 屏幕映射。全程矩阵，不分解、不重建。
     *
     * ## 上一版为什么抖、为什么翻转（真机 1.1.0-alpha11 实测数据）
     *
     * 旧实现把设备姿态拆成 forward，再用 `cross(forward, 天顶)` 叉出 right、
     * `cross(back, right)` 叉出 up。叉积模长 = sin θ（θ 为视线与重力轴夹角），
     * θ→0 时它趋零，归一化把噪声放大；更糟的是模长掉到 0.2 以下就换设备 y 轴做参考，
     * 而换轴那一刻 right 与天顶分支差约 90°，画面直接翻过去。
     *
     * 实测：108 秒里分支切换 10 次、单次最大跳 97.9°，横滚跨度 341.7°，
     * 平放稳定段横滚恒在 −94°（不是抖，是稳稳歪了 90°）。
     *
     * ## 现在为什么没有奇点
     *
     * 设备姿态本身就是正交矩阵，横滚一直完整地存在它的列里。只要不拆开再拼回去，
     * 就没有自由度会丢失，θ→0 时矩阵依然正交、依然连续。换轴分支也随之消失。
     * 「记住原来的方向」的数学形式，就是**别把方向拆出去**。
     *
     * ## 屏幕映射是常量，不是猜出来的
     *
     * Activity 锁定 landscape，实测 `Display.getRotation()` = 1（ROTATION_90）。
     * 它只决定屏幕两根轴对应设备的哪两根轴，是固定的轴重排，不需要重力判断。
     * 映射写错的表现是「整体歪 90°」，而不是抖——可验证、可回退。
     *
     * 输出的列依次是 (right, up, back)，第三列取负即视线方向——与
     * VrUiLayer.gazePoint / GestureRecognizer 使用的 -rotation[8..10] 一致。
     */
    private fun screenPoseOf(m: FloatArray): FloatArray {
        val s = screenToDevice(currentPanelRotation())

        // 行主序 3×3 → 列主序 4×4（列 = 设备 x / y / z 在世界中的方向）
        val r = FloatArray(16)
        r[0] = m[0]; r[1] = m[3]; r[2] = m[6]; r[3] = 0f
        r[4] = m[1]; r[5] = m[4]; r[6] = m[7]; r[7] = 0f
        r[8] = m[2]; r[9] = m[5]; r[10] = m[8]; r[11] = 0f
        r[12] = 0f; r[13] = 0f; r[14] = 0f; r[15] = 1f

        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, r, 0, s, 0)

        // 诊断：不再有分支。退化程度直接由 sinθ 表示，θ→0 时它→0 但不再引发任何切换。
        val fz = -m[8]
        lastCrossLen = kotlin.math.sqrt((1f - fz * fz).coerceAtLeast(0f))
        lastBranch = 1
        return out
    }

    /**
     * 屏幕坐标 → 设备坐标，按 `Display.getRotation()`。
     *
     * 三列的物理含义：屏幕的右、上、外分别对应设备的哪根轴。
     * ROTATION_90：屏幕右 = 设备 +y，屏幕上 = 设备 −x
     * （与官方 `remapCoordinateSystem(R, AXIS_Y, AXIS_MINUS_X)` 同一含义）。
     */
    private fun screenToDevice(rotation: Int): FloatArray {
        val s = FloatArray(16)
        Matrix.setIdentityM(s, 0)
        when (rotation) {
            Surface.ROTATION_90 -> {
                s[0] = 0f; s[1] = 1f; s[2] = 0f
                s[4] = -1f; s[5] = 0f; s[6] = 0f
            }
            Surface.ROTATION_180 -> {
                s[0] = -1f; s[1] = 0f; s[2] = 0f
                s[4] = 0f; s[5] = -1f; s[6] = 0f
            }
            Surface.ROTATION_270 -> {
                s[0] = 0f; s[1] = -1f; s[2] = 0f
                s[4] = 1f; s[5] = 0f; s[6] = 0f
            }
            else -> Unit   // ROTATION_0：屏幕轴即设备轴
        }
        return s
    }

    /** 每帧读一次面板朝向。拿不到就沿用上一次的值，不因为一次异常把画面翻过去。 */
    @Suppress("DEPRECATION")
    private fun currentPanelRotation(): Int {
        val r = runCatching { windowManager.defaultDisplay.rotation }.getOrDefault(-1)
        if (r in 0..3) lastPanelRotation = r
        return lastPanelRotation
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
