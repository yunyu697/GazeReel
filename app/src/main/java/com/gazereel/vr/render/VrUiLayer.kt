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
import com.gazereel.vr.core.GazeTarget
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.PosterRepository
import com.gazereel.vr.player.SubtitleParser
import com.gazereel.vr.ui.AdjustFeedback
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
        private val C_PANEL = 0xFF0E1B2C.toInt()
        private val C_PANEL_HI = 0xFF12283F.toInt()
        private val C_BORDER = 0xFF22364F.toInt()
        // 设计文档 08.5：把残留的旧绿色 C_ACCENT 统一到品牌青蓝 #67E8F9。
        // 第 143 行的规矩没变——这个颜色只给「现在该点的那个」，不做装饰。
        private val C_ACCENT = 0xFF67E8F9.toInt()
        private val C_TEXT = 0xFFEAF2FA.toInt()
        private val C_MUTED = 0xFFB9C6DA.toInt()
        private val C_POSTER = 0xFF173A5E.toInt()

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

        /** 20260712_143022 这类相机 / 手机命名。 */
        private val STAMP_COMPACT =
            Regex("(\\d{4})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})(\\d{2})")

        /** 2026-07-12 14.30.22 / 2026-07-12 14:30 这类导出后改名。 */
        private val STAMP_LOOSE =
            Regex("(\\d{4})-(\\d{2})-(\\d{2})[ _T](\\d{2})[:.](\\d{2})(?:[:.](\\d{2}))?")

        /** 控制台相对舒适中心再低多少（约 13°，与旧版的 −0.46 米等值）。 */
        private const val CONSOLE_DROP_RAD = 0.2269f
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
    data class PanelSpec(val width: Float, val height: Float, val yaw: Float, val pitch: Float)

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
        val candidate = state.postureCandidate
        if (candidate != null) {
            // 候选方向 = 用户此刻正在看的方向。卡片就摆在那儿，居中。
            return PanelSpec(1.8f, 0.9f, state.postureYaw, candidate)
        }
        return when {
            state.screen == VrScreen.PLAYER && state.overlay == VrOverlay.NONE ->
                PanelSpec(1.8f, 0.9f, 0f, -CONSOLE_DROP_RAD)
            state.screen == VrScreen.LIBRARY && state.overlay == VrOverlay.NONE ->
                PanelSpec(2.4f, 1.2f, 0f, 0f)
            else -> PanelSpec(1.8f, 0.9f, 0f, 0f)
        }
    }

    private val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val targets = mutableListOf<GazeTarget>()

    // render() 在 GL 线程写 targets，GazeDetector / TriggerDispatcher 在主线程读；
    // 每帧结束后发布不可变快照，避免跨线程并发修改。
    @Volatile
    private var published: List<GazeTarget> = emptyList()

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

    fun currentTargets(): List<GazeTarget> = published

    /** 准星射线与 UI 平面的交点命中哪个目标；凝视与触发键共用此判定。 */
    fun targetAt(rotation: FloatArray, state: VrUiState): GazeTarget? {
        val point = gazePoint(rotation, state)
        return currentTargets().firstOrNull { it.rect.contains(point.x, point.y) }
    }

    /** 播放控制台置于视野下方约 13°，其余界面居中（PRD 3.5）。 */
    fun panelPitch(state: VrUiState): Float = panelSpec(state).pitch

    /**
     * 头部姿态 → 准星在 UI 画布上的落点。面板尺寸与朝向都会影响映射。
     *
     * V2.19c：面板不再恒在正前方（姿态确认卡会整块转到候选方向），
     * 所以落点必须减掉面板自己的朝向，否则卡片上的按钮永远点不中。
     */
    fun gazePoint(rotation: FloatArray, state: VrUiState): PointF {
        val spec = panelSpec(state)
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

    fun render(
        state: VrUiState,
        gazeTargetId: String?,
        gazeProgress: Float,
        cursor: PointF? = null
    ): Bitmap {
        bitmap.eraseColor(Color.TRANSPARENT)
        targets.clear()

        // V2.19c：姿态确认卡出现时，**只画这一张卡**。
        //
        // 它要摆在候选方向的视线正前方，而整个面板只有一个（`panelSpec` 会把平面
        // 整块挪过去）。如果这时还画下层界面，控制台和片库会跟着一起平移几十度——
        // 那正是「检测到就动画面」这个禁忌本身。下层不画，就没有东西会跳。
        // 视频是另一层，完全不受影响，所以「画面一帧不动」仍然成立。
        val postureUp = state.gazeArmed && state.postureCandidate != null
        if (postureUp) {
            drawPosturePrompt(state)
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
            if (!state.gazeArmed) drawArmPrompt()
        }

        // V2.11：档位变化浮层，压在提示之上——用户按了键就得立刻看见结果。
        state.feedback?.let { drawAdjustFeedback(it) }

        published = targets.toList()

        // V2.14：光标跟着视线走，不再钉在画布正中。
        // 原来画在 (W/2, H/2)，而控制台的平面在 −13°，准星与真实视线差着十几度，
        // 用户看到的是一个「不动的小圈」，只能靠触发临近的按钮去猜。
        cursor?.let { drawCursor(it, state) }

        if (gazeTargetId != null && gazeProgress > 0f) {
            val target = targets.firstOrNull { it.id == gazeTargetId }
            if (target != null) {
                val r = target.rect
                val radius = 30f
                val oval = RectF(
                    r.centerX() - radius, r.centerY() - radius,
                    r.centerX() + radius, r.centerY() + radius
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
            state.overlay == VrOverlay.NONE && !state.consoleVisible
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
    private fun drawArmPrompt() {
        fill.color = 0xB308111E.toInt()
        canvas.drawRect(RectF(0f, 0f, W.toFloat(), H.toFloat()), fill)

        val card = RectF(212f, 140f, 812f, 380f)
        fill.color = 0xF00E1B2C.toInt()
        canvas.drawRoundRect(card, 26f, 26f, fill)
        stroke.color = C_ACCENT
        stroke.strokeWidth = 3f
        canvas.drawRoundRect(card, 26f, 26f, stroke)

        heading("戴稳了？", 212f)
        label(
            "按一下纸盒上的触发键开始",
            W / 2f, 274f, 30f, C_ACCENT, Paint.Align.CENTER
        )
        label(
            "开始之前凝视不生效 · 塞手机、调头带时不会误开视频",
            W / 2f, 316f, 19f, C_MUTED, Paint.Align.CENTER
        )
        label(
            "开始之后：凝视 1.2 秒确认 · 长按返回 · 摇头翻页",
            W / 2f, 346f, 19f, C_MUTED, Paint.Align.CENTER
        )
    }

    /**
     * V2.11：音量 / 亮度档位浮层。
     *
     * 设计第 415 行：「值的变化自己浮出来——视野中央大字档位 + 5 段条，1.2 秒淡出；
     * 不必低头进控制台确认，也不占用常驻画面。」
     *
     * 位置刻意放在画布中心偏上约 5°：卡片压住准星会让「我正在瞄什么」变得看不清，
     * 而偏上 5° 仍在舒适区中央，不用转头就能读到。
     *
     * 注意：它现在画在世界锁定的 UI 画布上，所以会跟着头动。
     * 设计要的是屏幕空间层，那属于 CHECKLIST B6（UI 三层拆分），还没做。
     */
    private fun drawAdjustFeedback(feedback: AdjustFeedback) {
        val remain = feedback.untilMs - SystemClock.elapsedRealtime()
        if (remain <= 0L) return
        // 最后 300ms 渐隐，避免「啪」一下消失。
        val alpha = ((remain / 300f).coerceIn(0f, 1f) * 255f).toInt().coerceIn(0, 255)

        val card = RectF(W / 2f - 190f, 96f, W / 2f + 190f, 220f)
        val layer = canvas.saveLayerAlpha(card, alpha)

        fill.color = 0xEE0E1B2C.toInt()
        canvas.drawRoundRect(card, 22f, 22f, fill)
        stroke.color = C_ACCENT
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(card, 22f, 22f, stroke)

        label(feedback.label, card.left + 28f, card.top + 44f, 30f, C_TEXT)
        label(feedback.detail, card.right - 28f, card.top + 44f, 30f, C_ACCENT, Paint.Align.RIGHT)

        // 段条：亮到第 level 段。5 档音量就是 5 段，3 档亮度就是 3 段。
        val barLeft = card.left + 28f
        val barRight = card.right - 28f
        val gap = 8f
        val segmentWidth = (barRight - barLeft - gap * (feedback.steps - 1)) / feedback.steps
        val barTop = card.top + 66f
        val barBottom = barTop + 24f
        for (i in 0 until feedback.steps) {
            val left = barLeft + i * (segmentWidth + gap)
            fill.color = if (i < feedback.level) C_ACCENT else C_BORDER
            canvas.drawRoundRect(
                RectF(left, barTop, left + segmentWidth, barBottom), 6f, 6f, fill
            )
        }

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
     * 1. 2×4 的格子排布让画布正中落在十字缝里，准星静止时压不到任何卡片——
     *    这是「进入 VR 后自动打开正对着的那个视频」的根治办法，不是靠加长静默期。
     * 2. 顶部那三个假 tab（点下去只说「V1 接入」）换成真能用的上一页 / 下一页，
     *    目标数不变，假功能变真功能（08.2 第 06 条同一思路）。
     * 3. 每格直接就是「播放这一部」的目标，不再需要先选中、再凝视中间的「打开」。
     */
    private fun drawLibrary(state: VrUiState) {
        panel(RectF(20f, 14f, 1004f, 498f))

        val pageCount = state.pageCount
        val page = state.page.coerceIn(0, pageCount - 1)
        val stack = state.currentStack

        // V2.18：用户要求把这个层级叫「集合」——「叠」不是人话，改掉。
        // 有集合时括号里补一句「共 M 个集合」，用户才知道自己现在在哪一层。
        // V2.18：选了某个柜子就把柜名写在最前面，用户一眼知道自己在看哪个设备。
        val root = state.cabinets.firstOrNull { it.sourceId == state.cabinet }?.label ?: "片库"
        val title = when {
            stack != null -> "集合 · ${stack.label} · ${stack.items.size} 部"
            state.showingStacks -> "$root · ${state.items.size} 部 · ${state.stacks.size} 个集合"
            else -> "$root · ${state.items.size} 部"
        }
        small.textSize = 30f
        label(ellipsize(title, 420f, small), 44f, 76f, 30f, C_TEXT)
        small.textSize = 18f
        if (pageCount > 1) {
            // 两侧箭头之间只有 116px，「第 x / y 页」会压到箭头框上，只留数字。
            label("${page + 1} / $pageCount", 636f, 74f, 22f, C_MUTED, Paint.Align.CENTER)
        }

        // V2.13：原来底部并排四个文字按钮（上一页 / 下一页 / 设置 / 退出）占了近 1/5 的画布。
        // 现在换成图标，且全部收进顶栏与底栏两条窄带，中间整块留给片子。
        // 图标本身画得小，但命中区仍然 ≥ 96×66px（约 5.8°×4.3°），没有牺牲好点。
        iconButton(
            "pagePrev", "上一页", RectF(482f, 28f, 578f, 94f),
            VrAction.PageFlip(-1), Glyph.CHEVRON_LEFT,
            dimmed = page <= 0
        )
        iconButton(
            "pageNext", "下一页", RectF(694f, 28f, 790f, 94f),
            VrAction.PageFlip(1), Glyph.CHEVRON_RIGHT,
            dimmed = page >= pageCount - 1
        )
        iconButton(
            "settingsLib", "设置", RectF(806f, 28f, 902f, 94f),
            VrAction.OpenSettings, Glyph.GEAR
        )
        iconButton(
            "exitLib", "退出", RectF(912f, 28f, 1008f, 94f),
            VrAction.Exit, Glyph.POWER
        )

        if (state.items.isEmpty()) {
            centerText("还没有片源 · 请回到戴镜前配置添加共享", 250f, 22f, C_MUTED)
        } else {
            val start = page * VrUiState.PAGE_SIZE
            if (state.showingStacks) {
                // V2.16：第一层是「一叠一叠」，不是 104 页片子。
                for (slot in 0 until VrUiState.PAGE_SIZE) {
                    val stack = state.stacks.getOrNull(start + slot) ?: break
                    val left = CELL_LEFT[slot % 4]
                    val top = CELL_TOP[slot / 4]
                    stackCard(stack, RectF(left, top, left + CELL_W, top + CELL_H))
                }
            } else {
                val items = state.visibleItems
                for (slot in 0 until VrUiState.PAGE_SIZE) {
                    val index = start + slot
                    val item = items.getOrNull(index) ?: break
                    // 2 行 × 4 列，正中那道十字缝留得比别处宽（横向 16px、纵向 16px）。
                    // 这只是在「已经上膛」之后多一道保险，不是防误开的主力——
                    // 配戴过程中手机一直在动，准星照样会扫到卡片上，主力是 V2.9 的触发键上膛。
                    val left = CELL_LEFT[slot % 4]
                    val top = CELL_TOP[slot / 4]
                    val resume = state.progress[mediaKeyOf(item)]
                        ?.takeIf { !it.finished && it.positionMs > 0L }
                    cell(
                        id = "cell$index",
                        item = item,
                        rect = RectF(left, top, left + CELL_W, top + CELL_H),
                        action = VrAction.OpenMedia(item.id),
                        badge = resume?.let { "续 ${formatTime(it.positionMs)}" },
                        current = item.id == state.currentMediaId
                    )
                }
            }
        }

        // ── 底栏（V2.16）────────────────────────────────────────────────
        // 播放中调出片库之后，必须有明摆着的路回到正在看的那一部；
        // 进了某个集合，也要有明摆着的路退回全部集合。两个都放在底栏，靠左排。
        var barRight = 24f
        if (state.currentMediaId != 0L) {
            button(
                "resumePlay", "返回播放",
                RectF(24f, 424f, 224f, 490f), VrAction.ResumePlayback,
                highlighted = true
            )
            barRight = 236f
        }
        if (state.activeStack != null) {
            button(
                "closeStack", "← 全部集合",
                RectF(barRight, 424f, barRight + 184f, 490f), VrAction.CloseStack
            )
            barRight += 196f
        }

        // V2.18：换片库靠右。只有两个以上真实片源时才出现——
        // 只有一个片源时这个按钮什么也换不了，摆着只会让人点一次白点。
        val showCabinets = state.cabinets.size > 2
        if (showCabinets) {
            button(
                "openCabinets", "换片库",
                RectF(1000f - 176f, 424f, 1000f, 490f), VrAction.OpenCabinets
            )
        }

        val hint = state.message ?: if (state.showingStacks) {
            "凝视一个集合即进入 · 摇头或左右箭头翻页"
        } else {
            "凝视一格即播放 · 摇头或左右箭头翻页"
        }
        val hintColor = if (state.message != null) C_ACCENT else C_MUTED
        if (barRight > 24f) {
            // 有按钮时提示靠右让位，避免压住按钮上的字。
            small.textSize = 18f
            val hintRight = if (showCabinets) 1000f - 176f - 16f else 1000f
            label(
                ellipsize(hint, hintRight - barRight - 16f, small), hintRight, 464f, 18f,
                hintColor, Paint.Align.RIGHT
            )
            small.textSize = 18f
        } else {
            centerText(hint, 470f, 20f, hintColor)
        }
    }

    /**
     * 一叠：一次拍摄或一个文件夹。
     *
     * 不画海报——叠的价值是「这一批是什么」，不是「其中某一帧长什么样」。
     * 左边一道亮边 + 名字 + 部数，一眼能看出它和单个片子的区别。
     */
    private fun stackCard(stack: MediaStack, rect: RectF) {
        targets += GazeTarget(
            "stack:${stack.key}", stack.label, RectF(rect), VrAction.OpenStack(stack.key)
        )
        fill.color = C_POSTER
        canvas.drawRoundRect(rect, 14f, 14f, fill)
        fill.color = C_ACCENT
        canvas.drawRoundRect(
            RectF(rect.left + 8f, rect.top + 12f, rect.left + 14f, rect.bottom - 12f),
            3f, 3f, fill
        )
        stroke.color = C_BORDER
        stroke.strokeWidth = 1.5f
        canvas.drawRoundRect(rect, 14f, 14f, stroke)

        strong.textSize = 28f
        val lines = wrapTwoLines(stack.label, rect.width() - 44f)
        val baseY = rect.centerY() - 4f - (lines.size - 1) * 16f
        lines.forEachIndexed { index, line ->
            strongLabel(
                line, rect.centerX() + 4f, baseY + index * 32f, 28f, C_TEXT, Paint.Align.CENTER
            )
        }
        label(
            stack.detail, rect.centerX() + 4f, baseY + lines.size * 32f + 6f, 20f,
            C_ACCENT, Paint.Align.CENTER
        )
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
        val card = RectF(140f, 92f, 884f, 420f)
        fill.color = 0xF00B1626.toInt()
        canvas.drawRoundRect(card, 26f, 26f, fill)
        stroke.color = C_ACCENT
        stroke.strokeWidth = 2.5f
        canvas.drawRoundRect(card, 26f, 26f, stroke)

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
        centerText("$where · 转过来之后，视频和界面一起跟着你", 200f, 21f, C_MUTED)

        // 两个按钮对称摆在画布正中两侧，中间留 32px 缝（496–528），
        // 与片库 2×4 格子同一条结构规则：**准星静止时压不到任何按钮**。
        // 缝要比片库的 16px 更宽一点——准星圆环半径 11px，16px 的缝会让环压到按钮边框上。
        // 否则卡片一出来，准星正好停在「挪到这里」上，1.2 秒后自己就确认了——
        // 那正是「没问就自己转了」。
        button(
            "postureOk", "挪到这里",
            RectF(168f, 236f, 496f, 350f), VrAction.ConfirmPosture,
            highlighted = true, sub = "0.8 秒转过去，只动这一次"
        )
        button(
            "postureNo", "不用了",
            RectF(528f, 236f, 856f, 350f), VrAction.DismissPosture,
            sub = "保持现在的画面"
        )

        // 候选自己会过期。给一条细条，让「它不会一直挡着」这件事看得见。
        val ttl = PostureTracker.CANDIDATE_TTL_MS.toFloat().coerceAtLeast(1f)
        val ratio = (state.postureRemainingMs / ttl).coerceIn(0f, 1f)
        fill.color = C_BORDER
        canvas.drawRoundRect(RectF(168f, 376f, 856f, 384f), 4f, 4f, fill)
        fill.color = C_ACCENT
        canvas.drawRoundRect(RectF(168f, 376f, 168f + 688f * ratio, 384f), 4f, 4f, fill)
        small.textSize = 17f
        label("按一下触发键也行 · 不按就走完收起", 856f, 408f, 17f, C_MUTED, Paint.Align.RIGHT)
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
        panel(RectF(20f, 14f, 1004f, 498f))

        label(ellipsize("换片库", 420f, small), 44f, 76f, 30f, C_TEXT)
        iconButton(
            "exitCabinets", "退出", RectF(912f, 28f, 1008f, 94f),
            VrAction.Exit, Glyph.POWER
        )

        val cabinets = state.cabinets.take(6)
        if (cabinets.isEmpty()) {
            centerText("还没有片源 · 请回到戴镜前配置添加共享", 250f, 22f, C_MUTED)
            return
        }

        val gap = 18f
        val left0 = 44f
        val cellW = (1000f - left0 - gap * 2f) / 3f
        for ((slot, cabinet) in cabinets.withIndex()) {
            val left = left0 + (slot % 3) * (cellW + gap)
            val top = if (slot < 3) 114f else 266f
            cabinetCard(
                cabinet,
                RectF(left, top, left + cellW, top + CELL_H),
                selected = cabinet.sourceId == state.cabinet
            )
        }

        // 底栏：左边回片库，右边说清这一层是干什么的。
        button(
            "closeCabinets", "← 回片库",
            RectF(24f, 424f, 208f, 490f), VrAction.CloseCabinets
        )
        small.textSize = 18f
        label(
            ellipsize("凝视一个柜子就切过去 · 每柜的片子不混在一起", 740f, small),
            1000f, 464f, 18f, C_MUTED, Paint.Align.RIGHT
        )
        small.textSize = 18f
    }

    /** 一个柜子：名字 + 部数 + 类型胶囊；当前柜子加一道青色边框。 */
    private fun cabinetCard(cabinet: Cabinet, rect: RectF, selected: Boolean) {
        targets += GazeTarget(
            "cabinet:${cabinet.sourceId}", cabinet.label, RectF(rect),
            VrAction.SwitchCabinet(cabinet.sourceId)
        )
        fill.color = C_POSTER
        canvas.drawRoundRect(rect, 14f, 14f, fill)
        fill.color = if (selected) C_ACCENT else C_MUTED
        canvas.drawRoundRect(
            RectF(rect.left + 8f, rect.top + 12f, rect.left + 14f, rect.bottom - 12f),
            3f, 3f, fill
        )
        stroke.color = if (selected) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (selected) 2.5f else 1.5f
        canvas.drawRoundRect(rect, 14f, 14f, stroke)

        strong.textSize = 28f
        val lines = wrapTwoLines(cabinet.label, rect.width() - 52f)
        val baseY = rect.centerY() - 6f - (lines.size - 1) * 16f
        lines.forEachIndexed { index, line ->
            strongLabel(
                line, rect.centerX() + 4f, baseY + index * 32f, 28f, C_TEXT, Paint.Align.CENTER
            )
        }
        val detail = if (cabinet.chip.isBlank()) "${cabinet.count} 部"
        else "${cabinet.chip} · ${cabinet.count} 部"
        label(
            detail, rect.centerX() + 4f, baseY + lines.size * 32f + 6f, 20f,
            if (selected) C_ACCENT else C_MUTED, Paint.Align.CENTER
        )
    }

    // ── 播放控制台（PRD 4.4：8 秒无操作淡出，控件位于视野下方） ────────
    private fun drawPlayer(state: VrUiState) {
        // 字幕独立于控制台淡出：戴镜时控制台消失，字幕仍需可读。
        if (state.settings.subtitlesEnabled) {
            SubtitleParser.cueAt(state.subtitles, state.positionMs)?.let { drawSubtitle(it) }
        }
        if (!state.consoleVisible) {
            // V2.14：这里原来常驻一行「低头或按触发键唤出控制台」。
            // 控制台能自动淡出了，这行字就成了新的遮挡物，而且是错的——
            // 「低头唤出」根本没实现，唤出只有触发键这一条路。整行删掉。
            return
        }
        panel(RectF(40f, 300f, 984f, 500f))
        small.textSize = 20f
        small.color = C_ACCENT
        canvas.drawText("自动识别 · " + state.format.label, W / 2f, 288f, small)
        small.color = C_MUTED

        val left = 80f
        val segmentW = 86.4f
        fill.color = C_BORDER
        canvas.drawRoundRect(RectF(left, 322f, left + segmentW * 10, 344f), 8f, 8f, fill)
        val played = if (state.durationMs > 0) {
            state.positionMs.toFloat() / state.durationMs
        } else 0f
        fill.color = C_ACCENT
        canvas.drawRoundRect(RectF(left, 322f, left + segmentW * 10 * played.coerceIn(0f, 1f), 344f), 8f, 8f, fill)
        // 10 个可凝视跳转区（PRD 4.4）
        for (i in 0 until 10) {
            val r = RectF(left + i * segmentW, 314f, left + (i + 1) * segmentW, 352f)
            targets += GazeTarget("seek$i", "跳到 ${i * 10}%", r, VrAction.SeekTo((i + 0.5f) / 10f))
        }

        val labels = listOf("上一部", "-60s", if (state.playing) "暂停" else "播放", "+60s", "下一部")
        val actions = listOf<VrAction>(
            VrAction.Previous, VrAction.SeekBy(-60), VrAction.TogglePlay,
            VrAction.SeekBy(60), VrAction.Next
        )
        labels.forEachIndexed { i, caption ->
            val cx = 150f + i * 180f
            button("transport$i", caption, RectF(cx - 70f, 368f, cx + 70f, 436f), actions[i], highlighted = i == 2)
        }

        // V2.8：设计文档第 394 行「任意界面一个动作回到片库」。
        // 之前播放页只有「退出」，想回片库只能长按触发键——戴镜后看不见键盘提示，
        // 等于没有出口。这里补一个明摆着的「片库」，语义就是已有的 VrAction.Back
        // （存断点 → 暂停 → 回片库，并落回正在看的这一部所在的页）。
        //
        // V2.19：加「正前方」。这是姿态闭环唯一的明路——躺好再戴镜的人，
        // 初始重定已经把正前方定成躺姿，自动检测永远不会触发，只能自己点。
        val quick = listOf(
            "片库" to VrAction.Back,
            "正前方" to VrAction.AlignForward,
            "格式" to VrAction.OpenFormat,
            // V2.11：亮度是 3 档，一次跳一档（原来是 +10% 的连续值）。
            "亮度" to VrAction.Adjust("brightness", 1),
            "音量" to VrAction.Adjust("volume", 1),
            "设置" to VrAction.OpenSettings,
            "退出" to VrAction.Exit
        )
        // 7 个按钮均分面板内部宽度（面板 40..984，左右各留 20），间隔 8px。
        // 6 个时是 153px 间距，加到 7 个必须重算，否则最后一个会越出面板右边界。
        val quickW = (904f - 6 * 8f) / 7f
        quick.forEachIndexed { i, (caption, action) ->
            val qx = 60f + i * (quickW + 8f)
            button("quick$i", caption, RectF(qx, 448f, qx + quickW, 490f), action)
        }
    }

    /** 字幕画在控制台上方，不与 300px 起的面板重叠；半透明底保证亮画面下可读。 */
    private fun drawSubtitle(value: String) {
        val lines = value.split('\n').take(2)
        small.textSize = 26f
        val widest = lines.maxOf { small.measureText(it) } + 56f
        val top = 196f
        val bottom = top + 20f + lines.size * 34f
        fill.color = 0xDD08111E.toInt()
        canvas.drawRoundRect(
            RectF(W / 2f - widest / 2f, top, W / 2f + widest / 2f, bottom), 12f, 12f, fill
        )
        text.textSize = 26f
        text.color = C_TEXT
        lines.forEachIndexed { i, line ->
            canvas.drawText(line, W / 2f, top + 34f + i * 34f, text)
        }
    }

    // ── 格式确认（V2.5：六项三列两行，2D 世界锁定银幕正式可选） ──────────
    private fun drawFormat(state: VrUiState) {
        panel(RectF(100f, 30f, 924f, 496f))
        heading("确认视频格式 · 自动识别已高亮", 72f)
        centerText(
            "2D 银幕会像真实银幕一样固定在世界里，转头能看到边缘",
            100f, 19f
        )
        VrFormat.QUICK_CHOICES.forEachIndexed { i, format ->
            val col = i % 3
            val row = i / 3
            // 248×128px ≈ 11.8°×6.1°，仍满足单目标 ≥6°×4°；
            // 六项 + 返回 = 7 个目标，守单屏 ≤9。
            val left = 120f + col * 268f
            val top = 120f + row * 148f
            val r = RectF(left, top, left + 248f, top + 128f)
            button(
                "fmt$i", format.label, r, VrAction.SetFormat(format),
                highlighted = sameFormat(format, state.suggestedFormat),
                sub = if (sameFormat(format, state.suggestedFormat)) "自动识别 · 建议" else "凝视或点击选择"
            )
        }
        button("fmtBack", "返回", RectF(380f, 424f, 644f, 478f), VrAction.CloseOverlay)
    }

    // ── 设置（PRD 4.4 / FR-12：全部可在戴镜状态完成，不出现键盘） ──────
    private fun drawSettings(state: VrUiState) {
        panel(RectF(100f, 20f, 924f, 500f))
        heading("设置", 62f)
        val s = state.settings
        // V2.11：亮度 / 音量显示档位，不再显示百分比——
        // 百分比会让人以为可以精确调到 43%，而实际只有 5 档 / 3 档。
        val volumeLevel = VrSettings.volumeStep(s.volume)
        val brightnessLevel = VrSettings.brightnessStep(s.brightness)
        val rows = listOf(
            Triple("亮度", VrSettings.BRIGHTNESS_LABELS[brightnessLevel], "brightness"),
            Triple("音量", "${volumeLevel + 1} / ${VrSettings.VOLUME_STEPS.size}", "volume"),
            Triple("瞳距 IPD", "${s.ipdMm} mm", "ipd"),
            Triple("凝视时长", "${s.gazeDurationMs} ms", "gaze")
        )
        rows.forEachIndexed { i, (label, value, key) ->
            val y = 84f + i * 48f
            centerText("$label   $value", y + 28f, 22f, C_TEXT)
            button("dec$i", "−", RectF(160f, y, 240f, y + 44f), VrAction.Adjust(key, -1))
            button("inc$i", "+", RectF(784f, y, 864f, y + 44f), VrAction.Adjust(key, 1))
        }
        // V2.28：删掉「头部手势」与「重置正前方」。
        // 前者一直误触发（点头自动确认格式、摇头自动翻页），后者是瞬跳 re-center，
        // 按钮又在面板下方，凝视它的那一刻朝向直接被写成了新的正前方。
        val toggles = listOf(
            Triple("自动连播", s.autoNext, "autoNext"),
            Triple("自动淡出", s.autoFade, "autoFade"),
            Triple("锁定视角", s.lockView, "lockView"),
            Triple("字幕", s.subtitlesEnabled, "subtitles"),
            Triple("虚拟光标", s.cursorEnabled, "cursor"),
            Triple("挪到这里", false, "align")
        )
        toggles.forEachIndexed { i, (label, enabled, key) ->
            val col = i % 3
            val row = i / 3
            val x = 110f + col * 270f
            val y = 286f + row * 48f
            val action: VrAction = when (key) {
                "align" -> VrAction.AlignForward
                else -> VrAction.ToggleOption(key)
            }
            val isAction = key == "align"
            button(
                "toggle$i", label + if (isAction) "" else if (enabled) " · 开" else " · 关",
                RectF(x, y, x + 250f, y + 46f), action,
                highlighted = enabled && !isAction
            )
        }
        button("clearMem", "清除格式记忆", RectF(140f, 438f, 430f, 490f), VrAction.ClearFormatMemory)
        button("setBack", "返回", RectF(594f, 438f, 884f, 490f), VrAction.CloseOverlay)
    }

    // ── 绘制辅助 ───────────────────────────────────────────────────────
    private fun panel(rect: RectF) {
        fill.color = 0xEE0B1626.toInt()
        canvas.drawRoundRect(rect, 24f, 24f, fill)
        stroke.color = C_BORDER
        stroke.strokeWidth = 2f
        canvas.drawRoundRect(rect, 24f, 24f, stroke)
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
        canvas.drawRoundRect(rect, 14f, 14f, fill)
        stroke.color = if (highlighted) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (highlighted) 3f else 2f
        canvas.drawRoundRect(rect, 14f, 14f, stroke)
        // V2.5：格式确认屏改三列后按钮只有 248px 宽，长标签按可用宽度等比缩字，
        // 绘制完立刻复位，避免共用 Paint 影响后续按钮与标题。
        text.textSize = 25f
        text.color = C_TEXT
        val labelLimit = rect.width() - 20f
        val labelWidth = text.measureText(label)
        if (labelWidth > labelLimit) {
            text.textSize = (25f * labelLimit / labelWidth).coerceAtLeast(17f)
        }
        // V2.7.4：缩到 17px 还放不下就截断，宁可少显示几个字也不能越过卡片边界。
        canvas.drawText(
            ellipsize(label, rect.width() - 24f, text),
            rect.centerX(), rect.centerY() + if (sub == null) 9f else -4f, text
        )
        text.textSize = 25f
        if (sub != null) {
            small.textSize = 18f
            small.color = if (highlighted) C_ACCENT else C_MUTED
            val subLimit = rect.width() - 20f
            val subWidth = small.measureText(sub)
            if (subWidth > subLimit) {
                small.textSize = (18f * subLimit / subWidth).coerceAtLeast(13f)
            }
            canvas.drawText(sub, rect.centerX(), rect.centerY() + 28f, small)
            small.textSize = 18f
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
    private fun cell(
        id: String,
        item: MediaItemEntity,
        rect: RectF,
        action: VrAction,
        badge: String?,
        current: Boolean
    ) {
        targets += GazeTarget(id, prettyName(item.title), RectF(rect), action)
        fill.color = C_POSTER
        canvas.drawRoundRect(rect, 14f, 14f, fill)

        val poster = item.posterPath.takeIf { it.isNotBlank() }?.let { posterLoader(it) }
        if (poster != null && poster.width > 0 && poster.height > 0) {
            canvas.save()
            val clip = Path()
            clip.addRoundRect(rect, 14f, 14f, Path.Direction.CW)
            canvas.clipPath(clip)
            val scale = max(rect.width() / poster.width, rect.height() / poster.height)
            val dw = poster.width * scale
            val dh = poster.height * scale
            val dst = RectF(
                rect.centerX() - dw / 2f, rect.centerY() - dh / 2f,
                rect.centerX() + dw / 2f, rect.centerY() + dh / 2f
            )
            canvas.drawBitmap(poster, Rect(0, 0, poster.width, poster.height), dst, bitmapPaint)
            canvas.restore()
            // V2.19：有真画面之后别把「什么时候拍的」丢掉。
            // 用户自己的片子认的是时刻，不是画面里的第 3 秒长什么样。
            // 兜底封面例外——它自己身上就写着日期和时刻，再压一条就是同一句话写两遍。
            if (!PosterRepository.isGeneratedCover(item.posterPath)) {
                drawPosterCaption(item, rect)
            }
        } else {
            drawIdentity(item, rect)
        }

        if (badge != null) {
            // 角标放右上角：日期在格子中央，左上角会和它挤在一起。
            // V2.16：格子收到 145 之后角标压到了日期行（离屏预览里一眼就看出来了），
            // 所以角标整体上移、日期行随之下移，两者纵向彻底分开。
            small.textSize = 17f
            val width = small.measureText(badge) + 18f
            fill.color = 0xE612283F.toInt()
            canvas.drawRoundRect(
                RectF(
                    rect.right - 10f - width, rect.top + 4f,
                    rect.right - 10f, rect.top + 28f
                ),
                12f, 12f, fill
            )
            label(badge, rect.right - 19f, rect.top + 23f, 17f, C_ACCENT, Paint.Align.RIGHT)
        }

        stroke.color = if (current) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (current) 3f else 1.5f
        canvas.drawRoundRect(rect, 14f, 14f, stroke)
    }

    /**
     * V2.19：真画面上的身份条。
     *
     * 一条从透明到黑的渐变压在底部，左边是时刻，右边是时长。
     * 不加这条的话，「有画面」反而比「没有画面」信息更少——用户在一堆
     * 都拍同一个房间的片段里，靠的正是时刻。
     */
    private fun drawPosterCaption(item: MediaItemEntity, rect: RectF) {
        val stamp = timestampOf(item.title)
        val when_ = stamp?.let { "${it.date} ${it.time}" } ?: prettyName(item.title)
        val length = item.durationMs.takeIf { it > 0L }?.let { formatTime(it) }

        val bandTop = rect.bottom - 42f
        scrimPaint.shader = LinearGradient(
            0f, bandTop, 0f, rect.bottom,
            0x00000000, 0xC4000000.toInt(), Shader.TileMode.CLAMP
        )
        canvas.save()
        val clip = Path()
        clip.addRoundRect(rect, 14f, 14f, Path.Direction.CW)
        canvas.clipPath(clip)
        canvas.drawRect(RectF(rect.left, bandTop, rect.right, rect.bottom), scrimPaint)
        canvas.restore()
        scrimPaint.shader = null

        small.textSize = 17f
        small.color = C_TEXT
        canvas.drawText(
            ellipsize(when_, rect.width() - 100f, small),
            rect.left + 12f, rect.bottom - 12f, small
        )
        if (length != null) {
            small.textSize = 16f
            small.color = C_ACCENT
            canvas.drawText(length, rect.right - 12f, rect.bottom - 12f, small)
        }
        small.color = C_MUTED
    }

    /** 没有海报时，格子里放「什么时候拍的」——这才是用户认得出这一部的东西。 */
    private fun drawIdentity(item: MediaItemEntity, rect: RectF) {
        val stamp = timestampOf(item.title)
        val meta = metaOf(item)
        if (stamp != null) {
            label(
                stamp.date, rect.centerX(), rect.top + 54f, 22f, C_MUTED, Paint.Align.CENTER
            )
            strongLabel(
                stamp.time, rect.centerX(), rect.top + 106f, 44f, C_TEXT, Paint.Align.CENTER
            )
            if (meta != null) {
                label(
                    meta, rect.centerX(), rect.top + 136f, 19f, C_MUTED, Paint.Align.CENTER
                )
            }
        } else {
            // 名字本身就是线索（GOPR0231、导出后的中文名），放大到能读清。
            val name = prettyName(item.title)
            strong.textSize = 30f
            val lines = wrapTwoLines(name, rect.width() - 36f)
            // V2.16：格子从 165 收到 145，原来两行名字 + 一行元信息会顶出下边界。
            val baseY = if (meta == null) rect.centerY() + 10f else rect.centerY() - 14f
            lines.forEachIndexed { index, line ->
                strongLabel(
                    line, rect.centerX(), baseY + index * 36f, 30f, C_TEXT, Paint.Align.CENTER
                )
            }
            if (meta != null) {
                label(
                    meta, rect.centerX(), baseY + lines.size * 36f + 2f, 19f,
                    C_MUTED, Paint.Align.CENTER
                )
            }
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

    private fun heading(value: String, y: Float) {
        text.textSize = 30f
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

    private fun centerText(value: String, y: Float, size: Float, color: Int = C_MUTED) {
        small.textSize = size
        small.color = color
        canvas.drawText(value, W / 2f, y, small)
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
    private enum class Glyph { CHEVRON_LEFT, CHEVRON_RIGHT, GEAR, POWER }

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

        val color = if (dimmed) 0x66EAF2FA else C_TEXT
        val cx = rect.centerX()
        val cy = rect.centerY()
        stroke.color = color
        stroke.strokeWidth = 4f
        stroke.strokeCap = Paint.Cap.ROUND
        when (glyph) {
            Glyph.CHEVRON_LEFT -> canvas.drawPath(
                Path().apply {
                    moveTo(cx + 8f, cy - 14f); lineTo(cx - 8f, cy); lineTo(cx + 8f, cy + 14f)
                },
                stroke
            )
            Glyph.CHEVRON_RIGHT -> canvas.drawPath(
                Path().apply {
                    moveTo(cx - 8f, cy - 14f); lineTo(cx + 8f, cy); lineTo(cx - 8f, cy + 14f)
                },
                stroke
            )
            Glyph.GEAR -> {
                // 齿轮：粗环 + 8 颗短而粗的齿。齿细长会看成太阳，和「亮度」撞车。
                canvas.drawCircle(cx, cy, 9f, stroke)
                stroke.strokeWidth = 7f
                for (i in 0 until 8) {
                    val angle = i * PI / 4.0
                    canvas.drawLine(
                        cx + (cos(angle) * 12f).toFloat(), cy + (sin(angle) * 12f).toFloat(),
                        cx + (cos(angle) * 17f).toFloat(), cy + (sin(angle) * 17f).toFloat(),
                        stroke
                    )
                }
                stroke.strokeWidth = 4f
            }
            Glyph.POWER -> {
                canvas.drawArc(
                    RectF(cx - 13f, cy - 13f, cx + 13f, cy + 13f), -58f, 296f, false, stroke
                )
                canvas.drawLine(cx, cy - 19f, cx, cy - 1f, stroke)
            }
        }
        stroke.strokeCap = Paint.Cap.BUTT
        stroke.color = C_BORDER
        stroke.strokeWidth = 2f
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
