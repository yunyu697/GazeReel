"""影院前墙对照图：旧（幕穿地）vs 新（幕挂墙）。

只画**前墙正视**——用户的问题就是「银幕像立在地上」，那是一眼能看出来的东西，
不需要透视渲染。数值全部来自 Kotlin 常量，改代码后重跑即可。

用法：python tools/preview_cinema_frame.py
输出：../output/design/cinema-wall-mount.png
"""

import math
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT.parent / "output/design/cinema-wall-mount.png"
MESH = ROOT / "app/src/main/java/com/gazereel/vr/render/ProjectionMesh.kt"
ROOM = ROOT / "app/src/main/java/com/gazereel/vr/render/SceneRoom.kt"

FONTS = ["C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/msyhbd.ttc"]

S = 74          # 像素/米（实际比例由面板尺寸反推）
PANEL_W, PANEL_H = 660, 440
PAD = 18
BG = (16, 16, 19)
WALL = (34, 36, 42)
WALL_LIT = (44, 47, 55)
VELVET = (58, 22, 26)
VELVET_HI = (86, 34, 40)
SCREEN_DARK = (9, 9, 11)
FLOOR = (70, 56, 40)
INK = (226, 226, 232)
DIM = (140, 140, 150)
ACCENT = (120, 190, 255)


def consts(path: Path) -> dict:
    found = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("//")[0]
        mm = re.search(r"const\s+val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*\w+)?\s*=\s*(.+)$", line)
        if not mm:
            continue
        name, expr = mm.group(1), mm.group(2).strip().rstrip(",")
        if not re.fullmatch(r"[0-9fFlL.\s+\-*/()A-Z_]+", expr):
            continue
        py = re.sub(r"(\d)[fF]\b", r"\1", expr)
        try:
            found[name] = float(eval(py, {"__builtins__": {}}, dict(found)))  # noqa: S307
        except Exception:  # noqa: BLE001
            continue
    return found


def font(size: int):
    for p in FONTS:
        if Path(p).exists():
            try:
                return ImageFont.truetype(p, size)
            except Exception:  # noqa: BLE001
                continue
    return ImageFont.load_default()


def draw_panel(d, ox: int, oy: int, *, old: bool, m: dict, r: dict, title: str):
    """画一面前墙的正视。ox/oy 是该面板左上角。"""
    w, h = m["SCREEN_WIDTH"], m["SCREEN_HEIGHT"]
    cy = 0.0 if old else m["SCREEN_CENTER_Y"]
    floor = r["FLOOR_Y"]
    ceil = r["CEIL_Y"]
    half_w, half_h = w / 2, h / 2
    s_bot, s_top = cy - half_h, cy + half_h

    frame_w = r.get("FRAME_W", 0.28)
    frame_b = 0.28 if old else r.get("FRAME_BOTTOM", 0.10)
    curtain = 0.0 if old else r.get("SIDE_CURTAIN_W", 0.0)

    # 世界坐标 → 面板像素。y=0 居中，纵向范围固定取 [-1.60, 3.00]
    # 比整厅略窄：厅高 4.15 米而墙裙只有 0.35 米，按整厅比例画的话墙裙只有 8%，
    # 看上去仍然「没差别」——这个对照图失去意义。
    y_hi, y_lo = 3.00, -1.60
    sy = PANEL_H / (y_hi - y_lo)
    sx = sy

    def px(x):
        return ox + PANEL_W / 2 + x * sx

    def py(y):
        return oy + (y_hi - y) * sy

    def box(xa, xb, ya, yb):
        """PIL 要求左上-右下，世界坐标的 y 是反的，这里统一排序。"""
        return [
            min(px(xa), px(xb)), min(py(ya), py(yb)),
            max(px(xa), px(xb)), max(py(ya), py(yb)),
        ]

    # 墙面
    d.rectangle(box(-3.9, 3.9, floor, ceil), fill=WALL)
    # 地板
    d.rectangle(box(-3.9, 3.9, y_lo, floor), fill=FLOOR)

    # 洞口（银幕区域）先铺黑
    d.rectangle(box(-half_w, half_w, s_bot, s_top), fill=SCREEN_DARK)

    # 幕框：上 / 左 / 右 / 下（下边窄）
    fL, fR = -half_w - frame_w, half_w + frame_w
    fT = s_top + frame_w
    fB = s_bot - frame_b
    d.rectangle(box(fL, fR, s_top, fT), fill=VELVET)
    d.rectangle(box(fL, -half_w, fB, fT), fill=VELVET)
    d.rectangle(box(half_w, fR, fB, fT), fill=VELVET)
    d.rectangle(box(fL, fR, fB, s_bot), fill=VELVET)

    if curtain > 0:
        # 侧幕：落地垂坠
        d.rectangle(box(fL - curtain, fL, floor, fT), fill=VELVET_HI)
        d.rectangle(box(fR, fR + curtain, floor, fT), fill=VELVET_HI)
        # 垂褶
        for i in range(5):
            xx = fL - curtain + curtain * (i + 0.5) / 5
            d.line([px(xx), py(fT), px(xx), py(floor)], fill=(44, 16, 20), width=2)
            xx = fR + curtain * (i + 0.5) / 5
            d.line([px(xx), py(fT), px(xx), py(floor)], fill=(44, 16, 20), width=2)
        # 幕头
        d.rectangle(box(fL - curtain, fR + curtain, fT, ceil), fill=VELVET_HI)
        for i in range(9):
            xx = fL - curtain + (fR + curtain - (fL - curtain)) * (i + 0.5) / 9
            d.line([px(xx), py(ceil), px(xx), py(fT)], fill=(44, 16, 20), width=2)

    # 地板线
    d.line([px(-3.9), py(floor), px(3.9), py(floor)], fill=(96, 78, 52), width=2)
    # 天花线
    d.line([px(-3.9), py(ceil), px(3.9), py(ceil)], fill=(58, 60, 68), width=2)

    # 墙裙区间：用青色括线标在银幕左侧的墙面上（幕框下沿 → 地板）
    skirt = fB - floor
    if skirt > 0.02:
        d.line([px(-3.40), py(fB), px(-3.40), py(floor)], fill=ACCENT, width=3)
        d.line([px(-3.40), py(fB), px(-3.15), py(fB)], fill=ACCENT, width=2)
        d.line([px(-3.40), py(floor), px(-3.15), py(floor)], fill=ACCENT, width=2)

    # 标题
    d.text((ox + PAD, oy + 8), title, font=font(19), fill=INK, anchor="la")
    return skirt


def main() -> int:
    m = consts(MESH)
    r = consts(ROOM)
    if not m or not r:
        print("常量解析失败")
        return 1

    img = Image.new("RGB", (PANEL_W * 2 + PAD * 3, PANEL_H + PAD * 2 + 96), BG)
    d = ImageDraw.Draw(img)

    d.text((PAD, 12), "影院前墙正视 · 1:1 比例（数值直接读自 Kotlin 常量）",
           font=font(17), fill=INK, anchor="la")

    top = PAD + 30
    k_old = draw_panel(d, PAD, top, old=True, m=m, r=r, title="OLD · 幕像插在地上")
    k_new = draw_panel(d, PAD * 2 + PANEL_W, top, old=False, m=m, r=r, title="NEW · 幕挂在墙上")

    cap_y = top + PANEL_H + 12
    old_below = (0 - m["SCREEN_HEIGHT"] / 2) - r["FLOOR_Y"]
    d.text((PAD, cap_y),
           f"底边在地板之下 {abs(old_below):.2f} m —— 没有墙裙，幕是插进地里的",
           font=font(15), fill=(240, 120, 120), anchor="la")
    d.text((PAD, cap_y + 24),
           "只有一圈 0.28 m 的框，框外直接是墙 → 读作「一块立牌」",
           font=font(14), fill=DIM, anchor="la")

    d.text((PAD * 2 + PANEL_W, cap_y),
           f"底边离地 0.35 m，青色括线是 {k_new:.2f} m 墙裙",
           font=font(15), fill=ACCENT, anchor="la")
    d.text((PAD * 2 + PANEL_W, cap_y + 24),
           "＋两侧落地垂幕 ＋顶部幕头 → 读作「从上面吊下来的幕」",
           font=font(14), fill=DIM, anchor="la")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"已输出 {OUT}  ({img.width}×{img.height})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
