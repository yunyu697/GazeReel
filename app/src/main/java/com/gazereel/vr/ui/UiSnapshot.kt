package com.gazereel.vr.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.graphics.Typeface
import com.gazereel.vr.core.FieldOfView
import com.gazereel.vr.core.Projection
import com.gazereel.vr.core.Refusal
import com.gazereel.vr.core.StereoLayout
import com.gazereel.vr.core.VrFormat
import com.gazereel.vr.data.RunLog
import com.gazereel.vr.data.db.MediaItemEntity
import com.gazereel.vr.data.db.PlayProgressEntity
import com.gazereel.vr.data.db.mediaKeyOf
import com.gazereel.vr.render.VrTheme
import com.gazereel.vr.render.VrUiLayer
import java.io.File

/**
 * V2.50 / 01：把 VR 界面的画布**离线**渲染出来，不用戴眼镜也能看。
 *
 * 为什么需要这个：`VrUiLayer` 画的是 1024×512 的 Canvas，只有进 VR 才看得到。
 * 于是过去每一轮改界面都是「改完编译、装机、戴镜、发现不对、再猜」，
 * 一轮十几分钟，而且猜错的方向会被下一次改动继承下去。
 *
 * 这个类用**真实的绘制代码**（同一个 `VrUiLayer`、同一套坐标）渲染七个屏，
 * 拼成一张长图，在手机上直接看。它不改任何界面逻辑，也不进渲染主循环。
 *
 * 数据是编的（`VID_20260712_143022` 这类真实命名），因为要看的是**版式**，
 * 不是某一部片子。真机上唯一不同的地方是有海报时卡片会被海报铺满。
 */
object UiSnapshot {

    private const val SOURCE_ID = 1L

    /** 九个屏的标题，与 [build] 的返回顺序一致。 */
    private val TITLES = listOf(
        "片库 · 三栏",
        "播放中 · 控制台",
        // alpha43：控制台淡出后的头控唤醒区。加这一屏是为了让「唤醒区长什么样、
        // 在什么位置」也能离线看到 —— 它只在播放页淡出后才出现，真机上要等 8 秒。
        "播放中 · 控制台已淡出（看这里唤出）",
        "设置 · 第 1 页",
        // 18:2x：设置浮层分成两页，两项新参数在第 2 页。快照必须两页都有，
        // 否则「银幕宽度 / 画面缩放」只能戴镜才看得见。
        "设置 · 第 2 页（画面调节）",
        // 19:2x：全画幅把第 2 页的画面圆改成「不计」、说明句也换掉 ——
        // 这两种状态都得能离线看到，否则只能戴镜才验得出文案有没有打架。
        "设置 · 第 2 页 · 全画幅",
        "换片库",
        "格式",
        // 19:2x：拒绝卡（未缝合双鱼眼 360）。这一屏最该离线看：它是「看不了」
        // 的唯一解释，真机上又只在这类片源上出现，手头没有片子就永远验不到。
        "播放中 · 未缝合双鱼眼（拒绝卡）"
    )

    /**
     * 渲染七个屏并纵向拼接。
     *
     * 每个屏之间留 28px 的深色缝，上方标一行标题——这样一张图就能看全，
     * 用户截一次屏就够，不用来回翻。
     */
    fun build(context: Context): Bitmap {
        val layer = VrUiLayer()
        val states = sampleStates()
        val gap = 28f
        val label = 34f
        val w = VrUiLayer.W
        val cellH = VrUiLayer.H + label
        val totalH = (states.size * cellH + (states.size - 1) * gap).toInt()

        val out = Bitmap.createBitmap(w, totalH, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFF0B0C0E.toInt())

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = VrTheme.Accent
            textSize = 22f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val bg = Paint().apply { color = 0xFF111214.toInt() }

        states.forEachIndexed { i, (title, state, targetId) ->
            val top = i * (cellH + gap)
            c.drawText(title, 16f, top + 24f, p)
            c.drawRect(RectF(0f, top + label - 4f, w.toFloat(), top + cellH), bg)

            val bmp = runCatching {
                layer.render(
                    state = state,
                    gazeTargetId = targetId,
                    gazeProgress = 0.62f,
                    cursor = PointF(w * 0.42f, VrUiLayer.H * 0.47f)
                )
            }.getOrNull()

            if (bmp != null) {
                c.drawBitmap(bmp, 0f, top + label, null)
            } else {
                p.color = VrTheme.Danger
                c.drawText("渲染失败（见 RunLog）", 16f, top + label + 40f, p)
                p.color = VrTheme.Accent
            }
            // 画布边缘描一条细线，方便看出「哪些地方是透明的」。
            p.color = VrTheme.Line2
            c.drawRect(
                RectF(0f, top + label, w - 1f, top + label + VrUiLayer.H - 1f),
                Paint().apply { style = Paint.Style.STROKE; strokeWidth = 1f; color = VrTheme.Line2 }
            )
            p.color = VrTheme.Accent
        }
        return out
    }

    /** 把快照写进应用私有目录，返回文件路径；失败返回 null。 */
    fun save(context: Context, bitmap: Bitmap): String? = runCatching {
        val dir = File(context.filesDir, "ui-snap").apply { mkdirs() }
        val file = File(dir, "vr-ui.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        RunLog.append(context, "ui", "快照已写出 ${file.absolutePath} ${bitmap.width}x${bitmap.height}")
        file.absolutePath
    }.getOrNull()

    // ── 样例数据 ────────────────────────────────────────────────────

    private fun item(
        id: Long,
        title: String,
        durationMs: Long,
        w: Int = 1920,
        h: Int = 1080,
        /** 20:2x：统一存完整 mime，别再写 "h264" 这种 4CC 名（判定要的是 mime）。 */
        codec: String = "video/avc"
    ) = MediaItemEntity(
        id = id,
        sourceId = SOURCE_ID,
        path = "/storage/emulated/0/DCIM/Camera/$title.mp4",
        title = title,
        durationMs = durationMs,
        width = w,
        height = h,
        codec = codec,
        detectedFormat = "PLANE_2D/MONO",
        sizeBytes = 48L * 1024 * 1024,
        mtime = 1_752_000_000_000L
    )

    private fun sampleStates(): List<Triple<String, VrUiState, String?>> {
        val items = listOf(
            item(1, "VID_20260712_143022", 134_000L),
            item(2, "VID_20260712_144011", 48_000L),
            // 20:2x：真机 5400×2700 VP9@59.94，VP9 硬解上限只有 4096 → 必报 4001。
            // 放在第一页第 3 格，让「本机解不了」角标在片库快照里能被看到。
            item(3, "AJVR-215-CD1", 302_000L, 5400, 2700, "video/x-vnd.on2.vp9"),
            item(4, "VID_20260712_151244", 96_000L),
            item(5, "VID_20260712_152001", 22_000L),
            item(6, "VID_20260712_153310", 227_000L),
            item(7, "VID_20260712_154002", 9_000L),
            item(8, "VID_20260712_154518", 63_000L),
            item(9, "GOPR0231", 302_000L, 3840, 2160),
            item(10, "GOPR0232", 88_000L, 3840, 2160),
            item(11, "VID_20260711_181500", 227_000L),
            item(12, "VID_20260711_180233", 48_000L)
        )

        val cabinets = listOf(
            Cabinet(SOURCE_ID, "本机", 1284, "1 284"),
            Cabinet(2L, "NAS-客厅", 736, "736"),
            Cabinet(3L, "NAS-书房", 92, "92"),
            Cabinet(0L, "全部片源", 2112, "2 112")
        )

        // 三条断点：卡片右下角会出现续播进度线。
        val progress = mapOf(
            mediaKeyOf(items[0]) to PlayProgressEntity(mediaKeyOf(items[0]), 72_000L, 0L, false),
            mediaKeyOf(items[2]) to PlayProgressEntity(mediaKeyOf(items[2]), 41_000L, 0L, false),
            mediaKeyOf(items[5]) to PlayProgressEntity(mediaKeyOf(items[5]), 130_000L, 0L, false)
        )

        val base = VrUiState(
            items = items,
            // 20:2x：把那部 5.4K VP9 标成「本机解不了」，快照才能覆盖角标绘制。
            unsupportedKeys = setOf(mediaKeyOf(items[2])),
            cabinets = cabinets,
            cabinet = SOURCE_ID,
            progress = progress,
            settings = VrSettings(),
            gazeArmed = true,
            currentMediaId = items[0].id,
            currentTitle = items[0].title,
            durationMs = items[0].durationMs,
            positionMs = 72_000L,
            playing = true,
            // 19:2x：键形看 wantPlay；缓冲段也要有值，否则快照里看不到新增的那一段。
            wantPlay = true,
            bufferedMs = 96_000L,
            consoleVisible = true
        )

        return listOf(
            Triple(
                TITLES[0],
                base.copy(screen = VrScreen.LIBRARY, overlay = VrOverlay.NONE),
                "cell0"
            ),
            Triple(
                TITLES[1],
                base.copy(screen = VrScreen.PLAYER, overlay = VrOverlay.NONE),
                null
            ),
            // alpha4 / 17:5x：控制台已淡出。唤醒热区现在**画布外、零像素**，
            // 这一格就该是一张干净的画面（连悬停态也没有可看的东西）。
            Triple(
                TITLES[2],
                base.copy(
                    screen = VrScreen.PLAYER,
                    overlay = VrOverlay.NONE,
                    consoleVisible = false
                ),
                null
            ),
            Triple(
                TITLES[3],
                base.copy(screen = VrScreen.PLAYER, overlay = VrOverlay.SETTINGS),
                null
            ),
            // 18:2x：第 2 页。用默认值 100%，快照里看到的就是用户首次打开的样子。
            Triple(
                TITLES[4],
                base.copy(
                    screen = VrScreen.PLAYER,
                    overlay = VrOverlay.SETTINGS,
                    settingsPage = 1
                ),
                null
            ),
            // 19:2x：全画幅下的第 2 页（画面圆显示「不计」，说明句换成半对角线那句）。
            Triple(
                TITLES[5],
                base.copy(
                    screen = VrScreen.PLAYER,
                    overlay = VrOverlay.SETTINGS,
                    settingsPage = 1,
                    settings = VrSettings(fisheyeFullFrame = true),
                    format = VrFormat(Projection.FISHEYE, StereoLayout.SBS, FieldOfView.FOV_FISHEYE)
                ),
                null
            ),
            Triple(
                TITLES[6],
                base.copy(screen = VrScreen.LIBRARY, overlay = VrOverlay.NONE, cabinetsOpen = true),
                null
            ),
            Triple(
                TITLES[7],
                base.copy(screen = VrScreen.PLAYER, overlay = VrOverlay.FORMAT),
                null
            ),
            // 19:2x：拒绝卡。格式同时降级成 2D 银幕 —— 快照里看到的就是用户打开
            // 一部 `.insv` 时的真实样子：画面原样铺在幕上，中央一张说明卡。
            Triple(
                TITLES[8],
                base.copy(
                    screen = VrScreen.PLAYER,
                    overlay = VrOverlay.NONE,
                    format = VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT),
                    suggestedFormat = VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT),
                    currentTitle = "VID_20260712_160512",
                    refusal = Refusal.DUAL_FISHEYE_360
                ),
                "refusalOk"
            )
        )
    }
}
