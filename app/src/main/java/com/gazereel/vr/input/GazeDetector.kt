package com.gazereel.vr.input

import android.os.SystemClock
import com.gazereel.vr.core.ActionDispatcher
import com.gazereel.vr.core.InputChannel
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.render.VrUiLayer
import com.gazereel.vr.ui.VrUiState

/**
 * PRD 4.2 / 6.5：准星停留在目标上推进进度环，走完即确认并进入冷却。
 * 头动不算「有活动」——只有触发键、实体键与识别到的手势会刷新控制台计时。
 *
 * V2.9：进入 VR 之后凝视默认是关的，必须由一次触发键单击「上膛」
 * （[VrUiState.gazeArmed] 由 false 变 true）。
 *
 * 为什么非要一次物理确认：
 * 把手机塞进纸盒、拉头带、对焦，整个过程手机一直在动，准星会扫过整个片库。
 * 任何「停留即确认」的判定在这种晃动里都等于随机点击，用户看到的就是
 * 「还没看清就自己打开了正对着的那个视频」。
 *
 * 试过但不成立的两条路，记在这里免得再走一遍：
 * - 「正中留空」只保证静止时准星压不到卡片，挡不住配戴期的扫动；
 * - 「必须先移动过头才计时」更糟——配戴时的晃动恰好会把它解开。
 *
 * 纸盒上唯一不会被晃动误触的输入就是触发键，所以上膛只能交给它。
 */
class GazeDetector(
    private val uiLayer: VrUiLayer,
    private val headset: VrHeadsetProvider,
    private val dispatcher: ActionDispatcher,
    private val settings: SettingsRepository,
    private val stateProvider: () -> VrUiState
) {
    companion object {
        /**
         * 换屏 / 上膛后的静默期：新界面第一眼不该算「停留」。
         * 只更新落点，不累计停留。
         */
        private const val ARM_DELAY_MS = 1_000L
    }

    @Volatile var currentId: String? = null
        private set
    @Volatile var progress: Float = 0f
        private set

    private var startedAt = 0L
    private var lastX = Float.NaN
    private var lastY = Float.NaN
    private var armedAt = 0L
    private var lastScreen: String? = null

    /** 上一帧压着的目标；上膛那一刻用它判断「准星是不是本来就停在一张卡上」。 */
    private var lastHit: String? = null

    /**
     * 上膛 / 换屏那一瞬间压着的目标。
     *
     * 必须先把视线移开、再回到它上面，才算一次真的选择。
     * 否则「戴稳后按一下触发键」会当场把触发键前就停着的那张卡确认掉。
     */
    private var mustLeave: String? = null

    /** 上一帧的凝视开关状态，用来识别「刚刚上膛」这个瞬间。 */
    private var wasArmed = false

    /**
     * V2.4：参考系跳变（重置正前方 / 未来的姿态缓转）后清空停留进度与上一帧落点。
     * 否则 update() 会把参考系切换造成的准星位移当成「仍在原目标上」，首帧误触发。
     */
    fun reset() {
        currentId = null
        progress = 0f
        startedAt = 0L
        lastX = Float.NaN
        lastY = Float.NaN
        armedAt = SystemClock.elapsedRealtime() + ARM_DELAY_MS
        mustLeave = null
    }

    fun update(now: Long = SystemClock.elapsedRealtime()) {
        val state = stateProvider()
        val rotation = headset.headRotation()
        val point = uiLayer.gazePoint(rotation, state)
        // V2.14：这里原来会在落点抖动超过 8px 时调 touch()，把头动当成「操作」。
        // 手机塞在纸盒里一直在抖，于是控制台的 8 秒静默永远数不完，一直挡在画面上。
        // 「操作」只算触发键、实体键与识别到的手势，头动不算。
        lastX = point.x
        lastY = point.y

        val target = uiLayer.currentTargets().firstOrNull { it.rect.contains(point.x, point.y) }

        // V2.9：没上膛就只记录落点，绝不累计停留。
        if (!state.gazeArmed) {
            lastHit = target?.id
            currentId = null
            progress = 0f
            wasArmed = false
            return
        }

        // 刚刚上膛：清掉一切可能继承下来的进度，并要求离开当前压着的目标。
        if (!wasArmed) {
            wasArmed = true
            armedAt = now + ARM_DELAY_MS
            startedAt = now
            currentId = null
            progress = 0f
            mustLeave = target?.id ?: lastHit
            lastHit = target?.id
            return
        }

        // 换屏（片库 → 播放器 → 格式浮层）后重新上膛：新界面第一眼不该算「停留」。
        val screenKey = "${state.screen}/${state.overlay}"
        if (screenKey != lastScreen) {
            lastScreen = screenKey
            armedAt = now + ARM_DELAY_MS
            currentId = null
            progress = 0f
            startedAt = now
            mustLeave = target?.id
            lastHit = target?.id
            return
        }
        if (now < armedAt) {
            currentId = null
            progress = 0f
            return
        }
        if (mustLeave != null) {
            if (target?.id == mustLeave) {
                currentId = null
                progress = 0f
                return
            }
            mustLeave = null
        }

        if (target?.id != currentId) {
            currentId = target?.id
            startedAt = now
            progress = 0f
            return
        }
        if (target == null) {
            progress = 0f
            return
        }
        progress = ((now - startedAt).toFloat() / settings.current.gazeDurationMs).coerceIn(0f, 1f)
        if (progress >= 1f) {
            dispatcher.dispatch(target.action, InputChannel.GAZE, gazeCompleted = true)
            currentId = null
            progress = 0f
            startedAt = now
        }
    }
}
