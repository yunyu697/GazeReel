#!/usr/bin/env python3
"""UI 规范 v1 的四页版面预览 + 版面断言（19:0x）。

坐标不在这里写：面板、栏宽、缝、行高、按钮行全部从 `UiGrid.kt` 的常量读出来，
Python 只复刻「哪一页有几格、每格占几栏」这层结构。改了 Kotlin 常量而忘了改脚本，
断言会先失败，而不是画出一张「看起来对」的图。

覆盖：格式页（12 格 4×3）、设置第 1 页（11 格 2×6）、设置第 2 页
（4 参数 + 画幅开关 + 恢复默认 + 4 预设）、换片库（6 柜 2×3）、
播放页拒绝卡（19:2x 未缝合双鱼眼）。输出 `output/design/ui-pages.png`。

用法：python tools/preview_ui_pages.py
"""

import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
GRID = ROOT / "app/src/main/java/com/gazereel/vr/render/UiGrid.kt"
OUT = ROOT.parent / "output/design/ui-pages.png"

INK = (232, 236, 244)
DIM = (150, 158, 172)
ACCENT = (255, 213, 112)
PANEL = (25, 25, 26)
CARD = (40, 41, 43)
LINE = (90, 90, 96)
GRID_C = (70, 120, 96)


def font(size):
    for p in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simhei.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()


def consts():
    out = {}
    for line in GRID.read_text(encoding="utf-8").splitlines():
        m = re.search(r"const val (\w+) = (.+)", line.strip())
        if not m:
            continue
        expr = re.sub(r"(\d)f\b", r"\1", m.group(2).split("//")[0].strip())
        try:
            out[m.group(1)] = eval(expr, {"__builtins__": {}}, dict(out))  # noqa: S307
        except Exception:
            pass
    return out


def main():
    c = consts()
    panel = (c["PANEL_L"], c["PANEL_T"], c["PANEL_R"], c["PANEL_B"])
    cl, cr, cw = c["CONTENT_L"], c["CONTENT_R"], c["CONTENT_W"]
    colw, gut = c["COL_W"], c["GUTTER"]
    step = colw + gut
    row_h, fmt_h, big_h = c["H_ROW"], c["H_FMT"], c["H_BIG"]
    gap, body_top, btn_y, btn_b = c["ROW_GAP"], c["BODY_TOP"], c["BUTTON_Y"], c["BUTTON_B"]

    def span(cols):
        return cols * colw + (cols - 1) * gut

    def cell(col, cols, top, h):
        l = cl + col * step
        return (l, top, l + span(cols), top + h)

    def footer(units):
        total = sum(span(u) for u in units) + gut * (len(units) - 1)
        x = cr - total
        out = []
        for u in units:
            out.append((x, btn_y, x + span(u), btn_b))
            x += span(u) + gut
        return out

    formats = ["360° · Mono", "360° · SBS", "180° · SBS", "180° · TAB",
               "220° · Mono", "220° · SBS", "270° · Mono", "270° · SBS",
               "2D · Mono", "2D · SBS", "鱼眼 · Mono", "鱼眼 · SBS"]
    settings1 = ["亮度", "音量", "重定正前方", "锁定视角", "自动连播", "自动淡出",
                 "字幕", "虚拟光标", "唤出热区", "瞳距 IPD", "凝视时长"]
    settings2 = ["银幕宽度 · 2D", "画面缩放 · 球面", "鱼眼张角", "鱼眼画面圆",
                 "鱼眼全画幅", "恢复 200° · 圆形"]
    presets = ["180 通用", "190 RF", "200 MKX", "220 VRCA"]
    cabinets = ["本机", "NAS-客厅", "NAS-书房", "T7 Shield", "PC-工作室", "手机热点"]

    pages = []

    cards = [cell(i % 4, 1, 120 + (i // 4) * (fmt_h + gap), fmt_h) for i in range(12)]
    pages.append(("格式 · 12 格 4×3（含鱼眼 Mono/SBS）", cards, formats, footer([2])))

    cards = [cell((i % 2) * 2, 2, body_top + (i // 2) * (row_h + gap), row_h) for i in range(11)]
    pages.append(("设置 · 第 1 页（11 格 2×6）", cards, settings1, footer([1, 2, 1])))

    # 19:2x：第 2 页多了一行「画幅形态 + 恢复默认」，预设整体下移到 247。
    cards = [cell(0, 2, body_top, row_h), cell(2, 2, body_top, row_h),
             cell(0, 2, 125, row_h), cell(2, 2, 125, row_h),
             cell(0, 2, 186, row_h), cell(2, 2, 186, row_h)]
    cards += [cell(i, 1, 247, row_h) for i in range(4)]
    pages.append(("设置 · 第 2 页（4 参数 + 画幅开关 + 4 预设）", cards,
                  settings2 + presets, footer([2, 2])))

    cards = [cell((i % 2) * 2, 2, body_top + (i // 2) * (big_h + gap), big_h) for i in range(6)]
    pages.append(("换片库 · 6 柜 2×3", cards, cabinets, [cell(0, 2, btn_y, row_h)]))

    # 19:2x：播放页的拒绝卡。数值同步自 VrUiLayer.drawRefusal ——
    # 268 是正文首行基线、24 行高、12 段落间距、10 按钮前间距；
    # 正文 1 行、提示 2 行（文案长度由 check_format_and_view.py 钉住，超长会先在那里失败）。
    body_lines, hint_lines = 1, 2
    refusal_top, refusal_body_top = 128, 248
    refusal_buttons_top = (refusal_body_top + body_lines * 24 + 12
                           + hint_lines * 24 + 10)
    refusal_card = (int(c["CANVAS_W"] / 2 - span(3) / 2), refusal_top,
                    int(c["CANVAS_W"] / 2 - span(3) / 2) + span(3),
                    refusal_buttons_top + row_h + 16)
    pages.append(("播放 · 拒绝卡（未缝合双鱼眼 360）",
                  [refusal_card], ["说明卡 584×286（128–414）"],
                  [cell(1, 1, refusal_buttons_top, row_h), cell(2, 1, refusal_buttons_top, row_h)]))

    ok = True
    print(f"面板 {panel} · 内容区 {cl}..{cr}（{cw}）· 栏宽 {colw} · 缝 {gut}")
    for title, rects, labels, buttons in pages:
        print(f"\n{title}")
        for i, (r, label) in enumerate(zip(rects, labels)):
            if r[0] < cl - 0.5 or r[2] > cr + 0.5 or r[1] < panel[1] or r[3] > panel[3]:
                print(f"  FAIL 「{label}」越出内容区 {r}")
                ok = False
            if r[3] > btn_y - 4 and r[1] < btn_b:
                print(f"  FAIL 「{label}」压到按钮行 {r}")
                ok = False
        for i in range(len(rects)):
            for j in range(i + 1, len(rects)):
                a, b = rects[i], rects[j]
                if a[0] < b[2] and b[0] < a[2] and a[1] < b[3] and b[1] < a[3]:
                    print(f"  FAIL 重叠：「{labels[i]}」×「{labels[j]}」")
                    ok = False
        last = max(r[3] for r in rects)
        print(f"  卡片 {len(rects)} 个 · 末行下沿 {last:.0f} · 按钮顶 {btn_y:.0f}")
        for b in buttons:
            if b[3] > c["PANEL_B"] - 8:
                print(f"  FAIL 按钮越出面板 {b}")
                ok = False

    # ── 出图：四页竖排，每页带栏线 ──────────────────────────────────
    page_h = int(c["CANVAS_H"]) + 46
    img = Image.new("RGB", (int(c["CANVAS_W"]) + 80, page_h * len(pages) + 20), (12, 13, 16))
    d = ImageDraw.Draw(img)

    for idx, (title, rects, labels, buttons) in enumerate(pages):
        ox, oy = 40, 20 + idx * page_h
        d.text((ox, oy - 2), title, font=font(16), fill=INK)
        top = oy + 30
        d.rounded_rectangle([ox + panel[0], top + panel[1], ox + panel[2], top + panel[3]],
                            radius=12, fill=PANEL, outline=LINE)
        # 栏线（4 栏），让人一眼看出格子是不是贴着同一组列
        for i in range(5):
            x = ox + cl + i * step - (0 if i == 0 else gut)
            if i == 4:
                x = ox + cr
            d.line([x, top + panel[1], x, top + panel[3]], fill=GRID_C)
        for r, label in zip(rects, labels):
            d.rounded_rectangle([ox + r[0], top + r[1], ox + r[2], top + r[3]],
                                radius=8, fill=CARD)
            d.text(((ox + r[0] + ox + r[2]) / 2, (top + r[1] + top + r[3]) / 2),
                   label, font=font(15), fill=INK, anchor="mm")
        for b in buttons:
            d.rounded_rectangle([ox + b[0], top + b[1], ox + b[2], top + b[3]],
                                radius=8, fill=(28, 28, 30), outline=ACCENT)
            d.text(((ox + b[0] + ox + b[2]) / 2, (top + b[1] + top + b[3]) / 2),
                   "按钮", font=font(15), fill=ACCENT, anchor="mm")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"\n已生成 {OUT}")
    print("结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
