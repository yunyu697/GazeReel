package com.gazereel.vr.core

import android.os.SystemClock

/**
 * PRD 6.5 / 4.6：统一做去重、冷却与优先级仲裁，同一时刻只执行一个动作。
 * 凝视完成后进入 900ms 冷却，避免进度环走完后重复触发；
 * 播放类动作可在冷却期内抢占设置类动作。
 */
class ActionDispatcher(
    private val sink: (VrAction, InputChannel) -> Unit
) {
    companion object {
        const val GAZE_COOLDOWN_MS = 900L
        const val DEDUPE_MS = 300L
    }

    private val lock = Any()
    private var lastAction: VrAction? = null
    private var lastActionAt = 0L
    private var lastPriority = 0
    private var cooldownUntil = 0L

    /** @param gazeCompleted 凝视进度环刚走完（触发键不需要额外冷却）。 */
    fun dispatch(action: VrAction, channel: InputChannel, gazeCompleted: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (action == lastAction && now - lastActionAt < DEDUPE_MS) return
            if (now < cooldownUntil) {
                val canPreempt = action.isPlaybackRelated && action.priority > lastPriority
                if (!canPreempt) return
            }
            lastAction = action
            lastActionAt = now
            lastPriority = action.priority
            if (channel == InputChannel.GAZE || gazeCompleted) {
                cooldownUntil = now + GAZE_COOLDOWN_MS
            }
        }
        sink(action, channel)
    }

    fun reset() {
        synchronized(lock) {
            lastAction = null
            lastActionAt = 0L
            lastPriority = 0
            cooldownUntil = 0L
        }
    }
}
