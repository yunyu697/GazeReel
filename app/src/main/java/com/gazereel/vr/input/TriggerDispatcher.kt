package com.gazereel.vr.input

import com.gazereel.vr.core.ActionDispatcher
import com.gazereel.vr.core.InputChannel
import com.gazereel.vr.core.TriggerEvent
import com.gazereel.vr.core.TriggerKind
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.render.VrUiLayer
import com.gazereel.vr.ui.VrOverlay
import com.gazereel.vr.ui.VrScreen
import com.gazereel.vr.ui.VrUiState

/**
 * PRD 4.2 / 6.5：归一化单击、双击、长按，屏蔽 v2 导电触控片与 v1 磁铁差异。
 * 单击执行当前准星目标；无目标时在播放页切换播放/暂停，避免「必须瞄准按钮」。
 */
class TriggerDispatcher(
    private val uiLayer: VrUiLayer,
    private val headset: VrHeadsetProvider,
    private val dispatcher: ActionDispatcher,
    private val stateProvider: () -> VrUiState,
    private val onActivity: () -> Unit
) {
    companion object {
        const val LONG_PRESS_MS = 600L
        private const val DOUBLE_TAP_MS = 320L
    }

    private var lastClickAt = 0L

    /**
     * V2.38：双击的含义从「打开设置」改成「立刻把正前方对齐到你现在看的方向」。
     *
     * 为什么双击适合干这件事：它**不落在任何凝视目标上**。旧版给「重定」配过一个
     * 按钮，可那个按钮在面板下方，凝视它的时候视线已经离开舒适中心了，等于把
     * 「低头看按钮」写成了新的正前方——V2.28 因此删掉了那个按钮。双击不碰按钮，
     * 记下的就是用户此刻看的方向，这正是它要的。
     *
     * 设置浮层没有丢入口：片库右上角的齿轮、播放控制台的「设置」都还在；
     * 而且这个入口本来就在（V2.11 加的），双击只是重复的一条近路。
     */
    private fun doubleClickAction(): VrAction =
        // 调瞳距时双击仍是「快速结束」——那层 0xEE 面板会把正在看的画面整个盖住。
        if (stateProvider().ipdTuning) VrAction.StopIpdTuning else VrAction.RecenterNow

    fun onTrigger(event: TriggerEvent) {
        // 必须在 onActivity() 之前取：它会把控制台点亮，之后再看就已经是「显示中」了。
        val before = stateProvider()
        val consoleWasHidden = before.screen == VrScreen.PLAYER &&
            before.overlay == VrOverlay.NONE && !before.consoleVisible
        onActivity()

        // V2.9：还没上膛时，触发键只有一种含义 ——「我戴稳了，开始」。
        //
        // 长按 / 双击在这里一律忽略：配戴过程中手指会蹭到触控片，
        // 那两下如果生效就是「突然弹出设置」或「突然退回配置页」，
        // 而且此时用户还看不见屏幕，根本不知道发生了什么。
        if (!stateProvider().gazeArmed) {
            if (event.kind == TriggerKind.CLICK) {
                lastClickAt = 0L
                dispatcher.dispatch(VrAction.ArmGaze, InputChannel.TRIGGER)
            }
            return
        }

        // V2.38：重定「正前方」的倒计时是独占的一层，输入语义也一起接管：
        // 长按 = 取消，单击 / 双击 = 「现在就定在这里」。此时不查凝视目标
        // （那一层不注册目标），也不落到「播放页单击 = 暂停」。
        if (stateProvider().alignCountdown != null) {
            lastClickAt = 0L
            dispatcher.dispatch(
                if (event.kind == TriggerKind.LONG_PRESS) VrAction.CancelAlign
                else VrAction.ConfirmAlign,
                InputChannel.TRIGGER
            )
            return
        }

        when (event.kind) {
            TriggerKind.LONG_PRESS -> {
                lastClickAt = 0L
                dispatcher.dispatch(VrAction.Back, InputChannel.TRIGGER)
            }
            TriggerKind.DOUBLE_CLICK -> {
                lastClickAt = 0L
                dispatcher.dispatch(doubleClickAction(), InputChannel.TRIGGER)
            }
            TriggerKind.CLICK -> {
                val tuning = stateProvider().ipdTuning
                if (event.atMs - lastClickAt < DOUBLE_TAP_MS) {
                    lastClickAt = 0L
                    dispatcher.dispatch(doubleClickAction(), InputChannel.TRIGGER)
                    return
                }
                lastClickAt = event.atMs
                // V2.19c：姿态确认卡挂着时，单击 = 确认。
                //
                // 卡片摆在视线正前方，准星静止时落在两个按钮**中间那道缝**里，
                // 所以单击多半点不到任何目标。若继续往下走，就会落到
                // 「播放页单击 = 暂停」——用户按一下只想确认，结果把片子停了。
                if (stateProvider().postureCandidate != null) {
                    lastClickAt = 0L
                    dispatcher.dispatch(VrAction.ConfirmPosture, InputChannel.TRIGGER)
                    return
                }
                // V2.14：控制台收起时，单击只负责「把它叫出来」。
                // 原来这条路径落到 TogglePlay，于是控制台一收起就再也叫不回来——
                // 之前它永远不隐藏，正好把这个洞盖住了。
                // 调瞳距时控制台是故意收起的，单击不该把它叫回来挡住画面。
                if (consoleWasHidden && !tuning) {
                    dispatcher.dispatch(VrAction.ShowConsole, InputChannel.TRIGGER)
                    return
                }
                val state = stateProvider()
                val target = uiLayer.targetAt(headset.headRotation(), state)
                when {
                    target != null -> dispatcher.dispatch(target.action, InputChannel.TRIGGER)
                    // V2.33 / B13：空白处单击 = 切粗/微，绝不落到「播放页单击 = 暂停」。
                    tuning -> dispatcher.dispatch(VrAction.ToggleIpdMode, InputChannel.TRIGGER)
                    state.screen == VrScreen.PLAYER && state.overlay == VrOverlay.NONE ->
                        dispatcher.dispatch(VrAction.TogglePlay, InputChannel.TRIGGER)
                    else -> dispatcher.dispatch(VrAction.ShowConsole, InputChannel.TRIGGER)
                }
            }
        }
    }
}
