package com.gazereel.vr.render

import android.content.Context
import android.opengl.Matrix
import android.os.SystemClock
import android.view.Surface
import android.view.WindowManager
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.EyeParams
import com.gazereel.vr.core.RotationBlend
import com.gazereel.vr.core.TriggerEvent
import com.gazereel.vr.core.TriggerKind
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.core.viewRotationOf
import com.gazereel.vr.data.RunLog
import com.google.cardboard.sdk.HeadTracker
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.nativetypes.EyeType
import kotlin.math.acos

/**
 * 官方 Cardboard SDK (googlevr/cardboard v1.36.0) 的 VrHeadsetProvider 实现。
 * 仅在 app/libs/cardboard-sdk.aar 存在时参与编译（见 app/build.gradle.kts）。
 *
 * ## V2.30：为什么现在敢启用
 *
 * 之前一直走手写 `TYPE_ROTATION_VECTOR`，是因为这个适配层里有三个没核实的约定，
 * 全都是错的。逐一对照本地保留的官方源码后可以确定：
 *
 * 1. **getPose 的时间戳是「相对当前时刻的 delta」，不是绝对时间。**
 *    `java_api/src/main/jni/cardboard_jni.cc:382` 是
 *    `const int64_t timestamp_ns = GetBootTimeNano() + delta_time_ns;`。
 *    以前传 `System.nanoTime()`（约 1e15 ns）等于让 EKF 按角速度外推到极远未来，
 *    姿态会彻底失控。官方 `CardboardViewApi.PREDICTION_TIME_WITHOUT_VSYNC_NANOS`
 *    默认是 50_000_000（50ms），这才是「顺滑」的来源之一。
 * 2. **SDK 四元数转出来的矩阵是 head view（世界 → 头），不是设备姿态。**
 *    官方 demo `hello_cardboard_app.cc:181` 直接 `head_view_ = GetPose()`，
 *    Java 层 `CardboardView.java:1056` 把它塞进 `headTransform.getHeadView()`。
 *    工程接口 [VrHeadsetProvider.headRotation] 要的是 pose（设备 → 世界），
 *    所以必须**转置一次**；以前直接当 pose 用、再在 eyeParams 里转置一次，
 *    等于把方向整体翻了过来。
 * 3. **deviceOrientation 参数是 CardboardViewportOrientation 枚举，不是 Surface.ROTATION_*。**
 *    官方 `CardboardViewApi.setDeviceOrientation()` 的映射是
 *    ROTATION_0→2(portrait)、90→0(landscapeLeft)、180→3、270→1。
 *    以前直接传 Surface 的值，横屏会被当成 reverseLandscape，再差 180°。
 *
 * SDK 带来的东西（手写链路一件都没有）：`SensorFusionEkf` 传感器融合与陀螺零偏
 * 估计、`PredictRotation` 姿态预测、`ApplyNeckModel`、显示空间旋转补偿、
 * viewport 变化时的基准连续性。这些正是「平躺慢慢歪 / 转头拖尾 / 不顺滑」的解药。
 *
 * ## 本次边界
 *
 * **只接管头追，不改光学。** `LensDistortion` 的真实 FOV / IPD 与畸变网格会改变
 * 现有球面网格的取景范围，必须连桶形畸变专项一起校准；本次先把
 * [USE_SDK_OPTICS] 留成开关（默认 false），沿用已经验证过的分屏几何，
 * 保证画面几何零变化、只换姿态来源。
 *
 * 任何 SDK 初始化 / 运行期异常都会整体回退到 SensorHeadsetProvider，
 * 保证缺 native 库（如 x86 模拟器）时仍可启动。
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
    private var fallbackStarted = false

    /** 装配点用反射读这个 getter（getSdkActive），false 就换成纯传感器实现。 */
    val sdkActive: Boolean get() = ready && headTracker != null

    // 首次基准：进 VR 时定一次，之后不再自动改（V2.29 定下的规则，SDK 路径同样遵守）。
    private var pendingInitialRecenter = false
    private var initialDeadlineMs = 0L
    private var stableSinceMs = 0L
    private var hasLastPose = false
    private val lastPose = FloatArray(16)

    @Volatile
    private var viewLocked = false
    private val frozen = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    // V2.19 / B5：缓转重定基准，与 SensorHeadsetProvider 同一套语义。
    private var easing = false
    private var easeStartMs = 0L
    private var easeDurationMs = 800L
    private val easeFrom = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val easeTo = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private val easeScratch = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }

    init {
        ready = runCatching {
            // 必须先初始化 native 层，再调用任何 QrCode / HeadTracker 方法。
            Initialize.initialize(context.applicationContext)
            headTracker = HeadTracker()
            true
        }.getOrDefault(false)
        // 光学参数只在开关打开时才解析：QrCode.getSavedDeviceParams() 会读外部存储，
        // 让它成为头追启用的前置条件没有道理。
        if (ready && USE_SDK_OPTICS) {
            lensDistortion = runCatching {
                val params = QrCode.getSavedDeviceParams() ?: QrCode.getCardboardV1DeviceParams()
                val dm = context.resources.displayMetrics
                LensDistortion(params, dm.widthPixels, dm.heightPixels)
            }.getOrNull()
        }
        RunLog.append(
            context, "headset",
            if (sdkActive) {
                "Cardboard SDK 头追已启用 · EKF融合/零偏估计/预测 ${PREDICTION_NS / 1_000_000}ms/显示空间补偿"
            } else {
                "Cardboard SDK 初始化失败，回退纯传感器"
            }
        )
    }

    override val supportsHardwareTrigger: Boolean = false

    override fun start() {
        if (!ready) {
            startFallback()
            return
        }
        synchronized(lock) {
            pendingInitialRecenter = true
            initialDeadlineMs = SystemClock.elapsedRealtime() + INITIAL_MAX_WAIT_MS
            stableSinceMs = 0L
            hasLastPose = false
        }
        runCatching { headTracker?.resume() }.onFailure { switchToFallback() }
    }

    override fun stop() {
        if (ready) {
            runCatching { headTracker?.pause() }
            runCatching { headTracker?.close() }
            runCatching { lensDistortion?.close() }
            headTracker = null
            lensDistortion = null
            ready = false
        } else if (fallbackStarted) {
            fallback.stop()
            fallbackStarted = false
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
            Matrix.multiplyMM(result, 0, offsetNow(), 0, rotation, 0)
        }
        return result
    }

    /**
     * V2.19 / B5：当前生效的参考系偏移（缓转期间是插值结果）。
     * 视图矩阵、凝视落点都走这里，否则缓转时画面在动、准星已到终点。
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
        fallback.setViewLocked(locked)
        if (locked && !viewLocked) {
            val snapshot = headRotation()
            synchronized(lock) { System.arraycopy(snapshot, 0, frozen, 0, 16) }
        }
        viewLocked = locked
    }

    override fun eyeParams(eye: Eye, aspect: Float, ipdMeters: Float): EyeParams {
        if (!ready) return fallback.eyeParams(eye, aspect, ipdMeters)
        val ld = lensDistortion
        if (USE_SDK_OPTICS && ld != null) {
            val sdk = runCatching { sdkEyeParams(ld, eye, ipdMeters) }
                .onFailure {
                    RunLog.append(context, "headset", "SDK 光学失败，回退直出几何：${it.message}")
                }
                .getOrNull()
            if (sdk != null) return sdk
        }
        return legacyEyeParams(eye, aspect, ipdMeters)
    }

    /**
     * 与 SensorHeadsetProvider 完全相同的一套分屏几何，唯一区别是姿态来自 SDK。
     *
     * 不能整个委托给 fallback.eyeParams()：那条路径会去读 fallback 自己的传感器姿态，
     * 头追就白切了。视图必须由 [headRotation] 的结果转置得到。
     */
    private fun legacyEyeParams(eye: Eye, aspect: Float, ipdMeters: Float): EyeParams {
        val eyeX = if (eye == Eye.LEFT) -ipdMeters / 2f else ipdMeters / 2f
        val translated = FloatArray(16)
        Matrix.setLookAtM(translated, 0, eyeX, 0f, 0f, eyeX, 0f, -1f, 0f, 1f, 0f)
        val view = FloatArray(16)
        Matrix.multiplyMM(view, 0, translated, 0, viewRotationOf(headRotation()), 0)
        val projection = FloatArray(16)
        val near = 0.1f
        Matrix.frustumM(projection, 0, -near * aspect, near * aspect, -near, near, near, 50f)
        return EyeParams(view, projection)
    }

    /** SDK 真实 FOV / IPD 的每眼参数，待桶形畸变专项一起启用。 */
    private fun sdkEyeParams(ld: LensDistortion, eye: Eye, ipdMeters: Float): EyeParams {
        val ordinal = if (eye == Eye.LEFT) EyeType.LEFT else EyeType.RIGHT
        val eyeFromHead = FloatArray(16)
        ld.getEyeFromHeadMatrix(ordinal, eyeFromHead)
        val view = FloatArray(16)
        Matrix.multiplyMM(view, 0, eyeFromHead, 0, viewRotationOf(headRotation()), 0)
        val ipdDelta = (ipdMeters - BASE_IPD_METERS) * 0.5f
        val sign = if (eye == Eye.LEFT) -1f else 1f
        Matrix.translateM(view, 0, -sign * ipdDelta, 0f, 0f)
        val projection = FloatArray(16)
        ld.getEyeProjectionMatrix(ordinal, NEAR, FAR, projection)
        return EyeParams(view, projection)
    }

    /**
     * V2.4：与 SensorHeadsetProvider 保持同一语义。锁定视角时 headRotation()
     * 不再采样 HeadTracker，所以重定基准前必须显式刷新一次当前姿态。
     */
    override fun recenter() {
        if (!ready) {
            fallback.recenter()
            return
        }
        refreshRotation()
        synchronized(lock) { recenterLocked() }
    }

    /** V2.19 / B5：0.8 秒缓转，语义见 [VrHeadsetProvider.recenterSmooth]。 */
    override fun recenterSmooth(durationMs: Long) {
        recenterTo(null, durationMs)
    }

    /** V2.19c：缓转到指定姿态，语义见 [VrHeadsetProvider.recenterTo]。 */
    override fun recenterTo(pose: FloatArray?, durationMs: Long) {
        if (!ready) {
            fallback.recenterTo(pose, durationMs)
            return
        }
        refreshRotation()
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

    /**
     * 从 SDK 取一次**预测姿态**，转置成工程约定的 pose 矩阵写入 [rotation]。
     *
     * [PREDICTION_NS] 是相对当前时刻的 delta，JNI 内部会加到现在时刻上；
     * 传绝对时间会让 EKF 外推到荒谬的未来（见类注释第 1 条）。
     */
    private fun refreshRotation() {
        runCatching {
            headTracker?.getPose(PREDICTION_NS, viewportOrientation(), position, orientation)
        }.getOrNull() ?: return
        val headView = FloatArray(16)
        quaternionToMatrix(orientation, headView)
        // SDK 输出 = head view（世界 → 头）；工程接口要 pose（设备 → 世界）→ 转置 3×3。
        val pose = FloatArray(16)
        transposeRotation(headView, pose)
        var established = false
        var stableMs = 0L
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            // 按时间判定，不用帧数：headRotation() 每帧会被左右眼、准星、主线程各调一次，
            // 帧数阈值会被调用频率放大好几倍，基准会定在用户还在塞手机的时候。
            val still = hasLastPose && poseDelta(pose, lastPose) < STILL_RAD
            if (still) {
                if (stableSinceMs == 0L) stableSinceMs = now
            } else {
                stableSinceMs = 0L
            }
            stableMs = if (stableSinceMs == 0L) 0L else now - stableSinceMs
            System.arraycopy(pose, 0, lastPose, 0, 16)
            hasLastPose = true
            System.arraycopy(pose, 0, rotation, 0, 16)
            if (pendingInitialRecenter &&
                (stableMs >= STILL_MS || now >= initialDeadlineMs)
            ) {
                pendingInitialRecenter = false
                recenterLocked()
                established = true
            }
        }
        if (established) {
            RunLog.append(context, "posture", "[sdk] 首次基准 · 姿态稳定 ${stableMs}ms 后定一次")
        }
    }

    private fun recenterLocked() {
        easing = false
        val inverse = FloatArray(16)
        if (Matrix.invertM(inverse, 0, rotation, 0)) {
            System.arraycopy(inverse, 0, inverseOffset, 0, 16)
            if (viewLocked) Matrix.setIdentityM(frozen, 0)
        }
    }

    private fun startFallback() {
        if (fallbackStarted) return
        fallbackStarted = true
        fallback.start()
    }

    private fun switchToFallback() {
        ready = false
        startFallback()
        RunLog.append(context, "headset", "SDK 运行期异常，已切换到传感器头追")
    }

    /**
     * CardboardViewportOrientation 与 Surface.ROTATION_* 的**数值不同**，必须显式映射。
     * 依据：官方 `CardboardViewApi.setDeviceOrientation()` 与 `include/cardboard.h:46-67`。
     */
    @Suppress("DEPRECATION")
    private fun viewportOrientation(): Int {
        val r = (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
            ?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        return when (r) {
            Surface.ROTATION_0 -> 2   // kPortrait
            Surface.ROTATION_90 -> 0  // kLandscapeLeft（锁 landscape 的手机通常是这个）
            Surface.ROTATION_180 -> 3 // kPortraitUpsideDown
            Surface.ROTATION_270 -> 1 // kLandscapeRight
            else -> 2
        }
    }

    /** 官方 MathUtils.getRotationMatrixFromQuaternion 的等价实现（已逐项核对）。 */
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

    /** 纯旋转矩阵的逆 = 转置；这里把 SDK 的 head view 转成工程要的 pose。 */
    private fun transposeRotation(src: FloatArray, out: FloatArray) {
        out[0] = src[0]; out[1] = src[4]; out[2] = src[8];  out[3] = 0f
        out[4] = src[1]; out[5] = src[5]; out[6] = src[9];  out[7] = 0f
        out[8] = src[2]; out[9] = src[6]; out[10] = src[10]; out[11] = 0f
        out[12] = 0f;    out[13] = 0f;    out[14] = 0f;     out[15] = 1f
    }

    /** 视线夹角（第三列 = 设备 z 轴在世界中的方向）。 */
    private fun poseDelta(a: FloatArray, b: FloatArray): Float {
        val dot = (a[8] * b[8] + a[9] * b[9] + a[10] * b[10]).coerceIn(-1f, 1f)
        return acos(dot)
    }

    private companion object {
        const val NEAR = 0.1f
        const val FAR = 50f
        const val BASE_IPD_METERS = 0.062f

        /** 官方 CardboardViewApi 默认预测量；JNI 内部加到当前 boottime 上。 */
        const val PREDICTION_NS = 50_000_000L

        const val STILL_RAD = 0.01f
        const val STILL_MS = 700L
        const val INITIAL_MAX_WAIT_MS = 4_000L

        /**
         * 打开后会用 SDK 的每眼 FOV / IPD 投影，需要同时适配 ProjectionMesh 的
         * 取景范围与桶形畸变网格；未做该专项前保持 false，画面几何零变化。
         */
        const val USE_SDK_OPTICS = false
    }
}
