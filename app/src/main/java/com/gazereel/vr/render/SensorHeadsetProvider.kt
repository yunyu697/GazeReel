package com.gazereel.vr.render

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import android.os.SystemClock
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.EyeParams
import com.gazereel.vr.core.RotationBlend
import com.gazereel.vr.core.TriggerEvent
import com.gazereel.vr.core.TriggerKind
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.core.viewRotationOf

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
        rotationSensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
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
        synchronized(lock) {
            val target = FloatArray(16)
            if (!Matrix.invertM(target, 0, rotation, 0)) return
            System.arraycopy(offsetNow(), 0, easeFrom, 0, 16)
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
        val deviceToWorld = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(deviceToWorld, event.values)
        val matrix = screenPoseOf(deviceToWorld)
        synchronized(lock) {
            System.arraycopy(matrix, 0, rotation, 0, 16)
            if (pendingInitialRecenter) {
                pendingInitialRecenter = false
                recenterLocked()
            }
        }
    }

    /**
     * 设备姿态 → 屏幕姿态（4x4 列主序）。
     *
     * 真机 V2.7.3：头左右一转画面就绕视轴旋转。原因是 VrActivity 锁了横屏，
     * 屏幕的「上」与手机的 y 轴差 90°，而旋转矢量给的是**设备物理轴**姿态，
     * 直接当屏幕坐标用，相机的 up 轴就落在水平面上——头一转，up 跟着在水平面
     * 里转，画面看起来就在自转。
     *
     * 这里不去猜屏幕方向（锁横屏时 display rotation 的取值各厂商还不一致），
     * 直接用重力定 up：视线 = -设备 z，up = 世界竖直投影到垂直于视线的平面。
     * 手机横放竖放都一样，画面上方永远是「天」。
     *
     * 输出的列依次是 (right, up, back)，第三列取负即视线方向——与
     * VrUiLayer.gazePoint / GestureRecognizer 使用的 -rotation[8..10] 一致。
     */
    private fun screenPoseOf(m: FloatArray): FloatArray {
        // 行主序 3x3：第三列 = 设备 z 轴在世界中的方向，屏幕朝外，故视线取其负。
        val forward = floatArrayOf(-m[2], -m[5], -m[8])
        var right = cross(forward, WORLD_UP)
        if (length(right) < 0.2f) {
            // 视线接近竖直（抬头看顶 / 躺下），与世界上方向叉乘退化。
            // 换设备的 y 轴做参考，它此刻一定不与视线平行。
            right = cross(forward, floatArrayOf(m[1], m[4], m[7]))
            if (length(right) < 0.2f) {
                right = cross(forward, floatArrayOf(m[0], m[3], m[6]))
            }
        }
        normalize(right)
        val back = floatArrayOf(-forward[0], -forward[1], -forward[2])
        val up = cross(back, right)
        normalize(up)

        val out = FloatArray(16)
        out[0] = right[0]; out[1] = right[1]; out[2] = right[2]; out[3] = 0f
        out[4] = up[0]; out[5] = up[1]; out[6] = up[2]; out[7] = 0f
        out[8] = back[0]; out[9] = back[1]; out[10] = back[2]; out[11] = 0f
        out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
        return out
    }

    private fun cross(a: FloatArray, b: FloatArray): FloatArray = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun length(v: FloatArray): Float =
        kotlin.math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])

    private fun normalize(v: FloatArray) {
        val len = length(v)
        if (len <= 1e-6f) return
        v[0] /= len; v[1] /= len; v[2] /= len
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** 旋转矢量的世界坐标系是东-北-天，天顶即 (0,0,1)。 */
        val WORLD_UP = floatArrayOf(0f, 0f, 1f)
    }
}
