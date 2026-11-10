package com.gazereel.vr.ui

import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.PlayProgressEntity
import com.gazereel.vr.player.SubtitleCue

/** PRD 4.5：主状态为片库 / 播放，格式确认与设置为覆盖浮层。 */
enum class VrScreen { LIBRARY, PLAYER }

enum class VrOverlay { NONE, FORMAT, SETTINGS }

/** 戴镜状态下可改的全部参数（PRD 4.4 设置浮层 / FR-12）。 */
data class VrSettings(
    val gazeDurationMs: Long = 1_200L,
    val autoNext: Boolean = true,
    val autoFade: Boolean = true,
    val gesturesEnabled: Boolean = true,
    val brightness: Int = 70,
    val volume: Int = 80,
    val ipdMm: Int = 62,
    val consoleTimeoutMs: Long = 8_000L,
    val lockView: Boolean = false,
    val subtitlesEnabled: Boolean = true
)

data class VrUiState(
    val screen: VrScreen = VrScreen.LIBRARY,
    val overlay: VrOverlay = VrOverlay.NONE,
    val items: List<MediaItemEntity> = emptyList(),
    val selectedIndex: Int = 0,
    /**
     * V2.8：片库分页。
     *
     * 设计文档第 408 行把「一次只露三张卡、摇头翻 1 部，832 部就要摇 832 次」列为要解决的问题，
     * 第 528 行给的答案是「摇头翻页从 1 部改为 10 部一页」，第 471 行要求「顶部常驻位置感」。
     * 这里一页放 [PAGE_SIZE] 格，摇头 / 上一页 / 下一页都整页翻。
     */
    val page: Int = 0,
    val currentMediaId: Long = 0L,
    val currentTitle: String = "",
    val format: VrFormat = VrFormat.DEFAULT,
    val suggestedFormat: VrFormat = VrFormat.DEFAULT,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val consoleVisible: Boolean = true,
    val lastActivityMs: Long = 0L,
    val settings: VrSettings = VrSettings(),
    /** mediaId → 断点续播记录（阶段二 FR-10）。 */
    val progress: Map<Long, PlayProgressEntity> = emptyMap(),
    /** 当前片源的同名字幕，GL 线程按 positionMs 取当前行。 */
    val subtitles: List<SubtitleCue> = emptyList(),
    val message: String? = null,
    /** V2.4：0 表示常驻；否则为提示自动淡出的时刻（SystemClock.elapsedRealtime）。 */
    val messageUntilMs: Long = 0L,
    /**
     * V2.9：凝视是否已上膛。
     *
     * 进 VR 时是 false —— 塞手机、调头带的过程里手机一直在动，准星会扫过整个片库，
     * 这时候任何「停留即确认」都等于随机点击。必须由用户戴稳后按一次触发键解锁。
     * 离开 VR（onPause）时重新置 false，所以拿出来再放回去要重新按一次。
     */
    val gazeArmed: Boolean = false
) {
    val selectedItem: MediaItemEntity? get() = items.getOrNull(selectedIndex)

    /** 总页数；没有片源时也算 1 页，避免页码出现「第 1 / 0 页」。 */
    val pageCount: Int get() = ((items.size + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)

    companion object {
        /**
         * 一页 8 格（2 行 × 4 列）。选 8 而不是 10，是因为画布只有 1024×512：
         * 4 列每格 214px ≈ 10.1°、2 行每格 108px ≈ 5.4°，仍守住「单目标 ≥6°×4°」。
         *
         * 更关键的是 2×4 的格子排布让画布正中 (512, 256) 正好落在十字缝里
         * （x 在 504–520 之间、y 在 248–264 之间），准星静止时压不到任何一张卡，
         * 这是「会误打开正对着的那个视频」的结构性修复。
         */
        const val PAGE_SIZE = 8
    }
}
