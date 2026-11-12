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
import com.gazereel.vr.core.HeadPose
import com.gazereel.vr.core.Optics
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

    /** V2.44 诊断：视线在水平面上的投影长度。视线正对天顶时它→0，但不再引发任何分支切换。 */
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
        // V2.39：瞳距的真正作用是「让每只眼的画面落在镜片中心」，所以作用在**光轴**上
        // （离轴视锥），不是相机间距——后者在半径 10 的球面上只有 0.04° 的视差，
        // 这正是「界面能拖、画面毫无变化」的原因。见 core/Optics 的推导。
        val near = 0.1f
        val bounds = Optics.frustumLeftRight(eye == Eye.LEFT, aspect, ipdMeters * 1000f, near)
        val projection = FloatArray(16)
        Matrix.frustumM(projection, 0, bounds[0], bounds[1], -near, near, near, 50f)
        return EyeParams(view, projection)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val deviceToWorld = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(deviceToWorld, event.values)
        // V2.44：一次完成「屏幕轴重排 + ENU(Z朝天)→Y-up 坐标转换」，
        // 照 Cardboard SDK 的 HeadTracker 约定。写进 rotation 的姿态从源头就是
        // 渲染世界里的合法旋转：没有开关、没有欧拉分解、没有翻转，也不会死锁。
        val matrix = HeadPose.headPose(deviceToWorld, currentPanelRotation())
        // 诊断：视线水平分量的长度，θ→0（正对天顶）时→0，但不引发任何分支切换。
        val f = HeadPose.forward(matrix)
        lastCrossLen = kotlin.math.sqrt((f[0] * f[0] + f[2] * f[2]).coerceAtLeast(0f))
        lastBranch = 2
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
     * V2.44：屏幕姿态的构造整段搬进 [HeadPose]，这里不再有任何本地算式。
     *
     * 旧实现在这里只做 `设备姿态 × 屏幕映射`，出来的矩阵列是「屏幕轴在 **ENU**
     * （Z 朝天）里的方向」，却被下游当成 Y-up 用 —— 这是连续四轮方向修错的总根因：
     * 正常戴镜时分解出的 pitch 恒为 ±90°（万向节死锁），俯仰响应反、翻转开关还会
     * 让 yaw/roll 跳 180°。详见 [HeadPose] 的类注释与 `tools/PoseMathCheck.kt`。
     *
     * 保留「不分解、不重建、不叉积」这条原则：V2.24 的叉积分支在真机 108 秒里
     * 切换 10 次、单次最大跳 97.9°，那类做法一律不再回来。
     */

    /** 每帧读一次面板朝向。拿不到就沿用上一次的值，不因为一次异常把画面翻过去。 */
    @Suppress("DEPRECATION")
    private fun currentPanelRotation(): Int {
        val r = runCatching { windowManager.defaultDisplay.rotation }.getOrDefault(-1)
        if (r in 0..3) lastPanelRotation = r
        return lastPanelRotation
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
