#!/usr/bin/env python3
"""画出唤醒热区在视野中的位置，以及徽标的实际外观。

左栏：垂直剖面（+90°~−90°），标出
  - **对称轴 = 当前内容正前方**：影院取银幕中心（≈+7.1°），球面取 0°
  - 基准水平线（0°）与银幕上下缘，用来看清两者的 7.1° 差
  - UI 画布覆盖范围、上下热区
右栏：悬停提示的实际外观（一条细线，0% / 62% 两个状态）+ 新旧宽度对照。

数值全部取自 Kotlin 源码常量，不手写。
"""

import math
import re
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/gazereel/vr/render/VrUiLayer.kt"
MESH = ROOT / "app/src/main/java/com/gazereel/vr/render/ProjectionMesh.kt"
OUT = ROOT.parent / "output/design/wake-zone.png"

BG = (14, 15, 18)
PANEL = (22, 24, 29)
INK = (232, 236, 244)
DIM = (138, 148, 164)
ACCENT = (120, 224, 176)
CANVAS = (58, 64, 78)
SCREEN = (196, 158, 96)


def grab(pattern, text, name):
    m = re.search(pattern, text)
    if not m:
        raise SystemExit(f"抓不到 {name}")
    return float(m.group(1))


def font(size):
    for p in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()


src = UI.read_text(encoding="utf-8")
mesh = MESH.read_text(encoding="utf-8")

BASE_AXIS = grab(r"const val WAKE_AXIS_DEG = (-?[\d.]+)f", src, "AXIS")
INNER = grab(r"const val WAKE_INNER_DEG = ([\d.]+)f", src, "INNER")
OUTER = grab(r"const val WAKE_OUTER_DEG = ([\d.]+)f", src, "OUTER")
SPAN = (INNER + OUTER) / 2
HALF = (OUTER - INNER) / 2
HALFW = grab(r"const val WAKE_HALF_WIDTH_DEG = ([\d.]+)f", src, "HALF_WIDTH")
LINE_PX = grab(r"const val WAKE_LINE_PX = ([\d.]+)f", src, "WAKE_LINE_PX")
TEX_W = int(grab(r"const val WAKE_W = (\d+)", src, "WAKE_W"))
TEX_H = int(grab(r"const val WAKE_H = (\d+)", src, "WAKE_H"))
QUAD_H = grab(r"const val WAKE_QUAD_H = ([\d.]+)f", src, "WAKE_QUAD_H")
# 细线在视野里的实际张角：纹理里的线厚占比 × quad 的垂直张角
TEX_LINE_DEG = LINE_PX / TEX_H * math.degrees(2 * math.atan((QUAD_H / 2) / 2.0))
PITCH = -math.degrees(grab(r"CONSOLE_DROP_RAD = ([\d.]+)f", src, "DROP"))
PANEL_H = grab(r"val h = ([\d.]+)f \* k", src, "面板高")

# 影院银幕中心 = 用户认定的「当前正前方」；与 Kotlin 的 screenCenterY(100) 同式。
SC_D = grab(r"const val SCREEN_DISTANCE = ([\d.]+)f", mesh, "SCREEN_DISTANCE")
SC_W = grab(r"const val SCREEN_WIDTH = ([\d.]+)f", mesh, "SCREEN_WIDTH")
FLOOR_Y = grab(r"const val ROOM_FLOOR_Y = (-?[\d.]+)f", mesh, "ROOM_FLOOR_Y")
SKIRT = grab(r"private const val SCREEN_SKIRT = ([\d.]+)f", mesh, "SCREEN_SKIRT")
SC_H = SC_W * 9 / 16
SC_CY = FLOOR_Y + SC_H / 2 + SKIRT
SC_DEG = math.degrees(math.atan2(SC_CY, SC_D))
SC_HALF = math.degrees(math.atan((SC_H / 2) / SC_D))
AXIS = SC_DEG
UP, DOWN = AXIS + SPAN, AXIS - SPAN

half_h = math.degrees(math.atan((PANEL_H / 2) / 2.0))
CANVAS_HI = PITCH + half_h
CANVAS_LO = PITCH - half_h

W_IMG, H_IMG = 1180, 742
# 20:1x 热区外移到 ±75°~81°，剖面必须装得下 ±90°，否则两条带子画到图外。
PX_PER_DEG = 3.1
TOP_DEG, BOT_DEG = 90.0, -90.0
PROFILE_H = (TOP_DEG - BOT_DEG) * PX_PER_DEG
TOP_Y = 112

img = Image.new("RGB", (W_IMG, H_IMG), BG)
d = ImageDraw.Draw(img)


def py(deg):
    return TOP_Y + (TOP_DEG - deg) * PX_PER_DEG


# ---------------- 左栏 ----------------
LX = 62
LW = 505
BX0, BX1 = LX + 96, LX + 250

d.rectangle([LX, TOP_Y, LX + LW, TOP_Y + PROFILE_H], fill=PANEL)

# 画布覆盖（灰带）
d.rectangle([LX, py(CANVAS_HI), LX + LW, py(CANVAS_LO)], fill=(34, 38, 47),
            outline=CANVAS, width=1)

# 影院银幕上下缘（暖色带，只描边不填色，避免压住画布带）
d.rectangle([LX, py(SC_DEG + SC_HALF), LX + LW, py(SC_DEG - SC_HALF)],
            outline=SCREEN, width=2)

# 刻度
for deg in range(-90, 91, 15):
    d.line([LX, py(deg), LX + LW, py(deg)], fill=(30, 33, 40), width=1)
    d.text((LX - 40, py(deg) - 8), f"{deg:+d}°", font=font(12), fill=DIM)

# 影院银幕中心：虚线
y_sc = py(SC_DEG)
for x in range(LX, LX + LW, 14):
    d.line([x, y_sc, x + 7, y_sc], fill=(150, 122, 74), width=2)
d.text((LX + 8, y_sc - 24),
       f"影院银幕中心 {SC_DEG:+.1f}° = 当前正前方", font=font(12), fill=SCREEN,
       anchor="la", stroke_width=3, stroke_fill=BG)

# 基准水平线：球面/鱼眼的对称轴，影院里只作参照
y_base = py(BASE_AXIS)
d.line([LX, y_base, LX + LW, y_base], fill=(92, 100, 116), width=2)
d.text((LX + 8, y_base + 8), f"基准水平线 {BASE_AXIS:+.0f}°（球面/鱼眼的轴）",
       font=font(12), fill=(132, 142, 160), stroke_width=3, stroke_fill=BG)

y_axis = py(AXIS)
d.line([LX, y_axis, LX + LW, y_axis], fill=(198, 210, 228), width=3)
d.text((LX + LW - 8, y_axis - 26), f"对称轴：当前内容正前方 {AXIS:+.1f}°",
       font=font(13), fill=(214, 224, 238), anchor="ra", stroke_width=3, stroke_fill=BG)

# 画布/银幕说明
d.text((LX + 8, py(CANVAS_LO) - 22),
       f"UI 画布只覆盖 {CANVAS_HI:+.1f}° ~ {CANVAS_LO:+.1f}°（在它之外画不出来）",
       font=font(12), fill=(120, 130, 148))

# 热区
for center, label in ((UP, "上区"), (DOWN, "下区")):
    hi, lo = center + HALF, center - HALF
    y0, y1 = py(hi), py(lo)
    d.rectangle([BX0, y0, BX1, y1], outline=ACCENT, width=2)
    d.ellipse([BX0 + 10, (y0 + y1) / 2 - 5, BX0 + 20, (y0 + y1) / 2 + 5], fill=ACCENT)
    d.text((BX1 + 12, (y0 + y1) / 2 - 24), f"{label} {lo:+.0f}° ~ {hi:+.0f}°",
           font=font(15), fill=ACCENT)
    d.text((BX1 + 12, (y0 + y1) / 2 + 1),
           f"中心距轴 {center - AXIS:+.0f}°", font=font(12), fill=DIM)

# 对称标注：上区中心 → 轴 → 下区中心
cxm = LX + 42
d.line([cxm, py(UP), cxm, y_axis], fill=(92, 168, 138), width=2)
d.line([cxm, y_axis, cxm, py(DOWN)], fill=(92, 168, 138), width=2)
for yy in (py(UP), y_axis, py(DOWN)):
    d.ellipse([cxm - 4, yy - 4, cxm + 4, yy + 4], fill=(92, 168, 138))
d.text((cxm + 8, (py(UP) + y_axis) / 2 - 8), f"{SPAN:.0f}°", font=font(12), fill=(124, 196, 166))
d.text((cxm + 8, (y_axis + py(DOWN)) / 2 - 8), f"{SPAN:.0f}°", font=font(12), fill=(124, 196, 166))

d.text((LX, 40), "视野垂直剖面（100% 缩放）", font=font(17), fill=INK)
d.text((LX, 66), "影院以银幕中心为正前方，不是平视水平线 —— 两者差 "
                 f"{abs(SC_DEG - BASE_AXIS):.1f}°", font=font(13), fill=DIM)

# ---------------- 右栏 ----------------
RX = 620
PXD = 12.0                      # 像素/度，用于画细线的真实比例

d.text((RX, 40), "悬停提示：一条细线（离开即零像素）", font=font(17), fill=INK)

line_w = HALFW * 2 * PXD
line_h = max(4.0, (TEX_LINE_DEG) * PXD)
for i, prog in enumerate((0.0, 0.62)):
    oy = TOP_Y + 26 + i * 96
    d.rounded_rectangle([RX - 8, oy - 24, RX + line_w + 54, oy + 40], radius=8,
                        fill=(18, 19, 23))
    # 底线
    d.rounded_rectangle([RX, oy - line_h / 2, RX + line_w, oy + line_h / 2],
                        radius=line_h / 2, fill=(150, 156, 168))
    # 进度
    if prog > 0:
        d.rounded_rectangle([RX, oy - line_h / 2, RX + line_w * prog, oy + line_h / 2],
                            radius=line_h / 2, fill=ACCENT)
    d.text((RX + line_w + 14, oy), f"{int(prog*100)}%", font=font(14), fill=DIM, anchor="lm")

# 热区形状对照：20:1x 横向从 6.4° 放宽到 25.2°（纵向 6° 不动）
cy = TOP_Y + 26 + 2 * 96 + 12
d.text((RX, cy - 26), "热区形状：横向 × 纵向（同一比例）", font=font(15), fill=INK)

OLD_W, OLD_H = 6.4, 6.0       # 上一版（19:1x）：6.4° × 6.0°
S = 11.0                       # 形状对照专用比例（PXD 是给细线放大的）
old_box = (OLD_W * S, OLD_H * S)
new_box = (HALFW * 2 * S, HALF * 2 * S)
sh_y = cy + 6
for k, ((bw, bh), col, label) in enumerate((
    (old_box, (120, 126, 140), f"旧 {OLD_W:.1f}° × {OLD_H:.1f}°"),
    (new_box, ACCENT, f"新 {HALFW*2:.1f}° × {HALF*2:.1f}°"),
)):
    bx = RX + k * 190
    d.rectangle([bx, sh_y, bx + bw, sh_y + bh], outline=col, width=2, fill=(24, 26, 32))
    d.line([bx - 7, sh_y + bh / 2, bx + bw + 7, sh_y + bh / 2], fill=(96, 102, 116), width=1)
    d.text((bx, sh_y + bh + 8), label, font=font(13), fill=col)

notes = [
    f"• 轴 {AXIS:+.1f}°（当前内容正前方）：上 {UP:+.0f}° / 下 {DOWN:+.0f}°，各 {HALF*2:.1f}° 高。",
    "• 从 ±38~44° 外移到 ±75~81°：误触发交给位置挡，不再靠做小热区。",
    f"• 横向 6.4° → {HALFW*2:.1f}°：位置够偏，宽一点更好压中。",
    f"• 半高 {HALF:.1f}° = 抖动(±1.5°)的 {HALF/1.5:.1f} 倍，真去瞄仍压得准。",
    f"• 细线宽 == 命中宽 {HALFW*2:.1f}°，线厚 {TEX_LINE_DEG:.2f}°。",
    f"• 热区在画布外，细线走独立 quad，从当前正前方抬头/低头 {SPAN:.0f}° 正对着它。",
    "• 设置可关；关掉后连命中区都不注册。",
]
ny = sh_y + old_box[1] + 44
for line in notes:
    d.text((RX, ny), line, font=font(13), fill=DIM)
    ny += 26

img.save(OUT)
print(f"已生成 {OUT}")
print(f"  对称轴 {AXIS:+.2f}°（银幕中心）   基准水平线 {BASE_AXIS:+.1f}°   差 {abs(SC_DEG-BASE_AXIS):.2f}°")
print(f"  画布 {CANVAS_HI:+.1f}° ~ {CANVAS_LO:+.1f}°")
print(f"  上 {UP-HALF:+.0f}~{UP+HALF:+.0f}  下 {DOWN-HALF:+.0f}~{DOWN+HALF:+.0f}")
