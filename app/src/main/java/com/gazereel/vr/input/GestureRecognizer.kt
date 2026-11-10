package com.gazereel.vr.input

import android.os.SystemClock
import com.gazereel.vr.core.ActionDispatcher
import com.gazereel.vr.core.InputChannel
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrHeadsetProvider
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.ui.VrOverlay
import com.gazereel.vr.ui.VrScreen
import com.gazereel.vr.ui.VrUiState
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * PRD 4.2 / 4.6：陀螺仪检测「快速往返 + 方向反转」，摇头约 15°、点头约 10°，
 * 单次缓慢转头不触发；命中后 800ms 冷却。可在设置里单独关闭。
 */
class GestureRecognizer(
    private val headset: VrHeadsetProvider,
    private val dispatcher: ActionDispatcher,
    private val settings: SettingsRepository,
    private val stateProvider: () -> VrUiState
) {
    companion object {
        private const val YAW_THRESHOLD_DEG = 15f
        // V2.7.8：10° 太小了。戴着纸盒看片，上下看一眼画面顶部/底部就超过 10°，
        // 于是动不动就自己暂停。16° 需要一次明确的点头才能到。
        private const val PITCH_THRESHOLD_DEG = 16f
        private const val WINDOW_MS = 700L
        private const val COOLDOWN_MS = 800L
    }

    private var lastYaw = Float.NaN
    private var lastPitch = Float.NaN
    private var yawMin = 0f
    private var yawMax = 0f
    private var pitchMin = 0f
    private var pitchMax = 0f
    private var lastYawDir = 0
    private var lastPitchDir = 0
    private var yawReversed = false
    private var pitchReversed = false
    private var windowStart = 0L
    private var lastGestureAt = 0L

    /**
     * V2.4：参考系跳变后清空角度窗口，并留一个冷却，避免切换瞬间的大幅
     * yaw / pitch 差值被判成点头或摇头。下一帧 update() 会以新姿态重建窗口。
     */
    fun reset() {
        val now = SystemClock.elapsedRealtime()
        lastYaw = Float.NaN
        lastPitch = Float.NaN
        lastGestureAt = now
        resetWindow(0f, 0f, now)
    }

    fun update(now: Long = SystemClock.elapsedRealtime()) {
        if (!settings.current.gesturesEnabled) return
        val rotation = headset.headRotation()
        val yaw = Math.toDegrees(
            atan2((-rotation[8]).toDouble(), (-rotation[10]).toDouble())
        ).toFloat()
        val pitch = Math.toDegrees(
            asin((-rotation[9]).coerceIn(-1f, 1f).toDouble())
        ).toFloat()

        if (lastYaw.isNaN()) {
            lastYaw = yaw; lastPitch = pitch
            resetWindow(yaw, pitch, now)
            return
        }
        if (now - windowStart > WINDOW_MS) resetWindow(yaw, pitch, now)

        val dyaw = yaw - lastYaw
        val dpitch = pitch - lastPitch

        yawMin = min(yawMin, yaw); yawMax = max(yawMax, yaw)
        pitchMin = min(pitchMin, pitch); pitchMax = max(pitchMax, pitch)

        val yawDir = sign(dyaw)
        val pitchDir = sign(dpitch)
        if (yawDir != 0 && lastYawDir != 0 && yawDir != lastYawDir) yawReversed = true
        if (pitchDir != 0 && lastPitchDir != 0 && pitchDir != lastPitchDir) pitchReversed = true
        if (yawDir != 0) lastYawDir = yawDir
        if (pitchDir != 0) lastPitchDir = pitchDir

        lastYaw = yaw
        lastPitch = pitch
        if (now - lastGestureAt < COOLDOWN_MS) return

        when {
            pitchReversed && pitchMax - pitchMin >= PITCH_THRESHOLD_DEG -> {
                emitNod(stateProvider())
                lastGestureAt = now
                resetWindow(yaw, pitch, now)
            }
            yawReversed && yawMax - yawMin >= YAW_THRESHOLD_DEG -> {
                emitShake(stateProvider(), yaw)
                lastGestureAt = now
                resetWindow(yaw, pitch, now)
            }
        }
    }

    private fun emitNod(state: VrUiState) {
        val action = if (state.overlay == VrOverlay.FORMAT) {
            VrAction.SetFormat(state.suggestedFormat)
        } else {
            VrAction.TogglePlay
        }
        RunLog.append("ui", "点头 → ${action.javaClass.simpleName}")
        dispatcher.dispatch(action, InputChannel.GESTURE)
    }

    private fun emitShake(state: VrUiState, currentYaw: Float) {
        // V2.7.8：播放中不再响应摇头。
        //
        // 看 180 / 360 内容时，左右转头就是最主要的操作，700ms 内 15° 的往返是常态。
        // 把它判成「下一部」，结果就是播几秒自己跳片，而且没有任何提示——
        // 用户只会觉得「这软件坏了」。换片请用控制台的上一部/下一部，或回片库。
        if (state.screen != VrScreen.LIBRARY || state.overlay != VrOverlay.NONE) {
            RunLog.append("ui", "摇头 ${currentYaw.toInt()}°（播放中忽略）")
            return
        }
        // V2.8：片库里摇头 = 翻一页。
        //
        // 设计文档第 528 行：「摇头翻页从 1 部改为 10 部一页」。摇头本身是往返动作，
        // 方向天生有歧义，所以只绑定「往后翻」这一个语义；往回翻用屏幕上的「上一页」，
        // 不让用户为了一个反向手势去记新的动作。
        RunLog.append("ui", "摇头 → 下一页")
        dispatcher.dispatch(VrAction.PageFlip(1), InputChannel.GESTURE)
    }

    private fun resetWindow(yaw: Float, pitch: Float, now: Long) {
        windowStart = now
        yawMin = yaw; yawMax = yaw
        pitchMin = pitch; pitchMax = pitch
        lastYawDir = 0; lastPitchDir = 0
        yawReversed = false; pitchReversed = false
    }

    private fun sign(value: Float): Int = when {
        value > 0.4f -> 1
        value < -0.4f -> -1
        else -> 0
    }
}
