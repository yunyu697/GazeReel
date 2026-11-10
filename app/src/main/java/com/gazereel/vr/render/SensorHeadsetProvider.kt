package com.gazereel.vr.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.EyeParams
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

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    /**
     * V2.20：屏幕旋转必须先查出来。
     *
     * 旋转矢量给的是**设备物理轴**的姿态，而画出来的是**屏幕轴**（横屏时屏幕的「上」
     * 是手机的长边）。两者差一个绕 Z 的 90° / 180° / 270°，不补这一下，
     * 左右的转头就会被当成绕视轴的横滚。
     */
    private val display: Display? = runCatching {
        (context.applicationContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .getDisplay(Display.DEFAULT_DISPLAY)
    }.getOrNull()

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
     * V2.7.3：进 VR 后要「第一眼正对片库」，但 start() 时传感器还没出过数据，
     * 此刻的 rotation 还是单位矩阵——立刻 recenter 等于把基准设成「零姿态」，
     * 等第一帧真数据到来，画面就跳到手机的绝对朝向去了。
     * 所以基准必须等第一帧数据到达时再定。
     */
    @Volatile
    private var pendingInitialRecenter = false

    override val supportsHardwareTrigger: Boolean = false

    override fun start() {
        pendingInitialRecenter = true
        refreshDisplayRotation()
        // 姿态出问题时，第一个要问的就是「它以为屏幕转了多少度」。
        RunLog.append("posture", "屏幕旋转=${rotationName(displayRotation)}·全姿态（无重力投影）")
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    private fun rotationName(r: Int): String = when (r) {
        Surface.ROTATION_90 -> "90°"
        Surface.ROTATION_180 -> "180°"
        Surface.ROTATION_270 -> "270°"
        else -> "0°"
    }

    override fun stop() = sensorManager.unregisterListener(this)

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
        refreshDisplayRotation()
        val deviceToWorld = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(deviceToWorld, event.values)
        val matrix = screenPoseOf(deviceToWorld, displayRotation)
        synchronized(lock) {
            System.arraycopy(matrix, 0, rotation, 0, 16)
            if (pendingInitialRecenter) {
                pendingInitialRecenter = false
                recenterLocked()
            }
        }
    }

    private var displayRotation: Int = Surface.ROTATION_0
    private var lastRotationCheckMs = 0L

    /**
     * 屏幕旋转在 VR 期间是锁死的，但要能读出来——它决定「屏幕轴」与「设备轴」差多少。
     * 每秒最多查一次：`Display.getRotation()` 可能触发一次显示信息的刷新，
     * 传感器事件是 100Hz，没必要每次都问。
     */
    private fun refreshDisplayRotation() {
        val now = SystemClock.elapsedRealtime()
        if (lastRotationCheckMs != 0L && now - lastRotationCheckMs < 1000L) return
        lastRotationCheckMs = now
        displayRotation = runCatching { display?.rotation ?: Surface.ROTATION_0 }
            .getOrDefault(Surface.ROTATION_0)
    }

    /**
     * 设备姿态 → **屏幕姿态**（4x4 列主序）。
     *
     * ## V2.20：这里换了算法，原因是 V2.7.3 那套用了重力定 up，在躺姿下必然坏
     *
     * 旧实现：`forward = -设备z`，`right = normalize(forward × 世界天顶)`，`up = back × right`。
     * 目的是让「画面上方永远是天的方向」，转头的横滚被重力抵消掉。
     * 问题出在 `forward × 天顶` 这个叉乘：**视线一旦平行于天顶就退化为零向量**。
     * 而「躺在床上、手机扣在脸上看天花板」——也就是这个 App 最主要的用法——
     * 视线恰好就是天顶方向。叉乘长度掉到 0.2 以下时旧代码切到「拿设备 y 轴当参考」的
     * 兜底分支，两个参考系差 90°，于是画面在临界点附近**打转、甚至整幅翻转**。
     * 用户反馈的「仰姿或俯姿到手机变成水平平放时画面会旋转甚至翻转」就是这条。
     *
     * 现在：直接用旋转矢量的**全姿态**，只补一个固定的屏幕旋转（屏幕轴 → 设备轴），
     * 再左乘参考系偏移（`inverseOffset`，即「正前方」）。
     *
     * - 没有叉乘，就没有奇点：任何俯仰、任何横滚都连续；
     * - 横滚是真的横滚。头一歪画面跟着歪，这本来就是 3DOF 该有的样子；
     *   真正的「摆正」由参考系负责——躺下后的第一个稳定姿态就是正前方（B4），
     *   所以戴上时画面正对着你，不需要靠重力去掰直它。
     *
     * 输出列依次是 (right, up, back)，第三列取负即视线方向——与
     * VrUiLayer.gazePoint / GestureRecognizer 使用的 -rotation[8..10] 一致。
     */
    private fun screenPoseOf(d2w: FloatArray, screenRotation: Int): FloatArray {
        // 旋转矢量给的是 9 元行主序 3x3：world = R · device。转成列主序 4x4。
        val deviceToWorld = FloatArray(16)
        for (col in 0..2) {
            for (row in 0..2) {
                deviceToWorld[col * 4 + row] = d2w[row * 3 + col]
            }
        }
        // screen = R · S  (S: 屏幕轴 → 设备轴)。Android 的约定与 remapCoordinateSystem 一致：
        // ROTATION_90 时屏幕右轴 = 设备 +y、屏幕上轴 = 设备 −x。
        val screenToDevice = screenToDevice(screenRotation)
        val out = FloatArray(16)
        Matrix.multiplyMM(out, 0, deviceToWorld, 0, screenToDevice, 0)
        return out
    }

    /** 屏幕轴 → 设备轴。列 0 = 屏幕右，列 1 = 屏幕上，列 2 = 屏幕法线（指向用户）。 */
    private fun screenToDevice(screenRotation: Int): FloatArray {
        val (x, y) = when (screenRotation) {
            Surface.ROTATION_90 -> floatArrayOf(0f, 1f, 0f) to floatArrayOf(-1f, 0f, 0f)
            Surface.ROTATION_180 -> floatArrayOf(-1f, 0f, 0f) to floatArrayOf(0f, -1f, 0f)
            Surface.ROTATION_270 -> floatArrayOf(0f, -1f, 0f) to floatArrayOf(1f, 0f, 0f)
            else -> floatArrayOf(1f, 0f, 0f) to floatArrayOf(0f, 1f, 0f)
        }
        return floatArrayOf(
            x[0], x[1], x[2], 0f,
            y[0], y[1], y[2], 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
