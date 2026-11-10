#!/usr/bin/env python3
"""生成 GazeReel / 凝影 的 Android 启动图标资源。

源图：branding/gazereel-icon.svg（stereo-cyan-dark，96x96 viewBox，电光青）。
Android 不直接消费 SVG，本机也没有 SVG 渲染器，因此这里按源图的几何常量
用 Pillow 重画一遍：底板圆角、radial halo、镜片对角渐变描边、中心播放键。
所有密度都从同一张高分辨率母版缩放，保证各 dpi 观感一致。

产物：
  app/src/main/res/mipmap-*/ic_launcher.png            legacy 方形图标
  app/src/main/res/mipmap-*/ic_launcher_round.png      圆形图标
  app/src/main/res/mipmap-*/ic_launcher_foreground.png 自适应图标前景
  app/src/main/res/mipmap-anydpi-v26/*.xml             自适应图标声明
  branding/ic_launcher-playstore.png                   512 商店图
"""
from __future__ import annotations

import math
import shutil
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
RES = ROOT / "app" / "src" / "main" / "res"
BRANDING = ROOT / "branding"
SOURCE_SVG = Path(
    r"C:\Users\Tyler Zhang\WorkBuddy AI\2026-11-08-22-44-23"
    r"\vr-icons-svg\stereo-cyan-dark.svg"
)

# 源图配色（stereo-cyan-dark）
BG = (13, 21, 38)          # #0D1526
BORDER = (28, 41, 66)      # #1C2942
CYAN = (103, 232, 249)     # #67E8F9
BLUE = (37, 99, 235)       # #2563EB
BTN_TOP = (56, 189, 248)   # #38BDF8
WHITE = (255, 255, 255)

LEGACY_MASTER = 768        # 96 * 8 超采样
FG_MASTER = 864            # 108 * 8 超采样
FOREGROUND_SCALE = 0.78    # 108dp 画布里图形占 56dp，落在 66dp 可见圆内

DENSITIES = {
    "mdpi": 1.0,
    "hdpi": 1.5,
    "xhdpi": 2.0,
    "xxhdpi": 3.0,
    "xxxhdpi": 4.0,
}

ADAPTIVE_XML = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@mipmap/ic_launcher_foreground" />
</adaptive-icon>
"""


def diagonal_gradient(w: int, h: int, c0, c1) -> Image.Image:
    """x1=0%,y1=0% → x2=100%,y2=100% 的 objectBoundingBox 线性渐变。"""
    w, h = max(1, int(w)), max(1, int(h))
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        fy = y / max(1, h - 1)
        for x in range(w):
            t = 0.5 * (x / max(1, w - 1) + fy)
            px[x, y] = tuple(round(c0[i] + (c1[i] - c0[i]) * t) for i in range(3))
    return img


def radial_halo(size: int, center, radius: float, color, alpha: float) -> Image.Image:
    """源图 vr25-halo：radialGradient cx=50% cy=44% r=62%，cyan 0.20 → 0。"""
    small = 192
    s = small / 96.0
    cx, cy, r = center[0] * s, center[1] * s, radius * s
    data = bytearray()
    for y in range(small):
        for x in range(small):
            t = 1.0 - math.hypot(x - cx, y - cy) / r
            data.append(int(alpha * 255 * t) if t > 0 else 0)
    mask = Image.new("L", (small, small))
    mask.putdata(bytes(data))
    mask = mask.resize((size, size), Image.Resampling.LANCZOS)
    layer = Image.new("RGBA", (size, size), (*color, 0))
    layer.putalpha(mask)
    return layer


def draw_lens(img, P, S, rect, radius, stroke_w, grad, fill):
    """一片 VR 镜片：半透明填充 + 对角渐变圆角描边。"""
    x0, y0, x1, y1 = rect

    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(overlay).rounded_rectangle(
        (*P(x0, y0), *P(x1, y1)), radius=S(radius), fill=fill
    )
    img.alpha_composite(overlay)

    # SVG stroke 居中，Pillow 的 outline 向内：把盒子外扩半个线宽即可得到同一圈环带
    h = stroke_w / 2
    outer = (x0 - h, y0 - h, x1 + h, y1 + h)
    mask = Image.new("L", img.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (*P(outer[0], outer[1]), *P(outer[2], outer[3])),
        radius=S(radius + h), outline=255, width=S(stroke_w),
    )
    gx0, gy0 = P(outer[0], outer[1])
    gx1, gy1 = P(outer[2], outer[3])
    tile = Image.new("RGBA", img.size, (0, 0, 0, 0))
    tile.paste(
        diagonal_gradient(gx1 - gx0, gy1 - gy0, *grad).convert("RGBA"),
        (int(gx0), int(gy0)),
    )
    img.paste(tile, (0, 0), mask)


def draw_gradient_circle(img, P, cx, cy, r, c0, c1):
    """中央播放按钮的 vr25-btn 渐变圆。"""
    px0, py0 = P(cx - r, cy - r)
    px1, py1 = P(cx + r, cy + r)
    w, h = int(px1 - px0), int(py1 - py0)
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, h - 1), fill=255)
    tile = Image.new("RGBA", img.size, (0, 0, 0, 0))
    tile.paste(diagonal_gradient(w, h, c0, c1).convert("RGBA"), (int(px0), int(py0)), mask)
    img.alpha_composite(tile)


def render(size: int, mode: str) -> Image.Image:
    if mode == "legacy":
        sc, ox, oy = size / 96.0, 0.0, 0.0
    elif mode == "foreground":
        sc = FOREGROUND_SCALE * size / 108.0
        ox = oy = size / 2 - 48 * sc
    else:
        raise ValueError(mode)

    def P(x, y):
        return (x * sc + ox, y * sc + oy)

    def S(v):
        return max(1, int(round(v * sc)))

    if mode == "legacy":
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
        ImageDraw.Draw(img).rounded_rectangle(
            (*P(0, 0), *P(96, 96)), radius=S(22), fill=(*BG, 255)
        )
        halo = radial_halo(size, (48, 42.24), 59.52, CYAN, 0.20)
        halo.putalpha(ImageChops.multiply(halo.getchannel("A"), img.getchannel("A")))
        img.alpha_composite(halo)
        ImageDraw.Draw(img).rounded_rectangle(
            (*P(0.5, 0.5), *P(95, 95)),
            radius=S(21.5), outline=(*BORDER, 255), width=S(1),
        )
    else:
        img = Image.new("RGBA", (size, size), (0, 0, 0, 0))

    for x0 in (12, 52):
        draw_lens(
            img, P, S, (x0, 28, x0 + 32, 68),
            radius=11, stroke_w=3.2, grad=(CYAN, BLUE),
            fill=(*CYAN, 43),  # fill-opacity .17
        )

    # 两道镜片高光：M25 38 v20 / M71 38 v20，round linecap，opacity .34
    overlay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(overlay)
    for x in (25, 71):
        a, b = P(x, 38), P(x, 58)
        w = S(2.4)
        d.line([a, b], fill=(*CYAN, 87), width=w)
        r = w / 2
        d.ellipse((a[0] - r, a[1] - r, a[0] + r, a[1] + r), fill=(*CYAN, 87))
        d.ellipse((b[0] - r, b[1] - r, b[0] + r, b[1] + r), fill=(*CYAN, 87))
    img.alpha_composite(overlay)

    d = ImageDraw.Draw(img)
    d.ellipse((*P(33.5, 33.5), *P(62.5, 62.5)), fill=(*BG, 255))
    draw_gradient_circle(img, P, 48, 48, 12.5, BTN_TOP, BLUE)

    d = ImageDraw.Draw(img)
    pts = [P(44, 42), P(56, 48), P(44, 54)]
    d.polygon(pts, fill=(*WHITE, 255))
    d.line(pts + [pts[0]], fill=(*WHITE, 255), width=S(1.8), joint="curve")
    return img


def make_round(legacy: Image.Image) -> Image.Image:
    w = legacy.width
    mask = Image.new("L", (w, w), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w - 1, w - 1), fill=255)
    out = Image.new("RGBA", (w, w), (0, 0, 0, 0))
    out.paste(Image.new("RGBA", (w, w), (*BG, 255)), (0, 0), mask)
    out.alpha_composite(legacy)
    out.putalpha(ImageChops.multiply(out.getchannel("A"), mask))
    return out


def main():
    BRANDING.mkdir(parents=True, exist_ok=True)
    svg = BRANDING / "gazereel-icon.svg"
    if not svg.exists():
        shutil.copyfile(SOURCE_SVG, svg)

    legacy = render(LEGACY_MASTER, "legacy")
    foreground = render(FG_MASTER, "foreground")
    round_icon = make_round(legacy)

    count = 0
    for dpi, factor in DENSITIES.items():
        folder = RES / f"mipmap-{dpi}"
        folder.mkdir(parents=True, exist_ok=True)
        legacy.resize((round(48 * factor),) * 2, Image.Resampling.LANCZOS).save(
            folder / "ic_launcher.png"
        )
        round_icon.resize((round(48 * factor),) * 2, Image.Resampling.LANCZOS).save(
            folder / "ic_launcher_round.png"
        )
        foreground.resize((round(108 * factor),) * 2, Image.Resampling.LANCZOS).save(
            folder / "ic_launcher_foreground.png"
        )
        count += 3

    anydpi = RES / "mipmap-anydpi-v26"
    anydpi.mkdir(parents=True, exist_ok=True)
    for name in ("ic_launcher", "ic_launcher_round"):
        (anydpi / f"{name}.xml").write_text(ADAPTIVE_XML, encoding="utf-8")

    legacy.resize((512, 512), Image.Resampling.LANCZOS).save(
        BRANDING / "ic_launcher-playstore.png"
    )

    print(f"母版: legacy {legacy.width}px / foreground {foreground.width}px")
    print(f"已生成 {count} 张 mipmap PNG + 2 个 adaptive-icon XML")
    print(f"品牌源图: {svg}")
    print(f"商店图: {BRANDING / 'ic_launcher-playstore.png'}")


if __name__ == "__main__":
    main()
