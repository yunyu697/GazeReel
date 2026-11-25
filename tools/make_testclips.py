#!/usr/bin/env python3
"""离线测试片源生成器：把「看不见的数学」画成戴上一眼就能判的测试卡。

## 为什么要有它

`VERIFY-2.2.0.md` 的九项验收（V1–V9）里有七项要「放一部片、看朝向对不对」，
而手里没有真实片库。没有片源，验收就只能停在纸面上——「数学算对了」和「戴上没错」
之间那道缝，恰恰是真机上最容易出事的地方（纹理上下翻、左右眼取反、鱼眼圆被拉扁）。

所以这里不生成「好看的画面」，只生成**能证伪的标注**：

  · 每个世界方向一个固定颜色（前=红、右90=绿、左90=蓝、后=黄、天顶=品红、地底=青）；
  · 同一套角度图案分别用等距柱状、圆形鱼眼、全画幅鱼眼三种投影画出去 ——
    投影正确时，三种片子里同一个方向必须是同一个颜色，且落在地平线的同一高度；
  · 画面里带秒表，静止一帧就不会变，能立刻看出「播放器卡住了」还是「我看错方向了」。

## 三个必须与 app 对齐的约定（改任何一处都要同步 ProjectionMesh.kt）

1. **正前方在 equirect 的中线**：`u = 0.5 - theta / span`，`theta` 向右为负。
   `u=0.5` 是正前方，`u>0.5` 是**右侧**，`u=0`/`u=1` 是同一条**正后方接缝**。
2. **北在上**：`v = phi / 180 + 0.5`，画面顶行是天顶（`phi=+90`），底行是地底。
3. **鱼眼是等距投影**：`alpha = r * FOV/2`，`r` 是「离圆心占半径的比例」。
   圆形画幅里 `r=1` 落在圆边；全画幅里 `r=1` 落在**四角**（半径按半对角线归一）。

这三条不是猜的，是从 `ProjectionMesh.curved()` / `fisheye()` / `fisheyeFullFrame()`
的顶点与 UV 公式反推出来的；`tools/check_testclips.py` 会独立重算一遍并对着**解码后的像素**
核对颜色，公式写反了会当场 FAIL。

## 用法

    python tools/make_testclips.py                 # 生成到 ../output/testclips/
    python tools/make_testclips.py --preview DIR   # 每段只出一帧 PNG，用来肉眼检查
    python tools/make_testclips.py --only fisheye  # 只生成某一段

产物不入库（`/output/` 已在 .gitignore 里）：它们是可复现的派生物，不是源码。
推送到手机：

    adb push ../output/testclips/. /sdcard/Movies/
"""

from __future__ import annotations

import argparse
import json
import math
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT = ROOT.parent / "output" / "testclips"

FPS = 20
DURATION = 8.0

# ── 方位色标 ────────────────────────────────────────────────────────────────
# 颜色只服务于「判读」：彼此在 RGB 上至少差 80，压缩到 yuv420p 之后也分得开。
COLORS = {
    "bg": (16, 20, 24),
    "sky": (34, 44, 54),
    "ground": (44, 38, 32),
    "grid_minor": (58, 72, 86),
    "grid_major": (96, 124, 150),
    "horizon": (150, 190, 220),
    "front": (230, 60, 45),      # 0°   正前 · 红
    "right45": (20, 110, 45),    # 右 45
    "right90": (40, 190, 70),    # 右 90 · 绿
    "right135": (150, 190, 40),  # 右 135
    "left45": (25, 50, 120),     # 左 45
    "left90": (45, 90, 225),     # 左 90 · 蓝
    "left135": (150, 50, 180),   # 左 135
    "back": (235, 220, 60),      # 180° 正后 · 黄
    "top": (230, 60, 200),       # 天顶 · 品红
    "bottom": (40, 200, 210),    # 地底 · 青
}

# (theta, 颜色键, 文字)。theta 向右为负，与 app 的 `u = 0.5 - theta/span` 一致。
MARKERS = [
    (0.0, "front", "前 FRONT"),
    (-45.0, "right45", "右45"),
    (-90.0, "right90", "右90 RIGHT"),
    (-135.0, "right135", "右135"),
    (45.0, "left45", "左45"),
    (90.0, "left90", "左90 LEFT"),
    (135.0, "left135", "左135"),
    (180.0, "back", "后 BACK"),
]
CAP_DEG = 6.0          # 标记是球面上的圆帽，半径 6°（直径 12°，2048 宽时约 68 px）
# 上下两个标记放在 ±62° 而不是极点：极点附近等距柱状的像素密度趋近无穷，
# 圆帽会被拉成一条横线，既取不到纯色也看不清。62° 仍是一个需要真抬头/低头的角度。
POLAR = [(0.0, 62.0, "top", "上方 UP"), (0.0, -62.0, "bottom", "下方 DOWN")]

# 供自检抽样的锚点：(标签, theta, phi, 颜色键)
ANCHOR_CANDIDATES = [
    ("front", 0.0, 0.0, "front"),
    ("right90", -90.0, 0.0, "right90"),
    ("left90", 90.0, 0.0, "left90"),
    ("back", 180.0, 0.0, "back"),
    ("right45", -45.0, 0.0, "right45"),
    ("left45", 45.0, 0.0, "left45"),
    ("right135", -135.0, 0.0, "right135"),
    ("left135", 135.0, 0.0, "left135"),
    ("top", 0.0, 62.0, "top"),
    ("bottom", 0.0, -62.0, "bottom"),
]

# 未缝合双鱼眼：两个圆各占半幅的 92%，中间留一条真黑缝。
# 半径取 0.5·h 时两圆正好相切，缝宽为 0 —— 那样「两圆之间是黑的」就无从判起。
DUAL_CIRCLE = 0.46

CLIPS = [
    dict(
        name="test-360-equirect-mono.mp4", kind="equirect",
        w=2048, h=1024, span=360.0,
        title="360° 等距柱状 · MONO",
        proves="V1：整球朝向、正后接缝、上下两极",
    ),
    dict(
        name="test-180-sbs.mp4", kind="equirect180_sbs",
        w=2048, h=1024, span=180.0,
        title="180° 等距柱状 · SBS（左右眼同图，标签不同）",
        proves="V2：左右边界是否切在 ±90°；两只眼是否取反",
    ),
    dict(
        name="test-2d-grid-720p.mp4", kind="plane2d",
        w=1280, h=720,
        title="2D 银幕测试卡",
        proves="V3：银幕档位、上下是否倒、左右是否镜像",
    ),
    dict(
        name="test-fisheye-circle-mono.mp4", kind="fisheye",
        w=1024, h=1024, fov=200.0, circle=100.0,
        title="鱼眼 · 圆形 200°（等距投影）",
        proves="V4：圆是否正、边缘有无月牙黑弧、中心是否正对前方",
    ),
    dict(
        name="test-fisheye-fullframe-mono.mp4", kind="fisheye_full",
        w=1280, h=720, fov=180.0,
        title="鱼眼 · 全画幅 180°（对角线口径）",
        proves="V5：四角是否有真实像素、边缘有无放射条纹",
    ),
    dict(
        name="test-dual-fisheye-unstitched.mp4", kind="dual",
        w=2048, h=1024, fov=200.0,
        title="未缝合双鱼眼 · 前后双圆（应被拒绝）",
        proves="V7：说明卡是否出现、两出口是否点得动",
    ),
]


# ── 字体 ────────────────────────────────────────────────────────────────────
def load_font(size: int):
    """优先中文字体；找不到就退回 DejaVu，并只画 ASCII（不让缺字体把生成搞挂）。"""
    candidates = [
        "C:/Windows/Fonts/msyh.ttc",
        "C:/Windows/Fonts/msyhl.ttc",
        "C:/Windows/Fonts/simhei.ttf",
        "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for path in candidates:
        if Path(path).exists():
            try:
                return ImageFont.truetype(path, size), ("msyh" in path or "simhei" in path or "CJK" in path)
            except OSError:
                continue
    return ImageFont.load_default(), False


# ── 几何：像素 → 方向 ───────────────────────────────────────────────────────
def angle_grids(kind: str, w: int, h: int, span: float, fov: float, circle: float):
    """返回 (theta, phi, inside)。theta 向右为负，phi 向上为正，单位度。"""
    px = (np.arange(w, dtype=np.float32) + 0.5)
    py = (np.arange(h, dtype=np.float32) + 0.5)

    if kind in ("equirect", "equirect180_sbs"):
        u = (px / w)[None, :].repeat(h, axis=0)
        v = 1.0 - (py / h)[:, None].repeat(w, axis=1)
        theta = (0.5 - u) * span
        phi = (v - 0.5) * 180.0
        return theta, phi, np.ones((h, w), bool)

    if kind == "fisheye":
        radius = 0.5 * min(w, h) * circle / 100.0
        nx = (px - w / 2.0)[None, :].repeat(h, axis=0) / radius
        ny = ((h / 2.0) - py)[:, None].repeat(w, axis=1) / radius
        r = np.hypot(nx, ny)
        inside = r <= 1.0
        alpha = np.minimum(r, 1.0) * math.radians(fov / 2.0)
        psi = np.arctan2(ny, nx)
        return _dir_to_angles(alpha, psi, inside)

    if kind == "fisheye_full":
        aspect = w / h
        nx = (px - w / 2.0)[None, :].repeat(h, axis=0) / (h / 2.0)
        ny = ((h / 2.0) - py)[:, None].repeat(w, axis=1) / (h / 2.0)
        half_diag = math.hypot(aspect, 1.0)
        r = np.hypot(nx, ny) / half_diag
        inside = np.ones((h, w), bool)
        alpha = r * math.radians(fov / 2.0)
        psi = np.arctan2(ny, nx)
        return _dir_to_angles(alpha, psi, inside)

    if kind == "dual":
        # 两个圆并排：左圆裁 0..180，右圆裁 -180..0（同一只眼的前后镜头，未缝合）
        radius = h * DUAL_CIRCLE
        cx = np.where(px < w / 2.0, w / 4.0, 3.0 * w / 4.0)
        nx = (px - cx)[None, :].repeat(h, axis=0) / radius
        ny = ((h / 2.0) - py)[:, None].repeat(w, axis=1) / radius
        r = np.hypot(nx, ny)
        inside = r <= 1.0
        alpha = np.minimum(r, 1.0) * math.radians(90.0)
        psi = np.arctan2(ny, nx)
        theta, phi, _ = _dir_to_angles(alpha, psi, inside)
        # 右圆当作「后镜头」：整体转 180°
        theta = np.where(cx > w / 2.0, theta + 180.0, theta)
        return theta, phi, inside

    raise ValueError(kind)


def _dir_to_angles(alpha, psi, inside):
    """与 ProjectionMesh 的鱼眼顶点反算一致：
    x = sinα·cosψ, y = sinα·sinψ, z = −cosα；theta = atan2(−x, −z)、phi = asin(y)。"""
    sa = np.sin(alpha)
    x = sa * np.cos(psi)
    y = sa * np.sin(psi)
    z = -np.cos(alpha)
    theta = np.degrees(np.arctan2(-x, -z))
    phi = np.degrees(np.arcsin(np.clip(y, -1.0, 1.0)))
    return theta.astype(np.float32), phi.astype(np.float32), inside


def forward_px(clip, theta_deg: float, phi_deg: float):
    """方向 → 像素（与上面的 angle_grids 互为逆）。返回 (px, py, inside)。"""
    kind = clip["kind"]
    w, h = clip["w"], clip["h"]
    th = math.radians(theta_deg)
    ph = math.radians(phi_deg)
    if kind in ("equirect", "equirect180_sbs"):
        return (0.5 - theta_deg / clip["span"]) * w, (0.5 - phi_deg / 180.0) * h, True

    if kind == "dual":
        # 后镜头整体转了 180°：先折回镜头自己的坐标系，再算 (alpha, psi)
        radius = h * DUAL_CIRCLE
        rear = abs(theta_deg) > 90.0
        th_eff = th - math.pi if rear else th
        alpha, psi = _angles_of_direction(th_eff, ph)
        r = alpha / math.radians(90.0)
        cx = 3.0 * w / 4.0 if rear else w / 4.0
        return cx + r * radius * math.cos(psi), h / 2.0 - r * radius * math.sin(psi), r <= 1.0

    # 球面方向 → (alpha, psi)：cosα = cosφ·cosθ，ψ = atan2(sinφ, −cosφ·sinθ)
    alpha, psi = _angles_of_direction(th, ph)
    if kind == "fisheye":
        radius = 0.5 * min(w, h) * clip["circle"] / 100.0
        r = alpha / math.radians(clip["fov"] / 2.0)
        return w / 2.0 + r * radius * math.cos(psi), h / 2.0 - r * radius * math.sin(psi), r <= 1.0
    if kind == "fisheye_full":
        aspect = w / h
        half_diag = math.hypot(aspect, 1.0)
        rho = alpha / math.radians(clip["fov"] / 2.0) * half_diag
        px = w / 2.0 + rho * math.cos(psi) * (h / 2.0)
        py = h / 2.0 - rho * math.sin(psi) * (h / 2.0)
        return px, py, 0 <= px <= w and 0 <= py <= h
    # 平面片不参与角度映射
    return -1.0, -1.0, False


def _angles_of_direction(th: float, ph: float):
    cos_a = math.cos(ph) * math.cos(th)
    alpha = math.acos(max(-1.0, min(1.0, cos_a)))
    psi = math.atan2(math.sin(ph), -math.cos(ph) * math.sin(th))
    return alpha, psi


def angular_distance(theta, phi, theta0: float, phi0: float):
    """球面距离（度）。用真角距而不是 Δθ/Δφ，否则高纬度上的圆帽会被拉成长条。"""
    t = np.radians(theta)
    p = np.radians(phi)
    t0 = math.radians(theta0)
    p0 = math.radians(phi0)
    cosd = np.cos(p) * math.cos(p0) * np.cos(t - t0) + np.sin(p) * math.sin(p0)
    return np.degrees(np.arccos(np.clip(cosd, -1.0, 1.0)))


def _dist_to_multiple(values, step: float):
    return np.abs(((values + step / 2.0) % step) - step / 2.0)


def angular_pattern(theta, phi):
    """世界角度上的图案。不含时间项，所以每段片只算一次，逐帧只叠秒表。"""
    shp = theta.shape
    rgb = np.zeros(shp + (3,), np.uint8)
    rgb[:] = COLORS["bg"]

    sky = phi > 0.5
    ground = phi < -0.5
    rgb[sky] = COLORS["sky"]
    rgb[ground] = COLORS["ground"]

    # 经纬网：极区（|φ|>80）不画，那里等距柱状的像素密度趋近无穷，画出来只剩一团糊
    polar_zone = np.abs(phi) > 80.0
    dth = _dist_to_multiple(theta, 15.0)
    dph = _dist_to_multiple(phi, 15.0)
    minor = ((dth < 0.55) | (dph < 0.55)) & ~polar_zone
    major = ((dth < 1.15) | (dph < 1.15)) & ~polar_zone
    rgb[minor] = COLORS["grid_minor"]
    rgb[major] = COLORS["grid_major"]

    # 地平线：判断「有没有整体抬头/低头」的基准线
    horizon = np.abs(phi) < 1.8
    rgb[horizon] = COLORS["horizon"]

    # 方位标记（球面圆帽）
    for th0, key, _ in MARKERS:
        cap = angular_distance(theta, phi, th0, 0.0) < CAP_DEG
        rgb[cap] = COLORS[key]
    for th0, ph0, key, _ in POLAR:
        cap = angular_distance(theta, phi, th0, ph0) < CAP_DEG
        rgb[cap] = COLORS[key]

    return rgb


def _hud_lines(t: float, clip) -> list[str]:
    frame = int(round(t * FPS))
    return [f"t = {t:5.2f}s   f = {frame:03d}", f"{clip['name']}   {FPS} fps"]


def _cache_key(clip, w, h, span):
    return (clip["kind"], w, h, round(span, 3), clip.get("fov", 200.0), clip.get("circle", 100.0))


_ANGLE_CACHE: dict = {}
_PATTERN_CACHE: dict = {}


def _angle_grids_cached(clip, w, h, span):
    """角度网格只与「片型 + 分辨率」有关，与时间无关 —— 每段算一次就够。"""
    key = _cache_key(clip, w, h, span)
    hit = _ANGLE_CACHE.get(key)
    if hit is None:
        hit = angle_grids(clip["kind"], w, h, span, clip.get("fov", 200.0),
                          clip.get("circle", 100.0))
        _ANGLE_CACHE[key] = hit
    return hit


def _pattern_cached(clip, w, h, span, theta, phi, inside):
    """静态图案同样只算一次。逐帧只叠「游标白点 + 秒表」，160 帧从 6 分钟降到 1 分钟。"""
    key = _cache_key(clip, w, h, span)
    hit = _PATTERN_CACHE.get(key)
    if hit is None:
        hit = angular_pattern(theta, phi)
        hit[~inside] = (0, 0, 0)
        _PATTERN_CACHE[key] = hit
    return hit


def render_angular(clip, t: float, w: int, h: int, span: float) -> np.ndarray:
    theta, phi, inside = _angle_grids_cached(clip, w, h, span)
    rgb = _pattern_cached(clip, w, h, span, theta, phi, inside).copy()

    # 运动元素：地平线上方 18° 绕一圈的白点 —— 停了就是播放器卡了
    orbit = -180.0 + 360.0 * ((t * 0.25) % 1.0)
    dot = angular_distance(theta, phi, orbit, 18.0) < 4.0
    rgb[dot] = (255, 255, 255)

    img = Image.fromarray(rgb)
    draw = ImageDraw.Draw(img)
    size = max(14, int(min(w, h) / 26))
    font, cjk = load_font(size)
    small, _ = load_font(max(11, int(size * 0.62)))

    def put(theta_deg, phi_deg, text, fnt=font, fill=(255, 255, 255)):
        px, py, ok = forward_px_scaled(clip, theta_deg, phi_deg, w, h, span)
        if not ok:
            return
        if 0 <= px < w and 0 <= py < h:
            # 贴边的标签改对齐方向，否则被画幅切掉（鱼眼的 ±90 正好在圆边上）
            anchor = "rm" if px > w * 0.86 else ("lm" if px < w * 0.14 else "mm")
            px = min(max(px, 6), w - 6)
            draw.text((px, py), text, font=fnt, fill=fill, anchor=anchor,
                      stroke_width=2, stroke_fill=(0, 0, 0))

    # 标题放正前上方，一眼确认「我正对着的是 0°」
    put(0.0, 34.0, clip["title"] if cjk else clip["title"].encode("ascii", "ignore").decode() or "TITLE")
    for th0, _, label in MARKERS:
        text = label if cjk else label.encode("ascii", "ignore").decode() or f"{th0:.0f}"
        put(th0, 10.5, text, small)
    for th0, ph0, _, label in POLAR:
        text = label if cjk else label.encode("ascii", "ignore").decode() or "POLAR"
        # 文字往画面外沿再挪 9°，别压在色帽上 —— 自检要在帽心取色
        put(th0, ph0 + (9.0 if ph0 > 0 else -9.0), text, small)

    for i, line in enumerate(_hud_lines(t, clip)):
        px, py, _ = forward_px_scaled(clip, 0.0, -34.0 - i * 7.0, w, h, span)
        if 0 <= px < w and 0 <= py < h:
            draw.text((px, py), line, font=small, fill=(255, 255, 255), anchor="mm",
                      stroke_width=2, stroke_fill=(0, 0, 0))

    # 360 的接缝：两条 1px 白线，错位会一眼看出台阶
    if clip["kind"] == "equirect" and abs(span - 360.0) < 1e-6:
        draw.line([(0, 0), (0, h)], fill=(255, 255, 255), width=1)
        draw.line([(w - 1, 0), (w - 1, h)], fill=(255, 255, 255), width=1)

    return np.asarray(img)


def forward_px_scaled(clip, theta_deg, phi_deg, w, h, span):
    """按当前渲染分辨率（可能与 clip 的标称分辨率不同）换算像素。"""
    scaled = dict(clip)
    scaled["w"], scaled["h"] = w, h
    px, py, ok = forward_px(scaled, theta_deg, phi_deg)
    return px, py, ok


def render_equirect180_sbs(clip, t: float, w: int, h: int) -> np.ndarray:
    half = w // 2
    eye = [render_angular(clip, t, half, h, 180.0) for _ in range(2)]
    left, right = eye
    img = Image.fromarray(np.hstack([left, right]))
    draw = ImageDraw.Draw(img)
    font, cjk = load_font(max(14, int(h / 26)))

    def tag(x_center, text):
        draw.rectangle([x_center - 120, h - 74, x_center + 120, h - 26], fill=(0, 0, 0))
        draw.text((x_center, h - 50), text, font=font, fill=(255, 255, 255), anchor="mm")

    tag(half // 2, "LEFT EYE 左眼" if cjk else "LEFT EYE")
    tag(half + half // 2, "RIGHT EYE 右眼" if cjk else "RIGHT EYE")
    draw.line([(half, 0), (half, h)], fill=(255, 255, 255), width=2)
    return np.asarray(img)


def render_dual(clip, t: float, w: int, h: int) -> np.ndarray:
    rgb = render_angular(clip, t, w, h, 360.0)
    img = Image.fromarray(rgb)
    draw = ImageDraw.Draw(img)
    font, cjk = load_font(max(14, int(h / 24)))
    radius = h / 2.0
    for cx, label in ((w / 4.0, "LENS A · 前 180°" if cjk else "LENS A"),
                      (3.0 * w / 4.0, "LENS B · 后 180°" if cjk else "LENS B")):
        draw.ellipse([cx - radius + 2, 2, cx + radius - 2, h - 2], outline=(200, 40, 40), width=4)
        # 标签贴圆的上方，别压住圆心的方位色标（自检要在圆心取色）
        draw.text((cx, h * 0.055), label, font=font, fill=(255, 255, 255), anchor="mm",
                  stroke_width=2, stroke_fill=(0, 0, 0))
    draw.text((w / 2.0, h * 0.80), "未缝合 · UNSTITCHED", font=font, fill=(255, 90, 90), anchor="mm")
    return np.asarray(img)


def render_plane(clip, t: float, w: int, h: int) -> np.ndarray:
    img = Image.new("RGB", (w, h), COLORS["bg"])
    draw = ImageDraw.Draw(img)
    font, cjk = load_font(max(16, int(h / 16)))
    small, _ = load_font(max(11, int(h / 34)))

    for i in range(1, 10):
        x = w * i / 10
        y = h * i / 10
        color = (96, 124, 150) if i == 5 else (52, 64, 76)
        draw.line([(x, 0), (x, h)], fill=color, width=1)
        draw.line([(0, y), (w, y)], fill=color, width=1)

    size = int(w * 0.06)
    inset = size * 0.9
    for (x, y, color) in ((inset, inset, COLORS["left45"]),
                          (w - inset, inset, COLORS["back"]),
                          (inset, h - inset, COLORS["left90"]),
                          (w - inset, h - inset, COLORS["top"])):
        draw.rectangle([x - size // 2, y - size // 2, x + size // 2, y + size // 2], fill=color)

    draw.line([(w / 2 - 40, h / 2), (w / 2 + 40, h / 2)], fill=COLORS["front"], width=4)
    draw.line([(w / 2, h / 2 - 40), (w / 2, h / 2 + 40)], fill=COLORS["front"], width=4)

    draw.text((w / 2, h * 0.34), "凝影 · 2D 银幕测试卡" if cjk else "GAZEREEL 2D TEST CARD",
              font=font, fill=(240, 244, 250), anchor="mm")
    draw.text((w / 2, h * 0.44), "字若是倒的 → 画面上下翻；若是镜像 → 取眼错了" if cjk else
              "upside-down = flipped    mirrored = wrong eye",
              font=small, fill=(190, 200, 214), anchor="mm")
    for i, line in enumerate(_hud_lines(t, clip)):
        draw.text((w / 2, h * 0.60 + i * (h * 0.07)), line, font=small, fill=(255, 255, 255),
                  anchor="mm")
    draw.text((16, h - 22), f"{w}x{h}", font=small, fill=(150, 160, 172), anchor="lm")
    return np.asarray(img)


def render_frame(clip, t: float, w: int | None = None, h: int | None = None) -> np.ndarray:
    w = w or clip["w"]
    h = h or clip["h"]
    kind = clip["kind"]
    if kind == "equirect":
        return render_angular(clip, t, w, h, clip["span"])
    if kind == "equirect180_sbs":
        return render_equirect180_sbs(clip, t, w, h)
    if kind == "fisheye" or kind == "fisheye_full":
        return render_angular(clip, t, w, h, 360.0)
    if kind == "dual":
        return render_dual(clip, t, w, h)
    if kind == "plane2d":
        return render_plane(clip, t, w, h)
    raise ValueError(kind)


# ── 输出 ────────────────────────────────────────────────────────────────────
def encode(clip, out_dir: Path, ffmpeg: str):
    w, h = clip["w"], clip["h"]
    dest = out_dir / clip["name"]
    cmd = [
        ffmpeg, "-y", "-v", "error",
        "-f", "rawvideo", "-pix_fmt", "rgb24", "-s", f"{w}x{h}", "-r", str(FPS),
        "-i", "-",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20",
        "-pix_fmt", "yuv420p", "-g", str(FPS),
        "-movflags", "+faststart", "-an",
        str(dest),
    ]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    frames = int(DURATION * FPS)
    for i in range(frames):
        frame = render_frame(clip, i / FPS)
        proc.stdin.write(frame.tobytes())
    proc.stdin.close()
    if proc.wait() != 0:
        raise RuntimeError(f"ffmpeg 编码失败：{clip['name']}")
    return dest


def anchors_of(clip):
    """给自检用的取样点。**按每只眼 / 每块色块分别给坐标**，不能拿整幅宽高硬套：
    SBS 的 180° 是「每眼各铺满 180°」，用整幅算会把锚点落在两眼之间的分界线上
    （自检第一次跑就是这么 FAIL 的，取到的是另一只眼的颜色）。"""
    w, h = clip["w"], clip["h"]
    margin = 16

    if clip["kind"] == "plane2d":
        size = int(w * 0.06)
        inset = size * 0.9
        corners = (("TL", inset, inset, "left45"), ("TR", w - inset, inset, "back"),
                   ("BL", inset, h - inset, "left90"), ("BR", w - inset, h - inset, "top"))
        return [dict(label=name, px=round(px, 2), py=round(py, 2),
                     color=list(COLORS[key])) for name, px, py, key in corners]

    if clip["kind"] == "equirect180_sbs":
        eye = dict(clip, kind="equirect", w=w // 2)
        out = []
        for label, th, ph, key in ANCHOR_CANDIDATES:
            if label not in ("front", "top", "bottom"):
                continue
            px, py, ok = forward_px(eye, th, ph)
            if not ok:
                continue
            for side, dx in (("L", 0.0), ("R", w / 2.0)):
                ax, ay = px + dx, py
                if margin <= ax < w - margin and margin <= ay < h - margin:
                    out.append(dict(label=f"{label}_{side}", theta=th, phi=ph,
                                    color=list(COLORS[key]),
                                    px=round(ax, 2), py=round(ay, 2)))
        return out

    out = []
    for label, th, ph, key in ANCHOR_CANDIDATES:
        px, py, ok = forward_px(clip, th, ph)
        if ok and margin <= px < w - margin and margin <= py < h - margin:
            out.append(dict(label=label, theta=th, phi=ph, color=list(COLORS[key]),
                            px=round(px, 2), py=round(py, 2)))
    return out


def write_manifest(out_dir: Path, entries):
    data = dict(
        fps=FPS, duration=DURATION,
        note="锚点坐标由 tools/make_testclips.py 按 ProjectionMesh 的投影公式正算；"
             "check_testclips.py 会独立重算并核对像素颜色。",
        clips=entries,
    )
    (out_dir / "manifest.json").write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description="生成离线测试片源")
    ap.add_argument("--out", default=str(DEFAULT_OUT), help="输出目录")
    ap.add_argument("--only", default="", help="只生成名字里含该子串的片")
    ap.add_argument("--preview", default="", help="只出一帧 PNG 到该目录（不出视频）")
    ap.add_argument("--list", action="store_true", help="列出片源清单")
    args = ap.parse_args(argv)

    if args.list:
        for c in CLIPS:
            print(f"{c['name']:<38} {c['w']}x{c['h']}  {c['proves']}")
        return 0

    ffmpeg = shutil.which("ffmpeg")
    selected = [c for c in CLIPS if args.only in c["name"]]

    if args.preview:
        out = Path(args.preview)
        out.mkdir(parents=True, exist_ok=True)
        for c in selected:
            png = out / (Path(c["name"]).stem + ".png")
            Image.fromarray(render_frame(c, 1.0)).save(png)
            print(f"preview {png}")
        return 0

    if not ffmpeg:
        print("找不到 ffmpeg，无法编码。装一个（winget install Gyan.FFmpeg）再来。", file=sys.stderr)
        return 2

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    for c in selected:
        dest = encode(c, out_dir, ffmpeg)
        entry = {k: c[k] for k in ("name", "kind", "w", "h", "title", "proves")}
        for k in ("span", "fov", "circle"):
            if k in c:
                entry[k] = c[k]
        entry["bytes"] = dest.stat().st_size
        entry["anchors"] = anchors_of(c)
        entries.append(entry)
        print(f"  {c['name']:<38} {dest.stat().st_size / 1e6:5.2f} MB  "
              f"{len(entry['anchors'])} 个锚点")
    write_manifest(out_dir, entries)
    print(f"\n完成：{len(entries)} 段 → {out_dir}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
