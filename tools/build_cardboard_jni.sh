#!/usr/bin/env bash
# 构建官方 Java API 的 JNI 桥接库 libcardboard_sdk_jni.so 并补进工程。
#
# 背景见 tools/cardboard-jni/CMakeLists.txt 的注释：sdk AAR 只含 C API 库，
# Java 层 Initialize/HeadTracker/LensDistortion 的 JNI 桥接库由官方 paperscope
# demo 单独构建。缺它会在 Initialize.<clinit> 抛 UnsatisfiedLinkError。
#
# 产物落在 app/src/main/jniLibs/<abi>/，AGP 会自动合并进 APK。
# 前置：先跑 tools/install_cardboard_sdk.sh（官方源码留在 TEMP 下）。
# 用法：bash tools/build_cardboard_jni.sh [v1.36.0]
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
VERSION="${1:-v1.36.0}"
SRC_DIR="${CARDBOARD_SRC_DIR:-${TEMP:-/tmp}/cardboard-sdk-build/cardboard-${VERSION#v}}"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
NDK="$ANDROID_SDK/ndk/29.0.14206865"
CMAKE_BIN="$ANDROID_SDK/cmake/3.31.6/bin/cmake.exe"
NINJA="$ANDROID_SDK/cmake/3.31.6/bin/ninja.exe"

[ -f "$SRC_DIR/java_api/src/main/jni/cardboard_jni.cc" ] || {
  echo "找不到官方源码：$SRC_DIR（先跑 tools/install_cardboard_sdk.sh）" >&2; exit 1; }
[ -x "$CMAKE_BIN" ] || { echo "找不到 CMake：$CMAKE_BIN" >&2; exit 1; }

# Windows 原生 cmake/ninja 对含空格的输出目录支持不佳，统一用无空格工作目录。
WORK="/c/Temp/gazereel-cardboard-jni"
mkdir -p "$WORK"
rm -rf "$WORK/src" "$WORK/out" "$WORK/prebuilt"
mkdir -p "$WORK/src/sdk" "$WORK/src/java_api/src/main/jni"

cp -r "$SRC_DIR/sdk/include" "$WORK/src/sdk/include"
cp -r "$SRC_DIR/sdk/jni_utils" "$WORK/src/sdk/jni_utils"
# jni_utils.cc 还 include "util/logging.h"，必须一并把 sdk/util 带上。
cp -r "$SRC_DIR/sdk/util" "$WORK/src/sdk/util"
cp "$SRC_DIR/java_api/src/main/jni/cardboard_jni.cc" "$WORK/src/java_api/src/main/jni/"
cp "$PROJECT_DIR/tools/cardboard-jni/CMakeLists.txt" "$WORK/src/CMakeLists.txt"

# 从 AAR 里解出两个 ABI 的 C API 库，保持文件名不变：
# 链接时生成的 DT_NEEDED 必须是 libGfxPluginCardboard.so，运行时才找得到。
python3 - "$PROJECT_DIR/app/libs/cardboard-sdk.aar" "$WORK/prebuilt" <<'PY'
import os, sys, zipfile
aar, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(aar) as z:
    for n in z.namelist():
        if n.endswith("libGfxPluginCardboard.so"):
            abi = n.split("/")[1]
            d = os.path.join(out, abi)
            os.makedirs(d, exist_ok=True)
            with open(os.path.join(d, "libGfxPluginCardboard.so"), "wb") as f:
                f.write(z.read(n))
            print("prebuilt:", abi)
PY

W="$(cygpath -m "$WORK")"
TOOLCHAIN="$(cygpath -m "$NDK/build/cmake/android.toolchain.cmake")"
MAKE_PROGRAM="$(cygpath -m "$NINJA")"

for ABI in arm64-v8a armeabi-v7a; do
  B="$WORK/out/$ABI"
  WB="$(cygpath -m "$B")"
  mkdir -p "$B"
  "$CMAKE_BIN" -G Ninja \
    -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
    -DCMAKE_MAKE_PROGRAM="$MAKE_PROGRAM" \
    -DANDROID_ABI="$ABI" \
    -DANDROID_PLATFORM=android-26 \
    -DANDROID_STL=c++_static \
    -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    "-DCARDBOARD_SDK_DIR=$W/src/sdk" \
    "-DCARDBOARD_JAVA_API_DIR=$W/src/java_api" \
    "-DCARDBOARD_LIB=$W/prebuilt/$ABI/libGfxPluginCardboard.so" \
    -S "$W/src" -B "$WB"
  "$CMAKE_BIN" --build "$WB"
  DST="$PROJECT_DIR/app/src/main/jniLibs/$ABI"
  mkdir -p "$DST"
  cp "$B/libcardboard_sdk_jni.so" "$DST/"
  echo "[$ABI] -> $DST/libcardboard_sdk_jni.so"
done
