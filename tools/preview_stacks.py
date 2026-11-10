"""按 VrUiLayer.drawLibrary() 的 V2.16 实际坐标画两张屏。

目的是在戴镜之前用眼睛核对三件事：
1. 一叠一格是不是真的比 104 页片子好认；
2. 底栏的「返回播放 / 全部叠」会不会压到提示文字；
3. 格子从 165 收到 145 之后，日期 / 时刻 / 时长还挤不挤。
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 512

C_BG = (8, 17, 30)
C_PANEL = (14, 27, 44)
C_BORDER = (34, 54, 79)
C_ACCENT = (103, 232, 249)
C_TEXT = (234, 242, 250)
C_MUTED = (185, 198, 218)
C_POSTER = (23, 58, 94)

CELL_W, CELL_H = 232, 145
CELL_LEFT = [24, 272, 520, 768]
CELL_TOP = [114, 266]


def font(size, bold=False):
    for name in (["msyhbd.ttc", "msyh.ttc"] if bold else ["msyh.ttc", "msyhbd.ttc"]):
        try:
            return ImageFont.truetype("C:/Windows/Fonts/" + name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def panel(d):
    d.rounded_rectangle((20, 14, 1004, 498), 24, fill=C_PANEL, outline=C_BORDER, width=2)


def icon_button(d, rect, glyph):
    d.rounded_rectangle(rect, 16, fill=C_PANEL, outline=C_BORDER, width=2)
    x0, y0, x1, y1 = rect
    cx, cy = (x0 + x1) / 2, (y0 + y1) / 2
    if glyph == "left":
        d.line([(cx + 8, cy - 14), (cx - 8, cy), (cx + 8, cy + 14)], fill=C_TEXT, width=4)
    elif glyph == "right":
        d.line([(cx - 8, cy - 14), (cx + 8, cy), (cx - 8, cy + 14)], fill=C_TEXT, width=4)
    elif glyph == "gear":
        d.ellipse((cx - 11, cy - 11, cx + 11, cy + 11), outline=C_TEXT, width=7)
    else:
        d.arc((cx - 12, cy - 12, cx + 12, cy + 12), 300, 240, fill=C_TEXT, width=4)
        d.line([(cx, cy - 12), (cx, cy - 2)], fill=C_TEXT, width=4)


def top_bar(d, title, page_text, can_prev, can_next):
    d.text((44, 76), title, font=font(30), fill=C_TEXT, anchor="ls")
    if page_text:
        d.text((636, 74), page_text, font=font(22), fill=C_MUTED, anchor="ms")
    icon_button(d, (482, 28, 578, 94), "left")
    icon_button(d, (694, 28, 790, 94), "right")
    icon_button(d, (806, 28, 902, 94), "gear")
    icon_button(d, (912, 28, 1008, 94), "power")


def button(d, rect, caption, highlighted=False, sub=None):
    x0, y0, x1, y1 = rect
    d.rounded_rectangle(rect, 14, fill=(18, 40, 63) if highlighted else C_PANEL,
                        outline=C_ACCENT if highlighted else C_BORDER,
                        width=3 if highlighted else 2)
    cy = (y0 + y1) / 2 + (-4 if sub else 9)
    d.text(((x0 + x1) / 2, cy), caption, font=font(25), fill=C_TEXT, anchor="mm")
    if sub:
        d.text(((x0 + x1) / 2, cy + 32), sub, font=font(18),
               fill=C_ACCENT if highlighted else C_MUTED, anchor="mm")


def stack_card(d, rect, label, detail):
    x0, y0, x1, y1 = rect
    d.rounded_rectangle(rect, 14, fill=C_POSTER, outline=C_BORDER, width=2)
    d.rounded_rectangle((x0 + 8, y0 + 12, x0 + 14, y1 - 12), 3, fill=C_ACCENT)
    d.text(((x0 + x1) / 2 + 4, (y0 + y1) / 2 - 4), label,
           font=font(28, True), fill=C_TEXT, anchor="mm")
    d.text(((x0 + x1) / 2 + 4, (y0 + y1) / 2 + 26), detail,
           font=font(20), fill=C_ACCENT, anchor="mm")


def item_card(d, rect, date, time, meta, badge=None):
    x0, y0, x1, y1 = rect
    d.rounded_rectangle(rect, 14, fill=C_POSTER, outline=C_BORDER, width=2)
    d.text(((x0 + x1) / 2, y0 + 54), date, font=font(22), fill=C_MUTED, anchor="ms")
    d.text(((x0 + x1) / 2, y0 + 106), time, font=font(44, True), fill=C_TEXT, anchor="ms")
    if meta:
        d.text(((x0 + x1) / 2, y0 + 136), meta, font=font(19), fill=C_MUTED, anchor="ms")
    if badge:
        w = d.textlength(badge, font=font(17)) + 18
        d.rounded_rectangle((x1 - 10 - w, y0 + 4, x1 - 10, y0 + 28), 12, fill=(18, 40, 63))
        d.text((x1 - 19, y0 + 23), badge, font=font(17), fill=C_ACCENT, anchor="rs")


def bottom_bar(d, with_resume, with_close, hint):
    right = 24
    if with_resume:
        button(d, (24, 424, 224, 490), "返回播放", highlighted=True)
        right = 236
    if with_close:
        button(d, (right, 424, right + 184, 490), "← 全部叠")
        right += 196
    if right > 24:
        d.text((1000, 464), hint, font=font(18), fill=C_MUTED, anchor="rs")
    else:
        d.text((W / 2, 470), hint, font=font(20), fill=C_MUTED, anchor="ms")


# ── 屏 1：全部叠（832 部 → 9 叠，一屏就看全） ──────────────────────
def screen_stacks():
    img = Image.new("RGB", (W, H), C_BG)
    d = ImageDraw.Draw(img)
    panel(d)
    top_bar(d, "片库 · 832 部 · 9 叠", "1 / 2", False, True)
    stacks = [
        ("2024 旅行", "124 部"), ("2025 春节", "86 部"), ("DCIM", "231 部"),
        ("GOPRO", "97 部"), ("Movies", "68 部"), ("手机导出", "54 部"),
        ("无人机", "43 部"), ("未整理", "129 部"),
    ]
    for slot, (label, detail) in enumerate(stacks):
        left, top = CELL_LEFT[slot % 4], CELL_TOP[slot // 4]
        stack_card(d, (left, top, left + CELL_W, top + CELL_H), label, detail)
    bottom_bar(d, with_resume=False, with_close=False,
               hint="凝视一叠即进入 · 摇头或左右箭头翻页")
    return img


# ── 屏 2：叠内（播放中调出片库，底栏两个按钮都在） ────────────────
def screen_inside():
    img = Image.new("RGB", (W, H), C_BG)
    d = ImageDraw.Draw(img)
    panel(d)
    top_bar(d, "叠 · DCIM · 231 部", "3 / 29", True, True)
    data = [
        ("2026-07-12", "14:30", "01:24 · 1920×1080", "续 03:12"),
        ("2026-07-12", "14:28", "00:46 · 1920×1080", None),
        ("2026-07-11", "19:02", "02:10 · 3840×2160", None),
        ("2026-07-11", "18:57", "00:31 · 1920×1080", None),
        ("2026-07-10", "09:15", "05:48 · 3840×2160", None),
        ("2026-07-10", "09:11", "00:58 · 1920×1080", None),
        ("2026-07-09", "21:40", "01:03 · 1920×1080", None),
        ("2026-07-09", "21:36", "00:22 · 1920×1080", None),
    ]
    for slot, (date, time, meta, badge) in enumerate(data):
        left, top = CELL_LEFT[slot % 4], CELL_TOP[slot // 4]
        item_card(d, (left, top, left + CELL_W, top + CELL_H), date, time, meta, badge)
    bottom_bar(d, with_resume=True, with_close=True,
               hint="凝视一格即播放 · 摇头或左右箭头翻页")
    return img


def main():
    a, b = screen_stacks(), screen_inside()
    gap = 16
    sheet = Image.new("RGB", (W, H * 2 + gap), (0, 0, 0))
    sheet.paste(a, (0, 0))
    sheet.paste(b, (0, H + gap))
    sheet.save("output/library-stacks.png")
    a.save("output/library-stacks-level1.png")
    b.save("output/library-stacks-level2.png")
    print("written output/library-stacks.png")


if __name__ == "__main__":
    main()
