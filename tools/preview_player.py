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
    # Android Canvas.drawText 的 y 是 baseline，不是文字中心；对照图必须用同样的锚点，
    # 否则看起来「字压到轨道上」，真机上其实还留着十几 px。
    d.text(((rect[0] + rect[2]) / 2, (rect[1] + rect[3]) / 2 + 9), label,
           font=f, fill=C_TEXT, anchor="ms")


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
    """姿态确认卡。

    V2.19c 起它整块摆在候选方向的**视线正前方**（`panelSpec` 把平面挪过去），
    所以这张图的画布正中就是准星所在。版式因此只有一条硬要求：
    **准星静止时不能压在任何一个按钮上**——和片库 2×4 格子同一条结构规则。
    图里把准星画出来，就是为了用眼睛确认它落在 496–528 那道缝里。

    不铺压暗层：面板只有 48.5°×25.4°，压暗盖不住整个视野，
    会在中间留一块带直角的暗色矩形，比不压更难看。
    """
    d = ImageDraw.Draw(img)

    card = (140, 92, 884, 420)
    d.rounded_rectangle(card, 26, fill=(11, 22, 38), outline=C_ACCENT, width=3)
    d.text((W / 2, 158), "正前方挪到这里？", font=font(30, True), fill=C_TEXT, anchor="mm")
    d.text((W / 2, 200), "你现在抬头 58° · 偏左 12° · 转过来之后，视频和界面一起跟着你",
           font=font(21), fill=C_MUTED, anchor="mm")

    button(d, "挪到这里", (168, 236, 496, 350), highlighted=True)
    button(d, "不用了", (528, 236, 856, 350))
    d.text((332, 332), "0.8 秒转过去，只动这一次", font=font(18), fill=C_ACCENT, anchor="mm")
    d.text((692, 332), "保持现在的画面", font=font(18), fill=C_MUTED, anchor="mm")

    d.rounded_rectangle((168, 376, 856, 384), 4, fill=C_BORDER)
    d.rounded_rectangle((168, 376, 168 + 688 * 0.62, 384), 4, fill=C_ACCENT)
    d.text((856, 408), "按一下触发键也行 · 不按就走完收起", font=font(17),
           fill=C_MUTED, anchor="rs")

    # 准星（drawCursor）：静止在画布正中，必须落在 496–528 那道缝里
    d.ellipse((512 - 11, 256 - 11, 512 + 11, 256 + 11), outline=(234, 242, 250), width=3)
    d.ellipse((512 - 3, 256 - 3, 512 + 3, 256 + 3), fill=C_ACCENT)
    d.text((24, 60), "红圈 = 准星，静止在画布正中；必须落在两键之间的缝里",
           font=font(15), fill=(255, 120, 120), anchor="ls")


def draw_adjust(img, *, label, detail, steps, fraction, hint, tuning, coarse=True):
    """按 VrUiLayer.drawAdjustFeedback() 的实际坐标画一条调节条。

    V2.33 / B14 的三条版式硬要求，只有画出来才看得见：
    1. 卡片底边 252 必须停在画布正中 256 **之上**——瞳距要看着画面才调得准，
       视频正中不能压住（播放页 UI 平面本身还低 13°，实际落在视线下方 3°–13°）；
    2. `fraction = 0` 时轨道填充宽度必须是 0，音量静音不能「亮一格」；
    3. 按钮排要够大、互相不重叠，且都落在卡片里：普通音量/亮度 3 个
       （− / + / 完成，168×54），瞳距「边看边调」态 4 个（多切粗微，126×54）。

    文字锚点全部用 baseline（Android `Canvas.drawText` 语义），不是居中。
    """
    d = ImageDraw.Draw(img)
    card = (212, 48, 812, 252)
    d.rounded_rectangle(card, 22, fill=(14, 27, 44), outline=C_ACCENT, width=3)

    d.text((card[0] + 26, 92), label, font=font(28), fill=C_TEXT, anchor="ls")
    d.text((card[2] - 26, 92), detail, font=font(32), fill=C_ACCENT, anchor="rs")

    track = (card[0] + 26, 110, card[2] - 26, 134)
    d.rounded_rectangle(track, 12, fill=C_BORDER)
    filled = (track[2] - track[0]) * max(0.0, min(1.0, fraction))
    if filled > 0:
        d.rounded_rectangle((track[0], track[1], track[0] + filled, track[3]),
                            12, fill=C_ACCENT)
    if steps > 1:
        for i in range(1, steps):
            x = track[0] + (track[2] - track[0]) * i / steps
            d.line([(x, track[1] + 3), (x, track[3] - 3)], fill=(11, 22, 38), width=3)
    if steps == 0:
        d.ellipse((track[0] + filled - 9, 113, track[0] + filled + 9, 131),
                  fill=C_TEXT)
    d.text((W / 2, 164), hint, font=font(17), fill=C_MUTED, anchor="ms")

    show_mode = tuning
    count = 4 if show_mode else 3
    bw = 126 if show_mode else 168
    gap = 14
    x = W / 2 - (count * bw + (count - 1) * gap) / 2
    labels = ["−", "+"] + (["切微调" if coarse else "切粗调"] if show_mode else []) + ["完成"]
    for caption in labels:
        button(d, caption, (x, 182, x + bw, 236), highlighted=(caption.startswith("切")))
        x += bw + gap

    # 视频正中：卡片必须在它上方停住
    d.line([(0, 256), (W, 256)], fill=(255, 120, 120), width=1)
    d.text((8, 262), "画布正中 256 = 视频中心；调节条底边 252 不许越过",
           font=font(15), fill=(255, 120, 120), anchor="ls")


def draw_settings(img):
    """按 VrUiLayer.drawSettings() 的坐标画一遍，核新增底栏有没有压到第二排开关。"""
    d = ImageDraw.Draw(img)
    panel(d, (100, 20, 924, 500))
    d.text((W / 2, 62), "设置", font=font(30, True), fill=C_TEXT, anchor="ms")

    rows = ["亮度   标准", "音量   4 / 5", "瞳距 IPD   62.0 mm", "凝视时长   1200 ms"]
    for i, value in enumerate(rows):
        y = 84 + i * 48
        d.text((W / 2, y + 28), value, font=font(22), fill=C_TEXT, anchor="ms")
        button(d, "−", (160, y, 240, y + 44))
        button(d, "+", (784, y, 864, y + 44))

    toggles = ["自动连播 · 开", "自动淡出 · 开", "锁定视角 · 关",
               "字幕 · 开", "虚拟光标 · 开", "挪到这里"]
    for i, caption in enumerate(toggles):
        x = 110 + (i % 3) * 270
        y = 286 + (i // 3) * 48
        button(d, caption, (x, y, x + 250, y + 46), highlighted=(i in (0, 1, 3, 4)))

    d.text((W / 2, 416), "瞳距要戴着看画面才调得准 · 边看边调不挡视频",
           font=font(17), fill=C_MUTED, anchor="ms")
    button(d, "清除格式记忆", (110, 438, 380, 490))
    button(d, "瞳距 · 边看边调", (392, 438, 632, 490), highlighted=True)
    button(d, "返回", (644, 438, 884, 490))
    # 第二排开关底边 380，说明基线 416，按钮顶 438：三段必须不重叠
    d.line([(100, 380), (924, 380)], fill=(255, 120, 120), width=1)
    d.text((104, 384), "第二排开关底边 380", font=font(14), fill=(255, 120, 120), anchor="ls")


def draw_adjust_sheet():
    """三种真机状态并排：静音 0 填充 / 音量满档 / 瞳距边看边调。"""
    cases = [
        dict(label="音量", detail="静音", steps=4, fraction=0.0,
             hint="音量键 ± · 或凝视下方按钮 · 准星停在条上不会消失", tuning=False),
        dict(label="音量", detail="5 / 5", steps=4, fraction=1.0,
             hint="音量键 ± · 或凝视下方按钮 · 准星停在条上不会消失", tuning=False),
        dict(label="瞳距 · 边看边调", detail="62.0 mm", steps=0, fraction=0.48,
             hint="粗调 2.0mm · 音量键 ± · 单击切粗微 · 长按完成", tuning=True),
    ]
    sheet = Image.new("RGB", (W, H * len(cases)), C_BG)
    for i, case in enumerate(cases):
        band = Image.new("RGB", (W, H), C_BG)
        draw_adjust(band, **case)
        sheet.paste(band, (0, i * H))
    return sheet


def main():
    a = Image.new("RGB", (W, H), C_BG)
    draw_console(a)
    a.save("output/player-console.png")

    b = Image.new("RGB", (W, H), C_BG)
    draw_posture(b)
    b.save("output/posture-prompt.png")

    c = draw_adjust_sheet()
    c.save("output/adjust-bars.png")

    s = Image.new("RGB", (W, H), C_BG)
    draw_settings(s)
    s.save("output/settings-ipd.png")
    print("wrote output/player-console.png, output/posture-prompt.png, "
          "output/adjust-bars.png, output/settings-ipd.png")


if __name__ == "__main__":
    main()
