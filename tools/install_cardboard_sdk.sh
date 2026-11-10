#!/usr/bin/env bash
# 安装 / 构建 Google Cardboard SDK，并把产物接入本工程。
#
# 背景：官方 SDK（googlevr/cardboard）已归档，不在 Maven，也没有预编译 AAR 发布；
# 唯一合规获取方式是从官方源码 tag 用 Gradle + NDK 现场构建 sdk-release.aar。
#
# 前置：JDK 17、Android SDK（platforms;android-35 / build-tools;34.0.0）、网络代理（可选）。
# 用法：bash tools/install_cardboard_sdk.sh [SDK 版本 tag，默认 v1.36.0]
set -euo pipefail

VERSION="${1:-v1.36.0}"
NDK_VERSION="29.0.14206865"          # 必须与官方 sdk/build.gradle 的 ndkVersion 一致
CMAKE_VERSION="3.31.6"
PROJECT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
ANDROID_SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$LOCALAPPDATA/Android/Sdk}}"
WORK_DIR="${TEMP:-/tmp}/cardboard-sdk-build"
SRC_DIR="$WORK_DIR/cardboard-${VERSION#v}"

export ANDROID_HOME="$ANDROID_SDK"
export ANDROID_SDK_ROOT="$ANDROID_SDK"
SDKM="$ANDROID_SDK/cmdline-tools/latest/bin/sdkmanager.bat"

echo "[1/5] Android SDK: $ANDROID_SDK"
[ -x "$SDKM" ] || { echo "找不到 sdkmanager，请先安装 Android SDK 命令行工具"; exit 1; }

echo "[2/5] 安装 NDK / CMake / 编译组件"
"$SDKM" "platforms;android-35" "build-tools;34.0.0" \
        "ndk;$NDK_VERSION" "cmake;$CMAKE_VERSION" >/dev/null
echo "      ndk=$NDK_VERSION cmake=$CMAKE_VERSION"

echo "[3/5] 下载官方源码 $VERSION"
mkdir -p "$WORK_DIR"
ZIP="$WORK_DIR/cardboard-$VERSION.zip"
curl -sSL --fail --max-time 300 -o "$ZIP" \
  "https://codeload.github.com/googlevr/cardboard/zip/refs/tags/$VERSION"
python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "$ZIP" "$WORK_DIR"
[ -d "$SRC_DIR" ] || { echo "解压后的源码目录不符合预期: $SRC_DIR"; exit 1; }

# Gradle 属性文件用 Java properties 语法，反斜杠与冒号需转义。
python3 - "$SRC_DIR/local.properties" "$ANDROID_SDK" <<'PY'
import sys
path, sdk = sys.argv[1], sys.argv[2]
with open(path, "w", encoding="utf-8") as f:
    f.write("sdk.dir=" + sdk.replace(":", "\\:").replace("\\", "\\\\") + "\n")
PY

echo "[4/5] 构建 sdk AAR（Gradle 9.8 + AGP 9.2，需显式 JVM 代理参数）"
# 必须构建 debug 变体：官方 release 变体开启 minifyEnabled，R8 会把未被引用的
# java_api 封装类（HeadTracker / LensDistortion 等）裁掉，导致无法做 Java 层绑定。
# 若要用 release 变体，需在官方 proguard-rules.pro 中补充 -keep com.google.cardboard.sdk.** { *; }
# Gradle 9.x 的 buildscript 依赖解析不读 systemProp.* 代理设置，必须用 -D 传给 daemon JVM。
PROXY_ARGS=()
if [ -n "${HTTPS_PROXY:-}" ]; then
  PROXY_HOST="$(python3 -c "import os,urllib.parse;u=urllib.parse.urlparse(os.environ['HTTPS_PROXY']);print(u.hostname or '')")"
  PROXY_PORT="$(python3 -c "import os,urllib.parse;u=urllib.parse.urlparse(os.environ['HTTPS_PROXY']);print(u.port or 80)")"
  if [ -n "$PROXY_HOST" ]; then
    PROXY_ARGS=(-Dhttps.proxyHost="$PROXY_HOST" -Dhttps.proxyPort="$PROXY_PORT" \
                -Dhttp.proxyHost="$PROXY_HOST" -Dhttp.proxyPort="$PROXY_PORT")
  fi
fi
( cd "$SRC_DIR" && ./gradlew :sdk:assembleDebug --console=plain --configure-on-demand "${PROXY_ARGS[@]}" )

AAR="$(find "$SRC_DIR/sdk/build/outputs/aar" -name 'sdk-debug.aar' | head -1)"
[ -n "$AAR" ] || { echo "未找到构建产物 AAR"; exit 1; }

echo "[5/5] 接入工程"
mkdir -p "$PROJECT_DIR/app/libs"
cp "$AAR" "$PROJECT_DIR/app/libs/cardboard-sdk.aar"
echo "已复制: $AAR -> app/libs/cardboard-sdk.aar"
echo "完成。app/build.gradle.kts 会自动把 src/cardboard/java 加入编译。"
