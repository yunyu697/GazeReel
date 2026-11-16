#!/usr/bin/env python3
"""校验「唤出控制」热区与徽标：对称轴、对称性、画布外坐标、纹理与几何一致性。

对应 VrUiLayer.drawConsoleWakeHint（热区）与 renderWakeBadge（徽标）。
脚本**从源码抓常量**，改源码忘改预期会立刻 FAIL。

判据：
  1) 基准轴 = 参考系水平线（WAKE_AXIS_DEG == 0），球面 / 鱼眼用它
  2) 影院（PLANE_2D）的实际轴 = 银幕中心仰角，由 screenCenterY / SCREEN_DISTANCE
     现算；wakeAxisDeg() 必须按投影分流，命中区与 quad 必须共用它
  3) 上下热区由「当前轴 ± 单侧张角」推导（不许两个独立字面量）
  4) 上 / 下关于当前轴对称，且用户从内容正前方低头/抬头 78° 正好命中
  5) 热区在画布 [0, H] 之外（100% 与 140% 缩放都成立）
  6) drawConsoleWakeHint 内零 canvas 绘制（视觉全在独立徽标 quad 上）
  7) VrRenderer 的徽标角度取自 VrUiLayer.wakeUpDeg/wakeDownDeg，无第二份硬编码
  8) 徽标 quad 宽高比 == 纹理宽高比（不等比会拉伸）
  9) 反算角度与源码一致，RectF 上下不颠倒
"""

import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/gazereel/vr/render/VrUiLayer.kt"
RENDERER = ROOT / "app/src/main/java/com/gazereel/vr/render/VrRenderer.kt"
MESH = ROOT / "app/src/main/java/com/gazereel/vr/render/ProjectionMesh.kt"

W = H = 0


def grab(pattern, text, name, cast=float):
    m = re.search(pattern, text)
    if not m:
        print(f"FAIL 抓不到 {name}")
        sys.exit(1)
    return cast(m.group(1))


def y_at(deg, height_m, pitch_rad):
    half_h = math.atan((height_m / 2.0) / 2.0)
    return H / 2.0 - (math.radians(deg) - pitch_rad) / half_h * (H / 2.0)


def deg_at(y, height_m, pitch_rad):
    half_h = math.atan((height_m / 2.0) / 2.0)
    return math.degrees((H / 2.0 - y) / (H / 2.0) * half_h + pitch_rad)


def main() -> int:
    text = UI.read_text(encoding="utf-8")
    gl = RENDERER.read_text(encoding="utf-8")
    mesh = MESH.read_text(encoding="utf-8")

    global W, H
    W = grab(r"const val W = (\d+)", text, "W", int)
    H = grab(r"const val H = (\d+)", text, "H", int)
    pitch_rad = -grab(r"CONSOLE_DROP_RAD = ([\d.]+)f", text, "CONSOLE_DROP_RAD")
    base_h = grab(r"val h = ([\d.]+)f \* k", text, "面板高")

    base_axis = grab(r"const val WAKE_AXIS_DEG = (-?[\d.]+)f", text, "WAKE_AXIS_DEG")
    inner = grab(r"const val WAKE_INNER_DEG = ([\d.]+)f", text, "WAKE_INNER_DEG")
    outer = grab(r"const val WAKE_OUTER_DEG = ([\d.]+)f", text, "WAKE_OUTER_DEG")
    span = (inner + outer) / 2.0
    half = (outer - inner) / 2.0
    half_w = grab(r"const val WAKE_HALF_WIDTH_DEG = ([\d.]+)f", text, "WAKE_HALF_WIDTH_DEG")
    line_px = grab(r"const val WAKE_LINE_PX = ([\d.]+)f", text, "WAKE_LINE_PX")
    tex_w = grab(r"const val WAKE_W = (\d+)", text, "WAKE_W", int)
    tex_h = grab(r"const val WAKE_H = (\d+)", text, "WAKE_H", int)
    quad_w = grab(r"const val WAKE_QUAD_W = ([\d.]+)f", text, "WAKE_QUAD_W")
    quad_h = grab(r"const val WAKE_QUAD_H = ([\d.]+)f", text, "WAKE_QUAD_H")

    # 影院的「当前正前方」= 银幕中心；centerY 必须和 Kotlin 的 screenCenterY(100) 同式。
    screen_d = grab(r"const val SCREEN_DISTANCE = ([\d.]+)f", mesh, "SCREEN_DISTANCE")
    screen_w = grab(r"const val SCREEN_WIDTH = ([\d.]+)f", mesh, "SCREEN_WIDTH")
    floor_y = grab(r"const val ROOM_FLOOR_Y = (-?[\d.]+)f", mesh, "ROOM_FLOOR_Y")
    skirt = grab(r"private const val SCREEN_SKIRT = ([\d.]+)f", mesh, "SCREEN_SKIRT")
    center_y = floor_y + (screen_w * 9 / 16) / 2 + skirt
    screen_deg = math.degrees(math.atan2(center_y, screen_d))
    axis = screen_deg
    up, down = axis + span, axis - span

    print(f"播放页面板 pitch {math.degrees(pitch_rad):+.2f}°，基础高 {base_h} m")
    print(f"基准轴（球面/鱼眼）{base_axis:+.1f}°；影院轴（银幕中心）{axis:+.2f}°")
    print(f"带子内/外缘 ±{inner:.0f}° / ±{outer:.0f}°"
          f"（中心 ±{span:.0f}°，半高 {half:.1f}°）")
    print(f"热区 上 {up - half:+.1f}°~{up + half:+.1f}°   下 {down - half:+.1f}°~{down + half:+.1f}°")
    print(f"用户量法（从当前内容正前方起）：上 +{inner:.0f}~+{outer:.0f}°，下 −{outer:.0f}~−{inner:.0f}°")
    print(f"纹理 {tex_w}×{tex_h}")
    print()

    ok = True

    # 1) 基准轴必须是参考系水平线；影院轴必须真的从银幕中心现算
    axis_ok = abs(base_axis) < 1e-6
    print(f"基准轴取参考系水平线（0°）  {'OK' if axis_ok else f'FAIL 现在是 {base_axis:+.1f}°'}")
    ok &= axis_ok

    axis_impl = bool(
        re.search(r"fun wakeAxisDeg\(state: VrUiState\): Float\s*=", text)
        and re.search(r"state\.format\.projection == Projection\.PLANE_2D", text)
        and re.search(r"ProjectionMesh\.screenCenterY\(state\.settings\.screenWidthPercent\)", text)
        and re.search(r"ProjectionMesh\.SCREEN_DISTANCE", text)
        and re.search(r"else WAKE_AXIS_DEG", text)
    )
    print(f"wakeAxisDeg 按投影现算影院银幕中心  {'OK' if axis_impl else 'FAIL 轴写死或没跟随幕宽'}")
    ok &= axis_impl

    distinct = abs(axis - base_axis) > 1.0
    print(f"影院轴 ≠ 基准轴（差 {abs(axis - base_axis):.1f}°）  "
          f"{'OK' if distinct else 'FAIL 又钉回水平线了'}")
    ok &= distinct

    registered = bool(re.search(r"val axis = wakeAxisDeg\(state\)", text))
    print(f"命中区注册用当前轴  {'OK' if registered else 'FAIL 命中区与 quad 会用不同轴'}")
    ok &= registered

    # 2) 源码必须用「当前轴 ± 张角」推导，不能是两个独立字面量
    derived = bool(
        re.search(r"fun wakeUpDeg\(state: VrUiState\): Float = wakeAxisDeg\(state\) \+ WAKE_SPAN_DEG", text)
        and re.search(r"fun wakeDownDeg\(state: VrUiState\): Float = wakeAxisDeg\(state\) - WAKE_SPAN_DEG", text)
    )
    print(f"上下由当前轴推导（非独立字面量）  {'OK' if derived else 'FAIL 又写回两个字面量了'}")
    ok &= derived

    # 2b) 中心与半高必须由两条边推导，不许写死 41 / 3
    edges_derived = bool(
        re.search(r"const val WAKE_SPAN_DEG = \(WAKE_INNER_DEG \+ WAKE_OUTER_DEG\) / 2f", text)
        and re.search(r"const val WAKE_HALF_DEG = \(WAKE_OUTER_DEG - WAKE_INNER_DEG\) / 2f", text)
    )
    print(f"中心/半高由 内缘+外缘 推导  {'OK' if edges_derived else 'FAIL 又写死中心或半高了'}")
    ok &= edges_derived

    # 3) 关于当前轴对称；用户从内容正前方低头/抬头 78° 必须正好落进带子
    sym = abs((up + down) - 2 * axis) < 1e-6
    print(f"上下关于当前轴 {axis:+.1f}° 对称（{up:+.1f} / {down:+.1f}）  {'OK' if sym else 'FAIL'}")
    ok &= sym

    mapped_up, mapped_down = up - axis, axis - down
    map_ok = abs(mapped_up - span) < 1e-6 and abs(mapped_down - span) < 1e-6
    print(f"从当前正前方抬头/低头 {mapped_up:.0f}° / {mapped_down:.0f}° 命中  "
          f"{'OK' if map_ok else 'FAIL 用户量法与热区不一致'}")
    ok &= map_ok

    # 3b) 用户 20:1x 给的口径：上下都落在 75°~81°（此前 38°~44° 判「太靠中间」）。
    # 这两个数就是需求本身，改它们必须是有意为之 —— 钉在脚本里，随手漂移会 FAIL。
    SPEC_INNER, SPEC_OUTER = 75.0, 81.0
    spec_ok = abs(inner - SPEC_INNER) < 1e-6 and abs(outer - SPEC_OUTER) < 1e-6
    print(f"带子口径 ±{inner:.0f}°~±{outer:.0f}°（需求 {SPEC_INNER:.0f}~{SPEC_OUTER:.0f}）"
          f"  {'OK' if spec_ok else 'FAIL 偏离用户口径'}")
    ok &= spec_ok

    tex_ar = tex_w / tex_h
    quad_ar = quad_w / quad_h
    ar_ok = abs(tex_ar - quad_ar) < 0.01
    print(f"宽高比 纹理 {tex_ar:.3f} vs quad {quad_ar:.3f}  {'OK' if ar_ok else 'FAIL 会拉伸'}")
    ok &= ar_ok

    print()

    # 横向必须足够长：20:1x 用户「唤醒条横向太短了，非常难找到」。
    # 位置外移到 ±78° 后，误触发由位置挡，横向不再需要靠「做小」来防，
    # 反而要够宽才好压中。下限 10°（整条 20°），防止以后又缩回 3.2°。
    wide_ok = half_w >= 10.0
    print(f"横向半宽 {half_w:.1f}°（整条 {half_w * 2:.1f}°，需 ≥10°）  "
          f"{'OK' if wide_ok else 'FAIL 又缩回难找的短条了'}")
    ok &= wide_ok

    # 纵向带宽：仍是 6°（半高 3°）的容错带，不跟横向一起放大 ——
    # 带子越厚，越容易在正常观影的抬头动作里踩进来。
    # 上限 4.5° / 下限 2.2° 沿用 19:0x 的抖动论证（凝视抖动约 ±1.5°）。
    band_ok = 2.2 <= half <= 4.5
    square_ok = half <= half_w * 1.3
    print(f"纵向半高 {half:.1f}°（2.2~4.5）  {'OK' if band_ok else 'FAIL 带宽不合'}",
          end="    ")
    print(f"不宽于横向×1.3 = {half_w * 1.3:.1f}°  {'OK' if square_ok else 'FAIL 纵向又放宽了'}")
    ok &= band_ok and square_ok

    for scale in (100, 140):
        h = base_h * scale / 100.0
        print(f"[{scale}%] 面板高 {h:.2f} m")
        for name, hi, lo in (("上", up + half, up - half), ("下", down + half, down - half)):
            top, bottom = y_at(hi, h, pitch_rad), y_at(lo, h, pitch_rad)
            where = "画布外" if (bottom < 0 or top > H) else "画布内"
            print(f"  {name}区 {lo:+.0f}°~{hi:+.0f}°  y {top:>7.1f}~{bottom:>7.1f}  {where}")
            if not top < bottom:
                print("    FAIL RectF 上下颠倒")
                ok = False
            for raw, y in ((hi, top), (lo, bottom)):
                if abs(deg_at(y, h, pitch_rad) - raw) > 0.05:
                    print(f"    FAIL 反算偏差 {raw}")
                    ok = False
        print()

    body = re.search(r"private fun drawConsoleWakeHint.*?\n    \}", text, re.S)
    if not body:
        print("FAIL 找不到 drawConsoleWakeHint")
        return 1
    snippet = body.group(0)
    draws = re.findall(r"canvas\.(draw\w+)", snippet)
    print(f"热区内 canvas 绘制：{draws if draws else '无'}  {'OK' if not draws else 'FAIL'}")
    if draws:
        ok = False

    proj_free = "projection" not in snippet
    print(f"函数体不引用 projection（三种投影同一组角度）  {'OK' if proj_free else 'FAIL'}")
    ok &= proj_free

    # 宽度：横向必须用常量（不许再出现 Math.toRadians(6.6) 这种硬编码）
    width_const = "WAKE_HALF_WIDTH_DEG" in snippet and not re.search(
        r"Math\.toRadians\([\d.]+", snippet
    )
    print(f"横向半宽取常量（无硬编码角度）  {'OK' if width_const else 'FAIL'}")
    ok &= width_const

    # 视觉宽度 == 命中宽度：quad 的横向张角必须等于 2×半宽
    quad_deg_w = math.degrees(2 * math.atan((quad_w / 2) / 2.0))
    width_match = abs(quad_deg_w - half_w * 2) < 0.15
    print(f"细线宽 {quad_deg_w:.2f}° == 命中宽 {half_w * 2:.2f}°  {'OK' if width_match else 'FAIL 看着线外会中'}")
    ok &= width_match

    # 线厚：0.2°~0.6°（低于 0.2 会闪烁，高于 0.6 就不是「细线」了）
    thick = line_px / tex_h * math.degrees(2 * math.atan((quad_h / 2) / 2.0))
    thick_ok = 0.2 <= thick <= 0.6
    print(f"线厚 {thick:.2f}°（0.20~0.60）  {'OK' if thick_ok else 'FAIL'}")
    ok &= thick_ok

    # 提示必须是「一条线」：不许再有文字 / 圆 / 弧
    badge = re.search(r"fun renderWakeBadge.*?\n    \}", text, re.S)
    if not badge:
        print("FAIL 找不到 renderWakeBadge")
        return 1
    bs = badge.group(0)
    forbidden = [w for w in ("drawText", "drawCircle", "drawArc") if w in bs]
    print(f"提示内无文字/圆环：{forbidden if forbidden else '无'}  {'OK' if not forbidden else 'FAIL 胶囊没删干净'}")
    if forbidden:
        ok = False
    has_line = "drawRoundRect" in bs
    print(f"提示用线绘制（drawRoundRect）  {'OK' if has_line else 'FAIL'}")
    ok &= has_line

    # 开关闸门：关掉时必须不注册命中区
    gate = "wakeZoneEnabled" in snippet
    print(f"设置开关可断开命中区  {'OK' if gate else 'FAIL 只藏视觉不关判定'}")
    ok &= gate

    hard = re.findall(r"Math\.toRadians\(-?\d+\.?\d*\)", gl)
    uses_axis = "uiLayer.wakeUpDeg(state)" in gl and "uiLayer.wakeDownDeg(state)" in gl
    print(f"渲染器 quad 角度取自 wakeUpDeg/wakeDownDeg  {'OK' if uses_axis else 'FAIL 与命中区不同轴'}")
    print(f"渲染器无硬编码角度        {'OK' if not hard else 'FAIL ' + str(hard)}")
    ok &= uses_axis and not hard

    print()
    print("结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
