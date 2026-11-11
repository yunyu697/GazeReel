#!/usr/bin/env bash
# 抽帧判布局的离线自检运行器（只用 JVM，不需要设备、不需要 Gradle）。
#
# 用本地 Gradle 缓存里的 Kotlin 编译器，把「真代码 + 合成图自检」编译成 jar 直接跑。
# 这样验的是 app 里那份 StereoFrameJudge.kt 本身，不是它的副本。
set -euo pipefail

cd "$(dirname "$0")/.."

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
COMPILER=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name 'kotlin-compiler-embeddable-*.jar' | sort | tail -1)
# 标准库取与编译器同版本的那一份：跨版本混用会报内部符号缺失。
KT_VER=$(basename "$COMPILER" .jar | sed 's/^kotlin-compiler-embeddable-//')
STDLIB="$CACHE/org.jetbrains.kotlin/kotlin-stdlib/$KT_VER"
STDLIB=$(find "$STDLIB" -name "kotlin-stdlib-$KT_VER.jar" | head -1)
# 编译器内部用到协程：不在 classpath 上会 NoClassDefFoundError: CoroutineScope。
COROUTINES=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' | sort | tail -1)
# 编译器内部还用了 IntelliJ 的 trove 集合。
TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' | sort | tail -1)
# 以及 org.jetbrains.annotations（生成 @NotNull 时要用）。
ANNOT=$(find "$CACHE/org.jetbrains/annotations" -name '*.jar' | sort | tail -1)
if [ -z "$COMPILER" ] || [ -z "$STDLIB" ] || [ -z "$COROUTINES" ] || [ -z "$TROVE" ] || [ -z "$ANNOT" ]; then
  echo "找不到 Kotlin 编译器 / 标准库 / 协程包，先在项目里跑一次 ./gradlew 让它们下载。" >&2
  exit 2
fi
echo "compiler: $COMPILER"
echo "stdlib  : $STDLIB"

OUT=build/stereo-check
rm -rf "$OUT"
mkdir -p "$OUT/src"

# StereoLayout 的真身与 VrFormat / Eye 写在同一批文件里（带 Android 依赖），
# 这里只补一个同包同名的枚举，让 StereoFrameJudge 能独立编译。
cat > "$OUT/src/StereoLayoutStub.kt" <<'EOF'
package com.gazereel.vr.core

enum class StereoLayout { MONO, SBS, TAB }
EOF

java -cp "$COMPILER:$STDLIB:$COROUTINES:$TROVE:$ANNOT" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -nowarn \
  -classpath "$STDLIB" \
  -d "$OUT/classes" \
  "$OUT/src/StereoLayoutStub.kt" \
  app/src/main/java/com/gazereel/vr/core/StereoFrameJudge.kt \
  tools/StereoJudgeCheck.kt

java -cp "$OUT/classes:$STDLIB" check.StereoJudgeCheckKt
