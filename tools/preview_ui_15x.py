"""15:0x 控制台 / 设置界面重排 · 按 VrUiLayer 的真实坐标出图。

用途：改版式时先在本地看到结果，再决定要不要装机。坐标、字号、圆角、
颜色都一比一抄自 VrUiLayer / VrTheme 的当前值，画布同为 1024×512。

换算口径（与代码注释一致）：
- 面板 2.4×1.2 米放在 2 米处，115% 缩放 → 2.76 米宽
- 横向全视场 2·atan(1.38/2) ≈ 69.2°，1024px / 69.2° ≈ 14.8 px/度
- 竖向全视场 2·atan(0.69/2) ≈ 38.2°，512px / 38.2° ≈ 13.4 px/度

跑法：python tools/preview_ui_15x.py
产物：output/design/ui-readability-preview.png
"""
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

S = 2  # 2 倍超采样，与 VrUiLayer.TEX_SCALE 一致
W, H = 1024, 512

# ── VrTheme 真值 ──────────────────────────────────────────────
PANEL = (25, 25, 26)
CARD = (40, 41, 43)
BORDER = (58, 58, 62)
INK = (255, 255, 255)
INK2 = (201, 201, 201)
INK4 = (125, 125, 125)
ACCENT = (255, 213, 112)
TIME = (119, 119, 119)
TRACK = (58, 58, 64)
SCREEN = (18, 18, 22)

FONT_PATHS = [
    "C:/Windows/Fonts/msyh.ttc",
    "C:/Windows/Fonts/msyhbd.ttc",
    "C:/Windows/Fonts/simhei.ttf",
]


def font(size: float, bold: bool = False):
    px = int(round(size * S))
    for p in (FONT_PATHS[1], FONT_PATHS[0]) if bold else FONT_PATHS:
        if Path(p).exists():
            return ImageFont.truetype(p, px)
    return ImageFont.load_default()


def canvas():
    im = Image.new("RGB", (W * S, H * S), SCREEN)
    return im, ImageDraw.Draw(im)


def rrect(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle(
        [box[0] * S, box[1] * S, box[2] * S, box[3] * S],
        radius=r * S, fill=fill, outline=outline, width=int(width * S)
    )


def text(d, s, x, y, size, color, align="left", bold=False):
    """锚点用 baseline（ls/ms/rs），与 Android `Canvas.drawText` 的 y 语义一致。

    用 lm/mm/rm（垂直居中）会让整块字高约 0.25 字高，预览就比真机「飘」，
    判断行距时会误判成「还有余量」。
    """
    f = font(size, bold)
    x *= S
    y *= S
    if align == "center":
        d.text((x, y), s, font=f, fill=color, anchor="ms")
    elif align == "right":
        d.text((x, y), s, font=f, fill=color, anchor="rs")
    else:
        d.text((x, y), s, font=f, fill=color, anchor="ls")


def glyph(d, kind, cx, cy, size, color, lw=2.4):
    """简化图标：只画轮廓，够判断大小与间距。"""
    r = size / 2
    cx *= S
    cy *= S
    r *= S
    w = max(1, int(lw * S))
    if kind == "play":
        d.polygon([(cx - r * .55, cy - r), (cx - r * .55, cy + r), (cx + r * .9, cy)], fill=color)
    elif kind == "pause":
        d.rectangle([cx - r * .7, cy - r, cx - r * .15, cy + r], fill=color)
        d.rectangle([cx + r * .15, cy - r, cx + r * .7, cy + r], fill=color)
    elif kind == "prev":
        d.rectangle([cx - r * .8, cy - r, cx - r * .45, cy + r], fill=color)
        d.polygon([(cx + r * .75, cy - r), (cx + r * .75, cy + r), (cx - r * .3, cy)], fill=color)
    elif kind == "next":
        d.rectangle([cx + r * .45, cy - r, cx + r * .8, cy + r], fill=color)
        d.polygon([(cx - r * .75, cy - r), (cx - r * .75, cy + r), (cx + r * .3, cy)], fill=color)
    elif kind == "back":
        d.polygon([(cx + r * .45, cy - r * .9), (cx + r * .45, cy + r * .9), (cx - r * .65, cy)], fill=color)
        d.polygon([(cx - r * .1, cy - r * .9), (cx - r * .1, cy + r * .9), (cx - r * 1.15, cy)], fill=color)
    elif kind == "fwd":
        d.polygon([(cx - r * .45, cy - r * .9), (cx - r * .45, cy + r * .9), (cx + r * .65, cy)], fill=color)
        d.polygon([(cx + r * .1, cy - r * .9), (cx + r * .1, cy + r * .9), (cx + r * 1.15, cy)], fill=color)
    elif kind == "grid":
        for i in range(2):
            for j in range(2):
                d.rectangle([
                    cx - r + j * (r + 2), cy - r + i * (r + 2),
                    cx - 2 + j * (r + 2), cy - 2 + i * (r + 2)
                ], outline=color, width=w)
    elif kind == "clock":
        d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=color, width=w)
        d.line([cx, cy, cx, cy - r * .55], fill=color, width=w)
        d.line([cx, cy, cx + r * .45, cy], fill=color, width=w)
    elif kind == "frame":
        d.rectangle([cx - r, cy - r * .75, cx + r, cy + r * .75], outline=color, width=w)
        d.line([cx - r, cy, cx + r, cy], fill=color, width=w)
    elif kind == "sliders":
        for i, fy in enumerate((-0.45, 0.1, 0.6)):
            d.line([cx - r, cy + r * fy, cx + r, cy + r * fy], fill=color, width=w)
            d.ellipse([cx + r * (i - 1) * .6 - 3, cy + r * fy - 3,
                       cx + r * (i - 1) * .6 + 3, cy + r * fy + 3], fill=color)
    elif kind == "power":
        d.arc([cx - r, cy - r, cx + r, cy + r], 300, 240, fill=color, width=w)
        d.line([cx, cy - r * 1.1, cx, cy - r * .1], fill=color, width=w)


# ── 新控制台（15:0x）─────────────────────────────────────────
def draw_console_new():
    im, d = canvas()
    x0, y0, x1, y1 = 132, 240, 892, 508
    rrect(d, (x0, y0, x1, y1), 22, fill=(16, 16, 19), outline=BORDER, width=1.5)

    text(d, "180° 半球 · SBS 左右 · 快退/快进 15 秒", x0 + 32, 280, 19, INK4)

    barL, barR, barY = x0 + 32, x1 - 32, 340
    text(d, "12:34", barL, 312, 20, INK2)
    text(d, "45:00", barR, 312, 20, INK4, align="right")

    rrect(d, (barL, barY - 4, barR, barY + 4), 4, fill=TRACK)
    played = 0.28
    rrect(d, (barL, barY - 4, barL + (barR - barL) * played, barY + 4), 4, fill=ACCENT)
    d.ellipse([(barL + (barR - barL) * played - 8) * S, (barY - 8) * S,
               (barL + (barR - barL) * played + 8) * S, (barY + 8) * S], fill=INK)

    ty = 400
    for gx, kind in ((248, "prev"), (380, "back"), (644, "fwd"), (776, "next")):
        glyph(d, kind, gx, ty, 32, INK, 3.2)
    d.ellipse([(512 - 34) * S, (ty - 34) * S, (512 + 34) * S, (ty + 34) * S], fill=ACCENT)
    glyph(d, "pause", 512, ty, 22, (16, 16, 19), 3.4)

    for gx, kind, name in (
        (248, "grid", "片库"), (380, "clock", "正前方"), (512, "frame", "格式"),
        (644, "sliders", "设置"), (776, "power", "退出")
    ):
        glyph(d, kind, gx, 468, 21, INK, 2.4)
        text(d, name, gx, 498, 18, INK2, align="center")

    # 命中区描边（仅预览用）：三层顺序 进度 330–350 / 运输 360–440 / 工具 450–508
    for cx in (248, 380, 512, 644, 776):
        d.rectangle([(cx - 40) * S, (ty - 40) * S, (cx + 40) * S, (ty + 40) * S],
                    outline=(80, 200, 120), width=1)
    for cx in (248, 380, 512, 644, 776):
        d.rectangle([(cx - 45) * S, 450 * S, (cx + 45) * S, 508 * S],
                    outline=(80, 200, 120), width=1)
    d.rectangle([barL * S, (barY - 10) * S, barR * S, (barY + 10) * S],
                outline=(80, 200, 120), width=1)
    return im


# ── 新设置界面（15:0x）───────────────────────────────────────
def draw_settings_new():
    im, d = canvas()
    rrect(d, (150, 12, 874, 500), 12, fill=PANEL, outline=BORDER, width=1.5)
    text(d, "设置", 178, 50, 26, INK, bold=True)
    text(d, "凝视一格即可调整", 846, 50, 18, INK4, align="right")

    cards = [
        ("亮度", "中", None), ("音量", "3 / 5", None),
        ("重定正前方", "看哪儿定哪儿", None), ("锁定视角", None, False),
        ("自动连播", None, True), ("自动淡出", None, True),
        ("字幕", None, False), ("虚拟光标", None, True),
        ("瞳距 IPD", "31.0 mm", None), ("凝视时长", "900 ms", None),
    ]
    for i, (title, value, on) in enumerate(cards):
        col, row = i % 2, i // 2
        left = 170 + col * 348
        top = 64 + row * 73
        box = (left, top, left + 336, top + 68)
        rrect(d, box, 12, fill=CARD)
        text(d, title, left + 20, top + 34, 21, INK)
        if on is not None:
            pw, ph = 58, 26
            pl = box[2] - 20 - pw
            rrect(d, (pl, top + 34 - ph / 2, pl + pw, top + 34 + ph / 2), ph / 2,
                  fill=ACCENT if on else (58, 58, 64))
            cx = pl + pw - 13 if on else pl + 13
            d.ellipse([(cx - 9) * S, (top + 34 - 9) * S, (cx + 9) * S, (top + 34 + 9) * S],
                      fill=(16, 16, 19) if on else (158, 158, 166))
        elif value:
            text(d, value, box[2] - 20, top + 34, 21, INK2, align="right")

    for bx, label, hi in (
        ((170, 434, 330, 490), "返回", False),
        ((350, 434, 672, 490), "瞳距 · 边看边调", True),
        ((692, 434, 854, 490), "清除格式记忆", False),
    ):
        rrect(d, bx, 8, fill=CARD if hi else PANEL,
              outline=ACCENT if hi else BORDER, width=3 if hi else 2)
        text(d, label, (bx[0] + bx[2]) / 2, (bx[1] + bx[3]) / 2, 24,
             INK, align="center", bold=True)
    return im


# ── 旧控制台（对照，1.8×0.9 面板 → 21.1 px/度）───────────────
def draw_console_old():
    im, d = canvas()
    x0, y0, x1, y1 = 60, 366, 964, 500
    rrect(d, (x0, y0, x1, y1), 26, fill=(16, 16, 19), outline=BORDER, width=1.5)
    text(d, "180° 半球 · SBS 左右", 26, 30, 16, INK)
    text(d, "自动识别 · 点「格式」改 · 快退/快进 15 秒", 26, 52, 15, INK4)

    barL, barR, barY = x0 + 26, x1 - 26, y0 + 44
    text(d, "12:34", barL, y0 + 22, 15, INK2)
    text(d, "45:00", barR, y0 + 22, 15, INK4, align="right")
    rrect(d, (barL, barY - 2, barR, barY + 2), 2, fill=TRACK)
    rrect(d, (barL, barY - 2, barL + (barR - barL) * 0.28, barY + 2), 2, fill=ACCENT)
    d.ellipse([(barL + (barR - barL) * 0.28 - 6) * S, (barY - 6) * S,
               (barL + (barR - barL) * 0.28 + 6) * S, (barY + 6) * S], fill=INK)

    ty = 462
    for gx, kind in ((352, "prev"), (432, "back"), (592, "fwd"), (672, "next")):
        glyph(d, kind, gx, ty, 26, INK, 3)
    d.ellipse([(512 - 26) * S, (ty - 26) * S, (512 + 26) * S, (ty + 26) * S], fill=ACCENT)
    glyph(d, "pause", 512, ty, 17, (16, 16, 19), 3)

    for gx, kind, name in (
        (116, "grid", "片库"), (190, "clock", "正前方"), (760, "frame", "格式"),
        (834, "sliders", "设置"), (908, "power", "退出")
    ):
        glyph(d, kind, gx, 454, 22, INK, 2.4)
        text(d, name, gx, 492, 15, INK2, align="center")
    return im


def draw_settings_old():
    im, d = canvas()
    rrect(d, (150, 12, 874, 500), 12, fill=PANEL, outline=BORDER, width=1.5)
    text(d, "设置", 178, 46, 20, INK, bold=True)
    text(d, "凝视一格即可调整", 846, 44, 15, INK4, align="right")
    cards = [
        ("亮度", "中", None), ("音量", "3 / 5", None),
        ("重定正前方", "看哪儿定哪儿", None), ("锁定视角", None, False),
        ("自动连播", None, True), ("自动淡出", None, True),
        ("字幕", None, False), ("虚拟光标", None, True),
        ("瞳距 IPD", "31.0 mm", None), ("凝视时长", "900 ms", None),
    ]
    for i, (title, value, on) in enumerate(cards):
        col, row = i % 2, i // 2
        left = 170 + col * 348
        top = 58 + row * 74
        box = (left, top, left + 336, top + 68)
        rrect(d, box, 12, fill=CARD)
        text(d, title, left + 18, top + 34, 16, INK)
        if on is not None:
            pw, ph = 46, 20
            pl = box[2] - 18 - pw
            rrect(d, (pl, top + 34 - ph / 2, pl + pw, top + 34 + ph / 2), 10,
                  fill=ACCENT if on else (58, 58, 64))
            cx = pl + 36 if on else pl + 10
            d.ellipse([(cx - 7) * S, (top + 34 - 7) * S, (cx + 7) * S, (top + 34 + 7) * S],
                      fill=(16, 16, 19) if on else (158, 158, 166))
        elif value:
            text(d, value, box[2] - 18, top + 34, 16, INK2, align="right")
    for bx, label, hi in (
        ((170, 434, 330, 490), "返回", False),
        ((350, 434, 672, 490), "瞳距 · 边看边调", True),
        ((692, 434, 854, 490), "清除格式记忆", False),
    ):
        rrect(d, bx, 8, fill=CARD if hi else PANEL,
              outline=ACCENT if hi else BORDER, width=3 if hi else 2)
        text(d, label, (bx[0] + bx[2]) / 2, (bx[1] + bx[3]) / 2, 20,
             INK, align="center", bold=True)
    return im


def sheet(pairs, out: Path):
    """四宫格：上排新、下排旧，每格按 1:1 逻辑缩到一半宽度。"""
    cw, ch = W, H + 34
    out_im = Image.new("RGB", (cw * 2, ch * 2), (8, 8, 10))
    d = ImageDraw.Draw(out_im)
    for i, (im, cap) in enumerate(pairs):
        x, y = (i % 2) * cw, (i // 2) * ch
        out_im.paste(im.resize((W, H), Image.LANCZOS), (x, y + 34))
        d.text((x + 12, y + 8), cap, font=font(15), fill=(255, 213, 112))
        d.rectangle([x, y, x + cw - 1, y + ch - 1], outline=(45, 45, 50))
    out.parent.mkdir(parents=True, exist_ok=True)
    out_im.save(out, quality=92)
    print("wrote", out, out_im.size)


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1] / ".."
    out = root / "output" / "design" / "ui-readability-preview.png"
    sheet([
        (draw_console_new(), "NEW · 控制台（面板 2.4×1.2 · 板 760×268 · 14.8px/度）"),
        (draw_settings_new(), "NEW · 设置（卡片 336×68 · 字 21px ≈1.42°）"),
        (draw_console_old(), "OLD · 控制台（面板 1.8×0.9 · 板 904×134 · 21.1px/度 · 字 15px≈0.71°）"),
        (draw_settings_old(), "OLD · 设置（卡片 336×68 · 字 16px≈0.76°）"),
    ], out)
