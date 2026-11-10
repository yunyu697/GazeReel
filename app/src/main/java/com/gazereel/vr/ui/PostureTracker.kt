package com.gazereel.vr.ui

import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2

/**
 * V2.19c：姿态闭环 —— 只负责「发现姿势从一个**稳定姿态**换到了另一个稳定姿态」。
 *
 * ## 基准不是某个角度，是当前参考系的 0 度
 *
 * `headRotation()` 已经乘过 Provider 的 `inverseOffset`，所以「正前方」在它里面恒等于
 * (0, 0, −1)。判据因此不需要记住任何历史角度：**偏离量 = 视线与参考系正前方的夹角**，
 * 而参考系换了（缓转 / 重定）它就自动归零。
 *
 * ## 第一个稳定姿态就是正前方
 *
 * 这是 V2.19c 的关键一条。真实顺序是**先躺好、再把手机塞进纸盒**，而 Provider 的首次
 * 重定发生在第一帧传感器数据到达时——那一刻用户还在往盒子里塞手机，正前方被定成了
 * 一个随便的方向。等躺好之后，若只靠「检测到 40° 变化就提示」，用户会先被问一次；
 * 若那次提示又被错过（没上膛、6 秒过期），就永远没有第二次机会——真机反馈
 * 「B4 完全没功能」就是这个。
 *
 * 所以进 VR 后先等第一个稳定姿态（[BASELINE_MS]），**静默**把它定成正前方。
 * 用户躺好、戴稳、不动的那一瞬间，画面自己转过来对着他，不需要任何操作。
 * 之后才是「稳定 → 稳定」的切换检测。
 *
 * ## 忽略也是一种回答
 *
 * 看 360 内容时抬头 40° 看天顶是常态。候选过期或用户点「不用了」之后，
 * 如果继续按「偏离 40°」判，1.5 秒后会再问一遍，变成每 7.5 秒弹一次。
 * 所以放弃过的方向记在 [declined] 里，只要还在它附近就不再问；走开 15° 以上才重新武装。
 */
class PostureTracker {

    companion object {
        /** 超过这个角度才算「换了姿势」。太小会被正常转头触发。 */
        const val DEADBAND_RAD = 0.70f          // 约 40°

        /** 稳定判据期间允许的抖动。手抖、呼吸都在这个量级以下。 */
        const val JITTER_RAD = 0.14f            // 约 8°

        /** 要稳定这么久才认为「真的换姿势了」。 */
        const val STABLE_MS = 1_500L

        /**
         * 首次建立基准要求的稳定时长。
         *
         * 比 [STABLE_MS] 短：用户戴好之后不该干等 1.5 秒才看到正确的画面。
         * 也不能太短——塞手机、拉头带的过程本身就慢，太短会把「正在动」当成「已经躺好」。
         */
        const val BASELINE_MS = 900L

        /**
         * 首次基准的容差。
         *
         * 小于这个角度就不动画面了：进 VR 后本来就坐在那儿看片库的话，
         * 首次重定给的方向已经是对的，再转一次纯属打扰。
         */
        const val BASELINE_DEADBAND_RAD = 0.21f // 约 12°

        /** 候选没人理就自己消失，不能长期挂在画面里挡着。 */
        const val CANDIDATE_TTL_MS = 6_000L

        /** 缓转时长。瞬跳会晕，太长又像卡住。 */
        const val ANIM_MS = 800L

        /**
         * 缓转结束后还要静默多久。
         *
         * 缓转的 0.8 秒里参考系在动，偏离量从候选值一路回到 0。
         * 如果此时还在判候选，缓动刚结束就会拿残差当场再弹一次确认。
         */
        const val SETTLE_MS = 600L
    }

    /** 非 null 表示「检测到了新姿势，等用户确认」。值是候选俯仰角（给 UI 说人话用）。 */
    var candidate: Float? = null
        private set

    /** 候选的偏航分量。只说俯仰会把「侧躺偏左 40°」说成「你现在不是正对画面」。 */
    var candidateYaw: Float = 0f
        private set

    /**
     * 候选那个**姿态矩阵**本身（设备 → 世界）。
     *
     * 确认时要把正前方挪到「这个姿态」，而不是「用户按下确认那一刻的朝向」——
     * 因为要确认就得把视线移到按钮上，那一下会带上几度的偏移，
     * 而「舒适中心」差 8° 就白调了。
     */
    var candidatePose: FloatArray? = null
        private set

    /** 首次基准是否已经建立。建立之前不产生候选。 */
    var baselineReady = false
        private set

    /**
     * 非 null 表示「第一个稳定姿态到了，请静默把它定成正前方」。
     * 调用方完成缓转后要调 [baselineEstablished]。
     */
    var pendingBaseline: FloatArray? = null
        private set

    private var candidateShownAt = 0L
    private var anchor = FloatArray(3)
    private var anchorValid = false
    private var stableSince = 0L

    /** 缓转期间 + 落定静默期：只跟姿态，不产生候选。 */
    private var settleUntilMs = 0L

    /** 用户放弃过（过期 / 点「不用了」）的那个方向。它附近不再追问。 */
    private var declined: FloatArray? = null

    // ── 姿态读数 ────────────────────────────────────────────────────────

    /** 抬头为正、低头为负。`headRotation()` 第 3 列取负是视线方向。 */
    fun pitchOf(rotation: FloatArray): Float {
        if (rotation.size < 11) return 0f
        return asin((-rotation[9]).coerceIn(-1f, 1f))
    }

    /** 向右为正、向左为负。 */
    fun yawOf(rotation: FloatArray): Float {
        if (rotation.size < 11) return 0f
        return atan2(-rotation[8], rotation[10])
    }

    /** 视线与当前正前方的夹角（弧度）。这个量就是「偏了多少」。 */
    fun offsetOf(rotation: FloatArray): Float {
        if (rotation.size < 11) return 0f
        return acos(rotation[10].coerceIn(-1f, 1f))
    }

    private fun forwardOf(rotation: FloatArray): FloatArray =
        floatArrayOf(-rotation[8], -rotation[9], -rotation[10])

    private fun angleBetween(a: FloatArray, b: FloatArray): Float =
        acos((a[0] * b[0] + a[1] * b[1] + a[2] * b[2]).coerceIn(-1f, 1f))

    /**
     * 姿态是否已经稳定了 [needMs]。
     *
     * 锚点会跟着漂移走：抖超过 [JITTER_RAD] 就把锚点推到现在、重新计时。
     * 这样「慢慢转头」也不会被当成稳定——它每隔几帧就会把锚点推走。
     */
    private fun stable(forward: FloatArray, nowMs: Long, needMs: Long): Boolean {
        if (!anchorValid) {
            anchor = forward.copyOf()
            anchorValid = true
            stableSince = nowMs
            return false
        }
        if (angleBetween(anchor, forward) > JITTER_RAD) {
            anchor = forward.copyOf()
            stableSince = nowMs
            return false
        }
        return nowMs - stableSince >= needMs
    }

    // ── 每帧 ────────────────────────────────────────────────────────────

    /**
     * 每帧调用（主线程，约 60Hz）。
     *
     * @param allowBaseline 是否还允许静默建立首次基准。用户一旦按过触发键
     *   （「我戴稳了，开始」）就该传 false：那时候他已经开始看内容了，
     *   再无声地把画面转几十度就是「抢走他正在看的东西」。万一此刻的基准是错的，
     *   40° 候选路径会把它问回来，不会把人卡死。
     * @return true 表示候选状态变了，UI 需要重画。
     */
    fun onPose(rotation: FloatArray, nowMs: Long, allowBaseline: Boolean = true): Boolean {
        if (rotation.size < 11) return false
        val forward = forwardOf(rotation)

        // ① 建立基准：进 VR 后的第一个稳定姿态。
        if (!baselineReady) {
            if (!allowBaseline) {
                baselineReady = true
                anchorValid = false
                return false
            }
            if (!stable(forward, nowMs, BASELINE_MS)) return false
            if (offsetOf(rotation) > BASELINE_DEADBAND_RAD) {
                pendingBaseline = rotation.copyOf()
            } else {
                // 已经正对着了，不必动画面。
                baselineReady = true
            }
            anchorValid = false
            return false
        }

        // ② 缓转中 / 刚落定：只清状态，不产生新候选。
        if (nowMs < settleUntilMs) {
            anchorValid = false
            if (candidate != null) {
                clearCandidate()
                return true
            }
            return false
        }

        // ③ 候选已经挂着。
        val pose = candidatePose
        if (pose != null) {
            if (nowMs - candidateShownAt > CANDIDATE_TTL_MS) {
                // 忽略也是一种回答：记下这个方向，别追着问第二遍。
                declined = forward
                clearCandidate()
                return true
            }
            // 用户又动了，候选作废，重新开始观察。
            if (angleBetween(forwardOf(pose), forward) > JITTER_RAD * 2f) {
                clearCandidate()
                anchorValid = false
                return true
            }
            return false
        }

        // ④ 放弃过的方向，附近不再追问；走开了才重新武装。
        val skip = declined
        if (skip != null) {
            if (angleBetween(skip, forward) < JITTER_RAD * 2f) return false
            declined = null
        }

        // ⑤ 偏离不够，或者还没稳定下来。
        if (offsetOf(rotation) < DEADBAND_RAD) {
            anchorValid = false
            return false
        }
        if (!stable(forward, nowMs, STABLE_MS)) return false

        candidate = pitchOf(rotation)
        candidateYaw = yawOf(rotation)
        candidatePose = rotation.copyOf()
        candidateShownAt = nowMs
        anchorValid = false
        return true
    }

    private fun clearCandidate() {
        candidate = null
        candidateYaw = 0f
        candidatePose = null
    }

    // ── 状态迁移 ────────────────────────────────────────────────────────

    /** 调用方已经把 [pendingBaseline] 缓转过去了。 */
    fun baselineEstablished(nowMs: Long) {
        baselineReady = true
        pendingBaseline = null
        anchorValid = false
        declined = null
        settleUntilMs = nowMs + ANIM_MS + SETTLE_MS
    }

    /**
     * 用户确认：进入静默期，并交出**候选姿态**。
     *
     * @return null 表示没有候选（按键误触），调用方不该做任何事。
     */
    fun confirm(nowMs: Long): FloatArray? {
        val pose = candidatePose ?: return null
        clearCandidate()
        anchorValid = false
        declined = null
        settleUntilMs = nowMs + ANIM_MS + SETTLE_MS
        return pose
    }

    /** 用户没理它 / 点了「不用了」：记下这个方向，不再追问。 */
    fun dismiss(rotation: FloatArray) {
        if (rotation.size >= 11) declined = forwardOf(rotation)
        clearCandidate()
        anchorValid = false
    }

    /**
     * 主动挪正前方之后进入静默期。
     *
     * 主动路径没有「候选」这一步，但缓转结束后同样会留下一段残差，必须一起静默。
     */
    fun suppress(nowMs: Long) {
        clearCandidate()
        anchorValid = false
        declined = null
        settleUntilMs = nowMs + ANIM_MS + SETTLE_MS
        // 手动挪过一次，就等于「基准已经认过了」，别再让首次基准把它覆盖掉。
        baselineReady = true
        pendingBaseline = null
    }

    /** 「重置正前方」：基准归零，候选与静默期一起清掉。基准仍然算已建立。 */
    fun reset() {
        clearCandidate()
        anchorValid = false
        declined = null
        settleUntilMs = 0L
        baselineReady = true
    }

    /** 候选还有多久消失，用来画一条细进度条。 */
    fun candidateRemainingMs(nowMs: Long): Long =
        (CANDIDATE_TTL_MS - (nowMs - candidateShownAt)).coerceAtLeast(0L)
}
