"""V2.50 片库三栏 · 按 VrUiLayer 的真实坐标出图（只出图，不改代码）。

用途：改版式时先在本地看到结果，再决定要不要装机。
坐标、字号、圆角一比一抄自 VrUiLayer 的 V2.50 常量与 VrTheme 的真值配色，
所以这张图和真机画布是同一套数字（1024×512，贴 2.4×1.2 米平面，约 16.5 px/度）。

跑法：python tools/preview_library_v250.py
产物：output/preview-library-v250.png（1× 与 2× 各一份）
"""
from PIL import Image, ImageDraw, ImageFont

S = 2
W, H = 1024, 512

# ── VrTheme 真值 ──────────────────────────────────────────────
PANEL  = (25, 25, 26)      # #19191A
SUB    = (35, 35, 39)      # #232327
LINE   = (51, 51, 53)      # #1FFFFFFF over panel
INK    = (255, 255, 255)
INK2   = (201, 201, 201)   # #C9C9C9
INK4   = (125, 125, 125)   # #7D7D7D
ACCENT = (255, 213, 112)   # #FFD570
TIME   = (119, 119, 119)   # #777777
TRACK  = (85, 85, 85)      # #555555
BG     = (11, 12, 14)

# ── 布局常量（抄自 VrUiLayer.companion）──────────────────────
COL_T, COL_B = 20, 492
COL_L, COL_LW = 40, 164
COL_M, COL_MW = 222, 560
COL_R, COL_RW = 800, 184
LIB_W, LIB_H = 116, 72
LIB_LEFT = [246, 378, 510, 642]
LIB_TOP = [100, 212, 324]
RAIL_TOP = [66, 132, 198, 264, 330, 396]
RAIL_H = 60

R_PANEL, R_CARD = 12, 8

F = "C:/Windows/Fonts/msyh.ttc"
FB = "C:/Windows/Fonts/msyhbd.ttc"


def fnt(size, bold=False):
    return ImageFont.truetype(FB if bold else F, int(size * S))


def rr(d, box, r, fill=None, outline=None, w=2):
    x0, y0, x1, y1 = [v * S for v in box]
    d.rounded_rectangle([x0, y0, x1, y1], radius=r * S, fill=fill,
                        outline=outline, width=max(1, int(w * S)))


# Android 的 drawText(x, y) 里 y 是**基线**；PIL 对应 "ls"/"ms"/"rs"。
# 早先用了 "la"/"ma"（ascender 顶），比真机低了一整个字高，图上看到的行距是假的。
_ANCHOR = {"la": "ls", "ma": "ms", "ra": "rs"}


def text(d, s, x, y, size, color, anchor="la", bold=False):
    d.text((x * S, y * S), s, font=fnt(size, bold), fill=color,
           anchor=_ANCHOR.get(anchor, anchor))


ITEMS = [
    ("2026-07-12", "14:30", 0.54),
    ("2026-07-12", "14:40", None),
    ("2026-07-12", "14:55", 0.14),
    ("2026-07-12", "15:12", None),
    ("2026-07-12", "15:20", None),
    ("2026-07-12", "15:33", 0.57),
    ("2026-07-12", "15:40", None),
    ("2026-07-12", "15:45", None),
    ("2026-07-12", "15:51", None),
    ("2026-07-12", "15:56", None),
    ("2026-07-12", "16:00", None),
    ("2026-07-12", "16:07", None),
]

RAIL = ["换片库", "全部集合", "上一页", "下一页", "设置", "退出"]
RAIL_ON = [True, False, True, True, True, True]


def main():
    img = Image.new("RGB", (W * S, H * S), BG)
    d = ImageDraw.Draw(img)

    # ── 三个面板 ─────────────────────────────────────────────
    rr(d, (COL_L, COL_T, COL_L + COL_LW, COL_B), R_PANEL, PANEL, LINE, 1.5)
    rr(d, (COL_M, COL_T, COL_M + COL_MW, COL_B), R_PANEL, PANEL, LINE, 1.5)
    rr(d, (COL_R, COL_T, COL_R + COL_RW, COL_B), R_PANEL, PANEL, LINE, 1.5)

    # ── 左栏 ─────────────────────────────────────────────────
    text(d, "片 库", COL_L + 20, COL_T + 32, 17, INK4)
    for i, (name, on) in enumerate(zip(RAIL, RAIL_ON)):
        top = RAIL_TOP[i]
        ink = INK if on else INK4
        d.rounded_rectangle(
            [(COL_L + 24) * S, (top + 20) * S, (COL_L + 44) * S, (top + 40) * S],
            radius=5 * S, fill=(62, 63, 66) if on else (42, 42, 45))
        text(d, name, COL_L + 58, top + 38, 21, ink)
        if on:
            text(d, "›", COL_L + COL_LW - 26, top + 36, 16, INK4, "mm")

    # ── 中栏 ─────────────────────────────────────────────────
    text(d, "本机", COL_M + 24, 64, 30, INK, "la", True)
    text(d, "12 部 · 摇头翻页", COL_M + 24, 92, 17, INK4)
    text(d, "1 / 1", COL_M + COL_MW - 24, 64, 17, INK4, "ra")

    for i, (date, tm, prog) in enumerate(ITEMS):
        left = LIB_LEFT[i % 4]
        top = LIB_TOP[i // 4]
        hover = i == 0
        rr(d, (left, top, left + LIB_W, top + LIB_H), R_CARD, SUB,
           ACCENT if hover else LINE, 3 if hover else 1.5)
        text(d, date, left + LIB_W / 2, top + LIB_H * 0.30, 14, INK4, "ma")
        text(d, tm, left + LIB_W / 2, top + LIB_H * 0.70, 30, INK, "ma", True)
        if prog is not None:
            rr(d, (left + 8, top + LIB_H - 8, left + LIB_W - 8, top + LIB_H - 5), 2, (30, 30, 32))
            rr(d, (left + 8, top + LIB_H - 8,
                   left + 8 + (LIB_W - 16) * prog, top + LIB_H - 5), 2, ACCENT)
        nm = f"{date[5:]} {tm}"
        text(d, nm, left + LIB_W / 2, top + LIB_H + 22,
             17 if hover else 15, INK if hover else INK2, "ma", hover)

    for i in range(3):
        x = COL_M + 24 + i * 15
        d.rounded_rectangle([x * S, 452 * S, (x + 9) * S, 456 * S],
                            radius=2 * S, fill=ACCENT if i == 0 else LINE)
    text(d, "凝视一格即播放", COL_M + 100, 460, 15, INK4)

    # ── 右栏 ─────────────────────────────────────────────────
    def hero(top, label, date, tm, prog, meta):
        text(d, label, COL_R + 20, top + 22, 17, INK4)
        card = (COL_R + 20, top + 34, COL_R + COL_RW - 20, top + 34 + 150)
        rr(d, card, R_CARD, SUB, LINE, 1.5)
        cx = (card[0] + card[2]) / 2
        text(d, date, cx, card[1] + 52, 14, INK4, "ma")
        text(d, tm, cx, card[1] + 92, 30, INK, "ma", True)
        if prog is not None:
            bar = (card[0] + 14, card[3] - 34, card[2] - 14, card[3] - 30)
            rr(d, bar, 2, TRACK)
            rr(d, (bar[0], bar[1], bar[0] + (bar[2] - bar[0]) * prog, bar[3]), 2, ACCENT)
            text(d, "01:12 / 02:14", cx, card[3] - 10, 15, TIME, "ma")
        else:
            text(d, meta, cx, card[3] - 10, 15, TIME, "ma")

    hero(COL_T, "继续观看", "2026-07-12", "14:30", 0.54, None)
    hero(246, "最近加入", "2026-07-12", "16:07", None, "刚扫到 · 01:41")


    # ── 准星 ─────────────────────────────────────────────────
    gx, gy = LIB_LEFT[0] + LIB_W * 0.5, LIB_TOP[0] + LIB_H * 0.5
    d.ellipse([(gx - 13) * S, (gy - 13) * S, (gx + 13) * S, (gy + 13) * S],
              outline=ACCENT, width=int(3 * S))
    d.arc([(gx - 21) * S, (gy - 21) * S, (gx + 21) * S, (gy + 21) * S],
          -90, -90 + 360 * 0.62, fill=ACCENT, width=int(4 * S))

    img.resize((W, H), Image.LANCZOS).save("output/preview-library-v250.png")
    img.save("output/preview-library-v250@2x.png")
    print("ok -> output/preview-library-v250.png")


if __name__ == "__main__":
    main()
