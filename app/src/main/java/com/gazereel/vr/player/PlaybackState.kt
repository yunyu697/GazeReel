package com.gazereel.vr.player

/** PRD 6.6：播放状态包含片源、播放头、播放/暂停与错误。 */
data class PlaybackState(
    val mediaId: Long = 0L,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null
)
