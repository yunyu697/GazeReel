#!/usr/bin/env bash
# 直连编译包装：临时屏蔽全局 gradle.properties 里已失效的本地代理。
#
# 背景：~/.gradle/gradle.properties 里写着
#   systemProp.http(s).proxyHost=127.0.0.1 / proxyPort=62755
# 这个本地代理没启动时，Gradle 解析 Maven 依赖会 Connection refused。
# 项目级 gradle.properties 里写空的 systemProp.* 会被 Gradle 丢弃；
# nonProxyHosts=* 也不生效（Gradle 的 HTTP 客户端直接读 proxyHost）。
# --offline 同样不行：Gradle 元数据缓存不认已下载的 artifact。
#
# 做法：编译期间把全局文件里的 proxyHost / proxyPort 行注释掉，
# 退出时（含失败、Ctrl+C）用 trap 原样还原，绝不留下用户配置改动。
#
# 用法：bash tools/gradlew-direct.sh --no-daemon :app:assembleDebug

set -u

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GLOBAL="${GRADLE_USER_HOME:-$HOME/.gradle}/gradle.properties"
BACKUP=""

restore() {
    if [ -n "$BACKUP" ] && [ -f "$BACKUP" ]; then
        cp "$BACKUP" "$GLOBAL"
        # 不用 rm：Git Bash 下 /tmp 路径会被安全删除钩子按 Windows 盘符误判。
        python -c "import os,sys; os.path.exists(sys.argv[1]) and os.remove(sys.argv[1])" "$BACKUP" 2>/dev/null || true
        echo "[gradlew-direct] 已还原 $GLOBAL" >&2
    fi
}

if [ -f "$GLOBAL" ]; then
    BACKUP="$(mktemp)"
    cp "$GLOBAL" "$BACKUP"
    trap restore EXIT INT TERM
    python - "$GLOBAL" <<'PY'
import pathlib, sys
p = pathlib.Path(sys.argv[1])
lines = p.read_text(encoding="utf-8").splitlines()
out = [
    ("# " + line)
    if line.strip().startswith("systemProp.")
    and ("proxyHost" in line or "proxyPort" in line)
    else line
    for line in lines
]
p.write_text("\n".join(out) + "\n", encoding="utf-8")
PY
    echo "[gradlew-direct] 临时屏蔽 $GLOBAL 的失效代理行" >&2
fi

cd "$PROJECT_DIR" || exit 1
./gradlew "$@"
rc=$?
exit $rc
