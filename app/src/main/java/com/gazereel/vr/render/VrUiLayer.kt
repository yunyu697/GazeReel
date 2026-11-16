package com.gazereel.vr.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import com.gazereel.vr.core.FisheyePreset
import com.gazereel.vr.core.GazeTarget
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.Refusal
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.PosterRepository
import com.gazereel.vr.player.SubtitleParser
import com.gazereel.vr.ui.AdjustFeedback
import com.gazereel.vr.ui.AlignCountdown
import com.gazereel.vr.ui.Cabinet
import com.gazereel.vr.ui.MediaStack
import com.gazereel.vr.ui.PostureTracker
import com.gazereel.vr.ui.VrOverlay
import com.gazereel.vr.ui.VrScreen
import com.gazereel.vr.ui.VrSettings
import com.gazereel.vr.ui.VrUiState
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import com.gazereel.vr.data.db.mediaKeyOf

/**
 * PRD 4.4 / 4.3：把世界锁定 UI 用 Canvas 画到一张纹理，再由 VrRenderer
 * 分别贴到左右眼的 2 米平面上。凝视进度环画在同一张纹理里，
 * 因此左右眼必然同时出现、位置一致，双眼融合不会冲突。
 *
 * 阶段二：片库海报位图、断点进度提示、字幕行与新增设置开关全部在这张纹理上合成。
 */
class VrUiLayer(
    private val posterLoader: (String) -> Bitmap? = { null }
) {

    companion object {
        const val W = 1024
        const val H = 512

        /**
         * alpha3 / 15:0x：位图绘制倍率（逻辑坐标不变，只提分辨率）。
         *
         * 之前 1024×512 的 UI 纹理直接贴到 2 米外的平面上，而真机离屏靶是
         * 2656×1220 —— 那 1.8 米宽的平面在靶上占约 1430px，纹理只有 1024px，
         * **被拉伸 1.4 倍才铺满**，所以文字边缘一直是糊的。字号再调大也只是把
         * 糊的字放大，读起来仍然吃力。
         *
         * 现在画布实际按 2048×1024 分配，`render()` 里统一 `canvas.scale(2f)`，
         * 所有绘制坐标、GazeTarget.rect 仍然用 1024×512 这套逻辑单位，一行都不用改。
         * 代价是位图内存 2MB → 8MB，可接受（真机堆 512MB）。
         */
        const val TEX_SCALE = 2
        // V2.50 / 01：配色全部改从 VrTheme 取（APK 序列化真值），
        // 这里保留旧名字，是为了让 1864 行绘制代码不用一次性改完——
        // 逐个函数重写时再把调用点换成 VrTheme.xxx，避免「改名 + 改布局」混在一次改动里。
        private val C_PANEL = VrTheme.Panel
        private val C_PANEL_HI = VrTheme.Card
        private val C_BORDER = VrTheme.Line2
        /**
         * V2.50：强调色从品牌青蓝 `#67E8F9` 改为 SourceVR 的琥珀 `#FFD570`。
         *
         * 原来那个青蓝是「科技感」的默认答案，但它和视频画面抢注意力：
         * 用户自己的片子里大量是暖色（室内、日落、肤色），青蓝压在上面像一层滤镜。
         * 琥珀是画面里本来就有的颜色，只在「现在该点这个」时出现，反而更跳。
         */
        private val C_ACCENT = VrTheme.Accent
        private val C_TEXT = VrTheme.Ink
        private val C_MUTED = VrTheme.Ink2
        private val C_POSTER = VrTheme.SubPanel
        private val C_DIM = VrTheme.Ink4
        /** V2.38：倒计时最后一程还在动时的提示色。不用红色——那是报错，这里是「再稳一下」。 */
        private val C_WARN = VrTheme.Warn
        /** 20:2x：片库「本机解不了」角标是失败态，用 Danger，不用 Warn。 */
        private val C_DANGER = VrTheme.Danger

        // 片库格子：4 列 × 2 行，每格 232×165px。
        // V2.13：格子在画布里变高（原来 106px），配合片库专用的大平面，
        // 每格从约 10.0°×5.3° 变成约 14.0°×10.8°，标题从 17px 提到 26px。
        private const val CELL_W = 232f
        /**
         * V2.16：格子从 165px 收到 145px，把底栏（424–490）腾出来放
         * 「返回播放 / 返回全部叠」。145px 在 2.4×1.2 米的平面上约 9.4°，
         * 仍然远高于「单目标 ≥6°×4°」。两行顶部 114 / 266，正中 256 仍落在行间缝里，
         * 准星静止时压不到任何一格——这条性质不能因为改尺寸丢掉。
         */
        private const val CELL_H = 145f
        private val CELL_LEFT = floatArrayOf(24f, 272f, 520f, 768f)
        private val CELL_TOP = floatArrayOf(114f, 266f)

        /**
         * V2.50 / 01：片库三栏。
         *
         * **比例不是照抄 SourceVR 的。** 它的三栏是 14.8% / 40.6% / 14.8%，
         * 因为操作工具是 Daydream 手柄的激光指针——像素级指哪打哪，卡片可以很小。
         * 我们只有凝视：一个目标至少要占 6°×4°（这条是 V2.16 定的），
         * 按 1024px / 61.9° ≈ 16.5 px/度 折算，卡片宽不得小于约 99px。
         *
         * 照抄 40.6% 的中栏，4 列卡片只剩 5.4°，会掉到门槛以下。
         * 所以中栏加到 54.7%（560px），卡片 116×72 = 7.0°×4.7°，两样都过线。
         * 左栏同时从 7 行收到 6 行、行高从 44 提到 60（2.9° → 3.9°），
         * 「返回播放」从底栏挪到右栏做成一张大卡——那是戴镜后最常用的一个动作，
         * 值得占掉整块右栏，而不是和「退出」挤在同一列。
         */
        private const val COL_T = 20f
        private const val COL_B = 492f
        private const val COL_L = 40f
        private const val COL_LW = 164f
        private const val COL_M = 222f
        private const val COL_MW = 560f
        private const val COL_R = 800f
        private const val COL_RW = 184f
        /** alpha43：左栏 4 行（上一页/下一页已移到底栏），行高 70、间距 36。 */
        private const val RAIL_H = 70f
        /**
         * alpha43：中栏从 4 列 × 3 行改成 **3 列 × 3 行**，一页 9 格
         * （`VrUiState.PAGE_SIZE` 同步改 9）。
         *
         * 真机反馈「一行排 4 个图标有点挤」：4 列时每格只有 116px、格间距 16px，
         * 卡片名挤成一行还容易和邻格看串。3 列后每格 157px（+35%），格间距 20.5px。
         *
         * **为什么不是 4 行**：`cell()` 把名字画在卡外 `rect.bottom + 22f`。
         * 4 行时最后一行 bottom = 422，名字落到 444 —— 正好压进底栏（432–480），
         * 和页码 / 分页按钮叠字（alpha43 首版快照实测）。3 行时行距 42px，
         * 名字（15px 高）落在行间空隙正中：上一行 176 底、名字 198、下一行 218 顶，
         * 最后一行 bottom 404、名字 426，离底栏 6px，谁都不压。
         */
        private const val LIB_COLS = 3
        private const val LIB_W = 157f
        private const val LIB_H = 72f
        private val LIB_LEFT = floatArrayOf(246f, 423.5f, 601f)
        private val LIB_TOP = floatArrayOf(104f, 218f, 332f)

        /** 20260712_143022 这类相机 / 手机命名。 */
        private val STAMP_COMPACT =
            Regex("(\\d{4})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})(\\d{2})")

        /** 2026-07-12 14.30.22 / 2026-07-12 14:30 这类导出后改名。 */
        private val STAMP_LOOSE =
            Regex("(\\d{4})-(\\d{2})-(\\d{2})[ _T](\\d{2})[:.](\\d{2})(?:[:.](\\d{2}))?")

        /** 控制台相对舒适中心再低多少（约 13°，与旧版的 −0.46 米等值）。 */
        private const val CONSOLE_DROP_RAD = 0.2269f

        // 17:7x：唤出热区与徽标的对称轴。
        //
        // **基准轴 = 参考系水平线（pitch 0）**，球面 / 180° / 鱼眼的内容正前方
        // 就是这条线。影院（PLANE_2D）不直接用它：用户认定的「当前正前方」是
        // 正在看的银幕中心，比水平线高 atan(screenCenterY / SCREEN_DISTANCE)
        // ≈ 7.1°，且随幕宽档位变化。实际轴由 [wakeAxisDeg] 按投影现算。
        //
        // 2026-11-16 真机反馈：轴钉在 0° 时，用户低头约 90° 才碰到下区、
        // 抬头 78° 反而越过上区。原因正是这 7.1° 差——按用户口径，
        // 「低头/抬头 78°」必须从当前看的内容中心量起，不是从水平线量起。
        //
        // 上下由「轴线 ± 单侧张角」推导，不写两个独立的字面量：只要改 SPAN，
        // 两侧同步移动，对称不可能被漏改破坏。
        const val WAKE_AXIS_DEG = 0f

        // 20:1x：用户给的口径「调到 75-81 度」，仍然是按两条边说话。
        // 内缘（靠轴线）±75°、外缘 ±81°，中心与半高由这两条边推导。
        // 动机：38-44° 太靠中间、看片时容易误触发；外移到视野边缘后，只有刻意
        // 抬头/低头才进得去。误触发交给「位置」挡，不再靠把热区做小来挡。
        const val WAKE_INNER_DEG = 75f
        const val WAKE_OUTER_DEG = 81f
        const val WAKE_SPAN_DEG = (WAKE_INNER_DEG + WAKE_OUTER_DEG) / 2f
        /**
         * 上下带宽：内缘 ±75°、外缘 ±81° → 中心 ±78°、半高 **3°**、每条 **6° 高**。
         *
         * 19:1x 定的是 38°~44°（中心 ±41°），20:1x 用户判「太靠中间，容易误触发」，
         * 整体外移 37° 到 75°~81°，带宽仍是 6°、仍关于 0° 对称。
         * 带宽不跟着加宽：带子越厚，越容易在正常观影的抬头动作里踩进来。
         *
         * 半高由两条边推导（`(外缘 − 内缘) / 2`），和中心一样只有一个来源。
         */
        const val WAKE_HALF_DEG = (WAKE_OUTER_DEG - WAKE_INNER_DEG) / 2f

        /**
         * 横向半宽 **±12.6°（整条 25.2°）**。
         *
         * 20:1x 用户：「唤醒条横向太短了，非常难找到」。18:5x 曾从 ±6.6° 收到 ±3.2°
         * 是为了压误触发；现在带子外移到 ±75°~81°，误触发由**位置**挡住，
         * 横向就可以放宽，让「刻意抬头找那条线」时更容易压中。
         *
         * 这个数不是随手取的：细线 quad 的横向张角必须严格等于命中区宽度，
         * 而 quad 又必须和纹理同宽高比。纹理 1024×64（宽高比 16）、quad 高 0.056 m，
         * 于是 quad 宽 = 0.056×16 = 0.896 m，2 米处张角 2·atan(0.896/4)=25.25°，
         * 半宽取 12.6°。`check_wake_zone.py` 会把这两条等式钉死。
         */
        const val WAKE_HALF_WIDTH_DEG = 12.6f

        // 视觉走独立纹理 + 独立 quad，不画进 1024×512 主画布：
        // 主画布在播放页只覆盖 ±16.7°，±78° 远在画布之外，画不出来；
        // 而把主画布放大到能装下 ±78°，字幕与按钮的字号比例会一起塌掉。
        //
        // 18:5x：提示从「胶囊 + 文字 + 进度环」改成**一条细线**（用户要求）。
        // 纹理 1024×64，线厚 [WAKE_LINE_PX] px ≈ 0.35°（真机约 5px），其余全透明。
        const val WAKE_W = 1024
        const val WAKE_H = 64
        const val WAKE_LINE_PX = 14f

        /**
         * quad 尺寸（米，2 米处）：0.896×0.056 ≈ **25.2°×1.6°**，宽高比 16:1。
         *
         * 必须同时满足两件事：宽高比 == 纹理宽高比（1024/64 = 16，否则拉伸），
         * 横向张角 == 热区宽度（`2 × WAKE_HALF_WIDTH_DEG`，否则看着线外也触发）。
         */
        const val WAKE_QUAD_W = 0.896f
        const val WAKE_QUAD_H = 0.056f
    }

    /**
     * UI 平面的物理尺寸与俯角。
     *
     * V2.13：片库单独用一块更大的平面。原因不是「想大一点」，是分辨率账：
     * 1.8×0.9 米在 2 米外只占约 48.5°×25.4°，1024×512 的画布摊到这个角度上，
     * 17px 的标题落到屏幕里只剩不到 9 个像素——真机上就是「看不清、认不出是哪一部」。
     */
    /**
     * UI 平面的物理尺寸与朝向。
     *
     * V2.19c：朝向存的是**角度**（yaw / pitch），不是「米」。平面要整块转向候选方向，
     * 而转向必须发生在几何里（`ProjectionMesh.uiQuad`）——平移会让大角度下的卡片
     * 被压扁到 `cos θ`，抬头 60° 就只剩一半高。存角度还有一个好处：
     * `gazePoint` 直接用这两个角度，不需要 atan2 反解。
     */
    data class PanelSpec(
        val width: Float,
        val height: Float,
        val yaw: Float,
        val pitch: Float,
        /**
         * alpha35 / 02：头锁定层。true 时这块 UI 在**相机空间**渲染（恒在眼前正中、
         * 与头姿态零耦合），yaw/pitch 不参与。用于「戴稳了？」这类塞手机/调头带时
         * 必须始终可见、又绝不能跟着姿态抖的屏。
         */
        val headLocked: Boolean = false
    )

    /**
     * V2.19 / B5：俯角只有「控制台比正前方低 13°」这一条相对规则，**没有绝对俯角**。
     *
     * 上一版把 −0.46 米写死在控制台上、片库与浮层一律 0，看起来是对的，
     * 因为「正前方」被默认成了水平前方。躺下之后脸朝上，画面还挂在天花板前面，
     * 用户只能仰头——这就是「脖子累」最直接的来源。
     *
     * 修法**不是**把 UI 平面往上挪（那样 UI 单独飞走、视频留在原地，更怪），
     * 而是缓转整个参考系：确认「把正前方挪到这里」后，Provider 用 0.8 秒
     * 把世界转到用户此刻的视线方向，视频 / UI / 字幕 / 准星作为一个整体一起转。
     * 参考系换了之后，这里的相对规则自动成立，不需要任何额外补偿。
     *
     * V2.19c：姿态确认卡是唯一的例外——它的平面**转到候选姿态的视线正前方**。
     * 原来它跟控制台一样在世界锁定的中心，于是卡片永远出现在「正前方附近」，
     * 用户为了确认还得转头去找它，而「转头」本身就在改变他要确认的那个姿势。
     */
    fun panelSpec(state: VrUiState): PanelSpec {
        // alpha43：UI 整体缩放只改**平面的物理尺寸**，不动 1024×512 画布里的任何坐标。
        // 三个理由：① 1900 行绘制代码与全部 GazeTarget.rect 一律不用改；
        // ② gazePoint() 用同一份 spec.width/height 反算准星落点，命中判定自动同步；
        // ③ 放大的是「每像素占多少度」，文字与图标一起变清楚，间距也等比放大，不会变挤。
        // 上限 140% 由 VrSettings 的档位约束，避免片库平面被推出纸盒视场。
        val k = state.settings.uiScalePercent / 100f
        // alpha3 / 15:0x：**所有界面统一到 2.4×1.2**，不再让播放页/设置页用 1.8×0.9。
        //
        // 旧值 1.8×0.9 是「控制台只占画面下方一条」留下的。问题是同一张 1024×512
        // 画布铺到更小的平面上，每度像素从 16.5 涨到 21.1 —— 也就是说**同样的字号
        // 在控制台上比片库小 22%**。而控制台字号本来就比片库小（15 vs 25），
        // 两者相乘差到 2 倍以上，真机反馈就是「片库调大有用，控制台和设置还是看不清」。
        //
        // 尺寸统一之后，「1024px 画布 = 约 69°（115% 时）」这一条对四个界面同时成立，
        // VrTheme 里那句「字号可以 1:1 搬」的换算前提才真正对得上。
        // 代价是控制条的角度变大，所以 drawPlayer() 同步把板宽收窄到 760px（约 51°）。
        val w = 2.4f * k
        val h = 1.2f * k
        // alpha35 / 02：「戴稳了？」改成**头锁定层**，不再每帧跟着视线重建世界 quad。
        //
        // 旧做法是主线程每帧把当前视线写进 armPromptYaw/Pitch，GL 线程再据此重建
        // 世界坐标 quad；两线程姿态不同步，面板相对视线来回游——这就是「闪」。
        // 本屏不注册任何凝视目标，所以直接走相机空间：恒在眼前正中、与姿态零耦合。
        // 头锁定渲染在 VrRenderer 里用「去掉姿态旋转」的 MVP 实现（IPD 视差保留）。
        if (!state.gazeArmed) {
            return PanelSpec(w, h, 0f, 0f, headLocked = true)
        }
        // V2.38：重定倒计时期间，面板朝向 = 当前视线方向（每帧由 coordinator 写下）。
        // 整块面板因此贴在眼前跟着头走：卡片永远在「你正在看的地方」偏下一点点，
        // 用户不需要去找它，也就不会为了找它而把视线从想要的方向上挪开。
        state.alignCountdown?.let {
            return PanelSpec(w, h, it.yaw, it.pitch)
        }
        val candidate = state.postureCandidate
        if (candidate != null) {
            // 候选方向 = 用户此刻正在看的方向。卡片就摆在那儿，居中。
            return PanelSpec(w, h, state.postureYaw, candidate)
        }
        return when {
            state.screen == VrScreen.PLAYER && state.overlay == VrOverlay.NONE ->
                PanelSpec(w, h, 0f, -CONSOLE_DROP_RAD)
            state.screen == VrScreen.LIBRARY && state.overlay == VrOverlay.NONE ->
                // 与 w/h 等价，写成 w/h 是为了让「四个界面同一个尺寸」在代码里没有特例分支。
                PanelSpec(w, h, 0f, 0f)
            else -> PanelSpec(w, h, 0f, 0f)
        }
    }

    private val bitmap =
        Bitmap.createBitmap(W * TEX_SCALE, H * TEX_SCALE, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val targets = mutableListOf<GazeTarget>()

    /**
     * render() 在 GL 线程写、输入层在主线程读的一帧快照。
     *
     * alpha35：**目标列表与进度条轨道范围必须一起发布**。先写一版用两个
     * 独立的 volatile 标量存轨道，在 render() 开头清零、drawPlayer() 里才赋值；
     * 中间那几毫秒若主线程恰好确认一次 SeekAt，就会读到 0 并退回兜底 50% ——
     * 用户明明盯着 30% 却跳到一半。现在两者装进同一个不可变对象，一次 volatile
     * 写完成发布，读侧永远拿到「同一帧的目标 + 同一帧的轨道」。
     */
    private data class UiTargets(
        val list: List<GazeTarget>,
        val seekLeft: Float,
        val seekRight: Float
    )

    @Volatile
    private var snapshot = UiTargets(emptyList(), 0f, 0f)

    /** 本帧正在绘制的轨道范围；只在 GL 线程用，随 [snapshot] 一次性发布。 */
    private var pendingSeekLeft = 0f
    private var pendingSeekRight = 0f

    /** 本帧的真实凝视落点；drawPlayer() 用它画「将跳到这里」的轨道标记。 */
    private var gazeCursor: PointF? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_MUTED; textAlign = Paint.Align.CENTER
    }
    /** 片库里「日期 + 时刻」用的粗体；与 text 分开是为了互不污染字号。 */
    private val strong = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_TEXT; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = C_ACCENT; style = Paint.Style.STROKE; strokeWidth = 8f
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** V2.19：真画面底部的身份条渐变，每次用时重设 shader。 */
    private val scrimPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun currentTargets(): List<GazeTarget> = snapshot.list

    /**
     * alpha35：把「按凝视点连续取值」这类动态动作，在 dispatch 前换成具体值。
     *
     * `GazeTarget.action` 是画界面时注册的静态值，那会儿准星还不知道会落在哪儿。
     * 旧版把进度条切成 5 段、每段给一个段中心的固定 fraction，于是用户明明盯着
     * 30% 的位置，跳转却落到 20% 或 40%——真机上就是「进度在凝视点左右飘」。
     *
     * 照 Android `SeekBar` / Material `Slider` 的契约：触点 x 线性映射到 value。
     * 这里的 x 取 [gazePoint]，与 [drawCursor] 用的是同一个落点，所以「看到的准星」
     * 和「跳到的位置」是同一个数。凝视通道与触发键通道都必须走这里。
     */
    fun resolveAction(action: VrAction, rotation: FloatArray, state: VrUiState): VrAction {
        if (action !== VrAction.SeekAt) return action
        // 一次拿到同一帧的轨道范围；不能用两个独立字段，见 UiTargets 的注释。
        val snap = snapshot
        val left = snap.seekLeft
        val right = snap.seekRight
        // 控制台已经收起 / 目标还没注册时，不猜一个位置：宁可不动，也不要乱跳。
        if (right <= left) return VrAction.SeekTo(0.5f)
        val x = gazePoint(rotation, state).x
        return VrAction.SeekTo(((x - left) / (right - left)).coerceIn(0f, 1f))
    }

    /** 准星射线与 UI 平面的交点命中哪个目标；凝视与触发键共用此判定。 */
    fun targetAt(rotation: FloatArray, state: VrUiState): GazeTarget? {
        val point = gazePoint(rotation, state)
        return currentTargets().firstOrNull { it.rect.contains(point.x, point.y) }
    }

    /** 播放控制台置于视野下方约 13°，其余界面居中（PRD 3.5）。 */
    fun panelPitch(state: VrUiState): Float = panelSpec(state).pitch

    /**
     * 当前投影下用户认定的「正前方」仰角（度，抬头为正）。
     *
     * 用户口径 2026-11-16：「低头 / 抬头 78°」必须从**当前正在看的内容中心**
     * 量起，不是从参考系水平线量起。
     * - `PLANE_2D`：银幕中心，仰角 = atan(screenCenterY / SCREEN_DISTANCE)，
     *   幕宽档位会改 centerY，所以必须按 [VrSettings.screenWidthPercent] 现算。
     * - 球面 / 180° / 鱼眼：内容以视线为中心，仍是基准 0°。
     *
     * 命中区与细线 quad 必须同时用这个函数的结果，否则「看到的线」和
     *「压中的区」会差一个银幕仰角——正是真机上的那个偏差。
     */
    fun wakeAxisDeg(state: VrUiState): Float =
        if (state.format.projection == Projection.PLANE_2D) {
            Math.toDegrees(
                atan2(
                    ProjectionMesh.screenCenterY(state.settings.screenWidthPercent).toDouble(),
                    ProjectionMesh.SCREEN_DISTANCE.toDouble()
                )
            ).toFloat()
        } else WAKE_AXIS_DEG

    /** 上区中心：当前内容正前方 + [WAKE_SPAN_DEG]。 */
    fun wakeUpDeg(state: VrUiState): Float = wakeAxisDeg(state) + WAKE_SPAN_DEG

    /** 下区中心：当前内容正前方 − [WAKE_SPAN_DEG]。 */
    fun wakeDownDeg(state: VrUiState): Float = wakeAxisDeg(state) - WAKE_SPAN_DEG

    /**
     * 头部姿态 → 准星在 UI 画布上的落点。面板尺寸与朝向都会影响映射。
     *
     * V2.19c：面板不再恒在正前方（姿态确认卡会整块转到候选方向），
     * 所以落点必须减掉面板自己的朝向，否则卡片上的按钮永远点不中。
     */
    fun gazePoint(rotation: FloatArray, state: VrUiState): PointF {
        val spec = panelSpec(state)
        // alpha35 / 02：头锁定层的落点恒为画布正中——画布跟着头走，用户永远在看中心。
        if (spec.headLocked) return PointF(W / 2f, H / 2f)
        val fx = -rotation[8]
        val fy = -rotation[9]
        val fz = -rotation[10]
        val yaw = atan2(fx.toDouble(), -fz.toDouble())
        val pitch = asin(fy.coerceIn(-1f, 1f).toDouble())
        val halfWidth = atan2((spec.width / 2f).toDouble(), 2.0)
        val halfHeight = atan2((spec.height / 2f).toDouble(), 2.0)
        val x = W / 2.0 + (yaw - spec.yaw) / halfWidth * (W / 2.0)
        val y = H / 2.0 - (pitch - spec.pitch) / halfHeight * (H / 2.0)
        return PointF(x.toFloat(), y.toFloat())
    }

    /** V2.43：当前凝视目标，供片库的「悬停放大」用（只影响绘制，不改命中区）。 */
    private var activeGazeTargetId: String? = null

    fun render(
        state: VrUiState,
        gazeTargetId: String?,
        gazeProgress: Float,
        cursor: PointF? = null
    ): Bitmap {
        bitmap.eraseColor(Color.TRANSPARENT)
        // alpha3 / 15:0x：逻辑坐标仍是 1024×512，位图是 2 倍。每帧重置再缩放，
        // 不依赖上一帧残留的矩阵（Canvas 的 matrix 跨帧保留，漏重置就会越缩越小）。
        canvas.setMatrix(null)
        canvas.scale(TEX_SCALE.toFloat(), TEX_SCALE.toFloat())
        targets.clear()
        activeGazeTargetId = gazeTargetId
        // alpha35：轨道每帧重算；控制台收起时归零，避免拿上一帧的旧轨道换算。
        pendingSeekLeft = 0f
        pendingSeekRight = 0f
        gazeCursor = cursor

        // V2.19c：姿态确认卡出现时，**只画这一张卡**。
        //
        // 它要摆在候选方向的视线正前方，而整个面板只有一个（`panelSpec` 会把平面
        // 整块挪过去）。如果这时还画下层界面，控制台和片库会跟着一起平移几十度——
        // 那正是「检测到就动画面」这个禁忌本身。下层不画，就没有东西会跳。
        // 视频是另一层，完全不受影响，所以「画面一帧不动」仍然成立。
        // V2.38：倒计时那一层同样独占，而且**不注册任何凝视目标**。
        // 此时用户正在靠转头摆姿态，任何可点的东西都会被顺眼扫到。
        val countdown = state.alignCountdown
        val postureUp = state.gazeArmed && state.postureCandidate != null
        // 15:5x：调节条改为**独占一层**，不再叠在下层界面上。
        //
        // 旧做法是先把片库/设置/控制台画完，再把调节卡盖上去 —— 用户看到的是
        // 「两块 UI 打在一起」，而且那张卡在画布 y 48–252，换算到 −13° 的面板上
        // 正好落在视线 **−10.7° ~ +1.6°**，也就是画面正中偏上，挡住的恰恰是
        // 调亮度时要看着的那块内容。
        //
        // 独占之后不需要再考虑「盖不盖得住」，也不会出现「点了下层按钮」这种
        // 目标顺序问题（原来的做法要先保存下层 targets 再恢复，专门防这个）。
        val adjust = state.feedback?.takeIf { it.untilMs > SystemClock.elapsedRealtime() }
        // 19:2x：被明确拒绝的片源（未缝合双鱼眼 360）单独占一层。
        // 排在这里而不是压在 drawMessage 上：它比「播放失败」更该被看见 ——
        // 画面是在动的、看起来「能看」，不说清楚用户根本不会发现哪里不对。
        // 上膛前不抢这层：那时准星还不确认任何目标，「戴稳了？」的提示必须先出来，
        // 否则会出现「卡片摆在那儿、按钮点不动、也没有任何解释」的死角。
        val refusal = activeRefusal(state)
        if (countdown != null) {
            drawAlignCountdown(countdown)
        } else if (refusal != null) {
            drawRefusal(refusal)
        } else if (postureUp) {
            drawPosturePrompt(state)
        } else if (adjust != null) {
            drawAdjustFeedback(state, adjust)
        } else {
            when (state.overlay) {
                VrOverlay.FORMAT -> drawFormat(state)
                VrOverlay.SETTINGS -> drawSettings(state)
                VrOverlay.NONE -> when (state.screen) {
                    // V2.18：柜子层盖在片库上，不另开一个 screen——它只换「看哪个片源」，
                    // 其余状态（翻页、断点、播放器）都不用重建。
                    VrScreen.LIBRARY -> if (state.cabinetsOpen) drawCabinets(state) else drawLibrary(state)
                    VrScreen.PLAYER -> drawPlayer(state)
                }
            }

            // V2.9：还没上膛就盖一层提示。这里不注册任何目标，凝视此时也不生效。
            if (!state.gazeArmed) drawArmPrompt(state)
        }

        // V2.46 / P0：统一提示层压在所有界面之上，包括「控制台已淡出」的播放页。
        drawMessage(state)

        // 目标与轨道范围一次发布：输入层不会读到「新目标 + 旧轨道」的半帧状态。
        snapshot = UiTargets(targets.toList(), pendingSeekLeft, pendingSeekRight)

        // V2.14：光标跟着视线走，不再钉在画布正中。
        // 原来画在 (W/2, H/2)，而控制台的平面在 −13°，准星与真实视线差着十几度，
        // 用户看到的是一个「不动的小圈」，只能靠触发临近的按钮去猜。
        cursor?.let { drawCursor(it, state) }

        // 17:6x：唤醒区的环画在独立徽标纹理里（热区在 ±38°，主画布只覆盖 ±16.7°，
        // 在这里画只会被画布边界裁掉 —— 用户看到的「圈被遮挡」就是这个）。
        if (gazeTargetId != null && gazeProgress > 0f &&
            !gazeTargetId.startsWith("wakeConsole")
        ) {
            val hit = targets.firstOrNull { it.id == gazeTargetId }
            if (hit != null) {
                // alpha35：进度环围绕**真实凝视点**，不再围绕目标矩形中心。
                // 进度条上这条最要命：准星停在条内偏左，环却画在段中心，
                // 用户以为自己瞄的是环的位置，跳转也按段中心算，两边一起飘。
                // 现在环、准星、跳转落点三者是同一个 x。
                val center = gazeCursor ?: PointF(hit.rect.centerX(), hit.rect.centerY())
                val radius = 30f
                val oval = RectF(
                    center.x - radius, center.y - radius,
                    center.x + radius, center.y + radius
                )
                ring.strokeWidth = 8f
                ring.color = C_ACCENT
                canvas.drawArc(oval, -90f, 360f * gazeProgress.coerceIn(0f, 1f), false, ring)
            }
        }
        return bitmap
    }

    /**
     * V2.14：视线光标。
     *
     * 三条例外，都是为了「不影响看片」：
     * - 设置里关掉就完全不画；
     * - 没上膛不画（那时凝视本来就不生效，画出来只会让人以为坏了）；
     * - 播放中控制台收起时不画——那一刻用户在纯看片，画面正中多一个点就是干扰。
     */
    private fun drawCursor(point: PointF, state: VrUiState) {
        if (!state.settings.cursorEnabled) return
        if (!state.gazeArmed) return
        if (state.screen == VrScreen.PLAYER &&
            state.overlay == VrOverlay.NONE && !state.consoleVisible && !state.ipdTuning
        ) return
        // 视线落到面板之外时，画面里没有可瞄的东西，画一个贴边的假光标只会误导。
        if (point.x < 0f || point.y < 0f || point.x > W || point.y > H) return

        ring.strokeWidth = 3f
        ring.color = 0xCCEAF2FA.toInt()
        canvas.drawCircle(point.x, point.y, 11f, ring)
        fill.color = C_ACCENT
        canvas.drawCircle(point.x, point.y, 3f, fill)
    }

    /**
     * V2.9：「戴稳了按一下触发键」这一屏。
     *
     * 它存在的理由不是礼貌，是安全：塞手机进纸盒、拉头带、对焦的整个过程里
     * 手机一直在动，准星会扫过整个片库。只要凝视还在工作，就会有卡片被「停留」确认掉。
     * 所以进 VR 后凝视默认关闭，这一屏是唯一还亮着的东西，而它只接受物理按键。
     */
    private fun drawArmPrompt(state: VrUiState) {
        fill.color = 0xB3101013.toInt()
        canvas.drawRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), fill)

        val card = RectF(212f, 140f, 812f, 380f)
        fill.color = 0xF016161A.toInt()
        canvas.drawRoundRect(card, 26f, 26f, fill)
        stroke.color = C_ACCENT
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(card, 26f, 26f, stroke)

        heading("戴稳了？", 212f)
        label(
            "按一下纸盒上的触发键开始",
            W / 2f, 276f, 34f, C_ACCENT, Paint.Align.CENTER
        )
        label(
            "开始之前凝视不生效 · 塞手机、调头带时不会误开视频",
            W / 2f, 318f, 22f, C_MUTED, Paint.Align.CENTER
        )
        // 15:0x：顺手修一处文案错误 —— 全工程 `PageFlip` 只有底栏按钮一个触发源，
        // 没有摇头手势。alpha2 只改了片库那处，这屏漏了，仍写着「摇头翻页」，
        // 会让人真的去摇。按 R2 以「底栏翻页」为唯一口径。
        label(
            "开始之后：凝视 ${"%.1f".format(state.settings.gazeDurationMs / 1000f)} 秒确认 · 长按返回 · 底栏翻页",
            W / 2f, 350f, 22f, C_MUTED, Paint.Align.CENTER
        )
    }

    /**
     * V2.46 / P0：把 `state.message` 真正画出来。
     *
     * 原来全工程只有片库底栏一处消费它（15px 暗灰小字），播放页 / 格式 / 设置 /
     * 倒计时结束后的提示全是静默的：「此编码当前设备不支持」「共享已断开」
     * 「正前方已定」用户一个字都看不到，只会以为自己没对准按钮。
     *
     * 两类分开处理：
     * - 短反馈（`messageUntilMs > 0`）：顶部一条小胶囊，不挡视线；
     * - 播放错误（`messageUntilMs == 0`，`onPlaybackState` 的约定）：中央一张常驻卡，
     *   并**清空下层凝视目标**——被它盖住的按钮不该还能被瞄到。卡上给「重试」
     *   和「回片库」两个真实动作，戴镜状态下不用摘手机就能恢复。
     */
    private fun drawMessage(state: VrUiState) {
        val msg = state.message ?: return
        // 倒计时是独占层，它自己会画完成/取消说明；这里不叠加第二块卡。
        if (state.alignCountdown != null) return
        // 19:2x：拒绝卡也是独占层，且它的结论比任何短提示都重要。
        if (activeRefusal(state) != null) return
        val persistent = state.messageUntilMs == 0L

        if (!persistent) {
            // 19:0x：条状提示按栅格取 3 栏（584），不再是 520 的孤立宽度。
            val pill = UiGrid.centered(3, 14f, UiGrid.H_ROW)
            fill.color = 0xF2161A20.toInt()
            canvas.drawRoundRect(pill, VrTheme.R_PANEL, VrTheme.R_PANEL, fill)
            stroke.color = C_ACCENT
            stroke.strokeWidth = 2f
            canvas.drawRoundRect(pill, VrTheme.R_PANEL, VrTheme.R_PANEL, stroke)
            label(
                ellipsize(msg, pill.width() - 48f, small),
                W / 2f, pill.centerY() + 9f, VrTheme.ITEM, C_TEXT, Paint.Align.CENTER
            )
            return
        }

        targets.clear()
        // 2.1.0：第二行不再是「检查设备是否在线、账号是否过期」那句通用话 ——
        // 解码失败时网络明明没问题，照着做一遍什么都不会变。现在由播放器按错误
        // 类型给出具体出路（见 PlaybackState.errorHint），长句折两行，卡片跟着长高。
        small.textSize = VrTheme.BODY
        // 2.1.0-alpha2：诊断句变长了（「这个尺寸下本机 VP9 最高 30fps，此片 60fps；
        // 转成 30fps 就能播，分辨率不用降」这类），两行会截成省略号把结论吃掉，
        // 所以放到三行，卡片按实际行数长高。19:0x 换成 BODY 21，行高 24 仍够。
        val hintLines = state.errorHint?.takeIf { it.isNotBlank() }
            ?.let { wrapText(it, small, UiGrid.spanW(3) - 72f, 3) }
            .orEmpty()
        val buttonTop = when (hintLines.size) {
            0 -> 290f
            1 -> 296f
            2 -> 318f
            else -> 342f
        }
        // 19:0x：失败卡按栅格取 3 栏（584），两个按钮各 1 栏、居中成一行。
        val card = UiGrid.centered(3, 148f, buttonTop + UiGrid.H_ROW + 16f - 148f)
        fill.color = 0xF61A1214.toInt()
        canvas.drawRoundRect(card, VrTheme.R_PANEL, VrTheme.R_PANEL, fill)
        stroke.color = C_WARN
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(card, VrTheme.R_PANEL, VrTheme.R_PANEL, stroke)
        heading("播放没成功", 208f)
        label(
            ellipsize(msg, card.width() - 72f, small),
            W / 2f, 252f, VrTheme.ITEM, C_TEXT, Paint.Align.CENTER
        )
        if (hintLines.isEmpty()) {
            label(
                "检查设备是否在线、账号是否过期；也可以直接重试这一部。",
                W / 2f, 282f, VrTheme.BODY, C_MUTED, Paint.Align.CENTER
            )
        } else {
            hintLines.forEachIndexed { i, line ->
                label(line, W / 2f, 282f + i * 24f, VrTheme.BODY, C_MUTED, Paint.Align.CENTER)
            }
        }
        val buttons = UiGrid.row(1, 2, buttonTop, UiGrid.H_ROW, 2, 1)
        button("msgRetry", "重试", buttons[0], VrAction.Retry, highlighted = true)
        button("msgLibrary", "回片库", buttons[1], VrAction.Back)
    }

    /**
     * 19:2x：**明确拒绝**的说明卡（未缝合双鱼眼 360）。
     *
     * 为什么不是一条可淡出的小提示：这类片子的画面**是在动的**，用户看到两个圆
     * 只会以为片子拍坏了、或者播放器坏了。一句 3 秒就消失的话来不及读完，
     * 也不会留下「该怎么做」。所以做成独占卡：说清是什么、为什么、怎么办，
     * 并且给两个真实出口（就这么看 / 回片库）。
     *
     * 和播放失败卡同一套栅格与字号：3 栏 584 宽、居中，标题 + 两句 + 按钮行。
     */
    /** 当前是否该画拒绝卡：播放页 + 已上膛（与其它独占层同一套生效条件）。 */
    private fun activeRefusal(state: VrUiState): Refusal? =
        state.refusal?.takeIf { state.screen == VrScreen.PLAYER && state.gazeArmed }

    private fun drawRefusal(refusal: Refusal) {
        // 独占：被这张卡盖住的东西不该还能被瞄到。
        targets.clear()
        small.textSize = VrTheme.BODY
        val limit = UiGrid.spanW(3) - 72f
        val bodyLines = wrapText(refusal.body, small, limit, 2)
        val hintLines = wrapText(refusal.hint, small, limit, 3)
        // 纵向以 128 起：正文 1 行 + 提示 2 行时卡高 286（128–414），
        // 上下各留 116 / 86 —— 拒绝卡是「该读一眼」的卡，不能顶着底部按钮行长。
        val top = 128f
        val bodyTop = 248f
        val buttonsTop = bodyTop + bodyLines.size * 24f + 12f + hintLines.size * 24f + 10f
        // 卡片先把位置算出来再画底：高度跟着折行数走，字多时卡跟着长高而不是压按钮。
        val card = UiGrid.centered(3, top, buttonsTop + UiGrid.H_ROW + 16f - top)
        fill.color = 0xF61A1214.toInt()
        canvas.drawRoundRect(card, VrTheme.R_PANEL, VrTheme.R_PANEL, fill)
        stroke.color = C_WARN
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(card, VrTheme.R_PANEL, VrTheme.R_PANEL, stroke)
        heading("这个片子看不了", 176f)
        label(refusal.title, W / 2f, 216f, VrTheme.ITEM, C_ACCENT, Paint.Align.CENTER)
        bodyLines.forEachIndexed { i, line ->
            label(line, W / 2f, bodyTop + i * 24f, VrTheme.BODY, C_TEXT, Paint.Align.CENTER)
        }
        val hintTop = bodyTop + bodyLines.size * 24f + 12f
        hintLines.forEachIndexed { i, line ->
            label(line, W / 2f, hintTop + i * 24f, VrTheme.BODY, C_MUTED, Paint.Align.CENTER)
        }
        val buttons = UiGrid.row(1, 2, buttonsTop, UiGrid.H_ROW, 2, 1)
        button("refusalOk", "就这样看", buttons[0], VrAction.DismissRefusal, highlighted = true)
        button("refusalBack", "回片库", buttons[1], VrAction.Back)
    }

    /**
     * V2.11 建立、V2.33 / B14 重做：**可操作的**档位 / 瞳距调节条。
     *
     * 真机反馈「音量和亮度条激活之后，用户来不及操作就消失了」——原来这张卡
     * 一个 `GazeTarget` 都没注册，2.6 秒固定淡出后什么都来不及点。现在它带
     * `−` / `+` / 粗微 / 完成 四个大目标，淡出由交互感知计时驱动（见 VrCoordinator.tick）。
     *
     * 填充照 Android `SeekBar` / Material `Slider` 的契约用**真实值比例**：
     * `fraction = 0` 时填充宽度为 0，所以音量静音不会再「亮一格」。
     * 原来把 1 基档号 `level + 1` 当点亮段数，正是这个 bug 的来源。
     *
     * 位置（15:5x 修正）：**画布上部不等于视线上方**。面板中心在 −13°，画布 y=256
     * 才对应 −13°，y=48 对应的是 **+1.6°（视线正方略上）**。旧注释写「落在视线下方
     * 约 3°–13°」，把「画布上半部」当成了「视线上方」，于是这块 600×204 的卡一直
     * 压在画面正中偏上，还挡着调亮度时要看的那块画面 —— 用户的原话是「会叠在其它界面上」。
     *
     * 现在挪到 y 340–508（视线 −18.4° ~ −29.4°），贴视野下缘；并且由 `render()`
     * 作为**独占层**调用，下层界面不再同时绘制。
     *
     * 它画在世界锁定的 UI 画布上，屏幕空间层属于 B6，还没做。
     */
    private fun drawAdjustFeedback(state: VrUiState, feedback: AdjustFeedback) {
        val remain = feedback.untilMs - SystemClock.elapsedRealtime()
        if (remain <= 0L) return
        // 最后 300ms 渐隐，避免「啪」一下消失。常驻（Long.MAX_VALUE）时恒为不透明。
        val alpha = ((remain / 300f).coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

        // 15:5x：整条从画布上部（y 48–252）挪到**贴底**（y 340–508）。
        //
        // 旧位置换算到 −13° 的播放页面板上落在视线 −10.7° ~ +1.6°，正好压住画面
        // 正中偏上；而调亮度/音量时用户恰恰要看着那块画面判断亮暗。挪到底部之后
        // 是 −18.4° ~ −29.4°，接近视野下缘，中间的画面完全让开。
        //
        // 高度同时从 204 收到 168：它是一块临时 HUD，不是需要细读的表单。
        // 纵向顺序：标题行 → 轨道 → 提示 → 按钮行，四层各占 30–62px。
        val card = UiGrid.centered(3, 340f, 168f)
        val layer = canvas.saveLayerAlpha(card, alpha)

        fill.color = 0xF216161A.toInt()
        canvas.drawRoundRect(card, 22f, 22f, fill)
        stroke.color = C_ACCENT
        stroke.strokeWidth = 2.5f
        canvas.drawRoundRect(card, 22f, 22f, stroke)

        label(feedback.label, card.left + 26f, 374f, VrTheme.TITLE, C_TEXT)
        label(feedback.detail, card.right - 26f, 374f, VrTheme.HERO, C_ACCENT, Paint.Align.RIGHT)

        // 连续轨道：`fraction` 是唯一的长度依据，0 → 没有填充。
        val track = RectF(card.left + 26f, 382f, card.right - 26f, 408f)
        fill.color = C_BORDER
        canvas.drawRoundRect(track, 12f, 12f, fill)
        val filled = (track.width() * feedback.fraction.coerceIn(0f, 1f))
        if (filled > 0f) {
            fill.color = C_ACCENT
            canvas.drawRoundRect(
                RectF(track.left, track.top, track.left + filled, track.bottom), 12f, 12f, fill
            )
        }
        if (feedback.steps > 1) {
            // 档位刻度只说明「只有这几档」，不再拿档号当填充长度。
            stroke.color = 0x9916161A.toInt()
            stroke.strokeWidth = 3f
            for (i in 1 until feedback.steps) {
                val x = track.left + track.width() * i / feedback.steps
                canvas.drawLine(x, track.top + 3f, x, track.bottom - 3f, stroke)
            }
        }
        if (feedback.steps == 0) {
            // 连续值（瞳距 / 亮度）：滑块钉在真实值上，不画假档位。
            fill.color = C_TEXT
            canvas.drawCircle(
                track.left + filled.coerceIn(0f, track.width()), track.centerY(), 9f, fill
            )
        }
        // 不画「段条小点」：离屏对照图里它与提示挤成一行，静音时四个全灭还像坏了；
        // 连续轨道 + 刻度已经把「真实值」和「只有这几档」都说清楚了。

        small.textSize = 21f
        label(
            ellipsize(feedback.hint ?: "", card.width() - 52f, small),
            W / 2f, 426f, VrTheme.BODY, C_MUTED, Paint.Align.CENTER
        )

        // 19:0x：卡内按钮行改为「等分卡内宽度、间距 16」，不再写死 168/126 ——
        // 卡片宽度由栅格决定，按钮跟着算，换宽度不会出现一排挤在中间的小块。
        val showMode = feedback.key == "ipd" && state.ipdTuning
        val count = if (showMode) 4 else 3
        val gap = UiGrid.GUTTER
        val innerW = card.width() - 52f
        val bw = (innerW - gap * (count - 1)) / count
        var x = card.left + 26f
        val top = 444f
        val bottom = 500f
        button("adjDec", "−", RectF(x, top, x + bw, bottom), VrAction.Adjust(feedback.key, -1))
        x += bw + gap
        button("adjInc", "+", RectF(x, top, x + bw, bottom), VrAction.Adjust(feedback.key, 1))
        x += bw + gap
        if (showMode) {
            button(
                "adjMode", if (feedback.coarse) "切微调" else "切粗调",
                RectF(x, top, x + bw, bottom), VrAction.ToggleIpdMode,
                highlighted = true
            )
            x += bw + gap
        }
        button(
            "adjClose", "完成",
            RectF(x, top, x + bw, bottom),
            if (state.ipdTuning) VrAction.StopIpdTuning else VrAction.DismissAdjust
        )

        canvas.restoreToCount(layer)
    }

    // ── 片库浏览（PRD 4.4 / FR-10 海报与断点） ─────────────────────────
    /**
     * V2.8：片库从「三张卡」改成「一页 8 格」。
     *
     * 设计文档第 408 行把「一次只露三张卡、摇头翻 1 部」列为必须解决的问题，
     * 第 528 行给出答案「摇头翻页从 1 部改为 10 部一页」，第 471 行要求顶部常驻位置感。
     *
     * 三处结构性改动：
     * 1. alpha43 订正：这条「2×4 让正中落在十字缝」在 V2.50 换三栏后就失效了，
     *    现已改成 3 列 × 4 行（见 `VrUiState.PAGE_SIZE` 注释）。防误开由
     *    `gazeArmed` 上膛 + `GazeDetector.mustLeave` 承担，不靠格子排布。
     * 2. 顶部那三个假 tab（点下去只说「V1 接入」）换成真能用的上一页 / 下一页，
     *    目标数不变，假功能变真功能（08.2 第 06 条同一思路）。
     * 3. 每格直接就是「播放这一部」的目标，不再需要先选中、再凝视中间的「打开」。
     */
    /**
     * V2.43：按 SKYBOX 的视觉语言重做片库，但**不照抄它的三栏**。
     *
     * SKYBOX 是 Quest 手柄/指针，右栏那些小条目点得到；我们是凝视 + 一个触发键，
     * 把两块侧栏塞到视野边缘等于把二十个小目标放在余光里停 600ms，误触率会很难看。
     * 所以只留左边一栏（188px ≈ 11°，五个大目标），右栏那种历史列表用卡片角标表达。
     *
     * 三条来自稿子、与可读性直接相关的改动：
     * 1. 名字写在海报**外面**（原来压在渐变条上，17px 字只有 1° 高）；
     * 2. 悬停整卡放大 5%、名字变白加粗、描边强调色；
     * 3. 底栏不再摆四个纯文字按钮，只留页码 / 进度点 / 一句提示。
     */
    private fun drawLibrary(state: VrUiState) {
        val pageCount = state.pageCount
        val page = state.page.coerceIn(0, pageCount - 1)
        drawLibraryRail(state)

        panel(RectF(COL_M, COL_T, COL_M + COL_MW, COL_B))

        val stack = state.currentStack
        val root = state.cabinets.firstOrNull { it.sourceId == state.cabinet }?.label ?: "片库"
        val title = when {
            stack != null -> stack.label
            state.showingStacks -> "$root · ${state.stacks.size} 个集合"
            else -> root
        }
        // ellipsize 按 paint 当前的 textSize 量宽度，所以先定字号再截断。
        strong.textSize = VrTheme.TITLE
        strongLabel(ellipsize(title, 380f, strong), COL_M + 24f, 64f, VrTheme.TITLE, C_TEXT)
        // 副行只放「多少个 / 怎么操作」，不再重复标题里的片源名。
        // alpha43：底栏原来还有一行「凝视一格即播放」，和页码挤在左右按钮之间
        // 176px 的缝里像两行小字糊在一起，这里并进副行，底栏只留页码。
        // 原文案写「摇头翻页」，但全工程没有任何摇头手势（`PageFlip` 只有按钮一个
        // 触发源），戴镜时会让人真的去摇——改成指向底栏两个按钮。
        val sub = when {
            stack != null -> "${stack.items.size} 部 · 凝视一格即播放 · 底栏翻页"
            state.showingStacks -> "${state.items.size} 部 · 凝视集合进入"
            else -> "${state.items.size} 部 · 凝视一格即播放 · 底栏翻页"
        }
        label(sub, COL_M + 24f, 92f, VrTheme.META, C_DIM)

        // 页码靠右，和中栏标题同一行——省掉一整条底栏。
        label(
            "${page + 1} / $pageCount", COL_M + COL_MW - 24f, 64f,
            VrTheme.META, C_DIM, Paint.Align.RIGHT
        )

        if (state.items.isEmpty()) {
            centerText("片库是空的", 226f, VrTheme.ITEM, C_TEXT)
            centerText("凝视左栏「退出」回配置页，点「手机里的视频」加入片库", 268f, VrTheme.BODY, C_MUTED)
            centerText("或点「换片库」添加同一 Wi-Fi 下的设备", 300f, VrTheme.META, C_DIM)
            return
        }

        val start = page * VrUiState.PAGE_SIZE
        if (state.showingStacks) {
            for (slot in 0 until VrUiState.PAGE_SIZE) {
                val st = state.stacks.getOrNull(start + slot) ?: break
                val left = LIB_LEFT[slot % LIB_COLS]
                val top = LIB_TOP[slot / LIB_COLS]
                stackCard(
                    st,
                    RectF(left, top, left + LIB_W, top + LIB_H),
                    hover = activeGazeTargetId == "stack:${st.key}"
                )
            }
        } else {
            val items = state.visibleItems
            for (slot in 0 until VrUiState.PAGE_SIZE) {
                val index = start + slot
                val item = items.getOrNull(index) ?: break
                val left = LIB_LEFT[slot % LIB_COLS]
                val top = LIB_TOP[slot / LIB_COLS]
                val id = "cell$index"
                val resume = state.progress[mediaKeyOf(item)]
                    ?.takeIf { !it.finished && it.positionMs > 0L }
                cell(
                    id = id,
                    item = item,
                    rect = RectF(left, top, left + LIB_W, top + LIB_H),
                    action = VrAction.OpenMedia(item.id),
                    badge = resume?.let { "续 ${formatTime(it.positionMs)}" },
                    unsupported = mediaKeyOf(item) in state.unsupportedKeys,
                    current = item.id == state.currentMediaId,
                    hover = activeGazeTargetId == id,
                    progress = resume?.let {
                        item.durationMs.takeIf { d -> d > 0L }
                            ?.let { d -> (it.positionMs.toFloat() / d).coerceIn(0f, 1f) }
                    }
                )
            }
        }

        // alpha43：上一页 / 下一页**左右分置**在中栏底栏。
        // 原来两个都在左栏第 3、4 行，用户要先转头找左栏、再在 6 行里认出是哪两行；
        // 现在一个在左下、一个在右下，各 170×48px 的大目标，顺着眼就能扫到。
        // 左栏那两条 rail 仍保留作近路，但不再是没有替代的唯二入口。
        pageButton(
            "libPrev", "上一页", COL_M + 20f, 432f,
            Glyph.CHEVRON_LEFT, VrAction.PageFlip(-1), enabled = page > 0
        )
        pageButton(
            "libNext", "下一页", COL_M + COL_MW - 24f - 170f, 432f,
            Glyph.CHEVRON_RIGHT, VrAction.PageFlip(1), enabled = page < pageCount - 1
        )
        // 底栏正中只留一行：有 message 时显示 message（错误 / 提示优先），否则显示页码。
        val midX = COL_M + COL_MW / 2f
        small.textSize = VrTheme.META
        val midText = state.message ?: "第 ${page + 1} / $pageCount 页"
        label(
            ellipsize(midText, 176f, small), midX, 460f, VrTheme.META,
            if (state.message != null) C_ACCENT else C_MUTED, Paint.Align.CENTER
        )

        drawResumeColumn(state)
    }

    /**
     * V2.50：右栏只放两张**大卡**——「继续观看」和「最近加入」。
     *
     * SourceVR 的右栏是「收藏 / 历史」两条列表，因为它有激光指针，一屏点得中小行。
     * 凝视点不中小行：一行 46px 只有 3°，瞄准比读还费劲。
     * 而戴镜后真正高频的只有两件事——接着上次看、看刚拍的。所以这里给两张
     * 144×150 的卡（8.7°×9.8°），比列表少两个数量级的目标，也少一次「找」。
     */
    private fun drawResumeColumn(state: VrUiState) {
        panel(RectF(COL_R, COL_T, COL_R + COL_RW, COL_B))

        val cur = state.items.firstOrNull { it.id == state.currentMediaId }
        val curProgress = cur?.let { state.progress[mediaKeyOf(it)]?.positionMs ?: state.positionMs }
        val curDuration = cur?.durationMs?.takeIf { it > 0L } ?: state.durationMs

        heroCard(
            caption = "继续观看",
            item = cur,
            top = COL_T,
            id = "resumePlay",
            action = cur?.let { VrAction.ResumePlayback },
            progress = curProgress,
            duration = curDuration,
            emptyText = "还没看过"
        )

        val recent = state.items.maxByOrNull { it.mtime }?.takeIf { it.id != cur?.id }
        heroCard(
            caption = "最近加入",
            item = recent,
            top = 246f,
            id = "recentPlay",
            action = recent?.let { VrAction.OpenMedia(it.id) },
            progress = null,
            duration = recent?.durationMs ?: 0L,
            emptyText = "还没有新片"
        )
    }

    /** 右栏的一张卡：上半身份、下半进度或时长。不可用时画暗且不注册目标。 */
    private fun heroCard(
        caption: String,
        item: MediaItemEntity?,
        top: Float,
        id: String,
        action: VrAction?,
        progress: Long?,
        duration: Long,
        emptyText: String
    ) {
        label(caption, COL_R + 20f, top + 22f, VrTheme.META, C_DIM)
        val card = RectF(COL_R + 20f, top + 34f, COL_R + COL_RW - 20f, top + 184f)

        if (item == null || action == null) {
            fill.color = C_POSTER
            canvas.drawRoundRect(card, VrTheme.R_CARD, VrTheme.R_CARD, fill)
            centerText(emptyText, card.centerY() + 6f, 17f, C_DIM, card.centerX())
            return
        }

        targets += GazeTarget(id, caption, RectF(card), action)
        val hover = activeGazeTargetId == id
        fill.color = if (hover) C_PANEL_HI else C_POSTER
        canvas.drawRoundRect(card, VrTheme.R_CARD, VrTheme.R_CARD, fill)
        stroke.color = if (hover) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (hover) 2.5f else 1.5f
        canvas.drawRoundRect(card, VrTheme.R_CARD, VrTheme.R_CARD, stroke)

        val stamp = timestampOf(item.title)
        if (stamp != null) {
            label(stamp.date, card.centerX(), card.top + 52f, 14f, C_DIM, Paint.Align.CENTER)
            strongLabel(stamp.time, card.centerX(), card.top + 92f, 30f, C_TEXT, Paint.Align.CENTER)
        } else {
            strongLabel(
                ellipsize(prettyName(item.title), card.width() - 24f, strong),
                card.centerX(), card.top + 78f, 21f, C_TEXT, Paint.Align.CENTER
            )
        }

        if (progress != null && progress > 0L && duration > 0L) {
            val f = (progress.toFloat() / duration).coerceIn(0f, 1f)
            val bar = RectF(card.left + 14f, card.bottom - 34f, card.right - 14f, card.bottom - 30f)
            fill.color = VrTheme.Track
            canvas.drawRoundRect(bar, 2f, 2f, fill)
            fill.color = C_ACCENT
            canvas.drawRoundRect(
                RectF(bar.left, bar.top, bar.left + bar.width() * f, bar.bottom), 2f, 2f, fill
            )
            label(
                "${formatTime(progress)} / ${formatTime(duration)}",
                card.centerX(), card.bottom - 10f, VrTheme.TINY, VrTheme.Time, Paint.Align.CENTER
            )
        } else {
            label(
                "刚扫到 · ${formatTime(duration)}",
                card.centerX(), card.bottom - 10f, VrTheme.TINY, VrTheme.Time, Paint.Align.CENTER
            )
        }
    }

    /** 左栏一行。不可用时画成暗色且**不注册凝视目标**——不放假按钮。 */
    private fun railItem(
        id: String,
        name: String,
        kind: Glyph,
        action: VrAction,
        top: Float,
        enabled: Boolean,
        height: Float = 60f
    ) {
        val rect = RectF(COL_L + 10f, top, COL_L + COL_LW - 10f, top + height)
        if (enabled) targets += GazeTarget(id, name, RectF(rect), action)
        val ink = if (enabled) C_TEXT else C_DIM
        if (enabled && id == activeGazeTargetId) {
            fill.color = C_PANEL_HI
            canvas.drawRoundRect(rect, VrTheme.R_BUTTON, VrTheme.R_BUTTON, fill)
        }
        val cy = top + height / 2f
        glyph(kind, COL_L + 34f, cy, 20f, ink, 2.2f)
        label(name, COL_L + 58f, cy + 8f, VrTheme.BODY, ink)
        if (enabled) glyph(Glyph.CHEVRON_RIGHT, COL_L + COL_LW - 24f, cy, 12f, C_DIM, 2f)
    }

    /**
     * 常驻左栏。
     *
     * 行高 44 → 60 → **70**：44 时掉在「单目标 ≥6°×4°」之下，凝视要压很久才进得去。
     * alpha43 把「上一页 / 下一页」从这栏**移走**（改到中栏底栏左右分置），
     * 剩下 4 条各占 70px、间距 36px，既不复用同一个动作两遍，也让每条更好压。
     */
    private fun drawLibraryRail(state: VrUiState) {
        panel(RectF(COL_L, COL_T, COL_L + COL_LW, COL_B))
        label("片 库", COL_L + 20f, COL_T + 32f, VrTheme.META, C_DIM)

        railItem(
            "railCabinets", "换片库", Glyph.MORE, VrAction.OpenCabinets, 66f,
            enabled = state.cabinets.size > 2, height = RAIL_H
        )
        railItem(
            "railCloseStack", "全部集合", Glyph.GRID, VrAction.CloseStack, 172f,
            enabled = state.activeStack != null, height = RAIL_H
        )
        railItem(
            "railSettings", "设置", Glyph.SLIDERS, VrAction.OpenSettings, 278f,
            enabled = true, height = RAIL_H
        )
        railItem(
            "railExit", "退出", Glyph.POWER, VrAction.Exit, 384f,
            enabled = true, height = RAIL_H
        )
    }

    /**
     * alpha43：片库底栏的分页大按钮。
     *
     * 不可用（已在首页 / 末页）时画成暗色且**不注册凝视目标**——不放假按钮，
     * 和 `railItem` 是同一条约定。命中区就是可见矩形本身，不做「图标小、命中区大」
     * 那套：分页是要一眼看见并按得中的主操作。
     */
    private fun pageButton(
        id: String,
        text: String,
        left: Float,
        top: Float,
        chevron: Glyph,
        action: VrAction,
        enabled: Boolean
    ) {
        val rect = RectF(left, top, left + 170f, top + 48f)
        if (enabled) targets += GazeTarget(id, text, RectF(rect), action)
        val hovered = enabled && activeGazeTargetId == id
        val ink = if (enabled) C_TEXT else C_DIM
        fill.color = if (hovered) C_PANEL_HI else if (enabled) C_POSTER else C_PANEL
        canvas.drawRoundRect(rect, VrTheme.R_BUTTON, VrTheme.R_BUTTON, fill)
        stroke.color = if (hovered) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (hovered) 3f else 1.5f
        canvas.drawRoundRect(rect, VrTheme.R_BUTTON, VrTheme.R_BUTTON, stroke)
        glyph(
            chevron,
            if (chevron == Glyph.CHEVRON_LEFT) rect.left + 26f else rect.right - 26f,
            rect.centerY(), 16f, ink, 2.4f
        )
        label(text, rect.centerX(), rect.centerY() + 8f, 21f, ink, Paint.Align.CENTER)
    }

    /**
     * 一叠：一次拍摄或一个文件夹。不画海报——叠的价值是「这一批是什么」。
     * V2.43：卡片缩到 178×100，字号全部按 rect 高度算，不再硬编码绝对行号。
     */
    private fun stackCard(stack: MediaStack, rect: RectF, hover: Boolean = false) {
        targets += GazeTarget(
            "stack:${stack.key}", stack.label, RectF(rect), VrAction.OpenStack(stack.key)
        )
        val k = if (hover) 1.05f else 1f
        val w = rect.width() * k
        val h = rect.height() * k
        val box = RectF(
            rect.centerX() - w / 2f, rect.centerY() - h / 2f,
            rect.centerX() + w / 2f, rect.centerY() + h / 2f
        )
        fill.color = C_POSTER
        canvas.drawRoundRect(box, VrTheme.R_CARD, VrTheme.R_CARD, fill)
        fill.color = C_ACCENT
        canvas.drawRoundRect(
            RectF(box.left + 6f, box.top + 10f, box.left + 11f, box.bottom - 10f),
            3f, 3f, fill
        )
        stroke.color = if (hover) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (hover) 3f else 1.5f
        canvas.drawRoundRect(box, 12f, 12f, stroke)

        strong.textSize = VrTheme.BODY
        val lines = wrapTwoLines(stack.label, box.width() - 30f)
        val baseY = box.centerY() - (lines.size - 1) * 12f - 4f
        lines.forEachIndexed { index, line ->
            strongLabel(
                line, box.centerX() + 4f, baseY + index * 24f, 20f,
                C_TEXT, Paint.Align.CENTER
            )
        }
        small.textSize = 14f
        label(
            ellipsize(stack.detail, box.width() - 30f, small),
            box.centerX() + 4f, baseY + lines.size * 24f + 4f, 14f,
            C_ACCENT, Paint.Align.CENTER
        )
        // 名字/部数在卡内已经够读，叠卡不再往卡外写第二行，免得和下一行撞。
    }

    /**
     * V2.38：重定「正前方」的倒计时。
     *
     * 位置的语义和别的界面都不一样：**这块面板的朝向等于当前视线方向**
     * （见 [panelSpec]），所以卡片贴在眼前跟着头走，不需要去追它。
     * 旧版「凝视『正前方』→ 再凝视『挪到这里』」之所以一直差几度，
     * 就是因为「找按钮」这个动作本身在改写要确认的那个方向。
     *
     * 三条约束：
     * 1. 不铺压暗层、也不盖住视野正中——圆心比视线低 2–3°，
     *    用户必须看得见画面中心才判断得了「正前方在哪儿」；
     * 2. 倒计时期间**一帧都不动画面**，只在归零那一刻取姿态；
     * 3. 不注册任何凝视目标。这一层只接受触发键：单击 = 现在就定，长按 = 取消。
     */
    private fun drawAlignCountdown(cd: AlignCountdown) {
        val card = RectF(332f, 124f, 692f, 490f)
        fill.color = 0xD9101013.toInt()
        canvas.drawRoundRect(card, 24f, 24f, fill)
        stroke.color = C_BORDER
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(card, 24f, 24f, stroke)

        centerText("把现在的方向定为正前方", 172f, VrTheme.ITEM, C_TEXT)

        val cx = W / 2f
        val cy = 288f
        val radius = 66f
        val oval = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // 最后一程还在动就换成琥珀色：这是提醒，不是报错。
        val warn = !cd.steady && cd.remainingMs <= 2_500L
        val accent = if (warn) C_WARN else C_ACCENT

        stroke.color = C_BORDER
        stroke.strokeWidth = 10f
        canvas.drawCircle(cx, cy, radius, stroke)

        // 剩余的弧：归零时正好走完。比数字更早被余光看到，也比数字更「不用读」。
        val ratio = if (cd.totalMs <= 0L) 0f
        else (cd.remainingMs.toFloat() / cd.totalMs).coerceIn(0f, 1f)
        ring.color = accent
        ring.strokeWidth = 10f
        canvas.drawArc(oval, -90f, 360f * ratio, false, ring)

        // 向上取整：还剩 0.2 秒时显示 1，不会出现「0」卡在那里让人以为死机。
        val seconds = (cd.remainingMs + 999L) / 1000L
        text.textSize = 60f
        text.color = C_TEXT
        canvas.drawText(seconds.toString(), cx, cy + 20f, text)
        centerText("秒", cy + 48f, 17f, C_MUTED)

        centerText(
            if (cd.movedDeg <= 0) "还没动 · 摆到你想要的方向" else "已调整 ${cd.movedDeg}°",
            404f, 21f, C_TEXT
        )
        centerText(
            when {
                warn -> "还在动 · 再稳住一下"
                cd.steady -> "稳住了 · 保持这个方向"
                else -> "在限定的时间里摆好，然后别动"
            },
            434f, 20f,
            when {
                warn -> C_WARN
                cd.steady -> C_ACCENT
                else -> C_MUTED
            }
        )
        centerText("单击 = 现在就定 · 长按 = 取消", 470f, 17f, C_MUTED)
    }

    /**
     * 「你换姿势了，要不要把正前方挪过来？」
     *
     * 四条硬约束：
     * 1. **画面一帧不动**。检测到就动画面，等于把用户正在看的内容抢走；
     *    看 360 时抬头是常态，自动重定在这里是灾难。所以卡片挂着时下层整块不画
     *    （见 `render()`），只有这张卡出现在新方向。
     * 2. **卡片在候选方向的视线正前方**。`panelSpec` 把整块平面挪过去，
     *    用户不用转头去找它——要确认的是「我刚才那个姿势」，
     *    而任何「转头去找卡片」的动作都会把那个姿势改掉。
     * 3. **只有两个目标**。这一层是打断，打断上不该再摆一排按钮。
     * 4. 不铺压暗层。面板只有 48.5°×25.4°，压暗只能盖住中间一块，
     *    边缘露出一圈直角比不压更难看；卡片自己有不透明底，足够立得住。
     */
    private fun drawPosturePrompt(state: VrUiState) {
        // 19:0x：按栅格取 4 栏（784），两个按钮各 2 栏、缝隙固定 16。
        val card = RectF(UiGrid.CONTENT_L, 92f, UiGrid.CONTENT_R, 420f)
        fill.color = 0xF0101013.toInt()
        canvas.drawRoundRect(card, VrTheme.R_PANEL, VrTheme.R_PANEL, fill)
        stroke.color = C_ACCENT
        stroke.strokeWidth = 2.5f
        canvas.drawRoundRect(card, VrTheme.R_PANEL, VrTheme.R_PANEL, stroke)

        heading("正前方挪到这里？", 158f)

        // 说出「我看到了什么」，用户才知道这次打断不是误判。
        // 这句话对「自动检测」和「自己点正前方」都成立，所以不带「检测到」。
        val pitch = Math.toDegrees((state.postureCandidate ?: 0f).toDouble()).toInt()
        val yaw = Math.toDegrees(state.postureYaw.toDouble()).toInt()
        val parts = buildList {
            if (pitch > 8) add("抬头 $pitch°") else if (pitch < -8) add("低头 ${-pitch}°")
            if (yaw > 8) add("偏右 $yaw°") else if (yaw < -8) add("偏左 ${-yaw}°")
        }
        val where = if (parts.isEmpty()) "你现在不是正对画面" else "你现在" + parts.joinToString(" · ")
        centerText("$where · 转过来之后，视频和界面一起跟着你", 200f, VrTheme.BODY, C_MUTED)

        // 两个按钮按 2 栏等分，中间缝 16（比片库的缝更宽——准星圆环半径 11px）。
        val postureButtons = UiGrid.row(0, 4, 236f, UiGrid.H_BIG, 2, 2)
        button(
            "postureOk", "挪到这里",
            postureButtons[0], VrAction.ConfirmPosture,
            highlighted = true, sub = "0.8 秒转过去，只动这一次"
        )
        button(
            "postureNo", "不用了",
            postureButtons[1], VrAction.DismissPosture,
            sub = "保持现在的画面"
        )

        // 候选自己会过期。给一条细条，让「它不会一直挡着」这件事看得见。
        val ttl = PostureTracker.CANDIDATE_TTL_MS.toFloat().coerceAtLeast(1f)
        val ratio = (state.postureRemainingMs / ttl).coerceIn(0f, 1f)
        fill.color = C_BORDER
        canvas.drawRoundRect(RectF(UiGrid.CONTENT_L, 376f, UiGrid.CONTENT_R, 384f), 4f, 4f, fill)
        fill.color = C_ACCENT
        canvas.drawRoundRect(
            RectF(UiGrid.CONTENT_L, 376f, UiGrid.CONTENT_L + UiGrid.CONTENT_W * ratio, 384f),
            4f, 4f, fill
        )
        label(
            "按一下触发键也行 · 不按就走完收起",
            UiGrid.CONTENT_R, 408f, VrTheme.META, C_MUTED, Paint.Align.RIGHT
        )
    }

    // ── 柜子层（V2.18 / B2）────────────────────────────────────────────
    /**
     * 换柜子整层：一屏摆出全部片源，凝视一次换整柜。
     *
     * 为什么值得单独一层：用户手里有两台 NAS、一台 PC、一个手机，戴镜后不能取手机，
     * 想换个设备看只能退出 VR。这一层把「换设备」变成一次凝视。
     *
     * 3 列 × 2 行 = 6 个目标，仍在「单屏 ≤9」以内。第一个固定是「全部片库」——
     * 不选柜子的时候才是默认视图，用户永远有一条回到全部的近路。
     */
    private fun drawCabinets(state: VrUiState) {
        panel(UiGrid.panel())
        heading("换片库", UiGrid.TITLE_Y)
        // 图标按钮是唯一允许的方形例外：56×56，右缘贴内容右缘，垂直对齐标题。
        iconButton(
            "exitCabinets", "退出",
            RectF(UiGrid.CONTENT_R - 56f, 22f, UiGrid.CONTENT_R, 78f),
            VrAction.Exit, Glyph.POWER
        )

        val cabinets = state.cabinets.take(6)
        if (cabinets.isEmpty()) {
            centerText("还没有片源 · 请回到戴镜前配置添加共享", 250f, VrTheme.BODY, C_MUTED)
            return
        }

        // 19:0x：3 列改成 2 列 × 3 行，每格 2 栏（384×110）。柜名常常很长
        //（「NAS-客厅」「T7 Shield」），384 宽一行放得下，旧 3 列只有 300 出头要折行。
        for ((slot, cabinet) in cabinets.withIndex()) {
            cabinetCard(
                cabinet,
                UiGrid.cell(
                    (slot % 2) * 2, 2,
                    UiGrid.BODY_TOP + (slot / 2) * (UiGrid.H_BIG + UiGrid.ROW_GAP),
                    UiGrid.H_BIG
                ),
                selected = cabinet.sourceId == state.cabinet
            )
        }

        // 底栏：左边回片库（2 栏），右边一句说明右对齐到内容右缘。
        button(
            "closeCabinets", "← 回片库",
            UiGrid.cell(0, 2, UiGrid.BUTTON_Y, UiGrid.H_ROW), VrAction.CloseCabinets
        )
        label(
            ellipsize("凝视一个柜子就切过去 · 每柜的片子不混在一起", 420f, small),
            UiGrid.CONTENT_R, 466f, VrTheme.META, C_MUTED, Paint.Align.RIGHT
        )
    }

    /** 一个柜子：名字 + 部数 + 类型胶囊；当前柜子加一道青色边框。 */
    private fun cabinetCard(cabinet: Cabinet, rect: RectF, selected: Boolean) {
        targets += GazeTarget(
            "cabinet:${cabinet.sourceId}", cabinet.label, RectF(rect),
            VrAction.SwitchCabinet(cabinet.sourceId)
        )
        fill.color = C_POSTER
        canvas.drawRoundRect(rect, VrTheme.R_BUTTON, VrTheme.R_BUTTON, fill)
        fill.color = if (selected) C_ACCENT else C_MUTED
        canvas.drawRoundRect(
            RectF(rect.left + 8f, rect.top + 12f, rect.left + 14f, rect.bottom - 12f),
            3f, 3f, fill
        )
        stroke.color = if (selected) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (selected) 2.5f else 1.5f
        canvas.drawRoundRect(rect, VrTheme.R_BUTTON, VrTheme.R_BUTTON, stroke)

        strong.textSize = VrTheme.ITEM
        val lines = wrapTwoLines(cabinet.label, rect.width() - 52f)
        val baseY = rect.centerY() - 6f - (lines.size - 1) * 15f
        lines.forEachIndexed { index, line ->
            strongLabel(
                line, rect.centerX() + 4f, baseY + index * 30f, VrTheme.ITEM, C_TEXT, Paint.Align.CENTER
            )
        }
        val detail = if (cabinet.chip.isBlank()) "${cabinet.count} 部"
        else "${cabinet.chip} · ${cabinet.count} 部"
        label(
            detail, rect.centerX() + 4f, baseY + lines.size * 30f + 4f, VrTheme.META,
            if (selected) C_ACCENT else C_MUTED, Paint.Align.CENTER
        )
    }

    /**
     * 播放控制台（V2.42 重做）。
     *
     * 旧版面是 40..984 × 300..500 的一块大板：10 段跳转 + 5 个文字运输键 +
     * 7 个文字快捷 = **22 个凝视目标**，自己定的「单屏 ≤9」从来没满足过。
     * 现在的规矩：
     * 1. 收成一条 120px 高的胶囊，只占画面下缘 7.3°，不再压住半个屏；
     * 2. 运输键全部换图标（⏮ ⏪ ⏸ ⏩ ⏭ 是通用符号，不需要中文）；
     * 3. 其余六个入口（片库/正前方/格式/设置/退出/字幕）保留文字标签——
     *    图标看不懂就点不动，而凝视没有「悬停提示」这条后路；
     * 4. V2.42 把跳转从 10 段降到 5 段；alpha35 干脆合并成**一整条**连续轨道，
     *    落点按真实凝视 x 换算（`VrAction.SeekAt`）。分段跳转会让「盯着 30%」
     *    实际跳到段中心，用户看到的就是进度在凝视点左右飘。
     *
     * 目标数 22 → 15 → 11（其中 10 个是 ≥66px / 4.0° 的大目标）。
     */
    /**
     * 播放页的「唤出控制」热区：当前内容正前方偏上、偏下各一条，
     * **以 [wakeAxisDeg] 为对称轴**。
     *
     * ## 对称轴是哪条线（2026-11-16 真机后改定）
     *
     * 是**用户正在看的内容中心**，不是参考系水平线。影院形态下银幕中心比水平线
     * 高约 7.1°（且随幕宽档位变化）；球面 / 180° / 鱼眼的内容中心就是参考系 0°。
     *
     * 旧版把轴钉在 0°，结果按用户的量法（从银幕中心起算）下区落在约 −85°、
     * 上区落在约 +71°：真机就是「低头差不多 90° 才看到细条、抬头 78° 完全找不到」。
     * 轴改到内容中心后，两侧才真的是「低头 / 抬头 78°」。
     *
     * `panelSpec` 播放页 pitch 恒 −13° 是「控制台画布挂在哪」，与唤醒轴无关；
     * 轴只看投影的内容中心。
     *
     * ## 位置
     *
     * 热区中心 = `[wakeAxisDeg] ± WAKE_SPAN_DEG` = **相对内容中心 ±78°**，半高 3°，
     * 即上 **+75°~+81°**、下 **−81°~−75°**（每条 6° 高，横向 25.2° 宽）。
     * 中心与半高都由两条边推导（`(INNER+OUTER)/2`、`(OUTER−INNER)/2`），
     * 不存在「两个字面量碰巧相等」的对称。
     *
     * 20:1x 为什么外移：用户判 38-44°「太靠中间，容易误触发」，要求在没有触发键时
     * 仍能唤出控制台，但**总体优先观影体验**。解法是把热区推到视野边缘，
     * 让误触发由位置挡住，而不是靠把热区做小 —— 做小会同时把「想找时找得到」也弄丢。
     * 横向因此反而放宽到 25.2°：位置够偏，横向宽一点只会更好压中，不会更容易误触发。
     *
     * 必须按 `spec` 现算 y 而不是写死像素：`uiScalePercent` 会改面板物理尺寸，
     * 写死的 y 会随缩放漂到别的角度上。
     *
     * ## 视觉交给独立 quad，不画进主画布
     *
     * 主画布在播放页只覆盖 ±16.7°（100% 缩放），±78° 远在画布之外：胶囊画不出来，
     * 进度环也会被画布边界裁掉（用户反馈「操作计时的圆圈会被遮挡」）。而把主画布
     * 放大到能装下 ±78° 会让字幕和按钮的字号比例一起塌掉，毁掉刚修好的可读性。
     *
     * 所以这里**只注册命中区**；提示由 `renderWakeBadge` 画成一条细线，写进一张
     * 独立的 1024×64 纹理，再由 `VrRenderer` 挂到热区中心方向的独立 quad 上。
     * quad 中心就落在那个方向、距眼 2 米，从内容正前方抬头/低头到 ±78° 时正对着它，
     * 不需要靠「主画布外延伸」来显示。悬停即出现、离开即消失，不悬停时零像素。
     *
     * 18:5x：提示从胶囊改成细线、并在设置里加了开关（`wakeZoneEnabled`）。
     * 开关关掉时**连命中区都不注册**。
     * 19:1x 定过 38°~44°；20:1x 按用户口径外移到 **75°~81°**（带宽仍是 6°），
     * 同时把横向从 6.4° 放宽到 **25.2°**（用户「横向太短，非常难找到」）。
     *
     * 防重复触发靠 `GazeDetector.wakeMustLeave`：触发过一次后，视线必须离开
     * 唤醒区**且**控制台已收起，才允许重新累积。
     */
    private fun drawConsoleWakeHint(state: VrUiState) {
        // 未上膛时画布被「戴稳了？」头锁定层整块盖住；调瞳距 / 重定倒计时期间
        // 控制台是故意收起的，都不该冒出唤醒区。
        if (!state.gazeArmed || state.ipdTuning || state.alignCountdown != null) return
        // 18:5x：设置里可关。关掉之后**连命中区都不注册** —— 只藏视觉、判定还在，
        // 等于「看不见却仍会误触发」，比不做开关更糟。
        // 触发键单击（`TriggerDispatcher.consoleWasHidden`）不走这里，关掉照样能唤出。
        if (!state.settings.wakeZoneEnabled) return

        val spec = panelSpec(state)
        val halfHeight = atan2((spec.height / 2f).toDouble(), 2.0)
        val halfWidth = atan2((spec.width / 2f).toDouble(), 2.0)

        /** 视线仰角（度，正为上）→ 画布 y。与 gazePoint() 是同一个映射，方向相反。 */
        fun yAt(deg: Double): Float =
            (H / 2f - (Math.toRadians(deg) - spec.pitch) / halfHeight * (H / 2f)).toFloat()

        // 横向半宽 ±12.6°（整条 25.2°），与细线视觉宽度严格相等 —— 看着线外就不该中。
        val dx =
            (Math.toRadians(WAKE_HALF_WIDTH_DEG.toDouble()) / halfWidth * (W / 2f)).toFloat()

        // 上 / 下各离「当前内容正前方」78°，以 [wakeAxisDeg] 对称。
        // 影院里这条轴是银幕中心（≈+7.1°），球面里是参考系 0°；
        // 两组都远在画布 0~512 之外，这里只注册命中区，视觉交给独立徽标 quad。
        val axis = wakeAxisDeg(state)
        val up = axis + WAKE_SPAN_DEG
        val down = axis - WAKE_SPAN_DEG
        val upTop = yAt((up + WAKE_HALF_DEG).toDouble())
        val upBottom = yAt((up - WAKE_HALF_DEG).toDouble())
        val downTop = yAt((down + WAKE_HALF_DEG).toDouble())
        val downBottom = yAt((down - WAKE_HALF_DEG).toDouble())

        targets += GazeTarget(
            "wakeConsoleUp", "唤出控制",
            RectF(W / 2f - dx, upTop, W / 2f + dx, upBottom),
            VrAction.ShowConsole
        )
        targets += GazeTarget(
            "wakeConsoleDown", "唤出控制",
            RectF(W / 2f - dx, downTop, W / 2f + dx, downBottom),
            VrAction.ShowConsole
        )
    }

    // ---- 唤出提示：独立纹理，不进主画布 ----

    /**
     * 独立画布：坐标就是纹理像素（1024×64），不参与主画布的 `TEX_SCALE` 缩放。
     *
     * 为什么不用主画布：主画布 1024×512 在播放页只覆盖 ±16.7°，热区在 ±78°，
     * 超出部分会被裁掉 —— 上一版「计时的圆圈会被遮挡」正是这么来的。
     * 而把主画布放大到能装下 ±78°，字幕和按钮的字号比例会一起塌掉。
     */
    private val wakeBitmap = Bitmap.createBitmap(WAKE_W, WAKE_H, Bitmap.Config.ARGB_8888)
    private val wakeCanvas = Canvas(wakeBitmap)
    private val wakeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /**
     * 画出悬停提示：**一条细线**，进度在线内推进。
     *
     * 18:5x 用户要求「把提示从胶囊换成一条细线」。去掉的东西：胶囊底、描边、
     * 「凝视唤出控制」文字、左侧进度环 —— 它们在 6° 高的画面里占的面积
     * 比它提供的功能大得多。留下的只有：
     *
     * - **底线**：整条 25.2°，白色 40%，告诉你「这里能唤出」；
     * - **进度**：从左往右填成强调色，替代原来的环，仍然看得见还要停多久。
     *
     * 悬停才调用；不悬停时这张纹理根本不参与绘制，画面里零像素。
     * 线厚 [WAKE_LINE_PX]px / 纹理高 64px × quad 1.6° ≈ **0.35°**（真机约 5px）。
     *
     * @param progress 凝视进度 0~1，决定线内填充的比例。
     */
    fun renderWakeBadge(progress: Float): Bitmap {
        wakeCanvas.drawColor(Color.TRANSPARENT)

        val t = WAKE_LINE_PX
        val top = WAKE_H / 2f - t / 2f
        val bottom = WAKE_H / 2f + t / 2f
        val r = t / 2f

        // 底线：整条，低对比。它只承担「这里有个东西」，不抢注意力。
        wakeFill.color = 0x66FFFFFF
        wakeCanvas.drawRoundRect(RectF(0f, top, WAKE_W.toFloat(), bottom), r, r, wakeFill)

        // 进度：从左往右填。0 时不画，避免在起点留一个小圆头。
        val p = progress.coerceIn(0f, 1f)
        if (p > 0f) {
            wakeFill.color = C_ACCENT
            wakeCanvas.drawRoundRect(
                RectF(0f, top, WAKE_W * p, bottom), r, r, wakeFill
            )
        }
        return wakeBitmap
    }

    /**
     * 2.1.0：控制台收起后的缓冲指示。
     *
     * **它为什么不违反「非内容元素不侵占视野中央」**（16:3x 硬约束）：
     * 出现条件是 `buffering && wantPlay` —— 那一刻画面本来停着、完全不可观看，
     * 覆盖与否不改变「看不看得成」。用户 19:5x 的原话是「缓冲提示只会在画面
     * 处于不可观看的状态才出现」，即认可这条例外。控制台可见时**不出**：
     * 板顶已有「· 缓冲中」，两条同时出现只是噪音。
     *
     * 位置：y 292–340 → 视线 **−15.3° ~ −18.5°**。
     * - 在 2D 影院银幕下沿（−9.8°）之外的地面，也不压字幕（字幕最高到 y 284）；
     * - 比控制台（y 240 起、−12°）低一档，所以它出现时不会和「控制台正在展开」
     *   抢同一块位置，用户不会看到两块面板叠在一起闪一下。
     *
     * 不做动画：逐帧变化会让 UI 纹理每帧重传（`uiSignature` 按 1% 量化缓冲位置），
     * 为一条状态提示不值。百分比每变 1% 才重绘一次，本来就够「在动」。
     */
    private fun drawBufferingHint(state: VrUiState) {
        if (!state.buffering || !state.wantPlay) return

        val textLine = if (state.durationMs > 0L && state.bufferedMs > 0L) {
            val pct = (state.bufferedMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            "缓冲中 · 已缓 ${(pct * 100f).toInt()}%"
        } else {
            "缓冲中"
        }

        val pill = RectF(392f, 292f, 632f, 340f)
        fill.color = 0xE6161A20.toInt()
        canvas.drawRoundRect(pill, 24f, 24f, fill)
        stroke.color = C_ACCENT and 0x00FFFFFF or 0x66000000
        stroke.strokeWidth = 1.5f
        canvas.drawRoundRect(pill, 24f, 24f, stroke)

        text.textSize = VrTheme.BODY
        text.color = C_TEXT
        canvas.drawText(textLine, W / 2f, 318f, text)

        // 已缓冲比例：轨道 + 填充。它回答「是在走还是真卡住了」——
        // 数字停住 = 卡住，数字在涨 = 正在读，不用再靠猜。
        val barL = 424f
        val barR = 600f
        val barY = 330f
        fill.color = 0xFF3A3A40.toInt()
        canvas.drawRoundRect(RectF(barL, barY - 1.5f, barR, barY + 1.5f), 1.5f, 1.5f, fill)
        if (state.durationMs > 0L && state.bufferedMs > 0L) {
            val f = (state.bufferedMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
            if (f > 0f) {
                fill.color = VrTheme.AccentCache
                canvas.drawRoundRect(
                    RectF(barL, barY - 1.5f, barL + (barR - barL) * f, barY + 1.5f),
                    1.5f, 1.5f, fill
                )
            }
        }
    }

    private fun drawPlayer(state: VrUiState) {
        // 字幕独立于控制台淡出：戴镜时控制台消失，字幕仍需可读。
        if (state.settings.subtitlesEnabled) {
            SubtitleParser.cueAt(state.subtitles, state.positionMs)?.let { drawSubtitle(it) }
        }
        if (!state.consoleVisible) {
            // alpha43：控制台淡出后不再「什么都没有」——留一个头控唤醒区，
            // 不摸触发键也能叫回来。触发键单击那条近路仍然保留。
            drawConsoleWakeHint(state)
            // 2.1.0：控制台收起后，缓冲就彻底没有任何反馈了 —— 画面停住、什么都没有，
            // 「缓冲」和「卡死」在 VR 里长得一模一样。见 drawBufferingHint 的边界说明。
            drawBufferingHint(state)
            return
        }

        // ── 15:0x 重排：字号放大 + 按钮拆两行 ─────────────────────────────
        //
        // 旧版面是一块 904×134px 的板，里面塞了「状态两行 + 时间码 + 进度条 +
        // 5 个运输键 + 5 个工具键」，字号只能压到 15–16px。面板同时还是 1.8×0.9，
        // 每度 21.1px —— 折算下来 15px 只有 **0.71°**，远低于可读下限，
        // 所以真机反馈是「片库调大了有用，控制台还是看不清」。
        //
        // 现在面板统一到 2.4×1.2（每度 14.8px @115%），板宽收到 760px（约 51°）、
        // 高放到 268px，按钮拆成两行：运输一行、工具一行。目标数仍是 11 个，
        // 只是不再挤在一条横条里；每个命中区都 ≥6° 宽、≥4° 高。
        val x0 = 132f
        val y0 = 240f
        val x1 = 892f
        val y1 = 508f
        fill.color = 0xF2101013.toInt()
        canvas.drawRoundRect(RectF(x0, y0, x1, y1), 22f, 22f, fill)
        stroke.color = 0xFF303036.toInt()
        stroke.strokeWidth = 1.5f
        canvas.drawRoundRect(RectF(x0, y0, x1, y1), 22f, 22f, stroke)

        val step = state.settings.seekStepSeconds
        // 格式与步长合成一行放在板顶。它仍然是「状态，不是控件」，但不再飘在
        // 画布左上角 —— 那里在面板 −13° 的坐标系里正好是视野上方，会压住画面。
        //
        // 19:2x：缓冲中在这一行尾加「· 缓冲中」。不加这一句，缓冲和卡死的区别
        // 在 VR 里看不出来（画面停住、键还是「播放」），用户只能去按触发键试。
        val topLine = buildString {
            append(state.format.label)
            append(" · 快退/快进 ").append(VrSettings.seekStepLabel(step))
            if (state.buffering) append(" · 缓冲中")
        }
        label(topLine, x0 + 32f, 280f, VrTheme.META, if (state.buffering) C_ACCENT else C_DIM)

        val barL = x0 + 32f
        val barR = x1 - 32f
        val barY = 340f
        label(formatTime(state.positionMs), barL, 312f, VrTheme.BODY, C_MUTED)
        label(formatTime(state.durationMs), barR, 312f, VrTheme.BODY, C_DIM, Paint.Align.RIGHT)

        val played = if (state.durationMs > 0L) {
            (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
        } else 0f
        // 19:2x：缓冲段。设计稿里有、我们一直缺的一段 —— 没有它，SMB 上「卡住了」
        // 和「正在读」在画面上完全一样。恒定画在已播段之下、轨道之上：
        // 淡强调色 39%（0x63），比轨道亮、比已播段暗，一眼能分出三层。
        // 缓冲位置可能落后于播放头（回退 seek 后重建缓冲），所以下限钳到 played，
        // 免得出现「已播段比缓冲段还长」这种自相矛盾的画面。
        val buffered = if (state.durationMs > 0L && state.bufferedMs > 0L) {
            (state.bufferedMs.toFloat() / state.durationMs).coerceIn(played, 1f)
        } else played
        fill.color = 0xFF3A3A40.toInt()
        canvas.drawRoundRect(RectF(barL, barY - 4f, barR, barY + 4f), 4f, 4f, fill)
        if (buffered > played) {
            // 用设计稿里现成的 `VrTheme.AccentCache`（0x63FFC96B，同色 39% 不透明）。
            // 这个 token 从 01 批起就定义着，一直没人用 —— 就是缺这一段。
            fill.color = VrTheme.AccentCache
            canvas.drawRoundRect(
                RectF(barL, barY - 4f, barL + (barR - barL) * buffered, barY + 4f), 4f, 4f, fill
            )
        }
        if (played > 0f) {
            fill.color = C_ACCENT
            canvas.drawRoundRect(
                RectF(barL, barY - 4f, barL + (barR - barL) * played, barY + 4f), 4f, 4f, fill
            )
        }
        fill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(barL + (barR - barL) * played, barY, 8f, fill)

        // alpha35：整条轨道只注册一个目标，动作是 SeekAt —— 具体 fraction 在
        // 确认那一刻由 resolveAction() 按真实凝视 x 换算，和 played 条用同一条映射。
        // 命中区高 20px（约 1.35°）：横条本身够长，压准主要靠水平方向；垂直收窄
        // 到 ±10 是为了与运输行（上沿 360）留出 10px —— 原来 ±16 会顶到 356，
        // 与运输键重叠，拖进度会先命中「上一部」。
        pendingSeekLeft = barL
        pendingSeekRight = barR
        targets += GazeTarget(
            "seekBar",
            "跳到凝视的位置",
            RectF(barL, barY - 10f, barR, barY + 10f),
            VrAction.SeekAt
        )
        // 凝视轨道时把「会跳到哪儿」画在准星正下方：所见即所得，不用猜。
        if (activeGazeTargetId == "seekBar") {
            gazeCursor?.x?.let { x ->
                val at = x.coerceIn(barL, barR)
                stroke.color = C_ACCENT
                stroke.strokeWidth = 2.5f
                canvas.drawLine(at, barY - 18f, at, barY + 18f, stroke)
            }
        }

        // 运输行：命中区 80×80（5.4°×5.4°），图标 32px。
        //
        // 15:0x 修一个会直接导致误触的排布问题：这块板竖向只有 268px，却要放
        // 「进度条 / 运输行 / 工具行」三层。上一版的数值是进度条命中 324–356、
        // 运输命中 355–445、工具命中 446–508 —— 也就是说**进度条与运输键重叠 12px，
        // 运输与工具只隔 1px**。凝视压准本来就有抖动，这种间距下「想拖进度」会先
        // 命中「上一部」，「想点格式」会先命中「前进」。三层各让出 10px（约 0.68°），
        // 顺序从下往上：进度 330–350 / 运输 360–440 / 工具 450–508。
        val ty = 400f
        val playX = 512f
        transportButton("prev", "上一部", 248f, ty, Glyph.PREV, VrAction.Previous)
        transportButton(
            "rew", "后退 ${VrSettings.seekStepLabel(step)}", 380f, ty,
            Glyph.SEEK_BACK, VrAction.SeekBy(-step)
        )
        // 播放键是整条控制台唯一的强调色块：位置固定、形状固定，不用找。
        // 19:2x：键形跟**意图**（`wantPlay`）走，不跟 `isPlaying` 走。
        // 缓冲时 `isPlaying` 是 false，旧写法会把键翻回三角形；用户再按一下
        // 反而把 `playWhenReady` 翻成 false —— 「想继续看，结果暂停了」。
        fill.color = C_ACCENT
        canvas.drawCircle(playX, ty, 34f, fill)
        glyph(
            if (state.wantPlay) Glyph.PAUSE else Glyph.PLAY,
            playX, ty, 22f, 0xF0101013.toInt(), 3.4f
        )
        targets += GazeTarget(
            "transportPlay",
            if (state.wantPlay) "暂停" else "播放",
            RectF(playX - 40f, ty - 40f, playX + 40f, ty + 40f), VrAction.TogglePlay
        )
        transportButton(
            "fwd", "前进 ${VrSettings.seekStepLabel(step)}", 644f, ty,
            Glyph.SEEK_FORWARD, VrAction.SeekBy(step)
        )
        transportButton("next", "下一部", 776f, ty, Glyph.NEXT, VrAction.Next)

        // 工具行：图标 21px + 标签 18px 竖排，命中区 90×62（6.1°×4.2°）。
        // 与运输行同一组 x，两行上下对齐：错开排列在预览里看着像「两行宽度不一样」，
        // 戴镜后没有参照更难判断，整齐比多留 10px 间距值钱。
        toolButton("tLib", "片库", 248f, Glyph.GRID, VrAction.Back)
        toolButton("tFwd", "正前方", 380f, Glyph.CLOCK, VrAction.AlignForward)
        toolButton("tFmt", "格式", 512f, Glyph.FRAME, VrAction.OpenFormat)
        toolButton("tSet", "设置", 644f, Glyph.SLIDERS, VrAction.OpenSettings)
        toolButton("tExit", "退出", 776f, Glyph.POWER, VrAction.Exit)
    }

    /** 运输键：只有图标，命中区 80×80px（5.4°×5.4° @ 2.4m 面板）。 */
    private fun transportButton(
        id: String,
        name: String,
        cx: Float,
        cy: Float,
        kind: Glyph,
        action: VrAction
    ) {
        targets += GazeTarget(id, name, RectF(cx - 40f, cy - 40f, cx + 40f, cy + 40f), action)
        glyph(kind, cx, cy, 32f, C_TEXT, 3.2f)
    }

    /**
     * 工具键：图标 + 标签竖排，命中区 90×58px。
     *
     * 15:0x 之前是 66×58 + 标签 15px，命中区只有 3.1°×2.7°，凝视压准都费劲；
     * 标签 15px 在 1.8m 面板上更是只有 0.71°。现在跟着面板一起放大。
     *
     * 命中区上沿从 446 提到 450，是为了与运输行（下沿 440）留出 10px 间隔 ——
     * 原来只隔 1px，准星在交界处抖一下就会从「前进」跳到「格式」。
     */
    private fun toolButton(
        id: String,
        name: String,
        cx: Float,
        kind: Glyph,
        action: VrAction,
        active: Boolean = false
    ) {
        targets += GazeTarget(id, name, RectF(cx - 45f, 450f, cx + 45f, 508f), action)
        val color = if (active) C_ACCENT else C_TEXT
        glyph(kind, cx, 468f, 21f, color, 2.4f)
        label(name, cx, 498f, VrTheme.META, if (active) C_ACCENT else C_MUTED, Paint.Align.CENTER)
    }

    /** 字幕画在控制台上方，不与 300px 起的面板重叠；半透明底保证亮画面下可读。 */
    private fun drawSubtitle(value: String) {
        val lines = value.split('\n').take(2)
        // 15:0x：26px → 30px（约 2.0°）。字幕是「看片时必须读」的字，
        // 和正文同一档大小在暗场景里根本追不上。
        small.textSize = 30f
        val widest = lines.maxOf { small.measureText(it) } + 60f
        val top = 186f
        val bottom = top + 22f + lines.size * 38f
        fill.color = 0xDD0A0A0C.toInt()
        canvas.drawRoundRect(
            RectF(W / 2f - widest / 2f, top, W / 2f + widest / 2f, bottom), 12f, 12f, fill
        )
        text.textSize = 30f
        text.color = C_TEXT
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, W / 2f, top + 38f + i * 38f, text)
        }
    }

    // ── 格式确认（V2.5：六项三列两行，2D 世界锁定银幕正式可选） ──────────
    private fun drawFormat(state: VrUiState) {
        panel(UiGrid.panel())
        heading("确认视频格式 · 自动识别已高亮", UiGrid.TITLE_Y)
        centerText(
            "2D 银幕会像真实银幕一样固定在世界里，转头能看到边缘",
            102f, VrTheme.META
        )
        // 19:0x 规范：1 栏格 184×96，4 栏 × 3 行，行距 5；首行 120，末行下沿 418。
        // 十二项（10 + 鱼眼 Mono/SBS）正好铺满 4×3，不再出现「最后一行只剩两格」。
        VrFormat.QUICK_CHOICES.forEachIndexed { i, format ->
            val r = UiGrid.cell(
                i % 4, 1,
                120f + (i / 4) * (UiGrid.H_FMT + UiGrid.ROW_GAP),
                UiGrid.H_FMT
            )
            button(
                "fmt$i", format.shortLabel, r, VrAction.SetFormat(format),
                highlighted = sameFormat(format, state.suggestedFormat),
                sub = if (sameFormat(format, state.suggestedFormat)) "· 建议 ·" else null
            )
        }
        button("fmtBack", "返回", UiGrid.centered(2, UiGrid.BUTTON_Y, UiGrid.H_ROW), VrAction.CloseOverlay)
    }

    /**
     * 设置（V2.42 重做）。
     *
     * 旧版面是「− 值 +」四行 + 3×2 个「亮度 · 开」文字按钮，读起来像调试面板。
     * 但 SKYBOX 那种 40px 高的 iOS 式列表行**在凝视下用不了**：40px 只有 2.4°，
     * 压准都难，更别说停 600ms。所以这里取它的**视觉语言**（分组、卡片、右对齐值/开关），
     * 但格子是 2 列 × 5 行、每格 336×68（20.3°×4.1°），一格一个目标。
     */
    private fun drawSettings(state: VrUiState) {
        panel(UiGrid.panel())
        // 19:0x 规范：浮层标题居中、基线 50；右上说明右对齐到内容右缘 904。
        // 旧版标题左缘 178、说明右缘 846，两处都不在栅格上，换个浮层又变成别的数。
        heading(if (state.settingsPage == 0) "设置" else "画面调节", UiGrid.TITLE_Y)
        label(
            "凝视一格即可调整", UiGrid.CONTENT_R, UiGrid.TITLE_Y,
            VrTheme.META, C_DIM, Paint.Align.RIGHT
        )

        if (state.settingsPage == 1) {
            // 19:0x：按栅格重排。四项参数两列两行（各 2 栏），下面是四支鱼眼预设（各 1 栏）。
            val s = state.settings
            settingCard(
                "setView0",
                CardSpec("银幕宽度 · 2D", VrSettings.screenWidthLabel(s.screenWidthPercent), null,
                    VrAction.Adjust("screenWidth", 1)),
                UiGrid.cell(0, 2, UiGrid.BODY_TOP, UiGrid.H_ROW)
            )
            settingCard(
                "setView1",
                CardSpec("画面缩放 · 球面", VrSettings.contentScaleLabel(s.contentScalePercent), null,
                    VrAction.Adjust("contentZoom", 1)),
                UiGrid.cell(2, 2, UiGrid.BODY_TOP, UiGrid.H_ROW)
            )
            settingCard(
                "setFishFov",
                CardSpec("鱼眼张角", "${s.fisheyeFovDeg}°", null,
                    VrAction.Adjust("fisheyeFov", 1)),
                UiGrid.cell(0, 2, 125f, UiGrid.H_ROW)
            )
            // 19:2x：全画幅时画面圆不起作用，这里直接写「不计」而不是继续显示一个假数字。
            settingCard(
                "setFishCircle",
                CardSpec(
                    "鱼眼画面圆",
                    if (s.fisheyeFullFrame) "不计" else "${s.fisheyeCirclePercent}%",
                    null, VrAction.Adjust("fisheyeCircle", 1)
                ),
                UiGrid.cell(2, 2, 125f, UiGrid.H_ROW)
            )
            // 19:2x：画幅形态是**模式开关**，不是第七个旋钮 —— 单占一格，
            // 与「恢复默认」配成一行，用户不用去猜「圆 100% 是不是就等于全画幅」。
            settingCard(
                "setFishFrame",
                CardSpec("鱼眼全画幅", null, s.fisheyeFullFrame,
                    VrAction.ToggleOption("fisheyeFullFrame")),
                UiGrid.cell(0, 2, 186f, UiGrid.H_ROW)
            )
            button(
                "fishReset", "恢复 200° · 圆形", UiGrid.cell(2, 2, 186f, UiGrid.H_ROW),
                VrAction.ResetFisheye
            )
            FisheyePreset.entries.forEachIndexed { i, preset ->
                button(
                    "fishPreset$i", preset.label, UiGrid.cell(i, 1, 247f, UiGrid.H_ROW),
                    VrAction.ApplyFisheye(preset),
                    highlighted = s.fisheyeFovDeg == preset.fovDeg && !s.fisheyeFullFrame
                )
            }
            // 19:0x：当前片不是鱼眼时，这几项和四个预设改了都不会有画面变化。
            // 与其让人怀疑「是不是坏了」，不如把那句话直接写在页面上。
            val fishActive = state.format.projection == Projection.FISHEYE
            centerText(
                when {
                    !fishActive -> "当前片不是鱼眼 · 这几项改了不会有画面变化，换到鱼眼片才看得出来"
                    s.fisheyeFullFrame -> "全画幅 · 四角也采样，画面圆不计（按 ± 会自动退回圆形）"
                    else -> "圆形 · 圆外不采样；圆没占满画幅时把画面圆调小"
                },
                320f, VrTheme.META,
                if (fishActive) C_MUTED else C_ACCENT
            )
            // 19:1x：把「补偿了没有、按多少补偿」写出来。真机上圆是不是正的，
            // 只有戴上才知道；至少让这里的数字能对上他手上的片源。
            val eyeAspect = state.eyeAspect()
            centerText(
                when {
                    !fishActive -> "画面圆小于 100% 用于「圆没占满画面」的机器"
                    s.fisheyeFullFrame ->
                        "眼画幅 ${eyeAspectText(eyeAspect)} · 半径按半对角线归一，四角正好落在圆边"
                    kotlin.math.abs(eyeAspect - 1f) < 0.02f ->
                        "眼画幅 ${eyeAspectText(eyeAspect)} · 正方形，圆已是正圆"
                    else -> "眼画幅 ${eyeAspectText(eyeAspect)} · 按短边补偿，圆不会拉扁"
                },
                346f, VrTheme.TINY
            )
            // 第 2 页不提供「直接关闭」：必须回第 1 页退出，settingsPage 自然归零。
            val f = UiGrid.footer(2, 2)
            button("setViewBack", "← 返回设置", f[0], VrAction.SettingsPage(0))
            button("clearMem", "清除格式记忆", f[1], VrAction.ClearFormatMemory)
            return
        }

        val s = state.settings
        val volumeLevel = VrSettings.volumeStep(s.volume)
        val brightnessLevel = VrSettings.brightnessStep(s.brightness)

        val cards = listOf(
            CardSpec("亮度", VrSettings.BRIGHTNESS_LABELS[brightnessLevel], null, VrAction.Adjust("brightness", 1)),
            CardSpec("音量", "${volumeLevel + 1} / ${VrSettings.VOLUME_STEPS.size}", null, VrAction.Adjust("volume", 1)),
            CardSpec("重定正前方", "看哪儿定哪儿", null, VrAction.AlignForward),
            CardSpec("锁定视角", null, s.lockView, VrAction.ToggleOption("lockView")),
            CardSpec("自动连播", null, s.autoNext, VrAction.ToggleOption("autoNext")),
            CardSpec("自动淡出", null, s.autoFade, VrAction.ToggleOption("autoFade")),
            CardSpec("字幕", null, s.subtitlesEnabled, VrAction.ToggleOption("subtitles")),
            CardSpec("虚拟光标", null, s.cursorEnabled, VrAction.ToggleOption("cursor")),
            CardSpec("唤出热区", null, s.wakeZoneEnabled, VrAction.ToggleOption("wakeZone")),
            CardSpec("瞳距 IPD", VrSettings.ipdLabel(s.ipdHalfMm), null, VrAction.Adjust("ipd", 1)),
            CardSpec("凝视时长", "${s.gazeDurationMs} ms", null, VrAction.Adjust("gaze", 1))
        )
        // 19:0x：11 格 = 6 行 × 2 列，每格 2 栏（384×56），行距 5；
        // 末行下沿 425，按钮行 434 —— 与格式页、柜子页同一套节奏。
        cards.forEachIndexed { i, card ->
            settingCard(
                "set$i", card,
                UiGrid.cell(
                    (i % 2) * 2, 2,
                    UiGrid.BODY_TOP + (i / 2) * (UiGrid.H_ROW + UiGrid.ROW_GAP),
                    UiGrid.H_ROW
                )
            )
        }

        val f = UiGrid.footer(1, 2, 1)
        button("setBack", "返回", f[0], VrAction.CloseOverlay)
        button("ipdTune", "瞳距 · 边看边调", f[1], VrAction.StartIpdTuning, highlighted = true)
        button("setViewEnter", "画面调节 →", f[2], VrAction.SettingsPage(1))
    }

    private data class CardSpec(
        val title: String,
        val value: String?,
        val on: Boolean?,
        val action: VrAction
    )

    /** 一格设置：左边名字、右边值或开关；整格就是一个凝视目标。 */
    private fun settingCard(id: String, card: CardSpec, rect: RectF) {
        targets += GazeTarget(id, card.title, RectF(rect), card.action)
        fill.color = C_PANEL_HI
        canvas.drawRoundRect(rect, VrTheme.R_CARD, VrTheme.R_CARD, fill)
        // 15:0x：16px → 21px。18:5x 卡片高从 68 压到 56（11 格要 6 行），
        // 21px 仍居中，上下各留 17px，不贴边；56px ≈ 3.8°，高于凝视命中下限。
        label(card.title, rect.left + UiGrid.PAD, rect.centerY() + 8f, VrTheme.BODY, C_TEXT)
        val value = card.value
        val on = card.on
        if (on != null) {
            // 开关也从 46×20 放到 58×26：旧尺寸在 1.8m 面板上只有 2.8°×1.2°，
            // 看不出是「开」还是「关」，更别说确认自己点中了。
            val pillW = 58f
            val pillH = 26f
            val pillLeft = rect.right - UiGrid.PAD - pillW
            fill.color = if (on) C_ACCENT else 0xFF3A3A40.toInt()
            canvas.drawRoundRect(
                RectF(pillLeft, rect.centerY() - pillH / 2f, pillLeft + pillW, rect.centerY() + pillH / 2f),
                pillH / 2f, pillH / 2f, fill
            )
            fill.color = if (on) 0xFF101013.toInt() else 0xFF9E9EA6.toInt()
            canvas.drawCircle(
                if (on) pillLeft + pillW - 13f else pillLeft + 13f, rect.centerY(), 9f, fill
            )
        } else if (value != null) {
            label(value, rect.right - UiGrid.PAD, rect.centerY() + 8f, VrTheme.BODY, C_MUTED, Paint.Align.RIGHT)
        }
    }

    // ── 绘制辅助 ───────────────────────────────────────────────────────
    private fun panel(rect: RectF) {
        // V2.50：面板改成不透明实底。半透明 `0xEE` 在视频上会透出画面，
        // 亮场景里文字对比度掉到看不清；SourceVR 的面板是纯 #19191A。
        fill.color = C_PANEL
        canvas.drawRoundRect(rect, VrTheme.R_PANEL, VrTheme.R_PANEL, fill)
        stroke.color = C_BORDER
        stroke.strokeWidth = 1.5f
        canvas.drawRoundRect(rect, VrTheme.R_PANEL, VrTheme.R_PANEL, stroke)
    }

    private fun button(
        id: String,
        label: String,
        rect: RectF,
        action: VrAction,
        highlighted: Boolean = false,
        sub: String? = null
    ) {
        targets += GazeTarget(id, label, RectF(rect), action)
        fill.color = if (highlighted) C_PANEL_HI else C_PANEL
        canvas.drawRoundRect(rect, VrTheme.R_BUTTON, VrTheme.R_BUTTON, fill)
        stroke.color = if (highlighted) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (highlighted) 3f else 2f
        canvas.drawRoundRect(rect, VrTheme.R_BUTTON, VrTheme.R_BUTTON, stroke)
        // V2.5：格式确认屏改三列后按钮只有 248px 宽，长标签按可用宽度等比缩字，
        // 绘制完立刻复位，避免共用 Paint 影响后续按钮与标题。
        // 15:0x：25px → 28px（约 1.9°）。下限也从 17px 提到 20px ——
        // 旧下限在 1.8m 面板上只有 0.8°，缩到下限等于放弃可读性。
        text.textSize = VrTheme.ITEM
        text.color = C_TEXT
        val labelLimit = rect.width() - UiGrid.PAD
        val labelWidth = text.measureText(label)
        if (labelWidth > labelLimit) {
            text.textSize = (VrTheme.ITEM * labelLimit / labelWidth).coerceAtLeast(VrTheme.META)
        }
        // V2.7.4：缩到下限还放不下就截断，宁可少显示几个字也不能越过卡片边界。
        canvas.drawText(
            ellipsize(label, rect.width() - 24f, text),
            rect.centerX(), rect.centerY() + if (sub == null) 10f else -5f, text
        )
        text.textSize = VrTheme.ITEM
        if (sub != null) {
            small.textSize = VrTheme.BODY
            small.color = if (highlighted) C_ACCENT else C_MUTED
            val subLimit = rect.width() - UiGrid.PAD
            val subWidth = small.measureText(sub)
            if (subWidth > subLimit) {
                small.textSize = (VrTheme.BODY * subLimit / subWidth).coerceAtLeast(VrTheme.TINY)
            }
            canvas.drawText(sub, rect.centerX(), rect.centerY() + 31f, small)
            small.textSize = VrTheme.BODY
            small.color = C_MUTED
        }
    }

    /**
     * 片库的一格：232×165px，在片库那块 2.4×1.2 米的平面上约 14.0°×10.8°。
     *
     * V2.13：原来只有 106px 高，标题被压在 30px 的底条里、字号 17px——
     * 真机反馈「看不清、完全无法识别是哪一个文件」。现在格子中央放的是
     * **拍摄日期 + 时刻**：用户自己的片子就是 `VID_20260712_143022.mp4`，
     * 名字本身没有含义，时刻才是他记得住的东西。有海报就直接铺满，日期退到角标。
     */
    /**
     * V2.43：名字写在海报**外面**。原来 232×145 的格子里塞 44px 时刻 +
     * 22px 日期 + 19px 分辨率，还压在渐变条上，戴镜后根本认不出是哪一部。
     *
     * 命中区始终用**名义 rect**，不随悬停放大：否则准星一压上去目标就挪位，
     * 凝视进度会在临界处反复归零。放大只改绘制。
     */
    private fun cell(
        id: String,
        item: MediaItemEntity,
        rect: RectF,
        action: VrAction,
        badge: String?,
        /** 20:2x：后台探测 + `DecoderCaps.isUnsupported` 判定「确定解不了」。 */
        unsupported: Boolean = false,
        current: Boolean,
        hover: Boolean,
        progress: Float? = null
    ) {
        targets += GazeTarget(id, prettyName(item.title), RectF(rect), action)

        val k = if (hover) 1.05f else 1f
        val w = rect.width() * k
        val h = rect.height() * k
        val box = RectF(
            rect.centerX() - w / 2f, rect.centerY() - h / 2f,
            rect.centerX() + w / 2f, rect.centerY() + h / 2f
        )
        fill.color = C_POSTER
        canvas.drawRoundRect(box, VrTheme.R_CARD, VrTheme.R_CARD, fill)

        val poster = item.posterPath.takeIf { it.isNotBlank() }?.let { posterLoader(it) }
        if (poster != null && poster.width > 0 && poster.height > 0) {
            canvas.save()
            val clip = Path()
            clip.addRoundRect(box, VrTheme.R_CARD, VrTheme.R_CARD, Path.Direction.CW)
            canvas.clipPath(clip)
            val scale = max(box.width() / poster.width, box.height() / poster.height)
            val dw = poster.width * scale
            val dh = poster.height * scale
            val dst = RectF(
                box.centerX() - dw / 2f, box.centerY() - dh / 2f,
                box.centerX() + dw / 2f, box.centerY() + dh / 2f
            )
            canvas.drawBitmap(poster, Rect(0, 0, poster.width, poster.height), dst, bitmapPaint)
            canvas.restore()
            if (!PosterRepository.isGeneratedCover(item.posterPath)) {
                drawPosterCaption(item, box)
            }
        } else {
            drawIdentity(item, box)
        }

        // 续播进度：一条贴着海报底边的细线，只在有断点时出现。
        if (progress != null) {
            fill.color = 0x99000000.toInt()
            canvas.drawRoundRect(
                RectF(box.left + 8f, box.bottom - 8f, box.right - 8f, box.bottom - 5f),
                2f, 2f, fill
            )
            fill.color = C_ACCENT
            canvas.drawRoundRect(
                RectF(
                    box.left + 8f, box.bottom - 8f,
                    box.left + 8f + (box.width() - 16f) * progress.coerceIn(0f, 1f),
                    box.bottom - 5f
                ), 2f, 2f, fill
            )
        }

        if (badge != null) {
            small.textSize = 14f
            val width = small.measureText(badge) + 14f
            fill.color = 0xE62A2A2F.toInt()
            canvas.drawRoundRect(
                RectF(box.right - 8f - width, box.top + 4f, box.right - 8f, box.top + 24f),
                10f, 10f, fill
            )
            label(badge, box.right - 15f, box.top + 19f, VrTheme.TINY, C_ACCENT, Paint.Align.RIGHT)
        }

        // 20:2x：「本机解不了」角标。**左上角**，与右上角「续 mm:ss」分列两端。
        // 卡片只有 157px 宽：右上胶囊约 60px（从 left+89 起），左上 3 字约 56px
        // （到 left+64 止），中间留 25px，两者不会叠。文案压成 3 个字是宽度约束。
        // 只在「平台明确说不支持」时出现；编码未知 / 探测失败一律不标。
        if (unsupported) {
            val tag = "解不了"
            small.textSize = 14f
            val tw = small.measureText(tag) + 14f
            fill.color = 0xE62A2A2F.toInt()
            canvas.drawRoundRect(
                RectF(box.left + 8f, box.top + 4f, box.left + 8f + tw, box.top + 24f),
                10f, 10f, fill
            )
            label(tag, box.left + 15f, box.top + 19f, VrTheme.TINY, C_DANGER, Paint.Align.LEFT)
        }

        stroke.color = if (hover || current) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (hover || current) 3f else 1.5f
        canvas.drawRoundRect(box, 12f, 12f, stroke)

        // 名字在卡外：不压画面、不抢对比度，字号按 1024×512 / 2 米 ≈ 16.5px/度
        // 取 15px（0.9°）下限，悬停提到 17px 并变白加粗。
        val stamp = timestampOf(item.title)
        val name = stamp?.let { "${it.date} ${it.time}" } ?: prettyName(item.title)
        small.textSize = if (hover) 17f else 15f
        val shown = ellipsize(name, rect.width() + 20f, small)
        if (hover) {
            strongLabel(shown, rect.centerX(), rect.bottom + 22f, 17f, C_TEXT, Paint.Align.CENTER)
        } else {
            label(
                shown, rect.centerX(), rect.bottom + 22f, 15f,
                if (current) C_TEXT else C_MUTED, Paint.Align.CENTER
            )
        }
    }

    /**
     * 真海报上的一条细身份条：左边时刻、右边时长。
     * V2.43：卡片缩到 100px 高，渐变只占 30%，字号按高度算——
     * 原来固定 42px 渐变 + 17px 字在缩小的卡上会盖掉半个画面。
     */
    private fun drawPosterCaption(item: MediaItemEntity, rect: RectF) {
        val stamp = timestampOf(item.title)
        val when_ = stamp?.time ?: prettyName(item.title)
        val length = item.durationMs.takeIf { it > 0L }?.let { formatTime(it) }
        val bandH = (rect.height() * 0.30f).coerceAtMost(32f)
        val bandTop = rect.bottom - bandH

        scrimPaint.shader = LinearGradient(
            0f, bandTop, 0f, rect.bottom,
            0x00000000, 0xC4000000.toInt(), Shader.TileMode.CLAMP
        )
        canvas.save()
        val clip = Path()
        clip.addRoundRect(rect, VrTheme.R_CARD, VrTheme.R_CARD, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRect(RectF(rect.left, bandTop, rect.right, rect.bottom), scrimPaint)
        canvas.restore()
        scrimPaint.shader = null

        small.textSize = 14f
        small.color = C_TEXT
        small.textAlign = Paint.Align.LEFT
        canvas.drawText(
            ellipsize(when_, rect.width() - 70f, small),
            rect.left + 10f, rect.bottom - 9f, small
        )
        if (length != null) {
            small.color = C_ACCENT
            small.textAlign = Paint.Align.RIGHT
            canvas.drawText(length, rect.right - 10f, rect.bottom - 9f, small)
        }
        small.textAlign = Paint.Align.CENTER
        small.color = C_MUTED
    }

    /** 没有海报时，格子里放「什么时候拍的」；名字已经在卡外，卡内不重复写。 */
    private fun drawIdentity(item: MediaItemEntity, rect: RectF) {
        val stamp = timestampOf(item.title)
        val h = rect.height()
        if (stamp != null) {
            label(stamp.date, rect.centerX(), rect.top + h * 0.30f, 14f, C_DIM, Paint.Align.CENTER)
            strongLabel(stamp.time, rect.centerX(), rect.top + h * 0.70f, 30f, C_TEXT, Paint.Align.CENTER)
        } else {
            // 名字在卡外，卡内只留一个淡的播放三角：不重复文字，也不显得是空卡。
            glyph(Glyph.PLAY, rect.centerX(), rect.centerY(), 34f, C_DIM, 3f)
        }
    }

    private fun sameFormat(a: VrFormat, b: VrFormat): Boolean =
        a.projection == b.projection && a.layout == b.layout && a.fov == b.fov

    /**
     * V2.9：没海报时格子中间显示什么。
     *
     * 时长能区分「这一段是不是我要找的」，分辨率能区分「这是相机拍的大片还是手机随手拍」。
     * 两条都没有就返回 null，让格子保持干净，不塞占位符。
     */
    private fun metaOf(item: MediaItemEntity): String? {
        val parts = ArrayList<String>(2)
        if (item.durationMs > 0L) parts += formatTime(item.durationMs)
        if (item.width > 0 && item.height > 0) parts += "${item.width}×${item.height}"
        return if (parts.isEmpty()) null else parts.joinToString(" · ")
    }

    /**
     * V2.7.4：把文件名变成人能读的东西。
     *
     * 真机反馈「片库的文件名非常杂乱」。用户自己的视频本来就长这样：
     * `VID_20260712_143022.mp4`、`PXL_20260712_143022123.mp4`、`GOPR0231.MP4`。
     * 把日期时间抽出来显示成 `2026-07-12 14:30`，比原样堆在卡片上清楚得多；
     * 认不出的名字按原样显示（不再去掉第二层扩展名、不再把下划线换成空格）。
     */
    private fun prettyName(raw: String): String {
        // V2.29：传进来的 title 在扫描 / 列举时已经去掉过一次真实扩展名，
        // 这里再 substringBeforeLast('.') 就是**二次去扩展名** ——
        // "Avengers.Endgame.2024" 会被切成 "Avengers.Endgame"，
        // 中文名里带点的版本 / 集数也一起被切掉。去掉这一步。
        // 同理不再把 '_' 换成空格：用户自己起的名字、中文名要原样显示，
        // 对标参考应用按文件名如实呈现，不擅自改写。
        val name = raw.trim()
        val stamp = Regex("(\\d{4})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})").find(name)
        if (stamp != null) {
            val (y, mo, d, h, mi) = stamp.destructured
            return "$y-$mo-$d $h:$mi"
        }
        // 只折叠多余空白，不改字符本身。
        return name.replace(Regex("\\s+"), " ").trim()
    }

    /** 超宽就截断加省略号，绝不让文字越过卡片边界。 */
    private fun ellipsize(value: String, maxWidth: Float, paint: Paint): String {
        if (maxWidth <= 0f || paint.measureText(value) <= maxWidth) return value
        val dots = "…"
        val dotsWidth = paint.measureText(dots)
        var end = value.length
        while (end > 1 && paint.measureText(value, 0, end) + dotsWidth > maxWidth) end--
        return value.substring(0, end).trimEnd() + dots
    }

    /**
     * 2.1.0：按像素宽度折行，最多 [maxLines] 行，最后一行放不下就省略。
     *
     * 错误卡的第二行现在可能是「画面 5400×2700 超过本机上限 4096×2176；
     * 换一个 4096×2176 以内的版本或转 H.265」这种长句，20px 一行放不下。
     * 不折行就只能 ellipsize，把最关键的「换什么」那句截掉——等于白给。
     */
    private fun wrapText(
        value: String,
        paint: Paint,
        maxWidth: Float,
        maxLines: Int
    ): List<String> {
        if (maxWidth <= 0f || maxLines <= 0) return emptyList()
        val out = ArrayList<String>(maxLines)
        var start = 0
        while (start < value.length && out.size < maxLines) {
            var end = start
            while (end < value.length && paint.measureText(value, start, end + 1) <= maxWidth) end++
            if (end == start) end = start + 1
            if (out.size == maxLines - 1 && end < value.length) {
                var cut = end
                while (cut > start &&
                    paint.measureText(value, start, cut) + paint.measureText("…") > maxWidth
                ) cut--
                out.add(value.substring(start, cut).trimEnd() + "…")
                return out
            }
            out.add(value.substring(start, end))
            start = end
        }
        return out
    }

    private fun heading(value: String, y: Float) {
        // 15:0x：30px → 34px（约 2.3°）。区块标题是「一进这块就该读到」的那一行，
        // 之前和正文只差 1 档，视觉上分不出层级。
        text.textSize = VrTheme.TITLE
        text.color = C_TEXT
        canvas.drawText(value, W / 2f, y, text)
    }

    /**
     * V2.8：左对齐 / 右对齐的文本。
     * 片库顶栏要「左标题 + 右页码」，居中排会互相压在一起。
     * 画完立刻把 Paint 的对齐方式复位，避免污染后面共用的 centerText / button。
     */
    private fun label(
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        small.textSize = size
        small.color = color
        small.textAlign = align
        canvas.drawText(value, x, y, small)
        small.textAlign = Paint.Align.CENTER
        small.color = C_MUTED
    }

    /**
     * 居中一行小字。
     *
     * alpha43：补 `x` 参数。原来 x 写死成 `W / 2f`，于是 `heroCard()` 空态那句
     * 「还没有新片」被画到了**整块画布的正中**（也就是片库中栏第 3 行上），
     * 而不是右栏那张卡里 —— alpha43 的界面快照一眼就看出来了。
     * 整屏居中的调用点保持默认值，不受影响。
     */
    /** 眼画幅写成 `1.00:1`；locale 固定 US，避免小数逗号把「2,00:1」画进界面。 */
    private fun eyeAspectText(aspect: Float): String =
        String.format(java.util.Locale.US, "%.2f:1", aspect)

    private fun centerText(
        value: String,
        y: Float,
        size: Float,
        color: Int = C_MUTED,
        x: Float = W / 2f
    ) {
        small.textSize = size
        small.color = color
        canvas.drawText(value, x, y, small)
        small.color = C_MUTED
    }

    /** 粗体版的 label：日期时刻这种「一眼要读到」的字用它。 */
    private fun strongLabel(
        value: String,
        x: Float,
        y: Float,
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.LEFT
    ) {
        strong.textSize = size
        strong.color = color
        strong.textAlign = align
        canvas.drawText(value, x, y, strong)
        strong.textAlign = Paint.Align.CENTER
        strong.color = C_TEXT
    }

    // ── 图标按钮（V2.13） ─────────────────────────────────────────────
    /**
     * 片库顶栏的四个按钮。
     *
     * 用户的原话是「四个按钮也占了很大一部分面积（建议改成图标按钮）」。
     * 但纸盒上只有凝视可用，图标不能真的画成 40px 的小东西——那样根本瞄不中。
     * 所以做法是：**图标画小，命中区不缩小**，按钮框仍是 96×66px（约 5.8°×4.3°）。
     */
    private enum class Glyph {
        CHEVRON_LEFT, CHEVRON_RIGHT, GEAR, POWER,
        // V2.42：控制台要全是图标，光靠上面四个不够——文字按钮是这块「简陋」的主因。
        GRID, CLOCK, CC, FRAME, PLAY, PAUSE, PREV, NEXT,
        // alpha43：REW30/FWD30 的 3/4 圈弧线箭头在 26px 下箭头尖端与弧末端
        // 对不齐，真机上看起来就是「图标错位」。换成严格对称的双三角。
        SEEK_BACK, SEEK_FORWARD, MORE, SLIDERS
    }

    private fun iconButton(
        id: String,
        name: String,
        rect: RectF,
        action: VrAction,
        glyph: Glyph,
        dimmed: Boolean = false
    ) {
        targets += GazeTarget(id, name, RectF(rect), action)
        fill.color = C_PANEL
        canvas.drawRoundRect(rect, 16f, 16f, fill)
        stroke.color = C_BORDER
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(rect, 16f, 16f, stroke)

        val color = if (dimmed) 0x66F2F2F3 else C_TEXT
        glyph(glyph, rect.centerX(), rect.centerY(), 30f, color, 4f)
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = C_BORDER
        stroke.strokeWidth = 2f
    }


    /**
     * 统一图标绘制（V2.42）。
     *
     * 约定：`u/v` 是**图标单位**（−1..1，u 向右、v 向下），乘 `s` 之后才是画布像素。
     * 之前这里踩过 `(cx - 0.55) * s * S` 这种把画布坐标和图标单位乘到一起的坑，
     * 结果是图标整个缩到中心、看起来像"没画"。所有分支只准通过 p() 取值。
     */
    private fun glyph(kind: Glyph, cx: Float, cy: Float, size: Float, color: Int, lw: Float = 2.4f) {
        val s = size / 2f
        stroke.color = color
        stroke.strokeWidth = lw
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        fill.color = color

        fun ux(u: Float) = cx + u * s
        fun uy(v: Float) = cy + v * s

        fun line(vararg uv: Float) {
            val path = Path()
            path.moveTo(ux(uv[0]), uy(uv[1]))
            var i = 2
            while (i + 1 < uv.size) {
                path.lineTo(ux(uv[i]), uy(uv[i + 1]))
                i += 2
            }
            canvas.drawPath(path, stroke)
        }

        fun shape(vararg uv: Float) {
            val path = Path()
            path.moveTo(ux(uv[0]), uy(uv[1]))
            var i = 2
            while (i + 1 < uv.size) {
                path.lineTo(ux(uv[i]), uy(uv[i + 1]))
                i += 2
            }
            path.close()
            canvas.drawPath(path, fill)
        }

        fun box(u0: Float, v0: Float, u1: Float, v1: Float, r: Float = 0f, filled: Boolean = true) {
            val rect = RectF(ux(u0), uy(v0), ux(u1), uy(v1))
            if (filled) canvas.drawRoundRect(rect, r * s, r * s, fill)
            else canvas.drawRoundRect(rect, r * s, r * s, stroke)
        }

        fun circle(r: Float, filled: Boolean = false) {
            if (filled) canvas.drawCircle(cx, cy, r * s, fill) else canvas.drawCircle(cx, cy, r * s, stroke)
        }

        // alpha43：旧 REW30/FWD30 的「弧末端开口箭头」辅助函数随弧线图标一起删除。
        // 它按弧终点切向手算箭头，26px 下尖端与弧末端对不齐 —— 真机看到的
        // 「前进/后退图标错位」就出在这里。新图标只用 shape/line，严格居中对称。

        when (kind) {
            Glyph.CHEVRON_LEFT -> line(0.25f, -0.5f, -0.3f, 0f, 0.25f, 0.5f)
            Glyph.CHEVRON_RIGHT -> line(-0.25f, -0.5f, 0.3f, 0f, -0.25f, 0.5f)
            Glyph.GEAR -> {
                // 齿轮：细环 + 8 颗短齿。齿细长会看成太阳，和「亮度」撞车（V2.13 的老问题）。
                circle(0.34f)
                val tooth = lw * 1.4f
                stroke.strokeWidth = tooth
                for (i in 0 until 8) {
                    val a = i * PI / 4.0
                    canvas.drawLine(
                        ux((cos(a) * 0.52).toFloat()), uy((sin(a) * 0.52).toFloat()),
                        ux((cos(a) * 0.86).toFloat()), uy((sin(a) * 0.86).toFloat()), stroke
                    )
                }
                stroke.strokeWidth = lw
            }
            Glyph.POWER -> {
                canvas.drawArc(
                    RectF(ux(-0.72f), uy(-0.72f), ux(0.72f), uy(0.72f)), -58f, 296f, false, stroke
                )
                line(0f, -0.9f, 0f, -0.05f)
            }
            Glyph.GRID -> {
                // 2×2 小方块：描边必须比格子窄一大截，否则 22px 下四个格子糊成四个点。
                stroke.strokeWidth = lw * 0.72f
                for (du in floatArrayOf(-1f, 1f)) {
                    for (dv in floatArrayOf(-1f, 1f)) {
                        val c = 0.44f
                        box(du * c - 0.34f, dv * c - 0.34f, du * c + 0.34f, dv * c + 0.34f, 0.12f, false)
                    }
                }
                stroke.strokeWidth = lw
            }
            Glyph.CLOCK -> {
                circle(0.8f)
                line(0f, 0f, 0f, -0.5f)
                line(0f, 0f, 0.4f, 0.18f)
            }
            Glyph.CC -> {
                box(-0.92f, -0.55f, 0.92f, 0.55f, 0.16f, false)
                line(-0.6f, 0.06f, -0.34f, 0.06f)
                line(0.16f, 0.06f, 0.56f, 0.06f)
            }
            Glyph.FRAME -> {
                box(-0.86f, -0.6f, 0.86f, 0.6f, 0.12f, false)
                line(-0.86f, -0.12f, 0.86f, -0.12f)
            }
            Glyph.PLAY -> shape(-0.5f, -0.85f, -0.5f, 0.85f, 0.95f, 0f)
            Glyph.PAUSE -> {
                box(-0.62f, -0.8f, -0.16f, 0.8f, 0.08f)
                box(0.16f, -0.8f, 0.62f, 0.8f, 0.08f)
            }
            Glyph.PREV -> {
                box(-0.9f, -0.7f, -0.6f, 0.7f, 0.08f)
                shape(0.8f, -0.7f, 0.8f, 0.7f, -0.4f, 0f)
            }
            Glyph.NEXT -> {
                box(0.6f, -0.7f, 0.9f, 0.7f, 0.08f)
                shape(-0.8f, -0.7f, -0.8f, 0.7f, 0.4f, 0f)
            }
            // alpha43：快退 / 快进 = 两个同向三角，x 范围严格 [−0.8, 0.8] 居中对称。
            // 与 PREV/NEXT（竖条 + 单三角 = 换一部）区分得开，小尺寸下也不会糊。
            Glyph.SEEK_BACK -> {
                shape(0.8f, -0.68f, 0.8f, 0.68f, 0f, 0f)
                shape(0f, -0.68f, 0f, 0.68f, -0.8f, 0f)
            }
            Glyph.SEEK_FORWARD -> {
                shape(-0.8f, -0.68f, -0.8f, 0.68f, 0f, 0f)
                shape(0f, -0.68f, 0f, 0.68f, 0.8f, 0f)
            }
            Glyph.SLIDERS -> {
                // 设置用「滑杆」而不是齿轮：齿轮 8 颗齿缩到 22px 就成太阳，
                // 和「亮度」撞车（V2.13 已经踩过）。滑杆在任何尺寸都是「可调」。
                val us = floatArrayOf(-0.3f, 0.28f, -0.08f)
                for (i in 0 until 3) {
                    val v = -0.58f + i * 0.58f
                    line(-0.78f, v, 0.78f, v)
                    canvas.drawCircle(ux(us[i]), uy(v), 0.19f * s, fill)
                }
            }
            Glyph.MORE -> {
                for (i in -1..1) canvas.drawCircle(ux(i * 0.5f), cy, 0.13f * s, fill)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.strokeJoin = Paint.Join.MITER
    }


    // ── 文件名 → 人能读的身份（V2.13） ───────────────────────────────
    private data class Stamp(val date: String, val time: String)

    /**
     * 从文件名里抽出「什么时候拍的」。
     *
     * 覆盖两类真实名字：`VID_20260712_143022` / `PXL_20260712_143022123`
     * 与 `2026-07-12 14.30.22`。抽不出来就返回 null，由调用方退回显示名字本身。
     * 年份等于今年时不写年份——片库里绝大多数是今年拍的，写了只是噪音。
     */
    private fun timestampOf(raw: String): Stamp? {
        val base = raw.substringBeforeLast('.').ifBlank { raw }
        val groups = STAMP_COMPACT.find(base)?.groupValues
            ?: STAMP_LOOSE.find(base)?.groupValues
            ?: return null
        val year = groups[1].toIntOrNull() ?: return null
        val month = groups[2].toIntOrNull() ?: return null
        val day = groups[3].toIntOrNull() ?: return null
        val hour = groups[4].toIntOrNull() ?: return null
        val minute = groups[5].toIntOrNull() ?: return null
        if (month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return null
        val second = groups.getOrNull(6)?.toIntOrNull()?.takeIf { it in 0..59 }
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        val date = if (year == thisYear) "${month}月${day}日" else "${year}年${month}月${day}日"
        val time = if (second == null) {
            "%02d:%02d".format(hour, minute)
        } else {
            "%02d:%02d:%02d".format(hour, minute, second)
        }
        return Stamp(date, time)
    }

    /** 两行以内放下名字；放不下就先找平衡断点，再不行截断加省略号。 */
    private fun wrapTwoLines(value: String, maxWidth: Float): List<String> {
        strong.textSize = 30f
        if (strong.measureText(value) <= maxWidth) return listOf(value)
        val words = value.split(' ').filter { it.isNotEmpty() }
        if (words.size >= 2) {
            var bestCut = 0
            var bestDiff = Float.MAX_VALUE
            for (i in 1 until words.size) {
                val head = words.take(i).joinToString(" ")
                val tail = words.drop(i).joinToString(" ")
                val headWidth = strong.measureText(head)
                val tailWidth = strong.measureText(tail)
                if (headWidth <= maxWidth && tailWidth <= maxWidth) {
                    val diff = kotlin.math.abs(headWidth - tailWidth)
                    if (diff < bestDiff) {
                        bestDiff = diff
                        bestCut = i
                    }
                }
            }
            if (bestCut > 0) {
                return listOf(
                    words.take(bestCut).joinToString(" "),
                    words.drop(bestCut).joinToString(" ")
                )
            }
        }
        return listOf(ellipsize(value, maxWidth, strong))
    }

    private fun formatTime(ms: Long): String {
        val seconds = ms / 1_000L
        return "%02d:%02d".format(seconds / 60L, seconds % 60L)
    }
}
