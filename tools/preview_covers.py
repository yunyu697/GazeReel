"""按 PosterRepository.generateCover() 与 VrUiLayer.drawPosterCaption() 的实际坐标画对照图。

V2.19 的真机反馈是「片库的预览图真是难尽，也太敷衍了」。敷衍的不是颜色，
是信息：旧封面给所有 `VID_...` 都画一个首字母 V，八格一模一样。

这张图用来核对三件事（1024×512 画布，格子 232×145）：
1. 兜底封面里「日期 / 时刻 / 时长」三行不与格子边界、角标相撞；
2. 真画面底部那条身份压条，左侧时刻与右侧时长不会互相压住；
3. 同一批片子靠色相能不能分开——所以故意画 8 个连拍片段。
"""
import colorsys
import math
from PIL import Image, ImageDraw, ImageFont

W, H = 1024, 512
C_BORDER = (34, 54, 79)
C_ACCENT = (103, 232, 249)
C_TEXT = (234, 242, 250)
C_MUTED = (185, 198, 218)
C_POSTER = (23, 58, 94)
C_BG = (8, 17, 30)

CELL_W, CELL_H = 232, 145
CELL_LEFT = [24, 272, 520, 768]
CELL_TOP = [114, 266]

POSTER_W, POSTER_H = 320, 180


def font(size, bold=False):
    for name in (["msyhbd.ttc", "msyh.ttc"] if bold else ["msyh.ttc", "msyhbd.ttc"]):
        try:
            return ImageFont.truetype("C:/Windows/Fonts/" + name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def hash_hue(path):
    h = 0
    for ch in path:
        h = (h * 31 + ord(ch)) & 0xFFFFFFFF
    return (h % 360) / 360.0


def generate_cover(path, date, time_, footer):
    """对应 PosterRepository.generateCover()：色相散列 + 左亮边 + 三行字 + 播放三角。"""
    hue = hash_hue(path)
    top = tuple(int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.42, 0.30))
    bottom = tuple(int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.55, 0.11))
    img = Image.new("RGB", (POSTER_W, POSTER_H), bottom)
    d = ImageDraw.Draw(img)
    for y in range(POSTER_H):
        t = y / (POSTER_H - 1)
        d.line([(0, y), (POSTER_W, y)],
               fill=tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    edge = tuple(int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.55, 0.95))
    d.rectangle((0, 0, 4, POSTER_H), fill=edge)

    d.text((POSTER_W / 2, 64), date, font=font(20), fill=(200, 200, 200), anchor="mm")
    d.text((POSTER_W / 2, 110), time_, font=font(58, True), fill=(250, 250, 250), anchor="mm")
    if footer:
        d.text((POSTER_W / 2, 158), footer, font=font(18), fill=(190, 190, 190), anchor="mm")
    d.polygon([(POSTER_W - 30, POSTER_H - 34), (POSTER_W - 30, POSTER_H - 14),
               (POSTER_W - 13, POSTER_H - 24)], fill=(220, 220, 220))
    return img


def fake_frame(seed):
    """假装是抽出来的真画面：几块色块 + 一条地平线，只为看压条对比度。"""
    img = Image.new("RGB", (POSTER_W, POSTER_H), (30, 40, 52))
    d = ImageDraw.Draw(img)
    hue = (seed * 0.17) % 1.0
    sky = tuple(int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.45, 0.75))
    ground = tuple(int(c * 255) for c in colorsys.hsv_to_rgb(hue, 0.55, 0.25))
    d.rectangle((0, 0, POSTER_W, 110), fill=sky)
    d.rectangle((0, 110, POSTER_W, POSTER_H), fill=ground)
    d.ellipse((60 + seed * 12 % 160, 30, 140 + seed * 12 % 160, 90), fill=(255, 245, 220))
    return img


def draw_cell(img, slot, poster, caption, length, badge):
    """对应 VrUiLayer.cell()：铺满裁切 + 底部身份压条 + 右上角角标。"""
    x = CELL_LEFT[slot % 4]
    y = CELL_TOP[slot // 4]
    rect = (x, y, x + CELL_W, y + CELL_H)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle(rect, 14, fill=C_POSTER)

    # 铺满裁切
    scale = max(CELL_W / poster.width, CELL_H / poster.height)
    dw, dh = int(poster.width * scale), int(poster.height * scale)
    scaled = poster.resize((dw, dh), Image.LANCZOS)
    ox = (dw - CELL_W) // 2
    oy = (dh - CELL_H) // 2
    img.paste(scaled.crop((ox, oy, ox + CELL_W, oy + CELL_H)), (x, y))

    if caption is not None:
        band_top = y + CELL_H - 42
        overlay = Image.new("RGBA", (CELL_W, 42), (0, 0, 0, 0))
        od = ImageDraw.Draw(overlay)
        for i in range(42):
            od.line([(0, i), (CELL_W, i)], fill=(0, 0, 0, int(196 * i / 41)))
        img.paste(Image.alpha_composite(
            img.crop((x, band_top, x + CELL_W, y + CELL_H)).convert("RGBA"), overlay
        ).convert("RGB"), (x, band_top))
        d = ImageDraw.Draw(img)
        d.text((x + 12, y + CELL_H - 12), caption, font=font(17), fill=C_TEXT, anchor="ls")
        if length:
            d.text((x + CELL_W - 12, y + CELL_H - 12), length, font=font(16),
                   fill=C_ACCENT, anchor="rs")

    if badge:
        f = font(17)
        w = d.textlength(badge, font=f) + 18
        d.rounded_rectangle((x + CELL_W - 10 - w, y + 4, x + CELL_W - 10, y + 28),
                            12, fill=(18, 40, 63))
        d.text((x + CELL_W - 19, y + 23), badge, font=f, fill=C_ACCENT, anchor="rs")

    d.rounded_rectangle(rect, 14, outline=C_BORDER, width=2)


def main():
    img = Image.new("RGB", (W, H), C_BG)
    d = ImageDraw.Draw(img)
    d.text((24, 40), "片库预览图 · V2.19 核对", font=font(26, True), fill=C_TEXT)
    d.text((24, 78), "上排：抽到真画面 + 底部身份压条    下排：抽不到时生成的带身份封面",
           font=font(16), fill=C_MUTED)

    # 上排 4 格：真画面 + 压条（含一个带「续播」角标）
    for slot in range(4):
        poster = fake_frame(slot + 1)
        draw_cell(img, slot, poster, "7月12日 14:30", "00:42",
                  badge="续 12:34" if slot == 1 else None)

    # 下排 4 格：兜底封面
    times = [("7月12日", "14:30", "00:42 · 1920×1080"),
             ("7月12日", "14:30", "00:18 · 1920×1080"),
             ("7月13日", "09:02", "01:05 · 3840×2160"),
             ("7月13日", "09:07", "00:09 · 3840×2160")]
    for slot, (date, t, footer) in enumerate(times):
        cover = generate_cover(f"VID_2026071{2 + slot // 2}_1430{slot:02d}.mp4", date, t, footer)
        draw_cell(img, 4 + slot, cover, None, None, None)

    d.text((24, 424), "核对点：① 三行字不越界；② 压条左侧时刻与右侧时长不重叠；"
                      "③ 连拍片段靠色相分得开；④ 角标与压条分处上下两带",
           font=font(16), fill=C_MUTED)
    d.text((24, 452), "格子 232×145 与 VrUiLayer.CELL_W/CELL_H 一致；封面 320×180 与 "
                      "PosterRepository.POSTER_W/H 一致", font=font(16), fill=C_MUTED)

    img.save("output/library-covers.png")
    print("wrote output/library-covers.png")


if __name__ == "__main__":
    main()
