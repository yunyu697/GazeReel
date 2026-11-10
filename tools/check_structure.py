#!/usr/bin/env python3
"""Android 工程骨架静态校验（无需 Android SDK / Gradle）。

检查项：
1. Kotlin package 声明与目录路径一致；
2. 项目内 import 都能解析到已声明的类 / 顶层函数；
3. 括号平衡（跳过注释与字符串）；
4. 所有 XML 可解析，@string/@color/@array/@style/@drawable 引用均有定义；
5. build.gradle.kts 中的 libs.* version catalog alias 都存在。
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
MAIN = ROOT / "app/src/main"
KOTLIN_DIRS = [MAIN / "java", ROOT / "app/src/cardboard/java"]
RES = MAIN / "res"

errors = []
warnings = []

PKG = re.compile(r"^package\s+([\w.]+)", re.M)
DECL = re.compile(
    r"^(?:(?:data|sealed|enum|inner|abstract|open|private|internal)\s+)*"
    r"(?:class|object|interface)\s+(\w+)", re.M
)
FUN = re.compile(
    r"^(?:(?:internal|private|public|inline|suspend)\s+)*fun\s+(?:[\w<>]+\.)?(\w+)", re.M
)
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"


def kotlin_files():
    result = []
    for directory in KOTLIN_DIRS:
        if directory.exists():
            result += sorted(directory.rglob("*.kt"))
    return result


def expected_package(path: Path) -> str:
    parts = path.parts
    index = parts.index("java")
    return ".".join(parts[index + 1:-1])


def check_balance(source: str, path: Path):
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    state = "code"
    i = 0
    line = 1
    n = len(source)
    while i < n:
        c = source[i]
        nxt = source[i + 1] if i + 1 < n else ""
        if c == "\n":
            line += 1
        if state == "code":
            if c == "/" and nxt == "/":
                state = "line"; i += 2; continue
            if c == "/" and nxt == "*":
                state = "block"; i += 2; continue
            if source[i:i + 3] == '"""':
                state = "triple"; i += 3; continue
            if c == '"':
                state = "str"; i += 1; continue
            if c == "'":
                state = "char"; i += 1; continue
            if c in "([{":
                stack.append((c, line))
            elif c in ")]}":
                if not stack or stack[-1][0] != pairs[c]:
                    errors.append(f"{path.name}:{line} 括号不匹配 '{c}'")
                    return
                stack.pop()
            i += 1
            continue
        if state == "line":
            if c == "\n": state = "code"
            i += 1; continue
        if state == "block":
            if c == "*" and nxt == "/": state = "code"; i += 2; continue
            i += 1; continue
        if state == "triple":
            if source[i:i + 3] == '"""': state = "code"; i += 3; continue
            i += 1; continue
        if state in ("str", "char"):
            if c == "\\": i += 2; continue
            if (state == "str" and c == '"') or (state == "char" and c == "'"):
                state = "code"
            i += 1; continue
    if stack:
        errors.append(f"{path.name} 未闭合括号 {stack[-3:]}")


def collect_symbols(files):
    known = set()
    sources = {}
    for path in files:
        source = path.read_text(encoding="utf-8")
        sources[path] = source
        found = PKG.findall(source)
        if not found:
            errors.append(f"{path.name} 缺少 package 声明")
            continue
        package = found[0]
        expected = expected_package(path)
        if package != expected:
            errors.append(f"{path.name} package={package}，应为 {expected}")
        for name in DECL.findall(source) + FUN.findall(source):
            known.add(f"{package}.{name}")
    # 由 AGP / ViewBinding 生成：R 与每个 res/layout/*.xml 对应的 *Binding。
    # 从布局文件动态推导，避免每加一个布局就要回来改白名单。
    known.add("com.gazereel.vr.R")
    layout_dir = RES / "layout"
    if layout_dir.exists():
        for layout in layout_dir.glob("*.xml"):
            class_name = "".join(part.capitalize() for part in layout.stem.split("_"))
            known.add(f"com.gazereel.vr.databinding.{class_name}Binding")
    return known, sources


def check_imports(known, sources):
    for path, source in sources.items():
        for imp in re.findall(r"^import\s+([\w.]+)", source, re.M):
            if imp.startswith("com.gazereel.vr.") and imp not in known:
                errors.append(f"{path.name} 引用了不存在的类/函数：{imp}")


def collect_resources():
    defined = {}
    for path in sorted((RES / "values").glob("*.xml")):
        root = ET.parse(path).getroot()
        for element in root.iter():
            name = element.get("name") or element.get(ANDROID_NAME)
            if name:
                defined.setdefault(element.tag, set()).add(name)
    drawables = {p.stem for p in (RES / "drawable").glob("*.xml")}
    return defined, drawables


def check_xml(defined, drawables):
    xml_files = sorted(MAIN.rglob("*.xml"))
    tag_to_kind = {
        "string": "string", "color": "color", "string-array": "array",
        "style": "style", "integer": "integer", "bool": "bool",
    }
    for path in xml_files:
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            errors.append(f"XML 解析失败 {path.name}: {exc}")
            continue
        text = path.read_text(encoding="utf-8")
        for kind, name in re.findall(r"@(string|color|array|style|drawable)/([\w.]+)", text):
            # Material Components / AppCompat 的父样式由外部依赖提供
            if kind == "style" and name.startswith(
                ("Widget.MaterialComponents", "Theme.MaterialComponents", "TextAppearance.MaterialComponents")
            ):
                continue
            if kind == "drawable":
                if name not in drawables:
                    errors.append(f"{path.name} 引用了不存在的 drawable：{name}")
                continue
            tag = next((t for t, k in tag_to_kind.items() if k == kind), None)
            if tag is None or name not in defined.get(tag, set()):
                errors.append(f"{path.name} 引用了未定义的 @{kind}/{name}")
    return xml_files


def check_kotlin_resources(defined, drawables, sources):
    for path, source in sources.items():
        for kind, name in re.findall(r"\bR\.(string|drawable|layout|color)/([\w.]+)", source):
            if kind == "drawable" and name not in drawables:
                errors.append(f"{path.name} R.drawable.{name} 不存在")
            elif kind == "string" and name not in defined.get("string", set()):
                errors.append(f"{path.name} R.string.{name} 不存在")


def check_catalog():
    toml_path = ROOT / "gradle/libs.versions.toml"
    section = None
    libraries, plugins = set(), set()
    for raw in toml_path.read_text(encoding="utf-8").splitlines():
        line = raw.strip()
        if line.startswith("[") and line.endswith("]"):
            section = line.strip("[]")
            continue
        match = re.match(r"^([\w.-]+)\s*=", line)
        if not match:
            continue
        if section == "libraries":
            libraries.add(match.group(1))
        elif section == "plugins":
            plugins.add(match.group(1))
    gradle_files = [ROOT / "build.gradle.kts", ROOT / "settings.gradle.kts", ROOT / "app/build.gradle.kts"]
    for path in gradle_files:
        text = path.read_text(encoding="utf-8")
        for chain in re.findall(r"\blibs\.([\w.]+)", text):
            if chain.startswith("versions.toml"):
                continue  # 注释里提到的文件名，不是 alias
            parts = chain.split(".")
            if parts[0] == "plugins":
                key = "-".join(parts[1:])
                valid = key in plugins
            else:
                key = "-".join(parts)
                valid = key in libraries
            if not valid:
                errors.append(f"{path.name} 使用了未定义的 version catalog alias：libs.{chain}")
    return libraries, plugins


def main():
    files = kotlin_files()
    known, sources = collect_symbols(files)
    check_imports(known, sources)
    for path, source in sources.items():
        check_balance(source, path)
    defined, drawables = collect_resources()
    xml_files = check_xml(defined, drawables)
    check_kotlin_resources(defined, drawables, sources)
    libraries, plugins = check_catalog()

    total_lines = sum(s.count("\n") + 1 for s in sources.values())
    print(f"Kotlin 文件: {len(files)}  行数: {total_lines}")
    print(f"XML 文件: {len(xml_files)}  version catalog: {len(libraries)} libraries / {len(plugins)} plugins")
    print(f"项目内符号: {len(known)}  资源: {sum(len(v) for v in defined.values())} values / {len(drawables)} drawables")
    if warnings:
        print("\n警告:")
        for w in warnings: print(" -", w)
    if errors:
        print(f"\n校验失败，共 {len(errors)} 个问题:")
        for e in errors: print(" -", e)
        return 1
    print("\n静态校验通过：包路径、跨模块引用、括号、XML 资源、Gradle alias 全部一致。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
