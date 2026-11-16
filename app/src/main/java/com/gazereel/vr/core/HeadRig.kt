package com.gazereel.vr.core

import kotlin.math.atan2
import kotlin.math.hypot

/**
 * alpha38：头追运行时的**唯一实现**。
 *
 * 上游（官方 SDK）只负责「给我一帧原始姿态」，其余全部在这里：
 * 参考系偏移、首次基准、缓转重定、只转 Y 轴、锁定视角。
 * 每眼 view / projection / 畸变由官方 `LensDistortion` 提供，本类不碰光学。
 *
 * ## 为什么要把这些从两个 Provider 里搬出来
 *
 * 之前 `SensorHeadsetProvider` 与 `CardboardHeadsetProvider` 各写了一遍同样的
 * ~200 行（`inverseOffset` / `easing` / `frozen` / `recenterTo` 的 `O' = H⁻¹·O`）。
 * 两份已经分叉：`rotation` 一个存 pose、一个存过 poseᵀ；首次基准一个按帧数、
 * 一个按毫秒。任何一次改动都得改两处，改漏一处就出现「一个路径正常、另一个反向」。
 *
 * 现在只有一份，且**不依赖 Android**：时间通过 [clock] 注入，矩阵运算在 [HeadSpace]，
 * 所以 `tools/run-pose-check.sh` 能在 JVM 上直接跑它。
 *
 * ## 参考系模型
 *
 * `pose = offset · raw`。`offset` 是一个纯旋转：
 * - `recenter()` 让「此刻的朝向」成为正前方 → `offset = rawᵀ`；
 * - `recenterTo(H)` 让参考系里的姿态 H 成为正前方 → 解 `O'·raw = I` 且 `O·raw = H`
 *   得 `O' = H⁻¹·O`（**不是** `H⁻¹`，那会多乘一次 O，落点偏几十度）；
 * - `recenterYaw()` 只左乘 `R_y(ψ)`，保留俯仰与横滚的重力对齐。
 */
class HeadRig(private val clock: () -> Long = { System.nanoTime() / 1_000_000L }) {

    private val lock = Any()

    /** 上游最近一帧原始姿态（屏幕 → 世界）。 */
    private val raw = HeadSpace.identity()

    /** 参考系偏移。`pose = offset · raw`。 */
    private val offset = HeadSpace.identity()

    /** 锁定视角时冻结的 pose（参考系里的姿态，不是原始姿态）。 */
    private val frozen = HeadSpace.identity()

    private var locked = false
    private var hasSample = false

    // 首次基准：进 VR 时定一次，之后不再自动改。
    private var pendingInitial = false
    private var initialDeadlineMs = 0L
    private var stableSinceMs = 0L
    private val lastRaw = HeadSpace.identity()
    private var hasLastRaw = false

    // 缓转重定：起点/终点都是「参考系偏移」，中间对旋转做 slerp。
    private var easing = false
    private var easeStartMs = 0L
    private var easeDurationMs = 1L
    private val easeFrom = HeadSpace.identity()
    private val easeTo = HeadSpace.identity()
    private val easeScratch = HeadSpace.identity()

    companion object {
        /** 两次采样之间转角小于它算「没动」。约 0.6°，覆盖传感器噪声。 */
        private const val STILL_DEG = 0.6f

        /** 连续静止多久才认为「戴稳了」。 */
        private const val STILL_MS = 600L

        /** 最长等待；超时按当前姿态定，不能让用户一直没有正前方。 */
        private const val INITIAL_MAX_WAIT_MS = 4_000L

        /** 视线水平分量下限：低于它不做方位角对齐，避免正上/正下时放大噪声。 */
        private const val YAW_MIN_HORIZONTAL = 0.15f
    }

    /** 进入 VR：挂上首次基准。 */
    fun beginSession() {
        synchronized(lock) {
            pendingInitial = true
            initialDeadlineMs = clock() + INITIAL_MAX_WAIT_MS
            stableSinceMs = 0L
            hasLastRaw = false
        }
    }

    /**
     * 提交一帧原始姿态（屏幕 → 世界，必须是合法旋转）。
     *
     * @return 这一帧是否定下了首次基准（调用方据此写一条日志）
     */
    fun submit(pose: FloatArray): Boolean {
        synchronized(lock) {
            System.arraycopy(pose, 0, raw, 0, 16)
            hasSample = true
            if (!pendingInitial) return false

            val now = clock()
            val still = hasLastRaw && HeadSpace.rotationAngleDeg(pose, lastRaw) < STILL_DEG
            if (still) {
                if (stableSinceMs == 0L) stableSinceMs = now
            } else {
                stableSinceMs = 0L
            }
            System.arraycopy(pose, 0, lastRaw, 0, 16)
            hasLastRaw = true

            val stableMs = if (stableSinceMs == 0L) 0L else now - stableSinceMs
            if (stableMs >= STILL_MS || now >= initialDeadlineMs) {
                pendingInitial = false
                recenterLocked()
                return true
            }
            return false
        }
    }

    /** 当前生效的姿态（参考系里的）。锁定时返回冻结值。 */
    fun pose(): FloatArray {
        synchronized(lock) {
            if (locked) return frozen.copyOf()
            return HeadSpace.multiply(offsetNowLocked(), raw)
        }
    }

    /** 最近一帧原始姿态（诊断用，不参与渲染）。 */
    fun rawSnapshot(): FloatArray = synchronized(lock) { raw.copyOf() }

    /** 参考系的「上」：偏移矩阵作用在世界天顶上，即它的第 3 列（诊断用）。 */
    fun referenceUp(out: FloatArray) {
        synchronized(lock) {
            out[0] = offset[8]; out[1] = offset[9]; out[2] = offset[10]
        }
    }

    /** 「重置正前方」：把此刻朝向设为正前方（瞬跳）。 */
    fun recenter() {
        synchronized(lock) {
            pendingInitial = false
            easing = false
            recenterLocked()
        }
    }

    /**
     * 缓转到指定姿态。`pose == null` 表示「就用此刻的朝向」。
     *
     * @param pose 参考系里的姿态（即 [pose] 的返回值），不是原始姿态。
     */
    fun recenterTo(pose: FloatArray?, durationMs: Long) {
        synchronized(lock) {
            pendingInitial = false
            val off = offsetNowLocked()
            val h = if (pose != null) pose.copyOf() else HeadSpace.multiply(off, raw)
            val target = HeadSpace.multiply(HeadSpace.transpose(h), off)
            System.arraycopy(off, 0, easeFrom, 0, 16)
            System.arraycopy(target, 0, easeTo, 0, 16)
            easeStartMs = clock()
            easeDurationMs = durationMs.coerceAtLeast(1L)
            easing = true
            if (locked) System.arraycopy(HeadSpace.identity(), 0, frozen, 0, 16)
        }
    }

    /** 0.8 秒缓转，语义见 [VrHeadsetProvider.recenterSmooth]。 */
    fun recenterSmooth(durationMs: Long) = recenterTo(null, durationMs)

    /**
     * 只把方位角对齐到此刻朝向，俯仰与横滚保持重力对齐。
     *
     * 数学上是对参考系偏移**左乘**一个世界 Y 轴旋转：`O' = R_y(ψ)·O`，
     * 于是 `H' = R_y(ψ)·H`，水平分量归零而地平线仍是平的。
     * 视线接近正上/正下时水平投影趋零，方位角没有意义——这时直接不动，
     * 宁可这次不对齐，也不制造一次翻转。
     */
    fun recenterYaw() {
        synchronized(lock) {
            pendingInitial = false
            val h = if (locked) frozen.copyOf() else HeadSpace.multiply(offset, raw)
            val fx = -h[8]
            val fz = -h[10]
            if (hypot(fx, fz) < YAW_MIN_HORIZONTAL) return
            val psi = atan2(fx, -fz)
            val rotY = HeadSpace.rotYRad(psi)
            val updated = HeadSpace.multiply(rotY, offset)
            System.arraycopy(updated, 0, offset, 0, 16)
            easing = false
            if (locked) {
                System.arraycopy(HeadSpace.multiply(rotY, h), 0, frozen, 0, 16)
            }
        }
    }

    /** PRD FR-12「锁定视角」：冻结姿态输出，画面与 UI 都停在锁定瞬间的朝向。 */
    fun setLocked(value: Boolean) {
        synchronized(lock) {
            if (value && !locked) {
                val current =
                    if (hasSample) HeadSpace.multiply(offsetNowLocked(), raw)
                    else HeadSpace.identity()
                System.arraycopy(current, 0, frozen, 0, 16)
            }
            locked = value
        }
    }

    /** 当前生效的参考系偏移；缓转期间是插值结果。调用方需持有 [lock]。 */
    private fun offsetNowLocked(): FloatArray {
        if (!easing) return offset
        val t = ((clock() - easeStartMs).toFloat() / easeDurationMs).coerceIn(0f, 1f)
        if (t >= 1f) {
            easing = false
            System.arraycopy(easeTo, 0, offset, 0, 16)
            return offset
        }
        RotationBlend.slerp(easeFrom, easeTo, t, easeScratch)
        return easeScratch
    }

    /** 把当前姿态设为「正前方」。调用方需持有 [lock]。 */
    private fun recenterLocked() {
        easing = false
        System.arraycopy(HeadSpace.transpose(raw), 0, offset, 0, 16)
        if (locked) System.arraycopy(HeadSpace.identity(), 0, frozen, 0, 16)
    }
}
