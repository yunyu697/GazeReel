package com.gazereel.vr.render

import android.graphics.RectF

/**
 * UI 规范 v1 的**布局令牌**。19:0x 立，见 `UI_GRID.md`。
 *
 * 为什么要有这一层：在它之前，每个浮层各自写死自己的面板与格子坐标 ——
 * 格式页 824×466、设置页 724×488、换片库 984×484，卡片 336 / 190 / 684 三种，
 * 按钮 160 / 162 / 322 / 330 / 334 …。每一屏单看都说得过去，连起来看就是「拼起来的」：
 * 标题左缘一处 178 一处 44、说明一句居中一句右对齐、按钮一行三等分一行两等分。
 *
 * 现在所有浮层只允许用这里的格子：面板一个尺寸、内容区一条左右缘、四栏一条列距、
 * 组件只有三档高度。`tools/check_ui_grid.py` 会扫源码，浮层里再出现四个纯数字的
 * `RectF(...)` 或不在字号阶梯里的字号，直接失败。
 *
 * 画布 1024×512（纹理 ×2）。所有数值是**画布单位**，不是 dp。
 */
object UiGrid {

    // ── 画布与安全区 ────────────────────────────────────────────────
    const val CANVAS_W = 1024f
    const val CANVAS_H = 512f

    /** 任何内容都不越过这一圈。 */
    const val SAFE = 20f

    // ── 浮层面板：一个尺寸，永远居中 ────────────────────────────────
    const val PANEL_W = 824f
    const val PANEL_H = 488f
    const val PANEL_L = (CANVAS_W - PANEL_W) / 2f   // 100
    const val PANEL_T = (CANVAS_H - PANEL_H) / 2f   // 12
    const val PANEL_R = PANEL_L + PANEL_W           // 924
    const val PANEL_B = PANEL_T + PANEL_H           // 500

    // ── 内容区：左右各 20 内边距 ────────────────────────────────────
    const val PAD = 20f
    const val CONTENT_L = PANEL_L + PAD             // 120
    const val CONTENT_W = PANEL_W - PAD * 2f        // 784
    const val CONTENT_R = CONTENT_L + CONTENT_W     // 904

    // ── 四栏栅格 ────────────────────────────────────────────────────
    const val COL_W = 184f
    const val GUTTER = 16f
    const val COL_STEP = COL_W + GUTTER             // 200
    /** 列起点：120 / 320 / 520 / 720。 */
    fun colX(index: Int): Float = CONTENT_L + index * COL_STEP

    /** 占 [cols] 栏的宽度：184 / 384 / 584 / 784。 */
    fun spanW(cols: Int): Float = cols * COL_W + (cols - 1) * GUTTER

    // ── 垂直节奏 ────────────────────────────────────────────────────
    /** 单行卡片 / 按钮高度。 */
    const val H_ROW = 56f
    /** 格式格高度。 */
    const val H_FMT = 96f
    /** 大设置格 / 柜子卡高度。 */
    const val H_BIG = 110f
    /** 同一块里卡片之间的间距。 */
    const val ROW_GAP = 5f
    /** 分区块之间的间距。 */
    const val BLOCK_GAP = 20f

    /** 浮层标题基线。 */
    const val TITLE_Y = 50f
    /** 首行内容上沿。 */
    const val BODY_TOP = 64f
    /** 底部按钮行上沿；下沿 = 490，距面板底 10。 */
    const val BUTTON_Y = 434f
    const val BUTTON_B = BUTTON_Y + H_ROW           // 490
    /** 最后一行卡片的下沿上限（再往下就压按钮了）。 */
    const val BODY_BOTTOM_MAX = 425f

    // ── 常用矩形 ────────────────────────────────────────────────────
    fun panel(): RectF = RectF(PANEL_L, PANEL_T, PANEL_R, PANEL_B)

    /** 从第 [col] 栏起、占 [cols] 栏、上沿 [top]、高 [h]。 */
    fun cell(col: Int, cols: Int, top: Float, h: Float): RectF {
        val l = colX(col)
        return RectF(l, top, l + spanW(cols), top + h)
    }

    /** 横向居中、占 [cols] 栏（条状提示、居中按钮行用）。 */
    fun centered(cols: Int, top: Float, h: Float): RectF {
        val w = spanW(cols)
        val l = (CANVAS_W - w) / 2f
        return RectF(l, top, l + w, top + h)
    }

    /**
     * 一行等距按钮：从第 [col] 栏起，每个按钮占 [cols] 栏中的 [unit] 栏。
     * 间距固定 [GUTTER]，所以 [count] × unit 栏必须正好铺满 [cols]。
     */
    fun row(col: Int, cols: Int, top: Float, h: Float, count: Int, unit: Int): List<RectF> {
        val need = count * spanW(unit) + GUTTER * (count - 1)
        require(need <= spanW(cols) + 0.5f) { "按钮行 ${count}×${unit} 栏放不进 ${cols} 栏" }
        val out = ArrayList<RectF>(count)
        var x = colX(col)
        repeat(count) {
            out += RectF(x, top, x + spanW(unit), top + h)
            x += spanW(unit) + GUTTER
        }
        return out
    }

    /** 浮层底部按钮行：宽度按栏分配，整体右对齐内容右缘。 */
    fun footer(vararg units: Int): List<RectF> {
        var total = GUTTER * (units.size - 1)
        for (u in units) total += spanW(u)
        var x = CONTENT_R - total
        val out = ArrayList<RectF>(units.size)
        for (u in units) {
            out += RectF(x, BUTTON_Y, x + spanW(u), BUTTON_B)
            x += spanW(u) + GUTTER
        }
        return out
    }
}
