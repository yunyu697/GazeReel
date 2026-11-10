"""按 VrUiLayer.drawCabinets() 的 V2.18 实际坐标画柜子层。

核对三件事：
1. 6 个柜子（含「全部片库」）在 3 列 × 2 行里排得开、不越出面板；
2. 名字两行 + 「共享 · N 部」那一行不会顶出卡片下边界；
3. 底栏「← 回片库」与右侧提示不会压在一起。
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

CELL_H = 145


def font(size, bold=False):
    for name in (["msyhbd.ttc", "msyh.ttc"] if bold else ["msyh.ttc", "msyhbd.ttc"]):
        try:
            return ImageFont.truetype("C:/Windows/Fonts/" + name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def wrap_two(text, f, max_width):
    lines, cur = [], ""
    for ch in text:
        if f.getlength(cur + ch) <= max_width:
            cur += ch
        else:
            lines.append(cur)
            cur = ch
            if len(lines) == 2:
                break
    if cur and len(lines) < 2:
        lines.append(cur)
    return lines[:2]


def card(d, rect, label, detail, selected):
    left, top, right, bottom = rect
    d.rounded_rectangle(rect, radius=14, fill=C_POSTER)
    d.rounded_rectangle(
        (left + 8, top + 12, left + 14, bottom - 12), radius=3,
        fill=C_ACCENT if selected else C_MUTED
    )
    d.rounded_rectangle(
        rect, radius=14, outline=C_ACCENT if selected else C_BORDER,
        width=3 if selected else 2
    )
    f = font(28, bold=True)
    lines = wrap_two(label, f, (right - left) - 52)
    cy = (top + bottom) / 2
    base = cy - 6 - (len(lines) - 1) * 16
    for i, line in enumerate(lines):
        w = f.getlength(line)
        d.text(((left + right) / 2 + 4 - w / 2, base + i * 32 - 14), line,
               font=f, fill=C_TEXT)
    fs = font(20)
    w = fs.getlength(detail)
    d.text(((left + right) / 2 + 4 - w / 2, base + len(lines) * 32 - 6), detail,
           font=fs, fill=C_ACCENT if selected else C_MUTED)


def render(cabinets, path):
    img = Image.new("RGB", (W, H), C_BG)
    d = ImageDraw.Draw(img)
    d.rounded_rectangle((20, 14, 1004, 498), radius=20, fill=C_PANEL, outline=C_BORDER)

    d.text((44, 76 - 15), "换片库", font=font(30, bold=True), fill=C_TEXT)
    d.rounded_rectangle((912, 28, 1008, 94), radius=12, fill=C_POSTER, outline=C_BORDER)
    d.text((930, 50), "退出", font=font(18), fill=C_MUTED)

    gap, left0 = 18, 44
    cell_w = (1000 - left0 - gap * 2) / 3
    for slot, (label, detail) in enumerate(cabinets[:6]):
        left = left0 + (slot % 3) * (cell_w + gap)
        top = 114 if slot < 3 else 266
        card(d, (left, top, left + cell_w, top + CELL_H), label, detail, slot == 0)

    d.rounded_rectangle((24, 424, 208, 490), radius=12, fill=C_POSTER, outline=C_BORDER)
    d.text((52, 442), "← 回片库", font=font(22), fill=C_ACCENT)
    hint = "凝视一个柜子就切过去 · 每柜的片子不混在一起"
    fh = font(18)
    d.text((1000 - fh.getlength(hint), 448), hint, font=fh, fill=C_MUTED)

    img.save(path)
    print("saved", path)


if __name__ == "__main__":
    render([
        ("全部片库", "1234 部"),
        ("飞牛 NAS", "共享 · 832 部"),
        ("极空间", "共享 · 264 部"),
        ("客厅电脑", "共享 · 96 部"),
        ("这台手机", "本机 · 42 部"),
    ], "output/library-cabinets.png")
