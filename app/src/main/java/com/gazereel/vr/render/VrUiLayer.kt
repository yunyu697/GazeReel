package com.gazereel.vr.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import com.gazereel.vr.core.GazeTarget
import com.gazereel.vr.core.VrAction
import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.player.SubtitleParser
import com.gazereel.vr.ui.VrOverlay
import com.gazereel.vr.ui.VrScreen
import com.gazereel.vr.ui.VrSettings
import com.gazereel.vr.ui.VrUiState
import com.gazereel.vr.ui.AdjustFeedback
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

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
        private const val CELL_H = 165f
        private val CELL_LEFT = floatArrayOf(24f, 272f, 520f, 768f)
        private val CELL_TOP = floatArrayOf(116f, 297f)

        /** 20260712_143022 这类相机 / 手机命名。 */
        private val STAMP_COMPACT =
            Regex("(\\d{4})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})(\\d{2})")

        /** 2026-07-12 14.30.22 / 2026-07-12 14:30 这类导出后改名。 */
        private val STAMP_LOOSE =
            Regex("(\\d{4})-(\\d{2})-(\\d{2})[ _T](\\d{2})[:.](\\d{2})(?:[:.](\\d{2}))?")
    }

    /**
     * UI 平面的物理尺寸与俯角。
     *
     * V2.13：片库单独用一块更大的平面。原因不是「想大一点」，是分辨率账：
     * 1.8×0.9 米在 2 米外只占约 48.5°×25.4°，1024×512 的画布摊到这个角度上，
     * 17px 的标题落到屏幕里只剩不到 9 个像素——真机上就是「看不清、认不出是哪一部」。
     */
    data class PanelSpec(val width: Float, val height: Float, val centerY: Float)

    fun panelSpec(state: VrUiState): PanelSpec = when {
        state.screen == VrScreen.PLAYER && state.overlay == VrOverlay.NONE ->
            PanelSpec(1.8f, 0.9f, -0.46f)
        state.screen == VrScreen.LIBRARY && state.overlay == VrOverlay.NONE ->
            PanelSpec(2.4f, 1.2f, 0f)
        else -> PanelSpec(1.8f, 0.9f, 0f)
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

    fun currentTargets(): List<GazeTarget> = published

    /** 准星射线与 UI 平面的交点命中哪个目标；凝视与触发键共用此判定。 */
    fun targetAt(rotation: FloatArray, state: VrUiState): GazeTarget? {
        val point = gazePoint(rotation, state)
        return currentTargets().firstOrNull { it.rect.contains(point.x, point.y) }
    }

    /** 播放控制台置于视野下方约 13°，其余界面居中（PRD 3.5）。 */
    fun panelCenterY(state: VrUiState): Float = panelSpec(state).centerY

    /** 头部姿态 → 准星在 UI 画布上的落点。面板尺寸与俯角都会影响映射。 */
    fun gazePoint(rotation: FloatArray, state: VrUiState): PointF {
        val spec = panelSpec(state)
        val fx = -rotation[8]
        val fy = -rotation[9]
        val fz = -rotation[10]
        val yaw = atan2(fx.toDouble(), -fz.toDouble())
        val pitch = asin(fy.coerceIn(-1f, 1f).toDouble())
        val panelPitch = atan2(spec.centerY.toDouble(), 2.0)
        val halfWidth = atan2((spec.width / 2f).toDouble(), 2.0)
        val halfHeight = atan2((spec.height / 2f).toDouble(), 2.0)
        val x = W / 2.0 + yaw / halfWidth * (W / 2.0)
        val y = H / 2.0 - (pitch - panelPitch) / halfHeight * (H / 2.0)
        return PointF(x.toFloat(), y.toFloat())
    }

    fun render(
        state: VrUiState,
        gazeTargetId: String?,
        gazeProgress: Float
    ): Bitmap {
        bitmap.eraseColor(Color.TRANSPARENT)
        targets.clear()

        when (state.overlay) {
            VrOverlay.FORMAT -> drawFormat(state)
            VrOverlay.SETTINGS -> drawSettings(state)
            VrOverlay.NONE -> when (state.screen) {
                VrScreen.LIBRARY -> drawLibrary(state)
                VrScreen.PLAYER -> drawPlayer(state)
            }
        }

        // V2.9：还没上膛就盖一层提示。这里不注册任何目标，凝视此时也不生效。
        if (!state.gazeArmed) drawArmPrompt()

        // V2.11：档位变化浮层，压在提示之上——用户按了键就得立刻看见结果。
        state.feedback?.let { drawAdjustFeedback(it) }

        published = targets.toList()

        // 准星（真机中应作为独立屏幕中心层，此处按面板坐标近似）
        // V2.9：没上膛就不画准星。凝视此时不生效，画出来只会让人以为「准星坏了」。
        if (state.gazeArmed) {
            ring.strokeWidth = 3f
            ring.color = C_TEXT
            canvas.drawCircle(W / 2f, H / 2f, 12f, ring)
        }

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

        val items = state.items
        val pageCount = state.pageCount
        val page = state.page.coerceIn(0, pageCount - 1)

        label("片库 · ${items.size} 部", 44f, 76f, 30f, C_TEXT)
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

        if (items.isEmpty()) {
            centerText("还没有片源 · 请回到戴镜前配置添加共享", 250f, 22f, C_MUTED)
        } else {
            val start = page * VrUiState.PAGE_SIZE
            for (slot in 0 until VrUiState.PAGE_SIZE) {
                val index = start + slot
                val item = items.getOrNull(index) ?: break
                // 2 行 × 4 列，正中那道十字缝留得比别处宽（横向 16px、纵向 16px）。
                // 这只是在「已经上膛」之后多一道保险，不是防误开的主力——
                // 配戴过程中手机一直在动，准星照样会扫到卡片上，主力是 V2.9 的触发键上膛。
                val left = CELL_LEFT[slot % 4]
                val top = CELL_TOP[slot / 4]
                val resume = state.progress[item.id]
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

        val message = state.message
        if (message != null) {
            centerText(message, 490f, 20f, C_ACCENT)
        } else {
            centerText("凝视一格即播放 · 摇头或左右箭头翻页", 490f, 20f, C_MUTED)
        }
    }

    // ── 播放控制台（PRD 4.4：8 秒无操作淡出，控件位于视野下方） ────────
    private fun drawPlayer(state: VrUiState) {
        // 字幕独立于控制台淡出：戴镜时控制台消失，字幕仍需可读。
        if (state.settings.subtitlesEnabled) {
            SubtitleParser.cueAt(state.subtitles, state.positionMs)?.let { drawSubtitle(it) }
        }
        if (!state.consoleVisible) {
            centerText("低头或按触发键唤出控制台", 470f, 20f, C_MUTED)
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
        val quick = listOf(
            "片库" to VrAction.Back,
            "格式" to VrAction.OpenFormat,
            // V2.11：亮度是 3 档，一次跳一档（原来是 +10% 的连续值）。
            "亮度" to VrAction.Adjust("brightness", 1),
            "音量" to VrAction.Adjust("volume", 1),
            "设置" to VrAction.OpenSettings,
            "退出" to VrAction.Exit
        )
        quick.forEachIndexed { i, (caption, action) ->
            val qx = 60f + i * 153f
            button("quick$i", caption, RectF(qx, 448f, qx + 140f, 490f), action)
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
            val y = 88f + i * 54f
            centerText("$label   $value", y + 30f, 22f, C_TEXT)
            button("dec$i", "−", RectF(160f, y, 240f, y + 44f), VrAction.Adjust(key, -1))
            button("inc$i", "+", RectF(784f, y, 864f, y + 44f), VrAction.Adjust(key, 1))
        }
        // 两行三列，5 个开关；锁定视角与字幕是阶段二新增。
        val toggles = listOf(
            Triple("自动连播", s.autoNext, "autoNext"),
            Triple("自动淡出", s.autoFade, "autoFade"),
            Triple("头部手势", s.gesturesEnabled, "gestures"),
            Triple("锁定视角", s.lockView, "lockView"),
            Triple("字幕", s.subtitlesEnabled, "subtitles"),
            Triple("重置正前方", false, "recenter")
        )
        toggles.forEachIndexed { i, (label, enabled, key) ->
            val col = i % 3
            val row = i / 3
            val x = 110f + col * 270f
            val y = 306f + row * 54f
            val action: VrAction = if (key == "recenter") {
                VrAction.ResetForward
            } else {
                VrAction.ToggleOption(key)
            }
            button(
                "toggle$i", label + if (key == "recenter") "" else if (enabled) " · 开" else " · 关",
                RectF(x, y, x + 250f, y + 46f), action,
                highlighted = enabled && key != "recenter"
            )
        }
        button("clearMem", "清除格式记忆", RectF(140f, 430f, 430f, 482f), VrAction.ClearFormatMemory)
        button("setBack", "返回", RectF(594f, 430f, 884f, 482f), VrAction.CloseOverlay)
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
        } else {
            drawIdentity(item, rect)
        }

        if (badge != null) {
            // 角标放右上角：日期在格子中央，左上角会和它挤在一起。
            small.textSize = 17f
            val width = small.measureText(badge) + 18f
            fill.color = 0xE612283F.toInt()
            canvas.drawRoundRect(
                RectF(
                    rect.right - 10f - width, rect.top + 10f,
                    rect.right - 10f, rect.top + 36f
                ),
                13f, 13f, fill
            )
            label(badge, rect.right - 19f, rect.top + 29f, 17f, C_ACCENT, Paint.Align.RIGHT)
        }

        stroke.color = if (current) C_ACCENT else C_BORDER
        stroke.strokeWidth = if (current) 3f else 1.5f
        canvas.drawRoundRect(rect, 14f, 14f, stroke)
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
                stamp.time, rect.centerX(), rect.top + 112f, 44f, C_TEXT, Paint.Align.CENTER
            )
            if (meta != null) {
                label(
                    meta, rect.centerX(), rect.top + 148f, 19f, C_MUTED, Paint.Align.CENTER
                )
            }
        } else {
            // 名字本身就是线索（GOPR0231、导出后的中文名），放大到能读清。
            val name = prettyName(item.title)
            strong.textSize = 30f
            val lines = wrapTwoLines(name, rect.width() - 36f)
            val baseY = if (meta == null) rect.centerY() + 10f else rect.centerY() - 6f
            lines.forEachIndexed { index, line ->
                strongLabel(
                    line, rect.centerX(), baseY + index * 36f, 30f, C_TEXT, Paint.Align.CENTER
                )
            }
            if (meta != null) {
                label(
                    meta, rect.centerX(), baseY + lines.size * 36f + 6f, 19f,
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
     * 认不出的名字才退回「下划线换空格」。
     */
    private fun prettyName(raw: String): String {
        val base = raw.substringBeforeLast('.').ifBlank { raw }
        val stamp = Regex("(\\d{4})(\\d{2})(\\d{2})[_-](\\d{2})(\\d{2})").find(base)
        if (stamp != null) {
            val (y, mo, d, h, mi) = stamp.destructured
            return "$y-$mo-$d $h:$mi"
        }
        return base.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
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
