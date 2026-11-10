"""按 VrUiLayer.drawLibrary() 的实际坐标画对照图。

V2.13：不仅画 1024×512 的画布，还按 UI 平面在 2 米外的真实角度
（旧 1.8×0.9 米 ≈ 48.5°×25.4°，新 2.4×1.2 米 ≈ 61.9°×33.4°）
缩放到同一块视野里，这样「变大了多少」才是能看见的，而不是一句说法。
"""
import math
from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 512

C_PANEL = (14, 27, 44)
C_PANEL_HI = (18, 40, 63)
C_BORDER = (34, 54, 79)
C_ACCENT = (103, 232, 249)
C_TEXT = (234, 242, 250)
C_MUTED = (185, 198, 218)
C_POSTER = (23, 58, 94)
C_BG = (8, 17, 30)


def font(size, bold=False):
    for name in (["msyhbd.ttc", "msyh.ttc"] if bold else ["msyh.ttc", "msyhbd.ttc"]):
        try:
            return ImageFont.truetype("C:/Windows/Fonts/" + name, size)
        except OSError:
            continue
    return ImageFont.load_default()


# ────────────────────────────── 旧版（V2.9，8 格 + 四个文字按钮） ──
def draw_old():
    img = Image.new("RGB", (W, H), C_BG)
    d = ImageDraw.Draw(img)

    def cell(idx, title, meta, badge):
        x, y = [60, 286, 526, 752][idx % 4], [138, 268][idx // 4]
        cw, ch = 212, 106
        d.rounded_rectangle((x, y, x + cw, y + ch), 12, fill=C_POSTER, outline=C_BORDER, width=2)
        d.text((x + cw / 2, y + ch / 2 - 2), meta, font=font(17), fill=C_MUTED, anchor="mm")
        d.rectangle((x, y + ch - 30, x + cw, y + ch), fill=(11, 22, 38))
        d.text((x + 10, y + ch - 9), title, font=font(17), fill=C_TEXT, anchor="ls")
        if badge:
            fw = d.textlength(badge, font=font(15)) + 16
            d.rounded_rectangle((x + 8, y + 8, x + 8 + fw, y + 30), 11, fill=C_PANEL_HI)
            d.text((x + 16, y + 24), badge, font=font(15), fill=C_ACCENT, anchor="ls")

    def button(rect, label):
        d.rounded_rectangle(rect, 14, fill=C_PANEL, outline=C_BORDER, width=2)
        d.text(((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2 + 9),
               label, font=font(25, True), fill=C_TEXT, anchor="mm")

    d.rounded_rectangle((40, 20, 984, 492), 24, fill=C_PANEL, outline=C_BORDER, width=2)
    d.text((66, 62), "片库 · 832 部", font=font(26, True), fill=C_TEXT, anchor="ls")
    d.text((958, 62), "第 2 / 104 页", font=font(20), fill=C_MUTED, anchor="rs")
    button((60, 84, 260, 126), "上一页")
    button((764, 84, 964, 126), "下一页")
    d.text((512, 112), "第 9–16 部 · 摇头翻页", font=font(19), fill=C_MUTED, anchor="ms")

    rows = [
        ("2026-07-12 14:30", "00:42 · 4096×2048", "续 01:24"),
        ("GOPR0231", "12:04 · 3840×1920", None),
        ("2026-06-28 09:12", "03:17 · 4096×2048", None),
        ("VID 20260701 1830", "00:58 · 1920×1080", None),
        ("2026-05-03 20:41", "07:26 · 4096×2048", None),
        ("PXL 20260702", "01:09 · 1920×1080", "续 01:24"),
        ("海边 第3段", "05:48 · 4096×2048", None),
        ("2026-07-12 15:02", "00:31 · 4096×2048", None),
    ]
    for i, (t, m, b) in enumerate(rows):
        cell(i, t, m, b)

    button((60, 388, 300, 442), "设置")
    button((724, 388, 964, 442), "退出")
    d.text((512, 415), "凝视一格即播放 · 摇头翻页", font=font(19), fill=C_MUTED, anchor="ms")
    return img


# ────────────────────────────── 新版（V2.13，图标顶栏 + 日期时刻） ──
def draw_new():
    img = Image.new("RGB", (W, H), C_BG)
    d = ImageDraw.Draw(img)

    def icon(rect, kind, dim=False):
        d.rounded_rectangle(rect, 16, fill=C_PANEL, outline=C_BORDER, width=2)
        cx, cy = (rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2
        col = (110, 120, 135) if dim else C_TEXT
        if kind == "left":
            d.line([(cx + 8, cy - 14), (cx - 8, cy), (cx + 8, cy + 14)], fill=col, width=4, joint="curve")
        elif kind == "right":
            d.line([(cx - 8, cy - 14), (cx + 8, cy), (cx - 8, cy + 14)], fill=col, width=4, joint="curve")
        elif kind == "gear":
            d.ellipse((cx - 9, cy - 9, cx + 9, cy + 9), outline=col, width=4)
            for i in range(8):
                a = i * math.pi / 4
                d.line([(cx + math.cos(a) * 12, cy + math.sin(a) * 12),
                        (cx + math.cos(a) * 17, cy + math.sin(a) * 17)], fill=col, width=7)
        elif kind == "power":
            d.arc((cx - 13, cy - 13, cx + 13, cy + 13), -58, -58 + 296, fill=col, width=4)
            d.line([(cx, cy - 19), (cx, cy - 1)], fill=col, width=4)

    def cell(idx, date, time, meta, badge=None):
        x, y = [24, 272, 520, 768][idx % 4], [116, 297][idx // 4]
        cw, ch = 232, 165
        d.rounded_rectangle((x, y, x + cw, y + ch), 14, fill=C_POSTER, outline=C_BORDER, width=2)
        cx = x + cw / 2
        d.text((cx, y + 54), date, font=font(22), fill=C_MUTED, anchor="mm")
        d.text((cx, y + 112), time, font=font(44, True), fill=C_TEXT, anchor="mm")
        d.text((cx, y + 148), meta, font=font(19), fill=C_MUTED, anchor="mm")
        if badge:
            fw = d.textlength(badge, font=font(17)) + 18
            d.rounded_rectangle((x + cw - 10 - fw, y + 10, x + cw - 10, y + 36), 13, fill=C_PANEL_HI)
            d.text((x + cw - 19, y + 29), badge, font=font(17), fill=C_ACCENT, anchor="rs")

    d.rounded_rectangle((20, 14, 1004, 498), 24, fill=C_PANEL, outline=C_BORDER, width=2)
    d.text((44, 76), "片库 · 832 部", font=font(30, True), fill=C_TEXT, anchor="ls")
    d.text((636, 74), "2 / 104", font=font(22), fill=C_MUTED, anchor="mm")
    icon((482, 28, 578, 94), "left")
    icon((694, 28, 790, 94), "right")
    icon((806, 28, 902, 94), "gear")
    icon((912, 28, 1008, 94), "power")

    rows = [
        ("7月12日", "14:30:22", "00:42 · 4096×2048", "续 01:24"),
        ("6月28日", "09:12:05", "12:04 · 3840×1920", None),
        ("7月12日", "15:02:47", "03:17 · 4096×2048", None),
        ("7月1日", "18:30:11", "00:58 · 1920×1080", None),
        ("5月3日", "20:41:09", "07:26 · 4096×2048", None),
        ("7月12日", "14:58:33", "01:09 · 1920×1080", "续 01:24"),
        ("7月12日", "16:20:04", "05:48 · 4096×2048", None),
        ("7月12日", "17:02:56", "00:31 · 4096×2048", None),
    ]
    for i, (dt, tm, m, b) in enumerate(rows):
        cell(i, dt, tm, m, b)

    d.text((512, 490), "凝视一格即播放 · 摇头或左右箭头翻页", font=font(20), fill=C_MUTED, anchor="ms")
    return img


# ─────────────── 按 2 米外的真实角度缩放，同一块视野里对比 ───────────────
PX_PER_DEG = 12.0
FRAME_W, FRAME_H = 1100, 640


def angular(w_m, h_m):
    return (2 * math.degrees(math.atan(w_m / 2 / 2.0)),
            2 * math.degrees(math.atan(h_m / 2 / 2.0)))


def place(frame, canvas, w_m, h_m, label, sub):
    dw_deg, dh_deg = angular(w_m, h_m)
    pw, ph = int(dw_deg * PX_PER_DEG), int(dh_deg * PX_PER_DEG)
    scaled = canvas.resize((pw, ph), Image.LANCZOS)
    x = (frame.width - pw) // 2
    y = 74
    frame.paste(scaled, (x, y))
    d = ImageDraw.Draw(frame)
    d.text((frame.width / 2, 30), label, font=font(26, True), fill=C_TEXT, anchor="mm")
    d.text((frame.width / 2, 56), sub, font=font(17), fill=C_MUTED, anchor="mm")
    d.rectangle((x, y, x + pw, y + ph), outline=(255, 80, 80), width=1)


old, new = draw_old(), draw_new()
old.save("output/library-before.png")
new.save("output/library-after.png")

left = Image.new("RGB", (FRAME_W, FRAME_H), C_BG)
right = Image.new("RGB", (FRAME_W, FRAME_H), C_BG)
place(left, old, 1.8, 0.9, "改动前 · 1.8×0.9 米",
      "约占 48.5°×25.4° · 标题 17px · 底栏四个文字按钮")
place(right, new, 2.4, 1.2, "改动后 · 2.4×1.2 米",
      "约占 61.9°×33.4° · 时刻 44px · 顶栏四个图标按钮")

combo = Image.new("RGB", (FRAME_W * 2 + 24, FRAME_H), C_BG)
combo.paste(left, (0, 0))
combo.paste(right, (FRAME_W + 24, 0))
combo.save("output/library-compare.png")
print("saved output/library-before.png / library-after.png / library-compare.png")
