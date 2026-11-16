package com.gazereel.vr.render

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.SystemClock
import android.view.Display
import android.view.Surface
import com.gazereel.vr.core.DistortionComposer
import com.gazereel.vr.core.Eye
import com.gazereel.vr.core.EyeParams
import com.gazereel.vr.core.HeadRig
import com.gazereel.vr.core.HeadSpace
import com.gazereel.vr.core.TriggerEvent
import com.gazereel.vr.core.TriggerKind
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.data.RunLog
import com.google.cardboard.sdk.HeadTracker
import com.google.cardboard.sdk.Initialize
import com.google.cardboard.sdk.LensDistortion
import com.google.cardboard.sdk.QrCode
import com.google.cardboard.sdk.nativetypes.EyeType

/**
 * alpha39：**纯复刻** —— 官方 Cardboard SDK (googlevr/cardboard v1.36.0) 作为唯一底座。
 *
 * ## 这一版只做一件事：照抄官方，然后整合
 *
 * 官方 `CardboardViewApi` / `hello_cardboard` 的渲染循环是：
 * ```
 * HeadTracker.getPose(delta, viewportOrientation, position, orientation)
 * headView = quaternion → matrix
 * for each eye:
 *     eyeView    = LensDistortion.getEyeFromHeadMatrix(eye) × headView
 *     projection = LensDistortion.getEyeProjectionMatrix(eye, near, far)
 *     draw(projection × eyeView × model) 到全屏离屏纹理的对应半区
 * DistortionRenderer.renderEyeToDisplay(...)  // 桶形畸变合成到屏幕
 * ```
 * 这里逐行照它，没有自研离轴视锥、没有半屏直出、没有开关、没有传感器兜底。
 *
 * ## 与官方唯一的差别（「整合」而非「重写」）
 *
 * 官方 Java 层没有暴露 `CardboardHeadTracker_recenter()`，所以「重置正前方」用
 * [HeadRig] 维护一个参考系旋转：`headView = (offset · pose)ᵀ`。这是把官方
 * Java API 的缺口补上，不是另造骨架。UI / 凝视 / 手势仍读 [headRotation] 的 pose。
 *
 * ## 三个必须遵守的 SDK 约定
 *
 * 1. `getPose` 第 1 参数是**相对当前时刻的 delta（纳秒）**，JNI 内部会加到当前
 *    boottime 上；传绝对时间会让 EKF 外推到荒谬的未来。
 * 2. `deviceOrientation` 是 `CardboardViewportOrientation` 枚举，不是 `Surface.ROTATION_*`：
 *    0→2(portrait)、90→0(landscapeLeft)、180→3、270→1。
 * 3. SDK 四元数矩阵是 **view 矩阵**（世界→头），不是 pose：官方
 *    `HeadTransform.getForwardVector()` 的注释写明用「矩阵的逆」作用于 (0,0,-1)
 *    才得到世界系视线。工程 `headRotation()` 的契约是 pose（设备→世界），
 *    所以 [refresh] 在把姿态交给 [HeadRig] 前转置一次；渲染时
 *    [HeadSpace.viewMatrix] 再转置回去，净结果等于官方的 headView = M，
 *    公式 `eyeView = eyeFromHead × M` 与 `CardboardView.applyHeadView` 逐字一致。
 * 4. `Initialize.initialize()` 必须传 **Activity**（或包装它的 Context）：官方 Javadoc
 *    列出会调用 `getDisplay()`，native 侧 `ScreenParamsUtils.getScreenPixelDensity()`
 *    在 API 30+ 对 applicationContext 会抛 `UnsupportedOperationException`，
 *    而 JNI 没清这个 pending exception 就继续 `GetFieldID`，CheckJNI 直接 SIGABRT。
 *
 * **本版不预设任何兜底。** 初始化失败或方向不对，都是复刻之后才需要观察并处理的问题。
 */
class CardboardHeadsetProvider(private val context: Context) : VrHeadsetProvider {

    private val rig = HeadRig()

    /** `getPose` 会被主线程（姿态轮询）与 GL 线程（每眼参数）调用，共用缓冲区，必须串行。 */
    private val sdkLock = Any()

    private val position = FloatArray(3)
    private val orientation = FloatArray(4).apply { this[3] = 1f }

    private var triggerListener: ((TriggerEvent) -> Unit)? = null
    private var headTracker: HeadTracker? = null

    /** 纸盒光学参数：每眼矩阵、投影、畸变网格的唯一来源。 */
    private val lensDistortion: LensDistortion

    /** 官方 CardboardViewApi 构造里用 DisplayManager 拿默认 Display，不依赖已弃用的 defaultDisplay。 */
    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    /** 装配点用反射读这个 getter（getSdkActive）。 */
    val sdkActive: Boolean get() = headTracker != null

    init {
        // 必须先初始化 native 层，再调用任何 QrCode / HeadTracker 方法。
        // 官方 Initialize.initialize() 要求 Activity：native 侧会调 context.getDisplay()，
        // 传 applicationContext 在 API 30+ 会抛异常并触发 CheckJNI abort（alpha41 真机复现）。
        Initialize.initialize(context)
        headTracker = HeadTracker()
        // 用户没扫过码时用 SDK 内置的 Cardboard V1 参数，不依赖外部存储。
        val params = QrCode.getSavedDeviceParams() ?: QrCode.getCardboardV1DeviceParams()
        val dm = context.resources.displayMetrics
        lensDistortion = LensDistortion(params, dm.widthPixels, dm.heightPixels)
        RunLog.append(
            context, "headset",
            "mode=cardboard · 官方 SDK 唯一底座（头追 + 每眼矩阵 + 投影 + 桶形畸变）"
        )
    }

    override val supportsHardwareTrigger: Boolean = false

    override fun start() {
        rig.beginSession()
        headTracker?.resume()
    }

    override fun stop() {
        runCatching { headTracker?.pause() }
        runCatching { headTracker?.close() }
        runCatching { lensDistortion.close() }
        headTracker = null
    }

    override fun attachTriggerListener(listener: (TriggerEvent) -> Unit) {
        triggerListener = listener
    }

    /** 与 VrActivity 的触摸 / 按键归一化通道一致（v2 触控片即屏幕触摸）。 */
    fun injectTrigger(kind: TriggerKind) {
        triggerListener?.invoke(TriggerEvent(kind, SystemClock.elapsedRealtime()))
    }

    override fun headRotation(): FloatArray {
        refresh()
        return rig.pose()
    }

    override fun setViewLocked(locked: Boolean) = rig.setLocked(locked)

    override fun recenter() {
        refresh()
        rig.recenter()
    }

    override fun recenterSmooth(durationMs: Long) = recenterTo(null, durationMs)

    override fun recenterTo(pose: FloatArray?, durationMs: Long) {
        refresh()
        rig.recenterTo(pose, durationMs)
    }

    override fun recenterYaw() {
        refresh()
        rig.recenterYaw()
    }

    /** 官方畸变合成链，无条件启用。GL 资源在首次 beginFrame（GL 线程）才分配。 */
    override fun distortionComposer(): DistortionComposer =
        SdkDistortionComposer(lensDistortion)

    /**
     * 官方每眼公式：`eyeView = eyeFromHead × headView`，投影来自 SDK 纸盒参数。
     *
     * 眼位、FOV、IPD 全由 `LensDistortion` 按纸盒参数给出——这正是「直接用成熟方案
     * 替代」要摘掉的那层自研离轴几何，所以接口上连 `aspect` / `ipdMeters` 都不再传。
     */
    override fun eyeParams(eye: Eye): EyeParams {
        refresh()
        val ordinal = if (eye == Eye.LEFT) EyeType.LEFT else EyeType.RIGHT
        val eyeFromHead = FloatArray(16)
        lensDistortion.getEyeFromHeadMatrix(ordinal, eyeFromHead)
        val view = HeadSpace.multiply(eyeFromHead, HeadSpace.viewMatrix(rig.pose()))
        val projection = FloatArray(16)
        lensDistortion.getEyeProjectionMatrix(ordinal, NEAR, FAR, projection)
        return EyeParams(view, projection)
    }

    /** 取一帧预测姿态交给 [HeadRig]。两个线程共用 JNI 缓冲区，串行执行。 */
    private fun refresh() {
        synchronized(sdkLock) {
            val tracker = headTracker ?: return
            tracker.getPose(PREDICTION_NS, viewportOrientation(), position, orientation)
            val headView = FloatArray(16)
            quaternionToMatrix(orientation, headView)
            // SDK 给的是 view（世界→头）。工程 headRotation() 的契约是 pose（设备→世界），
            // 且 UI / 凝视 / PostureTracker 都按「第 3 列取负 = 世界系视线」来读，
            // 所以在 Provider 边界转置一次。eyeParams() 的 HeadSpace.viewMatrix() 再转回去，
            // 渲染侧最终拿到的仍是官方 headView = M，不会把方向翻过来。
            val pose = HeadSpace.transpose(headView)
            if (rig.submit(pose)) {
                RunLog.append(context, "posture", "[sdk] 首次基准 · 姿态稳定后定一次")
            }
        }
    }

    /** `CardboardViewportOrientation` 与 `Surface.ROTATION_*` 数值不同，必须显式映射。 */
    @Suppress("DEPRECATION")
    private fun viewportOrientation(): Int = when (surfaceRotation()) {
        Surface.ROTATION_0 -> 2   // kPortrait
        Surface.ROTATION_90 -> 0  // kLandscapeLeft（锁 landscape 的手机通常是这个）
        Surface.ROTATION_180 -> 3 // kPortraitUpsideDown
        Surface.ROTATION_270 -> 1 // kLandscapeRight
        else -> 2
    }

    @Suppress("DEPRECATION")
    private fun surfaceRotation(): Int = runCatching {
        displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
    }.getOrDefault(Surface.ROTATION_0)

    /**
     * 官方 `MathUtils.getRotationMatrixFromQuaternion` 的等价实现，逐项核对过
     * v1.36.0 源码（列主序）。输出的就是官方公式里的 headView = M，
     * 即 `CardboardView.getPose()` → `MathUtils.getMatrixFromPose()` 的旋转部分。
     * 这里**不做转置**；约定转换统一在 [refresh] 的 Provider 边界完成。
     */
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

        /** 官方 CardboardViewApi 默认预测量；JNI 内部加到当前 boottime 上。 */
        const val PREDICTION_NS = 50_000_000L
    }
}
