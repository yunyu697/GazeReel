"""按 VrUiLayer.drawLibrary() 的实际坐标画一张 1024x512 的对照图，只用于核对几何。"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 512
img = Image.new("RGB", (W, H), (8, 17, 30))
d = ImageDraw.Draw(img)

C_PANEL = (14, 27, 44)
C_PANEL_HI = (18, 40, 63)
C_BORDER = (34, 54, 79)
C_ACCENT = (103, 232, 249)
C_TEXT = (234, 242, 250)
C_MUTED = (185, 198, 218)
C_POSTER = (23, 58, 94)

def font(size, bold=False):
    for name in (["msyhbd.ttc", "msyh.ttc"] if bold else ["msyh.ttc", "msyhbd.ttc"]):
        try:
            return ImageFont.truetype("C:/Windows/Fonts/" + name, size)
        except OSError:
            continue
    return ImageFont.load_default()

def panel(rect, r=24):
    d.rounded_rectangle(rect, r, fill=C_PANEL, outline=C_BORDER, width=2)

def button(rect, label, hi=False, sub=None):
    d.rounded_rectangle(rect, 14, fill=C_PANEL_HI if hi else C_PANEL,
                        outline=C_ACCENT if hi else C_BORDER, width=3 if hi else 2)
    f = font(25, True)
    d.text(((rect[0]+rect[2])/2, (rect[1]+rect[3])/2 + (-4 if sub else 9)),
           label, font=f, fill=C_TEXT, anchor="mm")

CELL_W, CELL_H = 212, 106
CELL_LEFT = [60, 286, 526, 752]
CELL_TOP = [138, 268]

def cell(idx, title, meta=None, badge=None):
    x, y = CELL_LEFT[idx % 4], CELL_TOP[idx // 4]
    r = (x, y, x + CELL_W, y + CELL_H)
    d.rounded_rectangle(r, 12, fill=C_POSTER, outline=C_BORDER, width=2)
    if meta:
        d.text((x + CELL_W / 2, y + CELL_H / 2 - 2), meta, font=font(17), fill=C_MUTED, anchor="mm")
    d.rectangle((x, y + CELL_H - 30, x + CELL_W, y + CELL_H), fill=(11, 22, 38))
    d.text((x + 10, y + CELL_H - 9), title, font=font(17), fill=C_TEXT, anchor="ls")
    if badge:
        fw = d.textlength(badge, font=font(15)) + 16
        d.rounded_rectangle((x + 8, y + 8, x + 8 + fw, y + 30), 11, fill=C_PANEL_HI)
        d.text((x + 16, y + 24), badge, font=font(15), fill=C_ACCENT, anchor="ls")

panel((40, 20, 984, 492))
d.text((66, 62), "片库 · 832 部", font=font(26, True), fill=C_TEXT, anchor="ls")
d.text((958, 62), "第 2 / 104 页", font=font(20), fill=C_MUTED, anchor="rs")

button((60, 84, 260, 126), "上一页")
button((764, 84, 964, 126), "下一页")
d.text((512, 112), "第 9–16 部 · 摇头翻页", font=font(19), fill=C_MUTED, anchor="ms")

titles = ["2026-07-12 14:30", "GOPR0231", "2026-06-28 09:12", "VID 20260701 1830",
          "2026-05-03 20:41", "PXL 20260702", "海边 第3段", "2026-07-12 15:02"]
metas = ["00:42 · 4096×2048", "12:04 · 3840×1920", "03:17 · 4096×2048", "00:58 · 1920×1080",
         "07:26 · 4096×2048", "01:09 · 1920×1080", "05:48 · 4096×2048", "00:31 · 4096×2048"]
for i, t in enumerate(titles):
    cell(i, t, metas[i], "续 01:24" if i in (1, 5) else None)

button((60, 388, 300, 442), "设置")
button((724, 388, 964, 442), "退出")
d.text((512, 415), "凝视一格即播放 · 摇头翻页", font=font(19), fill=C_MUTED, anchor="ms")

# 画布正中：验证十字缝
d.line((512, 0, 512, H), fill=(255, 80, 80), width=1)
d.line((0, 256, W, 256), fill=(255, 80, 80), width=1)
d.ellipse((512 - 12, 256 - 12, 512 + 12, 256 + 12), outline=(255, 255, 255), width=3)

img.save("output/library-layout-check.png")
print("saved")
