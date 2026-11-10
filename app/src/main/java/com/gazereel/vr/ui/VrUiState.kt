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
    val volume: Int = 75,
    val ipdMm: Int = 62,
    val consoleTimeoutMs: Long = 8_000L,
    val lockView: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    /**
     * V2.14：视线光标（准星）开关。
     * 真机反馈「没有光标很难定位，经常要靠触发临近的按钮去猜」，
     * 但纯看片时画面正中多一个点也烦，所以做成可关、且控制台收起时自动不画。
     */
    val cursorEnabled: Boolean = true
) {
    companion object {
        /**
         * V2.11：音量 5 档、亮度 3 档。
         *
         * 设计第 392 行：「音量交给手机实体侧键，一次一档、零瞄准；亮度手动只有三档大胶囊。
         * 任何档位变化在视野中央浮出大字与 5 段条，1.2 秒淡出。不做凝视滑杆、不做要反复瞄准的 ± 按钮。」
         *
         * 存的是 0–100 的百分比，但**只允许落在档位上**——连续值意味着用户要按十几次才到头，
         * 而那正是设计说的「把连续、高频、要求精度的动作交给了脖子」。
         */
        val VOLUME_STEPS = intArrayOf(0, 25, 50, 75, 100)
        val BRIGHTNESS_STEPS = intArrayOf(35, 70, 100)
        val BRIGHTNESS_LABELS = arrayOf("暗", "标准", "亮")

        /** 找到当前值最接近的档位序号；越界的旧值也能落到最近的档上。 */
        fun stepOf(steps: IntArray, value: Int): Int {
            var best = 0
            var bestGap = Int.MAX_VALUE
            for (i in steps.indices) {
                val gap = kotlin.math.abs(steps[i] - value)
                if (gap < bestGap) {
                    bestGap = gap
                    best = i
                }
            }
            return best
        }

        fun volumeStep(volume: Int): Int = stepOf(VOLUME_STEPS, volume)
        fun brightnessStep(brightness: Int): Int = stepOf(BRIGHTNESS_STEPS, brightness)
    }
}

/**
 * V2.11：档位变化反馈。
 *
 * 设计第 415 行：「值的变化自己浮出来——视野中央大字档位 + 5 段条，1.2 秒淡出；
 * 不必低头进控制台确认，也不占用常驻画面。」
 *
 * 之前 `adjust()` 只改写设置值，戴镜后按音量键**视野里什么都不发生**，
 * 用户只会以为按键坏了或者软件卡住了。
 */
data class AdjustFeedback(
    val label: String,
    val detail: String,
    /** 当前档位，1 基；段条点亮到第几段。 */
    val level: Int,
    val steps: Int,
    /** 过期时刻（SystemClock.elapsedRealtime）。 */
    val untilMs: Long
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
    /**
     * V2.16：成叠视图。
     * - [stacks] 为空表示这个片源不值得成叠（部数少或看不出规律），片库直接平铺；
     * - [activeStack] 为空且在成叠模式时，片库显示的是「一叠一叠」；
     * - 进了某一叠，片库显示的就是那一叠里的片子。
     */
    val stacks: List<MediaStack> = emptyList(),
    val activeStack: String? = null,
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
    /** V2.11：音量 / 亮度档位变化的浮层反馈，1.2 秒后自行消失。 */
    val feedback: AdjustFeedback? = null,
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

    /** 当前在看的这一叠；没进叠时为 null。 */
    val currentStack: MediaStack? get() = stacks.firstOrNull { it.key == activeStack }

    /** 片库这一屏真正要显示的片子：进了叠就是那一叠，否则是全部。 */
    val visibleItems: List<MediaItemEntity> get() = currentStack?.items ?: items

    /** 片库这一屏显示的是「一叠一叠」而不是片子。 */
    val showingStacks: Boolean get() = stacks.isNotEmpty() && activeStack == null

    /** 这一屏要画多少个格子：成叠时是一叠一个。 */
    val cellCount: Int
        get() = if (showingStacks) stacks.size else visibleItems.size

    /** 总页数；没有片源时也算 1 页，避免页码出现「第 1 / 0 页」。 */
    val pageCount: Int get() = ((cellCount + PAGE_SIZE - 1) / PAGE_SIZE).coerceAtLeast(1)

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
