"""改版稿（只出图，不改代码）。

画布与真机一致：1024×512，贴 2.4×1.2 米平面（2 米外约 62°×33°，约 16.5 px/度）。
所以下面每个坐标都能一比一搬进 VrUiLayer，字号也按「≥15px ≈ 0.9°」这条线下限卡。

跑法：python tools/mock_design.py
产物：output/mock-home.png / mock-player.png / mock-settings.png
"""
import math
from PIL import Image, ImageDraw, ImageFont

S = 2
W, H = 1024, 512

BG     = (10, 10, 11)
PANEL  = (19, 19, 21)
PANEL2 = (28, 28, 32)
BORDER = (44, 44, 49)
TEXT   = (242, 242, 243)
MUTED  = (158, 158, 166)
DIM    = (110, 110, 118)
ACCENT = (103, 232, 249)

F  = "C:/Windows/Fonts/msyh.ttc"
FB = "C:/Windows/Fonts/msyhbd.ttc"


def fnt(size, bold=False):
    return ImageFont.truetype(FB if bold else F, int(size * S))


def rr(d, box, r, fill=None, outline=None, width=1):
    d.rounded_rectangle([v * S for v in box], radius=r * S, fill=fill,
                        outline=outline, width=max(1, int(width * S)))


def tx(d, xy, s, size, color=TEXT, align="left", bold=False):
    f = fnt(size, bold)
    x, y = xy[0] * S, xy[1] * S
    w = d.textlength(s, font=f)
    if align == "center":
        x -= w / 2
    elif align == "right":
        x -= w
    d.text((x, y), s, font=f, fill=color)


def tw(d, s, size, bold=False):
    return d.textlength(s, font=fnt(size, bold)) / S


def grad(im, box, top, bottom, r):
    x0, y0, x1, y1 = [int(v * S) for v in box]
    w, h = x1 - x0, y1 - y0
    strip = Image.new("RGB", (1, h))
    for i in range(h):
        t = i / max(1, h - 1)
        strip.putpixel((0, i), tuple(int(top[c] + (bottom[c] - top[c]) * t) for c in range(3)))
    strip = strip.resize((w, h))
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, w - 1, h - 1], radius=max(1, int(r * S)), fill=255)
    im.paste(strip, (x0, y0), mask)


def ic(d, kind, cx, cy, size, color, lw=2.4):
    """图标：坐标一律先算成**画布单位**再交给 rr/line/poly。

    踩过的坑：写成 (cx - 0.55) * s * S 会把「画布坐标」和「图标相对单位」
    乘到一起，图标整个糊在中心——所以统一走 U()。
    """
    s = size / 2.0
    L = max(1, int(lw * S))

    def U(u, v):
        return (cx + u * s, cy + v * s)

    def seg(*pts):
        d.line([(p[0] * S, p[1] * S) for p in (U(u, v) for u, v in pts)],
               fill=color, width=L, joint="curve")

    def poly(*pts):
        d.polygon([(p[0] * S, p[1] * S) for p in (U(u, v) for u, v in pts)], fill=color)

    def rect(u0, v0, u1, v1, fill=None, r=0, w=None):
        a, b = U(u0, v0), U(u1, v1)
        rr(d, (a[0], a[1], b[0], b[1]), r, fill=fill, outline=None if fill else color,
           width=w if w else lw)

    def circle(r, fill=None, w=None):
        a, b = U(-r, -r), U(r, r)
        d.ellipse([a[0] * S, a[1] * S, b[0] * S, b[1] * S], fill=fill,
                  outline=None if fill else color, width=int((w or lw) * S))

    if kind == "play":
        poly((-0.5, -0.85), (-0.5, 0.85), (0.95, 0))
    elif kind == "pause":
        rect(-0.62, -0.8, -0.14, 0.8, fill=color, r=0.06)
        rect(0.14, -0.8, 0.62, 0.8, fill=color, r=0.06)
    elif kind == "prev":
        rect(-0.9, -0.7, -0.62, 0.7, fill=color, r=0.06)
        poly((0.8, -0.7), (0.8, 0.7), (-0.4, 0))
    elif kind == "next":
        rect(0.62, -0.7, 0.9, 0.7, fill=color, r=0.06)
        poly((-0.8, -0.7), (-0.8, 0.7), (0.4, 0))
    elif kind in ("back30", "fwd30"):
        # 3/4 圈 + 切向箭头，和 VrUiLayer.glyph() 同一套算法
        cw = kind == "fwd30"
        r = 0.72
        start, sweep = (60, 260) if cw else (300, -260)
        box = U(-r, -r), U(r, r)
        d.arc([box[0][0] * S, box[0][1] * S, box[1][0] * S, box[1][1] * S],
              start, start + sweep, fill=color, width=L)
        a = math.radians(start + sweep)
        dirn = 1 if cw else -1
        tx_, ty_ = -math.sin(a) * dirn, math.cos(a) * dirn
        tip = (math.cos(a) * r, math.sin(a) * r)
        bx, by = tip[0] - tx_ * 0.42, tip[1] - ty_ * 0.42
        seg(tip, (bx - ty_ * 0.46, by + tx_ * 0.46))
        seg(tip, (bx + ty_ * 0.46, by - tx_ * 0.46))
    elif kind == "sliders":
        for i, uu in enumerate((-0.3, 0.28, -0.08)):
            v = -0.58 + i * 0.58
            seg((-0.78, v), (0.78, v))
            c = U(uu, v)
            d.ellipse([(c[0] - 0.19 * s) * S, (c[1] - 0.19 * s) * S,
                       (c[0] + 0.19 * s) * S, (c[1] + 0.19 * s) * S], fill=color)
    elif kind == "chevR":
        seg((-0.25, -0.5), (0.3, 0), (-0.25, 0.5))
    elif kind == "chevL":
        seg((0.25, -0.5), (-0.3, 0), (0.25, 0.5))
    elif kind == "folder":
        seg((-0.85, 0.62), (-0.85, -0.55), (-0.25, -0.55), (-0.06, -0.22),
            (0.85, -0.22), (0.85, 0.62), (-0.85, 0.62))
    elif kind == "globe":
        circle(0.8)
        seg((-0.8, 0), (0.8, 0))
        a, b = U(-0.4, -0.8), U(0.4, 0.8)
        d.ellipse([a[0] * S, a[1] * S, b[0] * S, b[1] * S], outline=color, width=L)
    elif kind == "grid":
        sq = 0.68
        c0 = 0.44
        st = max(1.1, lw * 0.72)
        for du in (-1, 1):
            for dv in (-1, 1):
                rect(du * c0 - sq / 2, dv * c0 - sq / 2, du * c0 + sq / 2, dv * c0 + sq / 2,
                     r=sq * 0.18, w=st)
    elif kind == "clock":
        circle(0.8)
        seg((0, 0), (0, -0.5))
        seg((0, 0), (0.4, 0.18))
    elif kind == "gear":
        circle(0.3)
        for i in range(8):
            a = math.radians(i * 45)
            seg((math.cos(a) * 0.48, math.sin(a) * 0.48), (math.cos(a) * 0.8, math.sin(a) * 0.8))
    elif kind == "cc":
        rect(-0.9, -0.55, 0.9, 0.55, r=0.14)
        seg((-0.6, 0.06), (-0.34, 0.06))
        seg((0.16, 0.06), (0.56, 0.06))
    elif kind == "frame":
        rect(-0.85, -0.6, 0.85, 0.6, r=0.12)
        seg((-0.85, -0.15), (0.85, -0.15))
    elif kind == "more":
        for i in (-1, 0, 1):
            a, b = U(i * 0.5 - 0.11, -0.11), U(i * 0.5 + 0.11, 0.11)
            d.ellipse([a[0] * S, a[1] * S, b[0] * S, b[1] * S], fill=color)
    elif kind == "power":
        r = 0.7
        box = U(-r, -r), U(r, r)
        d.arc([box[0][0] * S, box[0][1] * S, box[1][0] * S, box[1][1] * S],
              125, 415, fill=color, width=L)
        seg((0, -0.9), (0, -0.05))
    elif kind == "sort":
        seg((-0.7, -0.45), (0.1, -0.45))
        seg((-0.7, 0.05), (0.35, 0.05))
        seg((-0.7, 0.55), (0.6, 0.55))


PAL = [(40, 86, 106), (76, 48, 94), (100, 64, 34), (38, 76, 62), (90, 42, 48),
       (46, 60, 106), (72, 72, 38), (54, 42, 82), (36, 68, 90), (86, 56, 40)]


def poster(im, d, box, seed, r=9):
    top = PAL[seed % len(PAL)]
    grad(im, box, top, tuple(int(c * 0.32) for c in top), r)
    cx = (box[0] + box[2]) / 2 + ((seed * 41) % 46) - 23
    cy = (box[1] + box[3]) / 2 + ((seed * 17) % 20) - 10
    rr(d, (cx - 16, cy - 10, cx + 16, cy + 10), 10, fill=(238, 238, 228))


def card(im, d, box, title, seed, hover=False, badge=None, prog=None):
    x0, y0 = box[0], box[1]
    w = box[2] - box[0]
    th = w * 9 / 16
    if hover:                        # 悬停：整卡放大 5%，名字变白加粗
        cx, cy = x0 + w / 2, y0 + th / 2
        k = 1.05
        w2, th2 = w * k, th * k
        x0, y0 = cx - w2 / 2, cy - th2 / 2
        w, th = w2, th2
    rr(d, (x0, y0, x0 + w, y0 + th), 10, fill=PANEL2)
    poster(im, d, (x0, y0, x0 + w, y0 + th), seed, r=10)
    if prog is not None:
        rr(d, (x0 + 8, y0 + th - 9, x0 + w - 8, y0 + th - 5), 2, fill=(70, 70, 76))
        rr(d, (x0 + 8, y0 + th - 9, x0 + 8 + (w - 16) * prog, y0 + th - 5), 2, fill=ACCENT)
    if badge:
        bw = tw(d, badge, 10) + 14
        rr(d, (x0 + w - bw - 6, y0 + 6, x0 + w - 6, y0 + 21), 7, fill=(0, 0, 0))
        tx(d, (x0 + w - bw / 2 - 6, y0 + 7), badge, 10, ACCENT, "center")
    rr(d, (x0, y0, x0 + w, y0 + th), 10,
       outline=ACCENT if hover else BORDER, width=2 if hover else 1)
    # 名字在卡片**外面**：压在海报上会和画面抢对比度，这是最大的可读性差别
    tx(d, (x0 + w / 2, y0 + th + 7), title, 16 if hover else 15,
       TEXT if hover else MUTED, "center", bold=hover)


def rail(im, d):
    rr(d, (8, 8, 196, 504), 20, fill=PANEL, outline=BORDER)
    tx(d, (28, 24), "片 库", 10, DIM)
    items = [("全部片", "grid"), ("集合", "folder"), ("最近播放", "clock"), ("网络", "globe")]
    for i, (name, kind) in enumerate(items):
        y = 50 + i * 54
        on = i == 0
        if on:
            rr(d, (16, y - 6, 188, y + 34), 10, fill=PANEL2)
            rr(d, (16, y - 1, 20, y + 29), 2, fill=ACCENT)
        ic(d, kind, 40, y + 14, 18, ACCENT if on else MUTED)
        tx(d, (60, y + 4), name, 15, TEXT if on else MUTED, bold=on)
        ic(d, "chevR", 176, y + 14, 11, DIM)
    d.line([(24 * S, 424 * S), (180 * S, 424 * S)], fill=BORDER, width=max(1, S))
    for i, (name, kind) in enumerate([("设置", "gear"), ("退出", "power")]):
        y = 444 + i * 34
        ic(d, kind, 40, y + 6, 16, MUTED)
        tx(d, (60, y - 3), name, 14, MUTED)


def home():
    im = Image.new("RGB", (W * S, H * S), BG)
    d = ImageDraw.Draw(im)
    rail(im, d)

    rr(d, (210, 8, 1016, 504), 20, fill=PANEL, outline=BORDER)
    tx(d, (232, 22), "KAVR · 104 部", 17, TEXT, bold=True)
    tx(d, (232, 47), "凝视即播放 · 摇头翻页", 11, DIM)
    # 分段控件：一眼看出「全部 / 集合」两种浏览方式
    sx = 520
    rr(d, (sx, 22, sx + 168, 50), 14, fill=PANEL2)
    rr(d, (sx + 2, 24, sx + 84, 48), 12, fill=ACCENT)
    tx(d, (sx + 43, 30), "全部", 12, (10, 10, 12), "center", bold=True)
    tx(d, (sx + 126, 30), "集合", 12, MUTED, "center")
    ic(d, "sort", 930, 36, 14, MUTED)
    ic(d, "more", 972, 36, 14, MUTED)

    xs = [232, 426, 620, 814]
    ys = [88, 214]
    names = ["7月12日 14:30", "7月12日 15:02", "7月13日 09:02", "7月13日 09:07",
             "7月14日 21:30", "7月15日 08:12", "7月15日 19:44", "7月16日 07:20"]
    for i in range(8):
        card(im, d, (xs[i % 4], ys[i // 4], xs[i % 4] + 178, ys[i // 4] + 100),
             names[i], seed=i + 1, hover=(i == 1),
             badge=("续 12:34" if i == 4 else None), prog=(0.42 if i == 4 else None))
        d = ImageDraw.Draw(im)

    # 底栏只留页码 + 语义提示，不再摆四个纯文字按钮
    tx(d, (232, 412), "第 1 / 13 页", 11, DIM)
    for i in range(3):
        x = 314 + i * 15
        rr(d, (x, 416, x + 9, 419), 2, fill=ACCENT if i == 0 else (60, 60, 66))
    tx(d, (992, 412), "准星停住 600ms 即打开", 11, DIM, "right")
    rr(d, (232, 436, 404, 480), 12, fill=PANEL2)
    tx(d, (246, 445), "返回播放", 14, TEXT, bold=True)
    tx(d, (246, 461), "00:42:11 / 01:58:36", 10, DIM)

    im.save("output/mock-home.png")


def player():
    """与 VrUiLayer.drawPlayer() 的坐标逐个对齐：胶囊 60..964 × 366..500。"""
    im = Image.new("RGB", (W * S, H * S), BG)
    d = ImageDraw.Draw(im)
    for i in range(H * S):
        t = i / (H * S)
        c = int(14 + 26 * t)
        d.line([(0, i), (W * S, i)], fill=(c - 2, c + 2, c + 8))

    tx(d, (26, 18), "180° 半球 · 左右 3D", 16, (242, 242, 243))
    tx(d, (26, 40), "自动识别 · 需要时点「格式」改", 15, (110, 110, 118))

    x0, y0, x1, y1 = 60, 366, 964, 500
    rr(d, (x0, y0, x1, y1), 26, fill=(16, 16, 19))
    rr(d, (x0, y0, x1, y1), 26, outline=(48, 48, 54))

    barL, barR, barY = 86, 938, 410
    tx(d, (barL, y0 + 9), "00:42:11", 15, (158, 158, 166))
    tx(d, (barR, y0 + 9), "01:58:36", 15, (110, 110, 118), "right")
    rr(d, (barL, barY - 2, barR, barY + 2), 2, fill=(58, 58, 64))
    kx = barL + (barR - barL) * 0.36
    rr(d, (barL, barY - 2, kx, barY + 2), 2, fill=ACCENT)
    d.ellipse([(kx - 6) * S, (barY - 6) * S, (kx + 6) * S, (barY + 6) * S], fill=(255, 255, 255))

    ty = 462
    ic(d, "prev", 352, ty, 26, TEXT, 3)
    ic(d, "back30", 432, ty, 26, TEXT, 3)
    d.ellipse([(512 - 26) * S, (ty - 26) * S, (512 + 26) * S, (ty + 26) * S], fill=ACCENT)
    ic(d, "pause", 512, ty, 17, (16, 16, 19), 3)
    ic(d, "fwd30", 592, ty, 26, TEXT, 3)
    ic(d, "next", 672, ty, 26, TEXT, 3)

    for cx, kind, name in [(116, "grid", "片库"), (190, "clock", "正前方"),
                           (760, "frame", "格式"), (834, "sliders", "设置"),
                           (908, "power", "退出")]:
        ic(d, kind, cx, 454, 22, TEXT, 2.4)
        tx(d, (cx, 480), name, 15, (158, 158, 166), "center")

    im.save("output/mock-player.png")


def settings():
    """与 VrUiLayer.drawSettings() 的坐标逐个对齐：2 列 × 5 行，每格 336×68。"""
    im = Image.new("RGB", (W * S, H * S), BG)
    d = ImageDraw.Draw(im)
    rr(d, (150, 12, 874, 500), 24, fill=PANEL, outline=BORDER)
    tx(d, (178, 28), "设置", 20, TEXT, bold=True)
    tx(d, (846, 30), "凝视一格即可调整", 15, DIM, "right")

    cards = [("亮度", "标准"), ("音量", "3 / 5"),
             ("画面上下", None), ("锁定视角", "on"),
             ("自动连播", "on"), ("自动淡出", "on"),
             ("字幕", "on"), ("虚拟光标", None),
             ("瞳距 IPD", "63.0 mm"), ("凝视时长", "600 ms")]
    for i, (title, val) in enumerate(cards):
        left = 170 + (i % 2) * 348
        top = 58 + (i // 2) * 74
        rr(d, (left, top, left + 336, top + 68), 12, fill=PANEL2)
        tx(d, (left + 18, top + 25), title, 16, TEXT)
        if val == "on":
            rr(d, (left + 272, top + 24, left + 318, top + 44), 10, fill=ACCENT)
            d.ellipse([(left + 300) * S, (top + 26) * S, (left + 314) * S, (top + 42) * S],
                      fill=(16, 16, 19))
        elif val:
            tx(d, (left + 318, top + 25), val, 16, MUTED, "right")
        else:
            rr(d, (left + 272, top + 24, left + 318, top + 44), 10, fill=(58, 58, 64))
            d.ellipse([(left + 274) * S, (top + 26) * S, (left + 288) * S, (top + 42) * S],
                      fill=(158, 158, 166))

    for bx0, bx1, name, acc in [(170, 330, "返回", False),
                                (350, 672, "瞳距 · 边看边调", True),
                                (692, 854, "清除格式记忆", False)]:
        rr(d, (bx0, 434, bx1, 490), 12, fill=ACCENT if acc else PANEL2)
        tx(d, ((bx0 + bx1) / 2, 452), name, 16,
           (16, 16, 19) if acc else TEXT, "center", bold=acc)

    im.save("output/mock-settings.png")


if __name__ == "__main__":
    home(); player(); settings()
    print("wrote output/mock-home.png / mock-player.png / mock-settings.png")
