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
        // 18:5x：默认开（与旧行为一致）。关掉后唤醒热区连命中区都不注册。
        wakeZoneEnabled = prefs.getBoolean("wake_zone", true),
        // V2.38：旧安装没有这一项，默认 10 秒；读到的值一律夹进合法范围，
        // 免得某次写坏了（0 秒 / 999 秒）让倒计时变成「立刻定」或「永远不定」。
        alignCountdownMs = prefs.getLong("align_countdown_ms", 10_000L).coerceIn(
            VrSettings.ALIGN_MIN_MS, VrSettings.ALIGN_MAX_MS
        ),
        // alpha43：两项新增设置。读到非档位值（写坏过 / 旧版本残留）一律落回默认值，
        // 免得 UI 加减按 index 走时指向不存在的档。
        uiScalePercent = prefs.getInt("ui_scale_percent", VrSettings.UI_SCALE_DEFAULT)
            .coerceIn(VrSettings.UI_SCALE_MIN, VrSettings.UI_SCALE_MAX),
        seekStepSeconds = prefs.getInt("seek_step_seconds", VrSettings.SEEK_STEP_DEFAULT)
            .takeIf { it in VrSettings.SEEK_STEP_CHOICES } ?: VrSettings.SEEK_STEP_DEFAULT,
        // 18:2x：旧安装没有这两项，一律落回 100%；读到非档位值也落回默认。
        screenWidthPercent = prefs.getInt("screen_width_percent", VrSettings.SCREEN_WIDTH_DEFAULT)
            .takeIf { it in VrSettings.SCREEN_WIDTH_STEPS } ?: VrSettings.SCREEN_WIDTH_DEFAULT,
        contentScalePercent = prefs.getInt("content_scale_percent", VrSettings.CONTENT_SCALE_DEFAULT)
            .takeIf { it in VrSettings.CONTENT_SCALE_STEPS } ?: VrSettings.CONTENT_SCALE_DEFAULT,
        fisheyeFovDeg = prefs.getInt("fisheye_fov_deg", VrSettings.FISHEYE_FOV_DEFAULT)
            .takeIf { it in VrSettings.FISHEYE_FOV_STEPS } ?: VrSettings.FISHEYE_FOV_DEFAULT,
        fisheyeCirclePercent = prefs.getInt("fisheye_circle_percent", VrSettings.FISHEYE_CIRCLE_DEFAULT)
            .takeIf { it in VrSettings.FISHEYE_CIRCLE_STEPS } ?: VrSettings.FISHEYE_CIRCLE_DEFAULT,
        // 19:2x：全画幅鱼眼开关。旧安装没有这一项，默认关（圆形/贴边，与 alpha3 行为一致）。
        fisheyeFullFrame = prefs.getBoolean("fisheye_full_frame", false)
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
            .putBoolean("wake_zone", next.wakeZoneEnabled)
            // V2.44：两个旧方向开关键一并清掉，避免旧安装的残留值被以后的代码误读。
            .remove("flip_vertical")
            .remove("invert_pitch")
            .putLong("align_countdown_ms", next.alignCountdownMs)
            .putInt("ui_scale_percent", next.uiScalePercent)
            .putInt("seek_step_seconds", next.seekStepSeconds)
            .putInt("screen_width_percent", next.screenWidthPercent)
            .putInt("content_scale_percent", next.contentScalePercent)
            .putInt("fisheye_fov_deg", next.fisheyeFovDeg)
            .putInt("fisheye_circle_percent", next.fisheyeCirclePercent)
            .putBoolean("fisheye_full_frame", next.fisheyeFullFrame)
            .apply()
        return next
    }
}
