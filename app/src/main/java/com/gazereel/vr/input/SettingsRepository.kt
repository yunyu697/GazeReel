package com.gazereel.vr.input

import android.content.Context
import com.gazereel.vr.ui.VrSettings

/** 设置项内存态 + 轻量持久化；戴镜状态下的加减操作立即生效。 */
class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("vr_settings", Context.MODE_PRIVATE)

    @Volatile
    var current: VrSettings = load()
        private set

    private fun load(): VrSettings = VrSettings(
        gazeDurationMs = prefs.getLong("gaze_ms", 1_200L),
        autoNext = prefs.getBoolean("auto_next", true),
        autoFade = prefs.getBoolean("auto_fade", true),
        gesturesEnabled = prefs.getBoolean("gestures", true),
        brightness = prefs.getInt("brightness", 70),
        // V2.11：默认值落在档位上（音量 5 档里 75 是第 4 档，亮度 70 是「标准」）。
        // 存一个不在档位上的值，界面显示档位、播放器用另一个值，两边对不上。
        volume = prefs.getInt("volume", 75),
        // V2.33 / B13：旧版 `ipd_mm` 是整数毫米，只能 2mm 一跳。
        // 首次读到旧值时 ×2 迁到半毫米，并夹进合法范围；新值一律走 `ipd_half_mm`。
        ipdHalfMm = prefs.getInt("ipd_half_mm", legacyIpdHalf()),
        consoleTimeoutMs = 8_000L,
        lockView = prefs.getBoolean("lock_view", false),
        subtitlesEnabled = prefs.getBoolean("subtitles", true),
        cursorEnabled = prefs.getBoolean("cursor", true),
        flipPitch = prefs.getBoolean("flip_pitch", false)
    )

    private fun legacyIpdHalf(): Int {
        if (!prefs.contains("ipd_mm")) return VrSettings.IPD_DEFAULT_HALF
        val oldMm = prefs.getInt("ipd_mm", 62)
        return VrSettings.ipdHalfOf(oldMm.toFloat())
    }

    fun update(transform: (VrSettings) -> VrSettings): VrSettings {
        val next = transform(current)
        current = next
        prefs.edit()
            .putLong("gaze_ms", next.gazeDurationMs)
            .putBoolean("auto_next", next.autoNext)
            .putBoolean("auto_fade", next.autoFade)
            .putBoolean("gestures", next.gesturesEnabled)
            .putInt("brightness", next.brightness)
            .putInt("volume", next.volume)
            .putInt("ipd_half_mm", next.ipdHalfMm)
            // 迁移完成后不再保留旧 key：否则它永远躺在那里，
            // 以后任何「读不到新值就回落」的代码都会拿到过时的整数 mm。
            .remove("ipd_mm")
            .putBoolean("lock_view", next.lockView)
            .putBoolean("subtitles", next.subtitlesEnabled)
            .putBoolean("cursor", next.cursorEnabled)
            .putBoolean("flip_pitch", next.flipPitch)
            .apply()
        return next
    }
}
