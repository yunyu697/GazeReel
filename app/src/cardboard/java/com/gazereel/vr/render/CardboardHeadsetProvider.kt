package com.gazereel.vr.render

import android.content.Context
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.EyeParams
import com.gazereel.vr.core.TriggerEvent
import com.gazereel.vr.core.TriggerKind
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.core.viewRotationOf
import com.google.cardboard.sdk.HeadTracker
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.nativetypes.EyeType

/**
 * 官方 Cardboard SDK (googlevr/cardboard v1.36.0) 的 VrHeadsetProvider 实现。
 * 仅在 app/libs/cardboard-sdk.aar 存在时参与编译（见 app/build.gradle.kts）。
 *
 * 已接入：
 * 1. 头追：HeadTracker.getPose 的传感器融合姿态（含屏幕旋转补偿）；
 * 2. 光学：LensDistortion 的每眼 eyeFromHead 与 projection 矩阵（真实 FOV / IPD）；
 * 3. 参数：QrCode.getSavedDeviceParams，未扫码时回退 Cardboard V1 参数。
 *
 * 尚未接入（独立专项，非阶段二范围）：
 * - 桶形畸变网格 getDistortionMesh / DistortionRenderer：正确实现需把每眼先渲染到
 *   离屏 FBO，再用 SDK 畸变网格做后处理，且必须按纸盒参数真机校准。阶段二保留
 *   EyeParams.distortion 预留字段，不改动已验证的直出链路，避免引入黑屏风险；
 * - viewer button：Cardboard v2 导电触控片在 Android 上表现为屏幕触摸，已由
 *   VrActivity.onTouchEvent 归一化，无需额外 View 层。
 *
 * 任何 SDK 初始化或运行期异常都会整体回退到 SensorHeadsetProvider，
 * 保证无 native 库（如 x86 模拟器）时仍可启动。
 */
class CardboardHeadsetProvider(private val context: Context) : VrHeadsetProvider {

    private val fallback = SensorHeadsetProvider(context)
    private val lock = Any()
    private val rotation = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val inverseOffset = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val position = FloatArray(3)
    private val orientation = FloatArray(4).apply { this[3] = 1f }
    private var triggerListener: ((TriggerEvent) -> Unit)? = null

    private var headTracker: HeadTracker? = null
    private var lensDistortion: LensDistortion? = null
    private var ready = false

    @Volatile
    private var viewLocked = false
    private val frozen = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    init {
        ready = runCatching {
            // 必须先初始化 native 层，再调用任何 QrCode / HeadTracker 方法。
            Initialize.initialize(context.applicationContext)
            headTracker = HeadTracker()
            val params = QrCode.getSavedDeviceParams() ?: QrCode.getCardboardV1DeviceParams()
            val dm = context.resources.displayMetrics
            lensDistortion = LensDistortion(params, dm.widthPixels, dm.heightPixels)
            true
        }.getOrDefault(false)
    }

    override val supportsHardwareTrigger: Boolean = false

    override fun start() {
        if (ready) {
            runCatching { headTracker?.resume() }.onFailure { switchToFallback() }
        } else {
            fallback.start()
        }
    }

    override fun stop() {
        if (ready) {
            runCatching { headTracker?.pause() }
            runCatching { headTracker?.close() }
            runCatching { lensDistortion?.close() }
            headTracker = null
            lensDistortion = null
        } else {
            fallback.stop()
        }
    }

    override fun attachTriggerListener(listener: (TriggerEvent) -> Unit) {
        triggerListener = listener
        fallback.attachTriggerListener(listener)
    }

    /** 与 VrActivity 的触摸 / 按键归一化通道一致（v2 触控片即屏幕触摸）。 */
    fun injectTrigger(kind: TriggerKind) {
        triggerListener?.invoke(TriggerEvent(kind, SystemClock.elapsedRealtime()))
    }

    override fun headRotation(): FloatArray {
        if (viewLocked) return synchronized(lock) { frozen.copyOf() }
        if (!ready) return fallback.headRotation()
        refreshRotation()
        val result = FloatArray(16)
        synchronized(lock) {
            Matrix.multiplyMM(result, 0, inverseOffset, 0, rotation, 0)
        }
        return result
    }

    override fun setViewLocked(locked: Boolean) {
        fallback.setViewLocked(locked)
        if (locked && !viewLocked) {
            val snapshot = headRotation()
            synchronized(lock) { System.arraycopy(snapshot, 0, frozen, 0, 16) }
        }
        viewLocked = locked
    }

    override fun eyeParams(eye: Eye, aspect: Float, ipdMeters: Float): EyeParams {
        val ld = lensDistortion
        if (!ready || ld == null) return fallback.eyeParams(eye, aspect, ipdMeters)
        return runCatching {
            val ordinal = if (eye == Eye.LEFT) EyeType.LEFT else EyeType.RIGHT
            val eyeFromHead = FloatArray(16)
            ld.getEyeFromHeadMatrix(ordinal, eyeFromHead)
            val view = FloatArray(16)
            // eyeFromHead 只含眼位偏移，需再左乘「世界 → 头部」的视图旋转。
            // V2.7.2：headRotation() 是设备姿态（设备 → 世界），视图要它的逆（转置），
            // 否则头向左转画面会跟着向左转。
            Matrix.multiplyMM(view, 0, eyeFromHead, 0, viewRotationOf(headRotation()), 0)
            // SDK 的 eyeFromHeadMatrix 已含二维码参数标定的设备 IPD；这里只叠加
            // 用户相对 62mm 基准的手动微调，不覆盖 SDK 的光学标定结果（FR-12）。
            val ipdDelta = (ipdMeters - BASE_IPD_METERS) * 0.5f
            val sign = if (eye == Eye.LEFT) -1f else 1f
            Matrix.translateM(view, 0, -sign * ipdDelta, 0f, 0f)
            val projection = FloatArray(16)
            ld.getEyeProjectionMatrix(ordinal, NEAR, FAR, projection)
            EyeParams(view, projection)
        }.getOrElse { fallback.eyeParams(eye, aspect, ipdMeters) }
    }

    /**
     * V2.4：与 SensorHeadsetProvider 保持同一语义。锁定视角时 headRotation()
     * 不再采样 HeadTracker，所以重定基准前必须显式刷新一次当前姿态；同时
     * 同步更新 frozen，否则锁定态下 recenter 不生效。
     */
    override fun recenter() {
        if (!ready) {
            fallback.recenter()
            return
        }
        refreshRotation()
        synchronized(lock) {
            val inverse = FloatArray(16)
            if (Matrix.invertM(inverse, 0, rotation, 0)) {
                System.arraycopy(inverse, 0, inverseOffset, 0, 16)
                if (viewLocked) Matrix.setIdentityM(frozen, 0)
            }
        }
    }

    /** 从 SDK 取一次姿态写入 rotation（读写都受 lock 保护）。 */
    private fun refreshRotation() {
        runCatching {
            headTracker?.getPose(System.nanoTime(), deviceOrientation(), position, orientation)
        }.getOrNull() ?: return
        val matrix = FloatArray(16)
        quaternionToMatrix(orientation, matrix)
        synchronized(lock) { System.arraycopy(matrix, 0, rotation, 0, 16) }
    }

    private fun switchToFallback() {
        ready = false
        fallback.start()
    }

    @Suppress("DEPRECATION")
    private fun deviceOrientation(): Int =
        (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0

    /** SDK 姿态四元数 (x, y, z, w) → OpenGL 列主序 4x4 旋转矩阵。 */
    private fun quaternionToMatrix(q: FloatArray, out: FloatArray) {
        val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
        val xx = x * x; val yy = y * y; val zz = z * z
        val xy = x * y; val xz = x * z; val yz = y * z
        val wx = w * x; val wy = w * y; val wz = w * z
        out[0] = 1f - 2f * (yy + zz); out[1] = 2f * (xy + wz);      out[2] = 2f * (xz - wy);      out[3] = 0f
        out[4] = 2f * (xy - wz);      out[5] = 1f - 2f * (xx + zz); out[6] = 2f * (yz + wx);      out[7] = 0f
        out[8] = 2f * (xz + wy);      out[9] = 2f * (yz - wx);      out[10] = 1f - 2f * (xx + yy); out[11] = 0f
        out[12] = 0f;                 out[13] = 0f;                  out[14] = 0f;                  out[15] = 1f
    }

    private companion object {
        const val NEAR = 0.1f
        const val FAR = 50f
        const val BASE_IPD_METERS = 0.062f
    }
}
