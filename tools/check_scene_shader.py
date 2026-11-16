"""
在本地真正编译一遍场景着色器 —— 不是「看起来对」，是让 GL 驱动判它能不能过。

为什么需要这一步：GLSL 是**运行时**编译的，`assembleDebug` 通过完全不代表
着色器能链接。真机上的表现是「场景层静默不画」，在日志里只留下一个
`disabled = true`，很难从现象倒推。所以交付前必须先在这里编译一次。

做法：从 `GlUtils.kt` 抽出两段三引号源码 → 把 ES 3.0 改写成桌面 GLSL 330
（`#version`、去掉 precision 语句）→ 用 moderngl 建上下文编译并链接。

桌面与 ES 的差异只在少数语法，类型系统、函数重载、循环与内建函数一致，
所以这一步能抓到绝大多数会让真机编译失败的问题（类型不匹配、未声明标识符、
参数个数、精度以外的语法错误）。

运行：python tools/check_scene_shader.py
"""

import re
import sys
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/gazereel/vr/render/GlUtils.kt"


def extract(source: str, name: str) -> str:
    m = re.search(rf'val {name} = """(.*?)"""', source, re.S)
    if not m:
        raise SystemExit(f"没找到 {name}")
    return textwrap.dedent(m.group(1)).strip("\n")


def to_desktop(glsl: str) -> str:
    """ES 3.0 → 桌面 GLSL 330。只改版本行与 precision 语句。"""
    lines = []
    for line in glsl.splitlines():
        s = line.strip()
        if s.startswith("#version"):
            lines.append("#version 330 core")
            continue
        if s.startswith("precision ") and s.endswith(";"):
            continue
        lines.append(line)
    return "\n".join(lines)


def main() -> int:
    src = SRC.read_text(encoding="utf-8")
    vs_es = extract(src, "SCENE_VERTEX")
    fs_es = extract(src, "SCENE_FRAGMENT")
    vs = to_desktop(vs_es)
    fs = to_desktop(fs_es)

    import moderngl

    try:
        ctx = moderngl.create_standalone_context(require=330)
    except Exception as e:  # noqa: BLE001
        print(f"建 GL 上下文失败（机器没有可用 GL 驱动？）：{e}")
        return 2

    print(f"上下文：{ctx.info.get('GL_VERSION', '?')}")

    # moderngl 5.x 没有单独编译一个 shader 的 API，直接建 program：
    # 失败时异常文本里带 GLSL 编译日志，足够定位行号。
    try:
        prog = ctx.program(vertex_shader=vs, fragment_shader=fs)
    except Exception as e:  # noqa: BLE001
        print("\n编译/链接失败，驱动日志如下：\n")
        print(e)
        return 1

    print(f"[SHADER] 编译并链接通过 · 顶点 {len(vs.splitlines())} 行 / 片元 {len(fs.splitlines())} 行")

    # 属性与 uniform 必须与 Kotlin 侧的名字逐一对上，否则一律 getAttribLocation = -1。
    want_attrib = {"aPosition", "aNormal", "aMat"}
    want_uniform = {"uMvp", "uLightDir", "uLuma"}
    got_attrib = {n for n in prog if n in want_attrib}
    got_uniform = {n for n in prog if n in want_uniform}

    print(f"[ATTRIB]  找到 {sorted(got_attrib)}")
    print(f"[UNIFORM] 找到 {sorted(got_uniform)}")

    ok = True
    miss_a = want_attrib - got_attrib
    miss_u = want_uniform - got_uniform
    if miss_a:
        print(f"  缺属性：{sorted(miss_a)}  → Kotlin 里 attrib() 会拿到 -1")
        ok = False
    if miss_u:
        print(f"  缺 uniform：{sorted(miss_u)}  → Kotlin 里 uniform() 会拿到 -1")
        ok = False

    print()
    print("着色器可用" if ok else "存在问题，不要交付")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
