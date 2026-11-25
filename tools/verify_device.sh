#!/usr/bin/env bash
# 真机验收的驱动与取证：装包 → 推片源 → 逐项点名起播 → 截图 + 运行日志切片 → 汇总表。
#
# 为什么值得有它：九项验收每一项都要「放指定的那一部片」，而手机塞进纸盒之后没法翻页。
# 手工做这件事有三个稳定出错的地方——点错格子、截图忘了命名、回来对不上「哪张图是哪一项」。
# 这个脚本把三件事都消掉：片名由脚本点名（app 侧 `ClipRequest` 负责落片），
# 证据按项命名，回执直接从运行日志里读（`verify 命中/未命中`）。
#
# 它**不判定合格与否**——「圆是不是正圆」只能人眼看。它只保证：
# ① 放的是那一部片；② 证据存下来了、名字对得上；③ 没起播时立刻红，不让人对着黑屏瞎猜。
#
# 用法：
#   bash tools/verify_device.sh                 # 九项全跑
#   bash tools/verify_device.sh V4 V5           # 只跑点名的
#   bash tools/verify_device.sh --list          # 只看项与片源的对应
#   bash tools/verify_device.sh --no-install    # 已经装好了，跳过装包
#   WAIT_SEC=20 bash tools/verify_device.sh     # 慢机器上等久一点
#
# 环境变量：ADB / APK / CLIPS_DIR / SHOT_DIR / WAIT_SEC
set -uo pipefail

cd "$(dirname "$0")/.." || exit 1

PKG=com.gazereel.vr
LAUNCHER="$PKG/.ui.VerifyLaunchActivity"
VERSION=$(grep -E '^version=' version.properties | cut -d= -f2)
APK=${APK:-../output/GazeReel-$VERSION.apk}
CLIPS=${CLIPS_DIR:-../output/testclips}
SHOT=${SHOT_DIR:-../output/verify/$VERSION}
WAIT_SEC=${WAIT_SEC:-12}
INDEX_WAIT_SEC=${INDEX_WAIT_SEC:-5}
DO_INSTALL=1
ITEMS=()

clip_of() {
  case "$1" in
    V1 | V9) echo test-360-equirect-mono ;;
    V2) echo test-180-sbs ;;
    V3) echo test-2d-grid-720p ;;
    V4 | V6) echo test-fisheye-circle-mono ;;
    V5) echo test-fisheye-fullframe-mono ;;
    V7) echo test-dual-fisheye-unstitched ;;
    V8) echo "" ;;   # 设置页落点，不需要片源
    *) return 1 ;;
  esac
}

# 每项在「起播之后」还要人做的事。脚本做不到的部分明说，不假装自动。
note_of() {
  case "$1" in
    V1) echo "左右转一整圈看接缝、抬头到顶、低头到底" ;;
    V2) echo "左右各转到画面边缘，边界应正好在左右 90°" ;;
    V3) echo "进设置第 2 页，把「银幕宽度」在 80/100/110% 各停一次，记下最舒服的一档" ;;
    V4) echo "首次要在设置第 2 页选 200° 预设（选一次会被记住）；视线沿圆边走一圈" ;;
    V5) echo "看四个角、再扫到画面最边缘，找放射状条纹" ;;
    V6) echo "在 V4 正常的状态下打开「鱼眼全画幅」，看是否还能认出是同一画面" ;;
    V7) echo "确认说明卡出现、两行提示不被裁、两个按钮都点得动" ;;
    V8) echo "手动进设置第 2 页，依次指 6 张卡 + 4 个预设 + 2 个底部按钮" ;;
    V9) echo "从银幕中心低头/抬头 68° 各停 2.4 秒，重复 5 次；再正常看 10 分钟看会不会误触" ;;
    *) echo "" ;;
  esac
}

usage() {
  # 头注就是用法：从第 2 行打到 `set -` 之前。写死行号会在加两行注释后就漏字或漏出代码。
  sed -n '2,/^set -/p' "$0" | sed '$d' | sed 's/^# \{0,1\}//'
  exit 0
}

list_items() {
  printf '%-4s %-30s %s\n' 项 片源 起播
  for v in V1 V2 V3 V4 V5 V6 V7 V8 V9; do
    c=$(clip_of "$v")
    if [ -z "$c" ]; then
      printf '%-4s %-30s %s\n' "$v" "（不需要片源）" "手动"
    else
      printf '%-4s %-30s %s\n' "$v" "$c" "自动"
    fi
  done
}

for arg in "$@"; do
  case "$arg" in
    -h | --help) usage ;;
    --list) list_items; exit 0 ;;
    --no-install) DO_INSTALL=0 ;;
    V[1-9]) ITEMS+=("$arg") ;;
    *) echo "不认识的参数：$arg（用 --list 看项号）" >&2; exit 1 ;;
  esac
done
[ ${#ITEMS[@]} -eq 0 ] && ITEMS=(V1 V2 V3 V4 V5 V6 V7 V8 V9)

die() { echo "✗ $*" >&2; exit 1; }

# ---------- 工具与产物 ----------
ADB=${ADB:-}
if [ -z "$ADB" ]; then
  ADB=$(command -v adb 2>/dev/null || true)
fi
if [ -z "$ADB" ] && [ -n "${ANDROID_HOME:-}" ] && [ -x "$ANDROID_HOME/platform-tools/adb" ]; then
  ADB=$ANDROID_HOME/platform-tools/adb
fi
[ -n "$ADB" ] || die "找不到 adb。装 Android 平台工具，或设 ADB=/path/to/adb"
[ -x "$APK" ] || [ -f "$APK" ] || die "找不到安装包 $APK（先跑 tools/release.sh，或用 APK=... 指定）"
[ -d "$CLIPS" ] || die "找不到测试片源 $CLIPS（先跑 python tools/make_testclips.py）"

# 设备必须在、且只有一个在线：多设备时 am/pm 会「more than one device」，报错很难懂。
STATE=$("$ADB" get-state 2>&1 | tr -d '\r')
[ "$STATE" = "device" ] || die "没有可用的设备（adb get-state 返回：$STATE）。插上 USB、开 USB 调试、在手机上点「允许」。"

mkdir -p "$SHOT" || die "建不了取证目录 $SHOT"
echo "版本 $VERSION · 设备 $("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r') · 取证 $SHOT"

# ---------- 装包 + 推片源（一次性） ----------
if [ "$DO_INSTALL" = "1" ]; then
  echo "· 装包 $APK"
  OUT=$("$ADB" install -r "$APK" 2>&1 | tr -d '\r')
  case "$OUT" in
    *Success*) ;;
    *) die "装包失败：$OUT" ;;
  esac
fi

# 画幅权限：13 起是 READ_MEDIA_VIDEO，12 及以下才是 READ_EXTERNAL_STORAGE。
# 失败不致命（用户可能已经手动给过），但要说出来，否则「片库是空的」会让人查半天。
for perm in android.permission.READ_MEDIA_VIDEO android.permission.READ_EXTERNAL_STORAGE; do
  "$ADB" shell pm grant "$PKG" "$perm" >/dev/null 2>&1 || true
done

echo "· 推片源 → /sdcard/Movies/"
"$ADB" push "$CLIPS/." /sdcard/Movies/ >/dev/null 2>&1 || die "推片源失败"
echo "  推完先等系统索引，片库才看得见这些片子"
sleep "$INDEX_WAIT_SEC"

# ---------- 逐项 ----------
# 每项都从「干净进程」开始：上一项留在后台的 VrActivity 会叠在下面，
# 后面的截图里可能还是上一部片 —— 那正是这套取证要避免的错账。
fail_launch=0
declare -a ROW_ITEM ROW_CLIP ROW_VERDICT

for v in "${ITEMS[@]}"; do
  clip=$(clip_of "$v") || die "不认识的项号 $v"
  slug=$(echo "$v" | tr 'A-Z' 'a-z')

  echo
  echo "── $v · ${clip:-（不需要片源）}"
  echo "   人要做：$(note_of "$v")"
  # 行先登记，结论后补：汇总表按 ROW_ITEM 的下标走，
  # 只填结论不登记行的话，命中项会在表里凭空消失（第一版就是这么漏的，自检抓到）。
  ROW_ITEM+=("$v")
  ROW_CLIP+=("${clip:-—}")

  if [ -z "$clip" ]; then
    echo "   ↳ 这一项不由脚本起播，请手动进设置第 2 页"
    ROW_VERDICT+=("手动")
    continue
  fi

  "$ADB" shell am force-stop "$PKG" >/dev/null 2>&1
  # 日志是按行切片取的：先记当前行数，事后再 tail，等于「这一项自己写的那几行」。
  BASE=$("$ADB" shell run-as "$PKG" wc -l files/run_log.txt 2>/dev/null | tr -d '\r' | awk '{print $1}')
  [ -n "${BASE:-}" ] || BASE=0

  "$ADB" shell am start -W -n "$LAUNCHER" \
    -e gazereel.clip "$clip" -e gazereel.item "$v" >/dev/null 2>&1
  sleep "$WAIT_SEC"

  # 截图是给人看的；解码日志是给我定位的。两者缺一，回来就只能猜。
  "$ADB" exec-out screencap -p >"$SHOT/$slug.png" 2>/dev/null
  FULL=$("$ADB" shell run-as "$PKG" cat files/run_log.txt 2>/dev/null | tr -d '\r')
  if [ -n "$FULL" ]; then
    echo "$FULL" | tail -n "+$((BASE + 1))" >"$SHOT/$slug-run_log.txt"
  fi
  RECEIPT=$(grep -E "\[verify\] (命中|未命中)" "$SHOT/$slug-run_log.txt" 2>/dev/null | tail -1)

  # 「未命中」里也含「命中」两个字，所以先判否定，再判肯定。
  if echo "$RECEIPT" | grep -q "未命中"; then
    echo "   ✗ 回执：${RECEIPT#*verify }"
    echo "     片库里没有这部片，这一项的截图不算证据。常见原因："
    echo "     ① /sdcard/Movies 还没被索引（等几秒重跑，或先在配置页点一次「手机里的视频」）"
    echo "     ② 片源被隐藏了（隐藏的片库不进这一屏）"
    echo "     ③ 片名对不上（adb shell run-as $PKG cat files/run_log.txt 看最后几行）"
    ROW_VERDICT+=("未起播")
    fail_launch=$((fail_launch + 1))
  elif echo "$RECEIPT" | grep -q "命中"; then
    echo "   ↳ 回执：${RECEIPT#*verify }"
    ROW_VERDICT+=("已起播")
  else
    echo "   ✗ 回执：运行日志里没有 verify 行"
    echo "     没起播，或日志没读到（debug 包才能用 run-as）。这一项先解决「放不出片」。"
    ROW_VERDICT+=("未起播")
    fail_launch=$((fail_launch + 1))
  fi
done

# ---------- 汇总 ----------
VER=$("$ADB" shell dumpsys package "$PKG" 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '\r')
[ -n "${VER:-}" ] || VER=$VERSION
echo
echo "════════ 记录表（复制到对话里填「过/不过」）════════"
echo "版本：${VER:-$VERSION}    设备：$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r') / Android $("$ADB" shell getprop ro.build.version.release 2>/dev/null | tr -d '\r')"
echo
for i in "${!ROW_ITEM[@]}"; do
  printf '%-3s %-30s %-6s □过 □不过   备注：\n' "${ROW_ITEM[$i]}" "${ROW_CLIP[$i]}" "${ROW_VERDICT[$i]}"
done
echo
echo "截图与日志：$SHOT/"
echo "（每一项的判据见 VERIFY-2.2.0.md §2；V4/V5 是「数学层到 GL 层」的探针，失败最有信息量）"

if [ "$fail_launch" -gt 0 ]; then
  echo
  echo "✗ 有 $fail_launch 项没能起播 —— 这些项先解决「放不出片」，再谈合格与否。"
  exit 2
fi
exit 0
