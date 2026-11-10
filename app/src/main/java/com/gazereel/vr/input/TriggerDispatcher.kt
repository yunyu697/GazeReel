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

        when (event.kind) {
            TriggerKind.LONG_PRESS -> {
                lastClickAt = 0L
                dispatcher.dispatch(VrAction.Back, InputChannel.TRIGGER)
            }
            TriggerKind.DOUBLE_CLICK -> {
                lastClickAt = 0L
                dispatcher.dispatch(VrAction.OpenSettings, InputChannel.TRIGGER)
            }
            TriggerKind.CLICK -> {
                if (event.atMs - lastClickAt < DOUBLE_TAP_MS) {
                    lastClickAt = 0L
                    dispatcher.dispatch(VrAction.OpenSettings, InputChannel.TRIGGER)
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
                if (consoleWasHidden) {
                    dispatcher.dispatch(VrAction.ShowConsole, InputChannel.TRIGGER)
                    return
                }
                val state = stateProvider()
                val target = uiLayer.targetAt(headset.headRotation(), state)
                when {
                    target != null -> dispatcher.dispatch(target.action, InputChannel.TRIGGER)
                    state.screen == VrScreen.PLAYER && state.overlay == VrOverlay.NONE ->
                        dispatcher.dispatch(VrAction.TogglePlay, InputChannel.TRIGGER)
                    else -> dispatcher.dispatch(VrAction.ShowConsole, InputChannel.TRIGGER)
                }
            }
        }
    }
}
