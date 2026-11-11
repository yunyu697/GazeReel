package com.gazereel.vr.ui

import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.PlayProgressEntity
import com.gazereel.vr.player.SubtitleCue
import java.util.Locale
import kotlin.math.roundToInt

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
    /**
     * V2.33 / B13：瞳距的**唯一真相源**，单位是 0.5mm。
     *
     * 原来存的是整数 mm，只能 2mm 一跳，粗调与微调没有共同落点；
     * 改成半毫米整数后，粗调 4 个单位（2.0mm）与微调 1 个单位（0.5mm）
     * 直接叠加到同一个值上，既能一步跨到大致范围，也能修到 0.5mm。
     * 持久化从 `ipd_mm` 迁移到 `ipd_half_mm`，见 SettingsRepository。
     */
    val ipdHalfMm: Int = IPD_DEFAULT_HALF,
    val consoleTimeoutMs: Long = 8_000L,
    val lockView: Boolean = false,
    val subtitlesEnabled: Boolean = true,
    /**
     * V2.14：视线光标（准星）开关。
     * 真机反馈「没有光标很难定位，经常要靠触发临近的按钮去猜」，
     * 但纯看片时画面正中多一个点也烦，所以做成可关、且控制台收起时自动不画。
     */
    val cursorEnabled: Boolean = true,
    /**
     * V2.37：俯仰方向。
     *
     * 真机反馈（2026-11-11）：「抬头整个视频画面应该下移才能看到天花板，低头画面
     * 上移才能看到地板，现在是反的；左右正常。」这句话把范围锁死在**俯仰**这一维：
     * 画面内容没倒、左右也没反，只是抬头时画面往上走。
     *
     * 球面几何、纹理 v、相机三者在源码里都核对过，数学上是自洽的；真机上仍然反，
     * 说明有一层方向与这里不同（`Display.getRotation()` 与物理横向、或某台设备的
     * 旋转矢量约定）。与其继续用推演争论，先把这一维做成可切换的：
     * `true` 时把姿态的俯仰取反（偏航、横滚原样保留），戴上试一次即可判定。
     */
    val flipPitch: Boolean = false,
    /**
     * V2.39：方向自检做过没有。
     *
     * V2.37 把俯仰反向做成了「手机设置里的一项」，默认关——真机反馈是
     * 「和原来一样，没有任何变化」。原因很可能就是**没人知道要去打开它**：
     * 开关藏在戴镜前的对话框里，而症状出现在戴上之后。
     *
     * 所以第一次播放时主动问一次：抬头该看到天花板，看到的如果是地板，
     * 点一下就翻过来。问过之后 `pitchChecked` 为真，不再打扰。
     */
    val pitchChecked: Boolean = false,
    /**
     * V2.38：重定「正前方」的倒计时时长（毫秒）。
     *
     * 用户要「在限定时间内调整并稳定姿态」，这个「限定时间」得能调：
     * 10 秒对多数人刚好（摆好 + 停住），躺下再找角度的人可能想给 15 秒，
     * 已经摆好只想确认的人想给 5 秒。手机端设置里以秒为单位加减。
     */
    val alignCountdownMs: Long = 10_000L
) {
    /** 米制毫米，供渲染层每帧读取；不另存，避免两套数值打架。 */
    val ipdMm: Float get() = ipdHalfMm / 2f

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

        // B13：50.0–75.0mm，覆盖成人与儿童纸盒的常见范围。
        const val IPD_MIN_HALF = 100
        const val IPD_MAX_HALF = 150
        const val IPD_DEFAULT_HALF = 124      // 62.0mm
        const val IPD_COARSE_HALF = 4         // 2.0mm / 步，全程 13 步
        const val IPD_FINE_HALF = 1           // 0.5mm / 步

        /** V2.38：重定倒计时的范围与步长。3 秒是「已经摆好了，只想确认」的下限。 */
        const val ALIGN_MIN_MS = 3_000L
        const val ALIGN_MAX_MS = 20_000L
        const val ALIGN_STEP_MS = 1_000L

        fun alignSeconds(ms: Long): Long = (ms / 1_000L).coerceIn(ALIGN_MIN_MS / 1000, ALIGN_MAX_MS / 1000)

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

        fun ipdHalfOf(mm: Float): Int =
            (mm * 2f).roundToInt().coerceIn(IPD_MIN_HALF, IPD_MAX_HALF)

        fun ipdLabel(half: Int): String =
            String.format(Locale.US, "%.1f mm", half / 2f)
    }
}

/**
 * V2.11 建立、V2.33 / B14 重构：**可操作的调节条**，不再是纯反馈浮层。
 *
 * 真机反馈两条，都指向「它看着像能点、其实不能点、还来不及看就消失」：
 * 1. 原来 2.6 秒固定淡出，且 `drawAdjustFeedback` 没注册任何 `GazeTarget`；
 * 2. `level = 档号 + 1` 把 **1 基档号**当成「点亮段数」，音量 0 仍亮一格。
 *
 * 成熟方案（附录 §6）：AOSP `VolumeDialogImpl.rescheduleTimeoutH()` 用「交互感知」的
 * 倒计时——每次交互取消旧计时重排，hover 期间 16000ms，离开回落到 3000ms；
 * Android `SeekBar` / Material `Slider` 用真实值比例填充轨道，`value=0 → 长度为 0`。
 *
 * 修法不是「把段数改对」，而是**不再画段条**：离屏对照图里连续轨道 + 一排小点
 * 既重复又挤，静音时四个全灭还像坏了。现在只有一条按 [fraction] 填充的轨道，
 * 档位用刻度线表达，连续值（瞳距）用滑块表达。VR 是 Canvas 手绘，没有 View 可复用，
 * 所以照契约实现：本对象带 `key` 让绘制层把 `−` / `+` / 模式注册成凝视目标，
 * coordinator 按准星原始落点判断 hover 续时。
 */
data class AdjustFeedback(
    /** "volume" | "brightness" | "ipd" —— 调节条上按钮回带的 key。 */
    val key: String,
    val label: String,
    val detail: String,
    /**
     * 把轨道均分几段并画 `steps - 1` 条刻度线；`0` = 连续值，不画刻度。
     * 音量 5 档去掉「静音」正好 4 段，刻度落在 25/50/75；亮度 35/70/100 不均分，
     * 所以不画假刻度；瞳距是连续值，也不画。
     */
    val steps: Int,
    /** 真实值比例 0–1，轨道填充的唯一依据；0 → 完全不填充。 */
    val fraction: Float,
    /** 过期时刻（SystemClock.elapsedRealtime）。 */
    val untilMs: Long,
    /** 瞳距专用：粗/微模式与按键说明。 */
    val hint: String? = null,
    val coarse: Boolean = true
)

/**
 * V2.38：重定「正前方」的倒计时状态。
 *
 * 旧路径是「凝视『正前方』按钮 → 再凝视『挪到这里』」。这两步都要瞄准，
 * 而瞄准的动作本身就把视线从「我想要的正前方」挪到了按钮那边——
 * 于是被记下的永远是按钮的位置。真机上「怎么调都差一点」的根子在这里，
 * 不在重定算法里（`recenterTo` 的数学在 V2.19c 就核对过）。
 *
 * 现在的语义：**看哪儿，哪儿就是正前方。** 用户在倒计时里把姿态摆好、
 * 停住，归零那一刻的朝向直接成为新基准。全程只需要「看」，不需要瞄准。
 *
 * [yaw] / [pitch] 每帧由 VrCoordinator 写成**当前视线方向**，`VrUiLayer.panelSpec`
 * 据此把整块面板摆到视线正前方——卡片因此贴着眼睛走，不会「跑到一边去」，
 * 也不会像世界锁定的控制台那样要求用户转头去找它。
 */
data class AlignCountdown(
    val totalMs: Long,
    val remainingMs: Long,
    /** 视线方向（弧度，参考系内），面板朝向跟它走。 */
    val yaw: Float,
    val pitch: Float,
    /** 从倒计时开始到现在视线一共挪了多少度 —— 让用户确认「我刚才那下被记下了」。 */
    val movedDeg: Int,
    /** 最近半秒是否稳住。false 时界面会提示「还在动」，不改变倒计时本身。 */
    val steady: Boolean
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
    /**
     * V2.18：换柜子整层（B2）。
     * - [cabinetsOpen] 为真时，片库页整层换成「柜子」；
     * - [cabinet] 是当前柜子的 sourceId，`0` 表示全部片库。
     */
    val cabinetsOpen: Boolean = false,
    val cabinet: Long = 0L,
    val cabinets: List<Cabinet> = emptyList(),
    /**
     * V2.19 / B4：非 null 表示「检测到新姿势，等一次凝视确认」。值是候选俯仰角。
     *
     * 注意这里**没有** comfortPitch。舒适中心不是靠「把 UI 平面往上挪」实现的，
     * 那样只会让 UI 单独飞走、视频留在原地。真正的做法是缓转整个参考系
     * （`VrHeadsetProvider.recenterSmooth`），视频 / UI / 字幕 / 准星一起转。
     */
    val postureCandidate: Float? = null,
    /**
     * V2.19c：候选方向的**偏航**分量（左右）。
     *
     * 只有俯仰不够。用户躺下时最常偏的是「仰头」，但侧躺着看、或者沙发上半靠着，
     * 偏的可能是左右——只说「抬头 32°」会把「偏左 40°」说成「你现在不是正对画面」，
     * 那就等于没告诉他任何事。
     */
    val postureYaw: Float = 0f,
    /** 候选还剩多久消失（毫秒），用来画那条细进度条。 */
    val postureRemainingMs: Long = 0L,
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
    /** V2.18：键是 `sourceId:path`，不再是可以变的自增 id。 */
    val progress: Map<String, PlayProgressEntity> = emptyMap(),
    /** 当前片源的同名字幕，GL 线程按 positionMs 取当前行。 */
    val subtitles: List<SubtitleCue> = emptyList(),
    val message: String? = null,
    /** V2.4：0 表示常驻；否则为提示自动淡出的时刻（SystemClock.elapsedRealtime）。 */
    val messageUntilMs: Long = 0L,
    /** V2.11 / V2.33：可操作的调节条；存在时绘制层注册凝视目标。 */
    val feedback: AdjustFeedback? = null,
    /**
     * V2.33 / B13：瞳距「边看边调」态。为真时调节条不自动淡出、音量键临时借用、
     * 触发键单击切粗/微；长按退出后音量键立刻归还。
     */
    val ipdTuning: Boolean = false,
    /**
     * V2.38：非 null 表示正在「调整正前方」的倒计时里。
     *
     * 这一层是**独占**的：面板只画它、不注册任何凝视目标、其他动作一律被吞掉。
     * 理由和姿态确认卡一样——这是打断，打断上不该再摆一排按钮；
     * 而且此时用户正在靠转头找方向，任何「顺手点一下」都会落在错的地方。
     */
    val alignCountdown: AlignCountdown? = null,
    /**
     * V2.39：方向自检卡。首次播放时出现一次，问「抬头看到的是不是天花板」。
     *
     * 为什么要问而不是自动判断：3DOF 只有姿态，没有任何信息能告诉 App
     * 「用户以为自己在看哪一边」。而这一个问题，用户 2 秒就能答。
     * 答完写进设置，以后不再出现（手机端设置里仍可改）。
     */
    val pitchCheck: Boolean = false,
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
