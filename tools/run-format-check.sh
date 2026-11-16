#!/usr/bin/env bash
# 格式判定的离线自检（只用 JVM，不需要设备、不需要 Gradle）。
#
# 编的是 app 里那份真代码（core/FormatDetector.kt、core/VrFormat.kt、
# core/SphericalMetadata.kt），不是副本。
#
# 为什么这一层值得有自检：识别一旦「时对时错」，代价是每次打开都正对接缝。
# 现在的规则是确定性的，所以结论可以被断言钉死——同样输入必须永远同样输出。
set -euo pipefail

cd "$(dirname "$0")/.."

CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
COMPILER=$(find "$CACHE/org.jetbrains.kotlin/kotlin-compiler-embeddable" -name 'kotlin-compiler-embeddable-*.jar' | sort | tail -1)
KT_VER=$(basename "$COMPILER" .jar | sed 's/^kotlin-compiler-embeddable-//')
STDLIB=$(find "$CACHE/org.jetbrains.kotlin/kotlin-stdlib/$KT_VER" -name "kotlin-stdlib-$KT_VER.jar" | head -1)
COROUTINES=$(find "$CACHE/org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm" -name '*.jar' | sort | tail -1)
TROVE=$(find "$CACHE/org.jetbrains.intellij.deps/trove4j" -name '*.jar' | sort | tail -1)
ANNOT=$(find "$CACHE/org.jetbrains/annotations" -name '*.jar' | sort | tail -1)
if [ -z "$COMPILER" ] || [ -z "$STDLIB" ] || [ -z "$COROUTINES" ] || [ -z "$TROVE" ] || [ -z "$ANNOT" ]; then
  echo "找不到 Kotlin 编译器 / 标准库 / 协程包，先在项目里跑一次 ./gradlew 让它们下载。" >&2
  exit 2
fi

OUT=build/format-check
rm -rf "$OUT"
mkdir -p "$OUT"

java -cp "$COMPILER:$STDLIB:$COROUTINES:$TROVE:$ANNOT" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -nowarn \
  -classpath "$STDLIB" \
  -d "$OUT/classes" \
  app/src/main/java/com/gazereel/vr/core/VrFormat.kt \
  app/src/main/java/com/gazereel/vr/core/VrHeadsetProvider.kt \
  app/src/main/java/com/gazereel/vr/core/DistortionComposer.kt \
  app/src/main/java/com/gazereel/vr/core/PoseMath.kt \
  app/src/main/java/com/gazereel/vr/core/SphericalMetadata.kt \
  app/src/main/java/com/gazereel/vr/core/Refusal.kt \
  app/src/main/java/com/gazereel/vr/core/FormatDetector.kt \
  tools/FormatDetectorCheck.kt

java -Dfile.encoding=UTF-8 -cp "$OUT/classes:$STDLIB" check.FormatDetectorCheckKt
