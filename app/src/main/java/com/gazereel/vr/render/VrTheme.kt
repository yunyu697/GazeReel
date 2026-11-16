package com.gazereel.vr.render

/**
 * V2.50 / 01 视觉换血：把配色与字号从「散落在 1864 行绘制代码里的字面量」收成一份 token。
 *
 * 所有色值的来源是 SourceVR（`xyz.skybox.player` 0.2.3.3）序列化资源里的 `Image.m_Color`
 * 与 `Text.m_Color` 直读，不是从截图上吸色。上一版从截图吸出来的强调色 `#E5C065`
 * 偏灰偏暗，真值是 `#FFD570`——同一张截图在不同显示器上吸三次会得到三个值，
 * 这也是为什么这一层必须是常量表、而不是每次重新估。
 *
 * 字号单位沿用 APK 的画布单位（1600×900 基准）。
 *
 * 换算依据（这一条决定了字号能不能照抄）：
 * - 我们的 UI 平面 2.4×1.2 米放在 2 米处 → 横向 2·atan(1.2/2) ≈ 61.9°，1024px / 61.9° ≈ 16.5 px/度；
 * - SourceVR 的界面铺满 Daydream 视野（约 96°），1600px / 96° ≈ 16.7 px/度。
 * 两边「每度多少像素」几乎相等，所以**同样的像素数在眼睛里是同样的大小**，
 * 字号可以 1:1 搬过来，不需要按画布宽度缩放。
 */
object VrTheme {

    // ── 底色 ────────────────────────────────────────────────────────
    /** 面板底。APK 里出现 26 次。 */
    val Panel = 0xFF19191A.toInt()
    /** 卡片 / 按钮底。出现 102 次，是最常用的一层。 */
    val Card = 0xFF28292B.toInt()
    /** 次级面板（嵌套一层）。 */
    val SubPanel = 0xFF232327.toInt()
    /** 面包屑胶囊。 */
    val Crumb = 0xFF2C2D2F.toInt()
    /** 未选中的进度轨道。 */
    val Track = 0xFF555555.toInt()

    // ── 强调 ────────────────────────────────────────────────────────
    /** 唯一强调色。出现 23 次。 */
    val Accent = 0xFFFFD570.toInt()
    /** 进度条填充，比强调色略深一档。 */
    val AccentFill = 0xFFFFC96B.toInt()
    /** 压在琥珀底上的字。 */
    val OnAccent = 0xFF333333.toInt()
    /** 缓冲段：同色 39% 不透明。凝影之前完全没有这个信息。 */
    val AccentCache = 0x63FFC96B

    // ── 文字 ────────────────────────────────────────────────────────
    val Ink = 0xFFFFFFFF.toInt()
    val Ink2 = 0xFFC9C9C9.toInt()
    val Ink3 = 0xFF8A8A8A.toInt()
    val Ink4 = 0xFF7D7D7D.toInt()
    /** 时间码专用。 */
    val Time = 0xFF777777.toInt()

    // ── 线 ──────────────────────────────────────────────────────────
    val Line = 0x12FFFFFF
    val Line2 = 0x1FFFFFFF

    // ── 语义色（只有两种，不做装饰）────────────────────────────────
    /** 「再稳一下」这类提示，不是报错。 */
    val Warn = 0xFFFFB74D.toInt()
    /** 失败 / 断开。 */
    val Danger = 0xFFF87171.toInt()

    // ── 字号阶梯（画布单位，1600 基准）──────────────────────────────
    /** 68：整屏唯一的大标题（如「换片库」）。 */
    const val HERO = 40f
    /** 40：区块标题。 */
    const val TITLE = 30f
    /** 30：列表主项 / 卡片名，出现最多的一档。 */
    const val ITEM = 26f
    /** 26：正文。 */
    const val BODY = 21f
    /** 21：次要说明。 */
    const val META = 17f
    /** 17：角标 / 时间码。 */
    const val TINY = 15f

    // ── 圆角 ────────────────────────────────────────────────────────
    /** 面板 11（1600 基准）→ 我们的画布按同密度取 11。 */
    const val R_PANEL = 12f
    const val R_CARD = 8f
    const val R_BUTTON = 8f
    const val R_PILL = 99f

    // ── 动效（毫秒，来自 AnimationClip / 代码常量）──────────────────
    const val MS_HOVER = 100
    const val MS_PANEL = 200
    const val MS_OPEN = 300
    const val MS_SCENE = 500
}
