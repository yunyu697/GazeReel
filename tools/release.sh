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
    # 实测踩到过：发版在 bump 之后被中断（超时/重试），留下一个「版本号已升但没提交、
    # tag 也没打」的文件。下次发版只会看到「1 项未提交」，不知道该还原还是该提交。
    if [ "$DIRTY" = "1" ] && [ -n "$(git status --porcelain -- version.properties)" ]; then
        die "只有 version.properties 是脏的，通常是上次发版中断留下的。先跑：
    git checkout -- version.properties
然后重发（否则会把一个没发出去的版本号带进这次提交）"
    fi
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

# ── 5. 核对：版本名进清单、新特性符号进 dex ──────────────────────────────
# 注意：版本名在 AndroidManifest（二进制 AXML），**不在 dex** —— App.kt 是运行时
# 读 PackageManager.versionName，编译期常量不进 dex。曾经在这里 grep dex 导致误报。
say "核对包内容"
AAPT=""
for cand in "${ANDROID_HOME:-}/build-tools/"*/aapt2* \
            "${LOCALAPPDATA:-}/Android/Sdk/build-tools/"*/aapt2*; do
    [ -e "$cand" ] && AAPT="$cand"
done
if [ -n "$AAPT" ]; then
    BADGE=$("$AAPT" dump badging "$DEST" 2>/dev/null | head -1 || true)
    case "$BADGE" in
        *"versionName='$VERSION'"*)
            printf '  %-24s OK\n' "manifest versionName" ;;
        *)
            printf '  %-24s MISMATCH\n' "manifest versionName"
            echo "    实际：$BADGE"
            die "清单里的 versionName 与 $VERSION 不一致" ;;
    esac
else
    printf '  %-24s 跳过（没找到 aapt2）\n' "manifest versionName"
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
unzip -o -q "$DEST" 'classes*.dex' -d "$TMP" || die "解不开 dex"

# 符号清单在 tools/release-symbols.txt，不写死在这里：
# 加一条主链路只改那个文件，脚本不用跟着改；清单为空也判失败，防止「核对形同虚设」。
SYMBOLS_FILE="tools/release-symbols.txt"
[ -f "$SYMBOLS_FILE" ] || die "缺少 $SYMBOLS_FILE，无法核对包内容"
SYMBOLS=$(grep -v -E '^\s*(#|$)' "$SYMBOLS_FILE" | tr -d '\r' || true)
COUNT=$(printf '%s\n' "$SYMBOLS" | grep -c . || true)
# grep -c 在零匹配时返回 1，set -e 虽被 || true 兜住，但变量会是空串，
# 下面 -gt 比较会报 "integer expression expected"。这里补成 0。
COUNT=${COUNT:-0}
[ "$COUNT" -gt 0 ] || die "$SYMBOLS_FILE 里没有符号，核对没有意义"

missing=0
while IFS= read -r s; do
    [ -n "$s" ] || continue
    if grep -a -q -- "$s" "$TMP"/*.dex > /dev/null 2>&1; then
        printf '  %-24s OK\n' "dex:$s"
    else
        printf '  %-24s MISSING\n' "dex:$s"
        missing=1
    fi
done <<< "$SYMBOLS"
[ "$missing" = "0" ] || die "包内缺少预期符号（$COUNT 项已核），产物可疑"

# ── 6. 提交与打 tag（仅 --commit）────────────────────────────────────────
if [ "$COMMIT" = "1" ]; then
    say "提交并打 tag"
    # 只提交版本号。用 git add -A 会把构建过程中任何意外产物一起吞进来，
    # 而工作树在发版前已经检查过必须干净，这里唯一该变的就是 version.properties。
    git add version.properties
    if git diff --cached --quiet; then
        say "版本号没有变化（--no-bump？），跳过提交，只打 tag"
    else
        # 断言条数不写进提交信息：这个数字每次都在变，写死过一次「283 条」，
        # 后来变成 323 条就成了假话，且改脚本还得同步改这里（自我指涉）。
        git commit -q -m "chore(release): $VERSION

一键发版：全量断言通过（明细见各 tools/check_*.py 输出），clean 构建通过，
产物符号清单已核。真机状态见 PROJECT_PLAN.md §6 验收清单。"
    fi
    git tag -a "v$VERSION" -m "$VERSION"
    git log -1 --oneline
    echo "tag: $(git tag -l "v$VERSION")"
    echo "提醒：tag 只在本地，推送需另跑 git push origin v$VERSION"
else
    say "未提交（要提交请加 --commit）"
    echo "当前工作树："
    git status --short
fi

say "完成 · $VERSION"
