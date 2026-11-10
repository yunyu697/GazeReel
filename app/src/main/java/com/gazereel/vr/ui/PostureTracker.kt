package com.gazereel.vr.ui

import kotlin.math.abs
import kotlin.math.asin

/**
 * V2.19 / B4：姿态闭环 —— 只负责「发现姿势变了」，不负责动画面。
 *
 * ## 为什么是「先问再动」而不是自动重定
 *
 * 3DOF 只有手机姿态，**无法区分「整个人换了姿势」和「头相对身体转了一下」**。
 * 看 360 内容时长时间抬头就是常态，任何「重力偏离 + 稳定时长」的自动判据
 * 都可能把用户正在看的那一段抢走。所以这里只做两件事：
 *
 * 1. 检测到稳定变化 → **只标记候选**，画面一帧不动；
 * 2. 用户凝视确认 → 才交给 Provider 做 0.8 秒缓转。
 *
 * 不确认就什么都不发生，候选 6 秒后自己消失。
 *
 * ## 为什么参考点永远是 0
 *
 * 「正前方」不是某个固定角度，而是**当前参考系的 0 度**。缓转重定基准之后，
 * 用户当时的朝向就变成了 0，所以判据不需要记住历史角度——
 * 这里只跟 `pitch` 本身比，基准由 Provider 的 `inverseOffset` 负责。
 */
class PostureTracker {

    companion object {
        /** 超过这个角度才算「换了姿势」。太小会被正常转头触发。 */
        const val DEADBAND_RAD = 0.70f          // 约 40°

        /** 稳定判据期间允许的抖动。手抖、呼吸都在这个量级以下。 */
        const val JITTER_RAD = 0.14f            // 约 8°

        /** 要稳定这么久才认为「真的换姿势了」。 */
        const val STABLE_MS = 1_500L

        /** 候选没人理就自己消失，不能长期挂在画面里挡着。 */
        const val CANDIDATE_TTL_MS = 6_000L

        /** 缓转时长。瞬跳会晕，太长又像卡住。 */
        const val ANIM_MS = 800L

        /**
         * 缓转结束后还要静默多久。
         *
         * 缓转的 0.8 秒里参考系在动，`pitch` 从候选值一路回到 0。
         * 如果此时还在判候选，缓动刚结束就会拿残差当场再弹一次确认。
         */
        const val SETTLE_MS = 600L
    }

    /** 非 null 表示「检测到了新姿势，等用户确认」。值是候选俯仰角。 */
    var candidate: Float? = null
        private set

    private var candidateShownAt = 0L
    private var stableAnchor = 0f
    private var stableSince = 0L

    /** 缓转期间 + 落定静默期：只跟姿态，不产生候选。 */
    private var settleUntilMs = 0L

    /**
     * 从头部姿态矩阵里取俯仰角。
     *
     * `headRotation()` 的第 3 列取负是视线方向（世界坐标，y 向上），
     * 所以 `pitch = asin(forward.y)`：抬头为正，低头为负。
     */
    fun pitchOf(rotation: FloatArray): Float {
        if (rotation.size < 11) return 0f
        val y = -rotation[9]
        return asin(y.coerceIn(-1f, 1f))
    }

    /**
     * 每帧调用（主线程，约 60Hz）。
     *
     * @return true 表示候选状态变了，UI 需要重画。
     */
    fun onPose(pitch: Float, nowMs: Long): Boolean {
        // 缓转中 / 刚落定：只清状态，不产生新候选。
        if (nowMs < settleUntilMs) {
            stableSince = 0L
            if (candidate != null) {
                candidate = null
                return true
            }
            return false
        }

        // 候选已经挂着：超时就撤掉，别一直挡着画面。
        val current = candidate
        if (current != null) {
            if (nowMs - candidateShownAt > CANDIDATE_TTL_MS) {
                candidate = null
                stableSince = 0L
                return true
            }
            if (abs(pitch - current) > JITTER_RAD * 2f) {
                // 用户又动了，候选作废，重新开始观察。
                candidate = null
                stableSince = 0L
                return true
            }
            return false
        }

        if (abs(pitch) < DEADBAND_RAD) {
            stableSince = 0L
            return false
        }

        if (stableSince == 0L) {
            stableSince = nowMs
            stableAnchor = pitch
            return false
        }
        // 还在动：把锚点推到现在，重新计时。
        if (abs(pitch - stableAnchor) > JITTER_RAD) {
            stableSince = nowMs
            stableAnchor = pitch
            return false
        }
        if (nowMs - stableSince >= STABLE_MS) {
            candidate = pitch
            candidateShownAt = nowMs
            stableSince = 0L
            return true
        }
        return false
    }

    /**
     * 用户确认：进入静默期，真正的缓转由调用方交给 Provider。
     *
     * @return false 表示没有候选（按键误触），调用方不该做任何事。
     */
    fun confirm(nowMs: Long): Boolean {
        if (candidate == null) return false
        candidate = null
        stableSince = 0L
        settleUntilMs = nowMs + ANIM_MS + SETTLE_MS
        return true
    }

    /** 用户没理它，或者换了屏：撤掉候选。 */
    fun dismiss() {
        candidate = null
        stableSince = 0L
    }

    /** 「重置正前方」：基准归零，候选与静默期一起清掉。 */
    fun reset() {
        candidate = null
        stableSince = 0L
        settleUntilMs = 0L
    }

    /** 候选还有多久消失，用来画一条细进度条。 */
    fun candidateRemainingMs(nowMs: Long): Long =
        (CANDIDATE_TTL_MS - (nowMs - candidateShownAt)).coerceAtLeast(0L)
}
