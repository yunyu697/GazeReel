package com.gazereel.vr.ui

import com.gazereel.vr.core.Refusal
import com.gazereel.vr.core.StereoLayout
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
     * 18:5x：播放页的「凝视唤出控制台」热区开关。
     *
     * 它是一块**看不见的**可交互区（视觉只在悬停时出现一条细线），
     * 而用户看片时本来就会长时间盯着画面上下缘 —— 所以必须能关。
     * 关掉后 `drawConsoleWakeHint` **连命中区都不注册**：只藏视觉、判定还在，
     * 等于「看不见却仍会误触发」，比不做开关更糟。
     *
     * 关掉不影响触发键单击唤出控制台（`TriggerDispatcher` 那条近路仍然有效）。
     */
    val wakeZoneEnabled: Boolean = true,
    // V2.44：原「抬头方向」开关整段删除。alpha39 起方向由官方 Cardboard SDK
    // 的 HeadTracker 姿态与 HeadSpace 的唯一转置约定保证，不是用户拨出来的。
    // alpha24–alpha30 四轮开关全是在错误坐标系里打补丁，越拨越坏，不再留字段。
    /**
     * V2.38：重定「正前方」的倒计时时长（毫秒）。
     *
     * 用户要「在限定时间内调整并稳定姿态」，这个「限定时间」得能调：
     * 10 秒对多数人刚好（摆好 + 停住），躺下再找角度的人可能想给 15 秒，
     * 已经摆好只想确认的人想给 5 秒。手机端设置里以秒为单位加减。
     */
    val alignCountdownMs: Long = 10_000L,

    /**
     * alpha43：UI 整体缩放（百分比）。真机反馈「UI 有点小，看清文字非常费劲」。
     *
     * 实现不是改 1024×512 画布里的像素，而是把 UI 平面的**物理尺寸**乘这个比例
     * （见 `VrUiLayer.panelSpec`）。这样每个画布像素在视野里占的角度等比变大，
     * 所有硬编码坐标、`GazeTarget.rect` 都不用动；`gazePoint()` 用同一份
     * `spec.width/height` 反算落点，命中判定自动跟着缩放。
     */
    val uiScalePercent: Int = UI_SCALE_DEFAULT,

    /** alpha43：控制台「前进 / 后退」一次跳多少秒。真机上 60 秒不一定合用。 */
    val seekStepSeconds: Int = SEEK_STEP_DEFAULT,

    /**
     * 18:2x：**2D 银幕宽度**（%）。墙不动、只改幕宽，张角随之变化；
     * 中心高度由 ProjectionMesh.screenCenterY() 按幕宽推导，各档墙裙恒定。
     */
    val screenWidthPercent: Int = SCREEN_WIDTH_DEFAULT,

    /**
     * 18:2x：**球面内容缩放**（%，数字变焦）。只对 180/220/270/360 生效，
     * 2D 恒传 1.0 —— 那边已经由银幕宽度调张角，叠两层会互相打架。
     * <100 档是「推远」，边缘会有极轻微拉伸（片源没有画面外内容）。
     */
    val contentScalePercent: Int = CONTENT_SCALE_DEFAULT,

    /**
     * 19:0x：**鱼眼张角**（°）。这是镜头的属性、不是片子的属性，所以放全局设置，
     * 不跟 `VrFormat` 一起按片记忆 —— 同一支 MKX200 拍的所有片子都是 200°。
     * 只对 `Projection.FISHEYE` 的片源生效，其它投影读都不读。
     */
    val fisheyeFovDeg: Int = FISHEYE_FOV_DEFAULT,

    /**
     * 19:0x：**鱼眼画面圆大小**（%）。圆没占满整幅时（黑边裁切、相机机内裁切），
     * 采样半径要按比例缩，否则会把圆外的黑边也卷进画面。
     * 只往小调（≤100）：圆比画面还大的情况是裁切，靠张角补偿，不是靠这个。
     */
    val fisheyeCirclePercent: Int = FISHEYE_CIRCLE_DEFAULT,

    /**
     * 19:2x：**鱼眼全画幅**（圆超出画幅、四角也被影像填满）。
     *
     * 与 [fisheyeCirclePercent] 是互斥的两种画幅形态，不是两个可叠加的旋钮：
     * - 关（默认）：画幅里能看见**完整的圆**，圆外是黑边/机身框 —— 网格是圆盘，
     *   采样只取圆内，圆外不生成几何；
     * - 开：圆大到被画幅**切掉四角**，整幅都是画面 —— 网格要改成铺满的矩形，
     *   半径按**半对角线**归一（四角正好 r=1），否则按圆盘铺会在四角留黑、
     *   边缘把像素拉成条纹。
     *
     * 大多数消费级鱼眼（VR180 相机、MKX / VRCA 的圆图）属于前者；
     * 单反/微单上装鱼眼镜头、机内裁成 16:9 或 3:2 的片子属于后者。
     */
    val fisheyeFullFrame: Boolean = false
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

        // ── alpha43：UI 缩放 ────────────────────────────────────────────
        /**
         * 缩放档位（%）。上限 140% 是**视场约束**，不是随便定的：片库平面
         * 2.4m × 1.4 = 3.36m，放在 2m 外横向约 80°，再放大边缘会被纸盒裁掉。
         * 默认 115% —— 用户明确要求「放大一些」，装上就该看到改善，而不是先去设置里找。
         * 只给档位不给连续滑杆：连续值要按十几次才到头，而这是戴镜前一次定好的参数。
         */
        val UI_SCALE_STEPS = intArrayOf(90, 100, 110, 115, 120, 130, 140)
        const val UI_SCALE_DEFAULT = 115
        const val UI_SCALE_MIN = 90
        const val UI_SCALE_MAX = 140

        // ── alpha43：快进 / 快退步长 ─────────────────────────────────────
        /** 秒。既有默认 60 保留在中间档，短片用户可降到 5~15 秒。 */
        val SEEK_STEP_CHOICES = intArrayOf(5, 10, 15, 30, 60, 120, 300)
        const val SEEK_STEP_DEFAULT = 60

        // ── 18:2x：银幕宽度 / 内容缩放 ──────────────────────────────────
        /** 上限 110% 由天花决定：幕头顶 = FLOOR_Y + H + SKIRT + FRAME_W < CEIL_Y。 */
        val SCREEN_WIDTH_STEPS = intArrayOf(80, 85, 90, 95, 100, 105, 110)
        const val SCREEN_WIDTH_DEFAULT = 100
        /** 95% 是「轻微推远」，越界只有 2.6%，clamp 后的边缘拉伸几乎看不见。 */
        val CONTENT_SCALE_STEPS = intArrayOf(95, 100, 105, 110, 115, 120)
        const val CONTENT_SCALE_DEFAULT = 100

        // ── 19:0x：单镜头鱼眼 ────────────────────────────────────────────
        /** 180~230°，5° 一档。230 以上没有消费级单镜头鱼眼，不做无效档位。 */
        val FISHEYE_FOV_STEPS = intArrayOf(180, 185, 190, 195, 200, 205, 210, 215, 220, 225, 230)
        const val FISHEYE_FOV_DEFAULT = 200
        /** 70~100%，5° 一档。只往小调，见字段注释。 */
        val FISHEYE_CIRCLE_STEPS = intArrayOf(70, 75, 80, 85, 90, 95, 100)
        const val FISHEYE_CIRCLE_DEFAULT = 100

        fun uiScaleLabel(percent: Int): String = "$percent%"
        // 只显示百分比：张角由 ProjectionMesh 的幕宽/距离算，这里再写一份 5.4/5.0
        // 就成了第二个真相源；真要显示角度应由渲染层传进来。
        fun screenWidthLabel(percent: Int): String = "$percent%"
        fun contentScaleLabel(percent: Int): String = "$percent%"
        fun fisheyeFovLabel(deg: Int): String = "$deg°"
        fun fisheyeCircleLabel(percent: Int): String = "$percent%"
        fun seekStepLabel(seconds: Int): String =
            if (seconds < 60) "$seconds 秒" else "${seconds / 60} 分钟"

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
    /** 18:2x：设置浮层页码。第 1 页 11 格排满，两项新参数放第 2 页，不压字号。 */
    val settingsPage: Int = 0,
    val items: List<MediaItemEntity> = emptyList(),
    /**
     * 20:2x：片库「本机解不了」标记，key = `mediaKeyOf(item)`。
     *
     * 后台按当前页探测 stsd（真实 mime + 宽高）后交给 `DecoderCaps.isUnsupported` 判定，
     * 只把**确定解不了**的片子放进来。**故意不写进 Room**：设备能力是静态的，
     * 这是「片源规格 × 本机能力」的派生结果；新增列要动 migration，而本工程曾因
     * 手写 DDL 与 Room 生成结果不一致触发 `fallbackToDestructiveMigration()` 把库删了。
     */
    val unsupportedKeys: Set<String> = emptySet(),
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
    /**
     * 19:2x：**明确拒绝**的片源（未缝合双鱼眼 360）。非 null 时播放页会盖一张独占说明卡，
     * [format] 同时已被降级成 2D 银幕原样显示。
     *
     * 只在「本侧判定拒绝」时置位；用户手动在格式页选了鱼眼就清掉（他要比我们更清楚）。
     */
    val refusal: Refusal? = null,
    val playing: Boolean = false,
    /**
     * 19:2x：**用户的播放意图**（`playWhenReady`），与 [playing]（画面真的在走）分开。
     *
     * 缓冲时 `playing` 是 false、它仍是 true。控制台的键形与键名必须看它，否则
     * 「按了播放 → 缓冲 → 键变回三角形 → 再按一下反而暂停」。离开前台要不要暂停、
     * 回来该不该说「已为你暂停」，也一律看它。
     */
    val wantPlay: Boolean = false,
    /** 19:2x：正在缓冲。控制台显示「缓冲中」，别让用户以为卡死。 */
    val buffering: Boolean = false,
    /** 19:2x：已缓冲到的时间位置，进度条据此画缓冲段。0 = 未知。 */
    val bufferedMs: Long = 0L,
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
    /**
     * 2.1.0：常驻错误卡的第二行「那我能做什么」。
     *
     * 原来这句写死「检查设备是否在线、账号是否过期」，对解码失败完全跑题 ——
     * 网络好好的，用户照着做一遍什么也没变。现在由 [com.gazereel.vr.player.PlaybackState.errorHint]
     * 按错误类型给：解码超限就说上限多少、该换什么版本。
     */
    val errorHint: String? = null,
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
     * V2.9：凝视是否已上膛。
     *
     * 进 VR 时是 false —— 塞手机、调头带的过程里手机一直在动，准星会扫过整个片库，
     * 这时候任何「停留即确认」都等于随机点击。必须由用户戴稳后按一次触发键解锁。
     * 离开 VR（onPause）时重新置 false，所以拿出来再放回去要重新按一次。
     */
    val gazeArmed: Boolean = false,
    /**
     * alpha35 / 02 起**废弃**：「戴稳了？」已改为头锁定层（相机空间渲染），
     * 不再需要把视线写进状态。这两个字段保留只为兼容旧构造调用，值恒为 0、无消费者。
     */
    val armPromptYaw: Float = 0f,
    val armPromptPitch: Float = 0f
) {
    val selectedItem: MediaItemEntity? get() = items.getOrNull(selectedIndex)

    /**
     * 19:1x：**该眼区域的像素宽高比**，鱼眼圆度补偿要用（SBS 取半幅、TAB 取半高）。
     *
     * 放在状态里而不是渲染器私有：设置第 2 页要把「补偿了没有、按多少补偿」写给人看，
     * 两处必须用同一个数 —— 各算一遍迟早会不一致。
     * 拿不到尺寸（条目没到 / 入库时没读到宽高）一律返回 1.0，即退回「不补偿」，
     * 宁可对正方形片源少做，也不能把规范片源改坏。
     */
    fun eyeAspect(): Float {
        val item = items.firstOrNull { it.id == currentMediaId } ?: return 1f
        var w = item.width
        var h = item.height
        if (w <= 0 || h <= 0) return 1f
        when (format.layout) {
            StereoLayout.SBS -> w /= 2
            StereoLayout.TAB -> h /= 2
            StereoLayout.MONO -> Unit
        }
        if (w <= 0 || h <= 0) return 1f
        return w.toFloat() / h.toFloat()
    }

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
         * alpha43：一页 9 格 = 中栏 **3 列 × 3 行**（`VrUiLayer.LIB_COLS`）。
         *
         * 从 4 列 × 3 行（12 格）改过来，是因为真机反馈「一行排 4 个图标有点挤」：
         * 4 列时每格 116px，3 列后 157px。格数从 12 降到 9 是为了给「卡外的名字」
         * 留出 42px 行距 —— 4 行时最后一行名字会压进底栏，见 `VrUiLayer.LIB_TOP` 注释。
         *
         * **订正旧注释**：这里原来写「一页 8 格 / 2×4 让正中 (512,256) 落在十字缝」，
         * 在 V2.50 换三栏时就已经不成立（4 列里 510–626 那格本来就盖住 x=512，
         * 常量也早改成了 12）。现在防误开靠 `gazeArmed` 上膛 + `GazeDetector.mustLeave`
         * （上膛那一刻压着的格子必须先移开、再回来才算一次选择），**不依赖格子排布**。
         * alpha42 真机未再出现「进 VR 自动打开视频」。
         */
        const val PAGE_SIZE = 9
    }
}
