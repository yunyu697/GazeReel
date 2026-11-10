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

    /** 「重置正前方」（PRD 4.6）。 */
    fun recenter()

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
