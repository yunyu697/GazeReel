#!/usr/bin/env python3
"""UI 规范 v1 的静态校验（19:0x）。

为什么要有这个脚本：规范写在文档里没人守，一周后又会冒出新宽度。这里把它变成
三条机器判据：

1. **栅格自洽** —— `UiGrid` 里的常量必须真的等于「4 栏 + 3 缝 = 内容区」；
2. **浮层不许裸坐标** —— 改动范围内的浮层函数里，出现四个纯数字的 `RectF(...)`
   就失败（例如 `RectF(20f, 14f, 1004f, 498f)`，那是上一版「拼起来」的来源）；
3. **字号只在阶梯上** —— `textSize = Nf` 必须是 14/15/17/21/26/30/40 之一；
   44（海报时刻）与 60（对齐倒计时）是页面级例外，见 `UI_GRID.md`。

用法：python tools/check_ui_grid.py
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
UI = ROOT / "app/src/main/java/com/gazereel/vr/render/VrUiLayer.kt"
GRID = ROOT / "app/src/main/java/com/gazereel/vr/render/UiGrid.kt"

ok = True


def ck(name, cond, detail=""):
    global ok
    print(f"  {name:<52} {'OK' if cond else 'FAIL'}" + (f"  {detail}" if detail and not cond else ""))
    if not cond:
        ok = False


def parse_consts(src):
    """把 `const val X = 表达式` 折成数值；表达式里只允许四则运算与已解析的名字。"""
    out = {}
    literal = re.compile(r"const val (\w+) = (.+)")
    for line in src.splitlines():
        m = literal.search(line.strip())
        if not m:
            continue
        name, expr = m.group(1), m.group(2).strip()
        expr = expr.split("//")[0].strip()
        expr = re.sub(r"(\d)f\b", r"\1", expr)
        try:
            out[name] = eval(expr, {"__builtins__": {}}, dict(out))  # noqa: S307
        except Exception:
            continue
    return out


def function_body(src, name):
    m = re.search(rf"private fun {name}\(.*?\n    \}}", src, re.S)
    return m.group(0) if m else ""


def main():
    src = UI.read_text(encoding="utf-8")
    grid = GRID.read_text(encoding="utf-8")
    c = parse_consts(grid)

    print("── 一、栅格自洽 ──")
    need = ["CANVAS_W", "CANVAS_H", "SAFE", "PANEL_W", "PANEL_H", "PANEL_L", "PANEL_T",
            "PANEL_R", "PANEL_B", "PAD", "CONTENT_L", "CONTENT_W", "CONTENT_R",
            "COL_W", "GUTTER", "COL_STEP", "H_ROW", "H_FMT", "H_BIG",
            "BUTTON_Y", "BUTTON_B", "BODY_TOP", "BODY_BOTTOM_MAX"]
    missing = [k for k in need if k not in c]
    ck("UiGrid 常量齐全", not missing, f"缺 {missing}")
    if missing:
        print("\n结论：存在 FAIL")
        return 1

    ck("面板宽 = 内容区 + 两侧内边距", abs(c["PANEL_W"] - (c["CONTENT_W"] + 2 * c["PAD"])) < 0.01)
    ck("内容区 = 4 栏 + 3 缝", abs(c["CONTENT_W"] - (4 * c["COL_W"] + 3 * c["GUTTER"])) < 0.01,
       f"{c['CONTENT_W']} vs {4 * c['COL_W'] + 3 * c['GUTTER']}")
    ck("栏距 = 栏宽 + 缝", abs(c["COL_STEP"] - (c["COL_W"] + c["GUTTER"])) < 0.01)
    ck("面板水平居中", abs(c["PANEL_L"] - (c["CANVAS_W"] - c["PANEL_W"]) / 2) < 0.01)
    ck("面板垂直居中", abs(c["PANEL_T"] - (c["CANVAS_H"] - c["PANEL_H"]) / 2) < 0.01)
    ck("面板下沿 = 上沿 + 高", abs(c["PANEL_B"] - (c["PANEL_T"] + c["PANEL_H"])) < 0.01)
    ck("按钮下沿不越面板（留 ≥8）", c["BUTTON_B"] <= c["PANEL_B"] - 8,
       f"{c['BUTTON_B']} > {c['PANEL_B'] - 8}")
    ck("最后一行卡片下沿在按钮上方", c["BODY_BOTTOM_MAX"] < c["BUTTON_Y"])
    ck("内容区不越安全区", c["CONTENT_L"] >= c["SAFE"] and c["CONTENT_R"] <= c["CANVAS_W"] - c["SAFE"])

    print("\n── 二、浮层不许裸坐标 ──")
    overlays = ["drawSettings", "drawFormat", "drawCabinets", "drawMessage",
                "drawPosturePrompt", "drawAdjustFeedback", "cabinetCard"]
    literal_rect = re.compile(
        r"RectF\(\s*-?\d+(?:\.\d+)?f\s*,\s*-?\d+(?:\.\d+)?f\s*,"
        r"\s*-?\d+(?:\.\d+)?f\s*,\s*-?\d+(?:\.\d+)?f\s*\)"
    )
    for name in overlays:
        body = function_body(src, name)
        ck(f"{name} 存在", bool(body))
        if not body:
            continue
        hits = literal_rect.findall(body)
        ck(f"{name} 无四个纯数字的 RectF", not hits, f"{hits[:2]}")

    print("\n── 三、面板统一 ──")
    for name in ("drawSettings", "drawFormat", "drawCabinets"):
        ck(f"{name} 用 UiGrid.panel()", "panel(UiGrid.panel())" in function_body(src, name))

    print("\n── 四、字号只在阶梯上 ──")
    allowed = {14, 15, 17, 21, 26, 30, 40, 44, 60}
    bad = []
    for m in re.finditer(r"textSize = (\d+(?:\.\d+)?)f", src):
        v = float(m.group(1))
        if v not in allowed:
            line = src[: m.start()].count("\n") + 1
            bad.append(f"{line}:{v:g}")
    ck("textSize 只用阶梯字号", not bad, ", ".join(bad[:6]))

    print("\n── 五、组件化 ──")
    ck("UiGrid 被浮层实际引用", src.count("UiGrid.") >= 20, f"仅 {src.count('UiGrid.')} 处")
    ck("设置卡片走 settingCard()", "private fun settingCard(" in src)
    ck("按钮统一走 button()", "private fun button(" in src)

    print("\n结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
