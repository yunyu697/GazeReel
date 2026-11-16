package com.gazereel.vr.player

/**
 * PRD 6.6：播放状态包含片源、播放头、播放/暂停与错误。
 *
 * ## 19:2x：把一个布尔拆成三个语义
 *
 * 之前只有 `playing = player.isPlaying`。它在**缓冲**时是 `false`，于是同一个
 * 「是否在播」同时承担了三种情况，三处判断全部判错：
 *
 * - 控制台的播放键在缓冲时显示成「播放」三角（用户明明按过播放）；再按一下
 *   `togglePlay()` 把 `playWhenReady` 翻成 false，等于「想继续看，结果变成暂停」；
 * - 离开 VR（`onLeaveForeground`）时 `if (playing) pause()` 在缓冲中不成立 →
 *   回到前台自己接着放，而提示写的是「已为你暂停」；
 * - 回来时 `!playing` 成立 → 明明还在缓冲/在播，却弹「已为你暂停」。
 *
 * 现在分开写：
 * - [playWhenReady]：**用户的意图**。控制台的键形、键名、离开时的暂停判断都看它。
 * - [playing]：**画面真的在走**（`isPlaying`）。不要再拿它当「用户想不想看」。
 * - [buffering]：正在等数据。控制台据此显示「缓冲中」，而不是让用户以为卡死。
 * - [bufferedMs]：已缓冲到哪儿。进度条据此画缓冲段（设计稿里有、我们一直缺的一段）。
 */
data class PlaybackState(
    val mediaId: Long = 0L,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    /** 用户的播放意图（`playWhenReady`）。缓冲中它为 true，而 [playing] 是 false。 */
    val playWhenReady: Boolean = false,
    /** 正在缓冲（`STATE_BUFFERING`）：等数据，不是暂停。 */
    val buffering: Boolean = false,
    /** 已缓冲到的时间位置；0 表示未知（直播、或还没开始缓冲）。 */
    val bufferedMs: Long = 0L,
    val error: String? = null,
    /**
     * 2.1.0：错误卡的第二行「那我能做什么」。
     *
     * 错误卡的这句话原来是写死的「检查设备是否在线、账号是否过期」——
     * 对解码失败完全跑题（网络明明好好的）。现在按错误类型给：
     * 解码能力不足 → 「换 4096×2176 以内 / 用 H.265 重压」，网络 → 检查共享，
     * 文件损坏 → 换片源。为 null 时卡片退回原来那句通用建议。
     */
    val errorHint: String? = null
)
