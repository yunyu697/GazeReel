"""影院几何自检：把 SceneRoom / ProjectionMesh 里的常量算一遍，核对前后关系。

为什么要有这个脚本：
17:2x 之前 `SCREEN_CENTER_Y = 0`，银幕半高 1.519 而地板在 −1.25，于是银幕下沿
陷进地板 27 厘米，前墙「洞口下方」那块 quad 的 y 范围是反的（−1.25 → −1.519），
等于那块墙根本不存在。用户看到的就是「银幕立在地上」。

这类错误看不出、也测不出（编译通过、着色器链接通过），只能量。
所以每次改 SCREEN_* / FLOOR_Y / FRAME_* / CEIL_Y 都要先跑这个脚本。

用法：python tools/check_cinema_geometry.py
"""

import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
MESH = ROOT / "app/src/main/java/com/gazereel/vr/render/ProjectionMesh.kt"
ROOM = ROOT / "app/src/main/java/com/gazereel/vr/render/SceneRoom.kt"


def consts(path: Path) -> dict:
    """抓 `const val NAME = <数字表达式>`，表达式就地求值（只放行数字与已知标识符）。

    按行扫描而不是整文件正则：`SCREEN_HEIGHT = SCREEN_WIDTH * 9f / 16f` 里的 `/`
    会被当成注释开头，把表达式截断成 `SCREEN_WIDTH * 9`，得出 48.6 米这种荒谬值。
    """
    found = {}
    for raw in path.read_text(encoding="utf-8").splitlines():
        line = raw.split("//")[0]
        mm = re.search(
            r"const\s+val\s+([A-Z][A-Z0-9_]*)\s*(?::\s*\w+)?\s*=\s*(.+)$", line
        )
        if not mm:
            continue
        name = mm.group(1)
        expr = mm.group(2).strip().rstrip(",")
        # 只处理能安全求值的：数字、四则运算、已解析过的常量名
        if not re.fullmatch(r"[0-9fFlL.\s+\-*/()A-Z_]+", expr):
            continue
        py = re.sub(r"(\d)[fF]\b", r"\1", expr)
        try:
            found[name] = float(eval(py, {"__builtins__": {}}, dict(found)))  # noqa: S307
        except Exception:  # noqa: BLE001
            continue
    return found


def main() -> int:
    m = consts(MESH)
    r = consts(ROOM)

    dist = m["SCREEN_DISTANCE"]
    w = m["SCREEN_WIDTH"]
    h = m["SCREEN_HEIGHT"]
    cy = m["SCREEN_CENTER_Y"]
    floor = r["FLOOR_Y"]
    ceil = r["CEIL_Y"]

    half_h = h / 2
    s_bot = cy - half_h
    s_top = cy + half_h

    frame_w = r.get("FRAME_W", 0.28)
    frame_bot = r.get("FRAME_BOTTOM", 0.10)
    curtain = r.get("SIDE_CURTAIN_W", 0.0)
    f_bot = s_bot - frame_bot
    f_top = s_top + frame_w

    print("── 银幕 ──")
    print(f"  距离 {dist:.2f} m · 尺寸 {w:.2f} × {h:.3f} m")
    print(f"  世界 y：底 {s_bot:+.3f} / 顶 {s_top:+.3f} / 中心 {cy:+.3f}")
    print(f"  地板 y = {floor:+.3f}  天花 y = {ceil:+.3f}")
    print(f"  底边离地 {s_bot - floor:+.3f} m   （必须 > 0，否则幕陷进地里）")
    print(f"  顶边距天花 {ceil - s_top:+.3f} m")

    hfov = 2 * math.degrees(math.atan(w / 2 / dist))
    vfov = 2 * math.degrees(math.atan(half_h / dist))
    print(f"  张角 水平 {hfov:.1f}° / 垂直 {vfov:.1f}°")
    print(f"  画面中心对视线：{math.degrees(math.atan(cy / dist)):+.1f}°（正 = 要抬头）")

    print("── 前墙（按银幕开洞）──")
    hole_below = s_bot - floor
    hole_above = ceil - s_top
    print(f"  洞下方墙高 {hole_below:+.3f} m  {'OK' if hole_below > 0.2 else 'FAIL（墙裙不存在或过窄）'}")
    print(f"  洞上方墙高 {hole_above:+.3f} m  {'OK' if hole_above > 0 else 'FAIL（洞比天花开得还高）'}")

    print("── 幕框 / 侧幕 / 幕头 ──")
    print(f"  下幕框：{f_bot:+.3f} → {s_bot:+.3f}（高 {frame_bot:.2f}）", end="")
    print(f"  离地 {f_bot - floor:+.3f} m  {'OK' if f_bot > floor else 'FAIL（下幕框埋进地板）'}")
    print(f"  可见墙裙 = 幕下方露出的墙：{(f_bot - floor):+.3f} m  "
          f"{'OK' if f_bot - floor > 0.15 else 'FAIL（墙裙被幕框吃掉，看着还是落地幕）'}")
    print(f"  上幕框顶 {f_top:+.3f} m")
    print(f"  侧幕：{curtain:.2f} m 宽，{floor:+.2f} → {f_top:+.2f}（落地）")
    print(f"  幕头：{f_top:+.2f} → {ceil:+.2f}（高 {ceil - f_top:.2f}）  "
          f"{'OK' if ceil > f_top else 'FAIL（幕头顶到天花下面还有缝）'}")

    print("── 与 UI 面板的关系 ──")
    # 播放页面板 pitch −13°，画布 512px 覆盖 ±16.7°，面板 2.4×1.2 m 放 2 m
    pitch = -13.0
    half_v = math.degrees(math.atan(0.6 / 2.0))
    top_deg = pitch + half_v
    bot_deg = pitch - half_v
    print(f"  画布 y=0   → 视线 {top_deg:+.1f}°")
    print(f"  画布 y=512 → 视线 {bot_deg:+.1f}°")
    for y, name in ((22, "上唤醒锚点"), (488, "下唤醒锚点"), (240, "控制台上沿")):
        deg = top_deg - (y / 512) * (top_deg - bot_deg)
        on_screen = s_bot / dist  # 用角度比较
        inside = math.degrees(math.atan(s_bot / dist)) <= deg <= math.degrees(math.atan(s_top / dist))
        print(f"  {name} y={y:>3} → {deg:+.1f}°  {'压在银幕画面上' if inside else '在银幕之外（厅内）'}")

    ok = True
    checks = [
        ("银幕不穿地", s_bot > floor + 0.01),
        ("洞下方有墙", hole_below > 0.2),
        ("洞上方有墙", hole_above > 0.0),
        ("下幕框不穿地", f_bot > floor),
        ("墙裙可见（>0.15m）", f_bot - floor > 0.15),
        ("幕头封到天花", ceil - f_top > 0.0),
        ("侧幕已定义", curtain > 0.0),
    ]
    print("\n判定：")
    for label, cond in checks:
        print(f"  {label:<22} {'OK' if cond else 'FAIL'}")
        ok &= cond
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
