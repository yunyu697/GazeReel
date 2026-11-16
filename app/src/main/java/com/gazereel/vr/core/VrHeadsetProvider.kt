package com.gazereel.vr.core

/**
 * alpha39 纯复刻：头追 / 光学 / 触发输入的唯一契约。
 *
 * 实现只有 `CardboardHeadsetProvider`（官方 googlevr/cardboard v1.36.0）。
 * 接口里不再为「另一条路径」预留默认实现：默认路径就是官方 SDK 的每眼矩阵、
 * 投影与桶形畸变，不存在左右半屏直出、也没有传感器回退。
 */
enum class Eye { LEFT, RIGHT }

enum class TriggerKind { CLICK, DOUBLE_CLICK, LONG_PRESS }

data class TriggerEvent(val kind: TriggerKind, val atMs: Long)

/** 每眼渲染参数。矩阵均为 OpenGL 列主序 4x4，由官方 `LensDistortion` 给出。 */
data class EyeParams(
    val viewMatrix: FloatArray,
    val projectionMatrix: FloatArray
)

interface VrHeadsetProvider {
    /** 是否由硬件（v2 导电触控片 / v1 磁铁）产生触发事件。 */
    val supportsHardwareTrigger: Boolean

    fun start()
    fun stop()
    fun attachTriggerListener(listener: (TriggerEvent) -> Unit)

    /**
     * 头部**姿态**矩阵（屏幕 → 世界；列 = 屏幕的右 / 上 / 后）。
     *
     * 约定与唯一的转置点都在 [HeadSpace]：视图矩阵 = `poseᵀ`。
     * **实现方不得自己转置** —— alpha35 真机的「抬头低头、左右转头画面方向全反，
     * 而画面本身是正的」就是「Provider 转置一次 + 渲染层再转置一次」造成的：
     * 静止时 `R ≈ Rᵀ` 看不出来，一转头两个轴一起反。
     */
    fun headRotation(): FloatArray

    /** 官方公式 `eyeView = eyeFromHead × headView`，投影来自 `getEyeProjectionMatrix`。 */
    fun eyeParams(eye: Eye): EyeParams

    /**
     * 官方桶形畸变合成链，无条件启用。
     *
     * GL 资源在首次 `beginFrame`（GL 线程）分配；本方法只在主线程造薄对象，不碰 GL。
     */
    fun distortionComposer(): DistortionComposer

    /** 「重置正前方」（PRD 4.6）。 */
    fun recenter()

    /** 只把方位角对齐到此刻朝向，俯仰与横滚保持重力对齐（V2.35）。 */
    fun recenterYaw()

    /** 把正前方在 [durationMs] 内缓转到当前姿态，避免瞬跳晕动（B5）。 */
    fun recenterSmooth(durationMs: Long)

    /** 把正前方缓转到指定姿态（姿态确认卡「挪到这里」用）。null = 此刻姿态。 */
    fun recenterTo(pose: FloatArray?, durationMs: Long)

    /** PRD FR-12「锁定视角」：冻结头追输出。 */
    fun setViewLocked(locked: Boolean)
}
