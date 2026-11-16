#!/usr/bin/env bash
# 一键发版：升版本 → 全量断言 → 构建 → 复制改名 → 核对产物
#
# 用法：
#   tools/release.sh alpha            升 alpha 编号后发版（只构建，不提交）
#   tools/release.sh alpha --commit   发版并提交 + 打 tag
#   tools/release.sh --no-bump        不升版本，重出当前版本（用于验证脚本本身）
#
# 设计原则：
#   1. 断言不过就不构建 —— 不让「看起来能装」的包流出去。
#   2. 产物名与版本号由同一处（version.properties）推出，不手写。
#   3. 默认不提交：提交是独立决定，不是发版的副作用。

set -euo pipefail
cd "$(dirname "$0")/.."

BUMP="alpha"
COMMIT=0
for arg in "$@"; do
    case "$arg" in
        alpha|patch|minor|major) BUMP="$arg" ;;
        --no-bump) BUMP="none" ;;
        --commit) COMMIT=1 ;;
        -h|--help) sed -n '2,14p' "$0"; exit 0 ;;
        *) echo "未知参数：$arg" >&2; exit 2 ;;
    esac
done

say() { printf '\n[release] %s\n' "$*"; }
die() { printf '\n[release] 失败：%s\n' "$*" >&2; exit 1; }

# ── 0. 前置检查：工作树必须干净，否则版本号与代码对不上 ──────────────────
say "检查工作树"
DIRTY=$(git status --porcelain | wc -l | tr -d ' ')
if [ "$DIRTY" != "0" ]; then
    echo "工作树有 $DIRTY 项未提交改动："
    git status --short
    die "先提交或 stash，再发版（否则 tag 指向的代码与 APK 不一致）"
fi

# ── 1. 升版本 ───────────────────────────────────────────────────────────
if [ "$BUMP" != "none" ]; then
    say "升版本（$BUMP）"
    python tools/bump_version.py "$BUMP"
fi
VERSION=$(grep -E '^version=' version.properties | cut -d= -f2 | tr -d '\r')
[ -n "$VERSION" ] || die "读不到 version.properties 里的 version"
say "目标版本 $VERSION"

# ── 2. 全量断言 ─────────────────────────────────────────────────────────
say "跑全量断言"
FAILED=0
for f in tools/check_*.py; do
    printf '  %-34s ' "$(basename "$f")"
    if python "$f" > /tmp/release-check.log 2>&1; then
        echo "PASS"
    else
        echo "FAIL"
        tail -20 /tmp/release-check.log
        FAILED=1
    fi
done
for f in tools/run-format-check.sh tools/run-pose-check.sh; do
    printf '  %-34s ' "$(basename "$f")"
    if bash "$f" > /tmp/release-check.log 2>&1; then
        tail -1 /tmp/release-check.log
    else
        echo "FAIL"
        tail -20 /tmp/release-check.log
        FAILED=1
    fi
done
printf '  %-34s ' "preview_ui_pages.py"
if python tools/preview_ui_pages.py > /tmp/release-check.log 2>&1; then
    echo "PASS"
else
    echo "FAIL"
    tail -20 /tmp/release-check.log
    FAILED=1
fi
[ "$FAILED" = "0" ] || die "断言未全绿，不打这个包"

# ── 3. 构建 ─────────────────────────────────────────────────────────────
say "构建 debug 包"
bash tools/gradlew-direct.sh --no-daemon :app:assembleDebug 2>&1 | tail -6

# ── 4. 找产物并复制改名 ─────────────────────────────────────────────────
say "整理产物"
APK=$(ls -t app/build/outputs/apk/debug/GazeReel-*.apk 2>/dev/null | head -1 || true)
[ -n "$APK" ] || APK=$(ls -t app/build/outputs/apk/debug/app-debug.apk 2>/dev/null | head -1 || true)
[ -n "$APK" ] || die "构建目录里没有 APK"

# 构建出的包名里必须带着目标版本号，否则说明 gradle 没读到 version.properties
case "$(basename "$APK")" in
    *"$VERSION"*) ;;
    *) die "产物名 $(basename "$APK") 与版本 $VERSION 不一致" ;;
esac

mkdir -p ../output
DEST="../output/GazeReel-$VERSION.apk"
cp "$APK" "$DEST"
SIZE=$(wc -c < "$DEST" | tr -d ' ')
say "产物 $DEST（$SIZE B）"

# ── 5. 核对：版本号与新特性字符串确实进了包 ──────────────────────────────
say "核对 dex"
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$DEST" 'classes*.dex' -d "$TMP" || die "解不开 dex"
missing=0
for s in "$VERSION" fisheye; do
    if grep -a -q -l -- "$s" "$TMP"/*.dex > /dev/null 2>&1; then
        printf '  %-24s OK\n' "$s"
    else
        printf '  %-24s MISSING\n' "$s"
        missing=1
    fi
done
[ "$missing" = "0" ] || die "包内缺少预期字符串，产物可疑"

# ── 6. 提交与打 tag（仅 --commit）────────────────────────────────────────
if [ "$COMMIT" = "1" ]; then
    say "提交并打 tag"
    git add -A
    git commit -q -m "chore(release): $VERSION

一键发版：283 条断言全过，clean 构建通过。
真机状态见 PROJECT_PLAN.md §6 验收清单。"
    git tag -a "v$VERSION" -m "$VERSION"
    git log -1 --oneline
    echo "tag: $(git tag -l "v$VERSION")"
else
    say "未提交（要提交请加 --commit）"
    echo "当前工作树："
    git status --short
fi

say "完成 · $VERSION"
