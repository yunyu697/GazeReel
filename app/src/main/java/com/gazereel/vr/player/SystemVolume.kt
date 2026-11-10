package com.gazereel.vr.player

import android.content.Context
import android.media.AudioManager
import kotlin.math.roundToInt

/**
 * 音量 = 系统媒体音量（STREAM_MUSIC），只有这一份真相。
 *
 * V2.14：以前音量是 app 自己存的一个 0–100 的整数，靠 `ExoPlayer.setVolume()` 生效，
 * 同时 `VrActivity.onKeyDown` 把 KEYCODE_VOLUME_UP/DOWN 消费掉了 —— 系统媒体音量
 * 因此永远不会变。两边一旦分叉就会出现真机反馈里的那一幕：
 * **打开视频是静音的（系统音量 0），调出来的音量条却是 5 格满（app 里存着 75）**。
 *
 * 现在侧键调的是它、我们调的也是它、读回来的还是它，不可能再对不上。
 * 播放器自身的 volume 固定 1.0，不再叠加第二层增益。
 */
class SystemVolume(context: Context) {

    private val audio = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** 当前媒体音量 0–100；读不到返回 -1，调用方保持原值，不要当成 0。 */
    fun percent(): Int {
        val manager = audio ?: return -1
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return -1
        val current = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (current * 100f / max).roundToInt().coerceIn(0, 100)
    }

    /** 按百分比设置媒体音量。flags 传 0：系统音量条不弹，我们自己有档位反馈。 */
    fun setPercent(percent: Int) {
        val manager = audio ?: return
        val max = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        val index = (percent.coerceIn(0, 100) / 100f * max).roundToInt().coerceIn(0, max)
        runCatching { manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }
}
