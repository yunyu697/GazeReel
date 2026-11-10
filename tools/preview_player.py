"""按 VrUiLayer.drawPlayer() 与 drawPosturePrompt() 的实际坐标画对照图。

V2.19 加了两处新东西，都是「只有画出来才看得见」的版式风险：
1. 控制台底栏从 6 个按钮加到 7 个（多了「正前方」），间距必须重算，
   否则最后一个会越出面板右边界（面板 40..984）；
2. 姿态确认卡只有两个目标，但卡片、标题、说明、进度细条四层叠在一起，
   字容易压到按钮或越出卡片。
"""
from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 512

C_PANEL = (14, 27, 44)
C_PANEL_HI = (18, 40, 63)
C_BORDER = (34, 54, 79)
C_ACCENT = (103, 232, 249)
C_TEXT = (234, 242, 250)
C_MUTED = (185, 198, 218)
C_BG = (8, 17, 30)


def font(size, bold=False):
    for name in (["msyhbd.ttc", "msyh.ttc"] if bold else ["msyh.ttc", "msyhbd.ttc"]):
        try:
            return ImageFont.truetype("C:/Windows/Fonts/" + name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def panel(d, rect):
    d.rounded_rectangle(rect, 24, fill=(11, 22, 38), outline=C_BORDER, width=2)


def button(d, label, rect, highlighted=False):
    d.rounded_rectangle(rect, 14, fill=C_PANEL_HI if highlighted else C_PANEL,
                        outline=C_ACCENT if highlighted else C_BORDER,
                        width=3 if highlighted else 2)
    f = font(25)
    tw = d.textlength(label, font=f)
    limit = rect[2] - rect[0] - 20
    if tw > limit:
        f = font(max(17, int(25 * limit / tw)))
    d.text(((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2 + 9), label,
           font=f, fill=C_TEXT, anchor="mm")


def draw_console(img):
    d = ImageDraw.Draw(img)
    panel(d, (40, 300, 984, 500))
    d.text((W / 2, 288), "自动识别 · 360° 全景 · 左右 3D", font=font(20),
           fill=C_ACCENT, anchor="mm")

    left, seg = 80, 86.4
    d.rounded_rectangle((left, 322, left + seg * 10, 344), 8, fill=C_BORDER)
    d.rounded_rectangle((left, 322, left + seg * 10 * 0.42, 344), 8, fill=C_ACCENT)

    for i, caption in enumerate(["上一部", "-60s", "暂停", "+60s", "下一部"]):
        cx = 150 + i * 180
        button(d, caption, (cx - 70, 368, cx + 70, 436), highlighted=(i == 2))

    quick = ["片库", "正前方", "格式", "亮度", "音量", "设置", "退出"]
    quick_w = (904 - 6 * 8) / 7
    for i, caption in enumerate(quick):
        qx = 60 + i * (quick_w + 8)
        button(d, caption, (qx, 448, qx + quick_w, 490))
    # 越界标记：最后一个按钮的右边界必须 ≤ 984
    d.line([(984, 440), (984, 496)], fill=(255, 120, 120), width=1)
    d.text((980, 470), "面板右界 984", font=font(14), fill=(255, 120, 120), anchor="rs")


def draw_posture(img):
    d = ImageDraw.Draw(img)
    overlay = Image.new("RGBA", (W, H), (6, 14, 24, 166))
    img.paste(Image.alpha_composite(img.convert("RGBA"), overlay).convert("RGB"), (0, 0))
    d = ImageDraw.Draw(img)

    card = (140, 92, 884, 420)
    d.rounded_rectangle(card, 26, fill=(11, 22, 38), outline=C_ACCENT, width=3)
    d.text((W / 2, 158), "把正前方挪到这里？", font=font(30, True), fill=C_TEXT, anchor="mm")
    d.text((W / 2, 200), "你现在抬头约 58° · 画面会转到你看的方向，视频和界面一起转",
           font=font(21), fill=C_MUTED, anchor="mm")

    button(d, "挪到这里", (168, 236, 528, 350), highlighted=True)
    button(d, "不用了", (552, 236, 856, 350))
    d.text((348, 332), "0.8 秒转过去，只动这一次", font=font(18), fill=C_ACCENT, anchor="mm")
    d.text((704, 332), "保持现在的画面", font=font(18), fill=C_MUTED, anchor="mm")

    d.rounded_rectangle((168, 376, 856, 384), 4, fill=C_BORDER)
    d.rounded_rectangle((168, 376, 168 + 688 * 0.62, 384), 4, fill=C_ACCENT)
    d.text((856, 408), "不选就保持原样 · 这条走完就收起", font=font(17),
           fill=C_MUTED, anchor="rs")


def main():
    a = Image.new("RGB", (W, H), C_BG)
    draw_console(a)
    a.save("output/player-console.png")

    b = Image.new("RGB", (W, H), C_BG)
    draw_posture(b)
    b.save("output/posture-prompt.png")
    print("wrote output/player-console.png, output/posture-prompt.png")


if __name__ == "__main__":
    main()
