#!/usr/bin/env python3
"""GazeReel / 凝影 版本号工具。

唯一真相源是根目录的 version.properties。这个脚本负责两件事：
1. 按用户定的规则升版本，并在段位越界时自动进位（不会产出 1.11.11-alpha1）；
2. 校验现有版本号是否合法。

规则：
    major 无上限
    minor / patch 不得超过 10（不能出现 11）
    alpha 编号无上限
    有重大更新时进位，alpha 重新从 1 开始
    正式完工之前只发 alpha

用法：
    python3 tools/bump_version.py               # 显示当前版本
    python3 tools/bump_version.py alpha         # 1.0.0-alpha1 → 1.0.0-alpha2
    python3 tools/bump_version.py patch         # 1.0.0-alpha3 → 1.0.1-alpha1
    python3 tools/bump_version.py minor         # 1.0.10-alpha2 → 1.1.0-alpha1
    python3 tools/bump_version.py major         # 1.10.10-alpha9 → 2.0.0-alpha1
    python3 tools/bump_version.py --check       # 只校验，不写文件
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "version.properties"
PATTERN = re.compile(r"^(\d+)\.(\d+)\.(\d+)(?:-alpha(\d+))?$")

MAX_MINOR = 10
MAX_PATCH = 10
# versionCode 是 32 位整数；分段是 major(1e8) | minor(1e6) | patch(1e4) | alpha 或 9000。
MAX_MAJOR = 21
MAX_ALPHA = 8999


class Version:
    def __init__(self, major: int, minor: int, patch: int, alpha: int | None):
        self.major = major
        self.minor = minor
        self.patch = patch
        self.alpha = alpha

    @classmethod
    def parse(cls, text: str) -> "Version":
        m = PATTERN.match(text.strip())
        if not m:
            raise SystemExit(
                f"版本号格式错误：{text!r}\n应为 <major>.<minor>.<patch>[-alpha<N>]，例如 1.0.0-alpha1"
            )
        major, minor, patch, alpha = m.groups()
        return cls(int(major), int(minor), int(patch), int(alpha) if alpha else None)

    def __str__(self) -> str:
        base = f"{self.major}.{self.minor}.{self.patch}"
        return base if self.alpha is None else f"{base}-alpha{self.alpha}"

    def version_code(self) -> int:
        # 必须与 app/build.gradle.kts 里的算法保持一致。
        tail = self.alpha if self.alpha is not None else 9000
        return self.major * 100_000_000 + self.minor * 1_000_000 + self.patch * 10_000 + tail

    def validate(self) -> list[str]:
        problems = []
        if not 1 <= self.major <= MAX_MAJOR:
            problems.append(f"major 必须在 1..{MAX_MAJOR}（versionCode 会溢出）：{self}")
        if not 0 <= self.minor <= MAX_MINOR:
            problems.append(f"minor 不能超过 {MAX_MINOR}（不能出现 11）：{self}")
        if not 0 <= self.patch <= MAX_PATCH:
            problems.append(f"patch 不能超过 {MAX_PATCH}（不能出现 11）：{self}")
        if self.alpha is not None and not 1 <= self.alpha <= MAX_ALPHA:
            problems.append(f"alpha 编号必须在 1..{MAX_ALPHA}：{self}")
        return problems


def bump(current: Version, part: str) -> Version:
    major, minor, patch = current.major, current.minor, current.patch
    alpha = current.alpha or 0

    if part == "alpha":
        return Version(major, minor, patch, alpha + 1)

    if part == "patch":
        patch += 1
        if patch > MAX_PATCH:
            patch, minor = 0, minor + 1
    elif part == "minor":
        patch, minor = 0, minor + 1
    elif part == "major":
        major, minor, patch = major + 1, 0, 0
    else:
        raise SystemExit(f"不认识的段位：{part}（可用：alpha / patch / minor / major）")

    if minor > MAX_MINOR:
        minor, major = 0, major + 1

    return Version(major, minor, patch, 1)


def read_version() -> Version:
    if not VERSION_FILE.exists():
        raise SystemExit(f"找不到 {VERSION_FILE}")
    for line in VERSION_FILE.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("version="):
            return Version.parse(stripped.split("=", 1)[1])
    raise SystemExit("version.properties 里没有 version= 这一行")


def write_version(version: Version) -> None:
    text = VERSION_FILE.read_text(encoding="utf-8")
    updated = re.sub(
        r"^version=.*$",
        f"version={version}",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    VERSION_FILE.write_text(updated, encoding="utf-8")


def main(argv: list[str]) -> int:
    current = read_version()

    if not argv or argv[0] in ("show", "--show"):
        print(f"当前版本  {current}")
        print(f"versionCode  {current.version_code()}")
        problems = current.validate()
        if problems:
            print("\n有问题：")
            for p in problems:
                print(f"  · {p}")
            return 1
        return 0

    if argv[0] == "--check":
        problems = current.validate()
        if problems:
            for p in problems:
                print(f"版本号不合法：{p}", file=sys.stderr)
            return 1
        print(f"版本号合法：{current}（versionCode {current.version_code()}）")
        return 0

    part = argv[0]
    nxt = bump(current, part)
    problems = nxt.validate()
    if problems:
        for p in problems:
            print(f"进位后仍不合法：{p}", file=sys.stderr)
        return 1

    write_version(nxt)
    print(f"{current}  →  {nxt}")
    print(f"versionCode  {current.version_code()}  →  {nxt.version_code()}")
    print(f"\n下一步：git add version.properties && git commit，然后 git tag v{nxt} 触发发版")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
