package com.gazereel.vr.core

/**
 * PRD 8.1 / 5.3：把畸变、头追、触发输入收敛到一个接口。
 * Cardboard SDK 只是首个实现；SDK 归档后可替换运行时而不改动 UI 与播放层。
 */
enum class Eye { LEFT, RIGHT }

enum class TriggerKind { CLICK, DOUBLE_CLICK, LONG_PRESS }

data class TriggerEvent(val kind: TriggerKind, val atMs: Long)

/** 每眼渲染参数。矩阵均为 OpenGL 列主序 4x4。 */
data class EyeParams(
    val viewMatrix: FloatArray,
    val projectionMatrix: FloatArray,
    val distortion: FloatArray = floatArrayOf(0f, 0f)
)

interface VrHeadsetProvider {
    /** 是否由硬件（v2 导电触控片 / v1 磁铁）产生触发事件。 */
    val supportsHardwareTrigger: Boolean

    fun start()
    fun stop()
    fun attachTriggerListener(listener: (TriggerEvent) -> Unit)

    /**
     * 头部**姿态**矩阵（设备 → 世界）。
     * 第 3 列取负即世界坐标系里的视线方向，世界锁定 UI 的准星与手势识别都基于它。
     * 注意：它不是相机视图矩阵，渲染前必须用 [viewRotationOf] 转置。
     */
    fun headRotation(): FloatArray

    fun eyeParams(eye: Eye, aspect: Float, ipdMeters: Float): EyeParams

    /**
     * C5 / V2.32：可选的桶形畸变后处理（见 [DistortionComposer]）。
     *
     * 默认 null = 左右半屏直出，画面几何零变化。返回非 null 时，VrRenderer
     * 会把两眼先画进离屏纹理，再交给它合成；SDK 的真实每眼 FOV / IPD 投影
     * 与畸变网格必须**同时**启用，只开一个会把视场和网格对不上。
     *
     * 默认实现让手写 Provider / 传感器回退路径无需改动。
     */
    fun distortionComposer(): DistortionComposer? = null

    /** 「重置正前方」（PRD 4.6）。 */
    fun recenter()

    /**
     * V2.19 / B5：把「正前方」在 [durationMs] 内**缓转**到当前姿态，而不是瞬跳。
     *
     * 瞬跳在头显里是典型的晕动触发源：整个视界在一帧里平移几十度。
     * 姿态闭环的「把正前方挪到这里」必须走这条，否则用户看到的就是闪一下。
     *
     * 默认实现退回 [recenter]（瞬跳），保证新增 Provider 时不会编译不过，
     * 但**只有真正实现了插值的 Provider 才算 B5 落地**。
     */
    fun recenterSmooth(durationMs: Long) = recenter()

    /**
     * V2.19c：把「正前方」缓转到**指定姿态**，而不是「此刻的姿态」。
     *
     * 差别只在几度，但恰好是舒适中心最要紧的那几度：姿态确认卡是给凝视点的，
     * 用户为了点到「挪到这里」必须把视线从候选方向移开几度，
     * 若按「按下那一刻的朝向」重定，正前方就永久偏了那几度。
     * 候选姿态是检测到的那一个，用它才是用户确认的那个方向。
     *
     * @param pose 设备 → 世界 的姿态矩阵；null 表示「就用此刻的姿态」。
     */
    fun recenterTo(pose: FloatArray?, durationMs: Long) = recenterSmooth(durationMs)

    /**
     * PRD FR-12「锁定视角」：冻结头追输出，画面与 UI 都停在锁定瞬间的朝向。
     * 默认空实现保证新增 Provider 时不需要立刻实现该能力。
     */
    fun setViewLocked(locked: Boolean) = Unit
}

/**
 * 姿态矩阵的逆（纯旋转，逆 = 转置），即「世界 → 设备」的视图旋转。
 *
 * 真机 V2.7.2 反馈：头向左转画面也向左转。原因是 [VrHeadsetProvider.headRotation]
 * 返回的是设备姿态 R（设备 → 世界），却被直接当成 view 矩阵塞进了 MVP。
 * 相机变换要的是 Rᵀ：世界中的点先被转到设备坐标系，才能投影。
 * 用 R 的话，物理转头 α 与矩阵旋转 α 叠加，画面会同向转 2α ——
 * 头往哪边转，画面就往哪边跑。
 *
 * 只转 3×3，平移列填 0（纯旋转不含平移）。
 */
fun viewRotationOf(pose: FloatArray): FloatArray {
    val out = FloatArray(16)
    out[0] = pose[0]; out[1] = pose[4]; out[2] = pose[8]; out[3] = 0f
    out[4] = pose[1]; out[5] = pose[5]; out[6] = pose[9]; out[7] = 0f
    out[8] = pose[2]; out[9] = pose[6]; out[10] = pose[10]; out[11] = 0f
    out[12] = 0f; out[13] = 0f; out[14] = 0f; out[15] = 1f
    return out
}
