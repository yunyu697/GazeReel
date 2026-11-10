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
        volume = prefs.getInt("volume", 80),
        ipdMm = prefs.getInt("ipd_mm", 62),
        consoleTimeoutMs = 8_000L,
        lockView = prefs.getBoolean("lock_view", false),
        subtitlesEnabled = prefs.getBoolean("subtitles", true)
    )

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
            .putInt("ipd_mm", next.ipdMm)
            .putBoolean("lock_view", next.lockView)
            .putBoolean("subtitles", next.subtitlesEnabled)
            .apply()
        return next
    }
}
