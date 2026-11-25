#!/usr/bin/env python3
"""校验 `tools/verify_device.sh` 在**没有真机**时也按设计行事。

为什么需要这一层：验收脚本是「唯一会碰到真机」的工具，而它出错的代价特别高——
装错包、点错片、把上一项的截图记到这一项上，都会让验收拿到一份看似齐备、实则错位的证据。
这类错没人会当场发现，等发现问题时人已经摘下头显、设备也还回去了。

办法：造一个假 `adb`（记录每次调用、按需返回成功/未命中/未连接），然后在临时目录里
真跑一遍脚本，断言它：
  · 装包失败就停，不硬着头皮往下播；
  · 每一项都先 `am force-stop`，不会让上一部的画面混进这一项的截图；
  · 点名的是那部片（`-e gazereel.clip <片名>`）而不是「片库里的第一个」；
  · 日志按行切片，回执只认本项新增的那几行；
  · 「未命中」判红并给出可执行的下一步，而不是当成功打印；
  · `--list` / `--no-install` / 设备离线这三种边界不会误伤人。

这里验的是**脚本的决策**，不是脚本能否连上真机——后者只能等设备。
"""

import os
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "tools" / "verify_device.sh"


def find_bash():
    """挑一个真 bash。

    Windows 上 `bash` 常常先解析到 `C:////Windows////System32////bash.exe`，那是 WSL 的门，
    在没装 WSL 的机器上直接报错、在被策略管住的机器上直接拦——两种都不是我们要的结果。
    按「环境变量 → Git for Windows → PATH」的顺序挑，并显式排除 System32。
    """
    for cand in (
        os.environ.get("BASH"),
        r"C://Program Files//Git//bin//bash.exe",
        r"C://Program Files (x86)//Git//bin//bash.exe",
        shutil.which("bash"),
    ):
        if not cand:
            continue
        if "System32" in cand or "System32" in cand.replace("/", "\\"):
            continue
        if Path(cand).exists():
            return cand
    raise RuntimeError("找不到可用的 bash（需要 Git for Windows）")


BASH = find_bash()

FAILED = []
PASSED = 0


def check(cond, name, detail=""):
    global PASSED
    if cond:
        PASSED += 1
        print(f"  ok   {name}")
    else:
        FAILED.append(name)
        print(f"  FAIL {name}" + (f"\n       {detail}" if detail else ""))


FAKE_ADB = r"""#!/usr/bin/env bash
# 假 adb：只做两件事——记录调用、按 FAKE_MODE 给出可预期的回执。
printf '%s\n' "$*" >> "$FAKE_ADB_LOG"

mode=${FAKE_MODE:-ok}

case "$1" in
  get-state)
    if [ "$mode" = "offline" ]; then echo "unknown"; else echo "device"; fi
    ;;
  install)
    if [ "$mode" = "installfail" ]; then
      echo "adb: failed to install: INSTALL_FAILED_UPDATE_INCOMPATIBLE"
    else
      echo "Performing Streamed Install"
      echo "Success"
    fi
    ;;
  push)
    echo "1 file pushed. 0 files skipped."
    ;;
  exec-out)
    # 截图：能写成文件就够了，内容不必是真 PNG
    printf '\211PNG\r\n\032\nfake-screenshot'
    ;;
  shell)
    shift
    case "$1" in
      getprop)
        case "$2" in
          ro.product.model) echo "Fake Device 9000" ;;
          ro.build.version.release) echo "14" ;;
          *) echo "" ;;
        esac
        ;;
      pm) : ;;
      dumpsys) echo "    versionName=9.9.9-fake" ;;
      am)
        case "$2" in
          force-stop) : ;;
          start)
            if [ "$mode" = "nomatch" ]; then
              echo "12:00:00.000 [verify] 未命中 · item=V4 clip=test-fisheye-circle-mono（片库没有这部片）" >> "$FAKE_APP_LOG"
            else
              echo "12:00:00.000 [verify] 命中 · item=V4 clip=test-fisheye-circle-mono → test-fisheye-circle-mono.mp4" >> "$FAKE_APP_LOG"
            fi
            echo "Status: ok"
            ;;
        esac
        ;;
      run-as)
        case "$3" in
          wc) wc -l < "$FAKE_APP_LOG" ;;
          cat) cat "$FAKE_APP_LOG" ;;
        esac
        ;;
    esac
    ;;
esac
"""

# 20 行「上一项的历史」，切片断言靠它：本项只该看到自己写的那 1 行。
PREEXISTING = "\n".join(f"[old] 历史日志 {i}" for i in range(1, 21)) + "\n"


class Run:
    def __init__(self, code, out, adb_calls, shots):
        self.code = code
        self.out = out
        self.adb_calls = adb_calls
        self.shots = shots


def run_script(tmp: Path, args, mode="ok"):
    adb_log = tmp / "adb-calls.log"
    app_log = tmp / "app-run-log.txt"
    adb_log.write_text("", encoding="utf-8")
    app_log.write_text(PREEXISTING, encoding="utf-8")

    shots = tmp / "evidence"
    env = dict(os.environ)
    env.update(
        ADB=str(tmp / "adb"),
        APK=str(tmp / "app.apk"),
        CLIPS_DIR=str(tmp / "clips"),
        SHOT_DIR=str(shots),
        WAIT_SEC="0",
        INDEX_WAIT_SEC="0",
        FAKE_ADB_LOG=str(adb_log),
        FAKE_APP_LOG=str(app_log),
        FAKE_MODE=mode,
    )
    proc = subprocess.run(
        [BASH, str(SCRIPT), *args],
        cwd=str(ROOT),
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    calls = [line.strip() for line in adb_log.read_text(encoding="utf-8").splitlines() if line.strip()]
    return Run(proc.returncode, (proc.stdout or "") + (proc.stderr or ""), calls, shots)


def setup(tmp: Path, with_launcher=True):
    fake = tmp / "adb"
    fake.write_text(FAKE_ADB, encoding="utf-8")
    fake.chmod(0o755)
    (tmp / "app.apk").write_bytes(b"fake-apk")
    clips = tmp / "clips"
    clips.mkdir(exist_ok=True)
    (clips / "test-fisheye-circle-mono.mp4").write_bytes(b"x")


def find(calls, *needles):
    """返回第一条同时含所有 needle 的调用次序；没有则 -1。"""
    for i, c in enumerate(calls):
        if all(n in c for n in needles):
            return i
    return -1


def main():
    print("· 静态：参数表与用法")
    src = SCRIPT.read_text(encoding="utf-8")
    for item, clip in [
        ("V1", "test-360-equirect-mono"),
        ("V2", "test-180-sbs"),
        ("V3", "test-2d-grid-720p"),
        ("V4", "test-fisheye-circle-mono"),
        ("V5", "test-fisheye-fullframe-mono"),
        ("V7", "test-dual-fisheye-unstitched"),
    ]:
        # 片名必须和 make_testclips.py 产出的文件名一致，否则「命中」永远不成立
        check(clip in src and item in src, f"{item} → {clip} 在脚本里")
    check("V8) echo \"\" ;;" in src or 'V8) echo ""' in src, "V8 明确不指定片源（手动项）")

    print("· 静态：清单与接线（导出面只多一个跳板，VrActivity 不因此开门）")
    manifest = (ROOT / "app/src/main/AndroidManifest.xml").read_text(encoding="utf-8")
    launcher_block = manifest.split('android:name=".ui.VerifyLaunchActivity"')
    check(len(launcher_block) == 2, "清单里只有一处 VerifyLaunchActivity")
    block = launcher_block[1].split("/>")[0] if len(launcher_block) == 2 else ""
    check('android:exported="true"' in block, "跳板导出（am start 的身份是 shell，只能进导出组件）")
    check("intent-filter" not in block, "跳板没有 intent-filter：不会被「打开视频」的隐式请求撞上")
    vr = manifest.split('android:name=".ui.VrActivity"')
    vrblock = vr[1].split("/>")[0] if len(vr) == 2 else ""
    check('android:exported="false"' in vrblock, "VrActivity 仍然不导出（验收不该削弱这块边界）")

    activity = (ROOT / "app/src/main/java/com/gazereel/vr/ui/VrActivity.kt").read_text(encoding="utf-8")
    check("consumePendingClip()" in activity, "片库回调里会消化验收请求")
    check("pendingClipTried" in activity, "只尝试一次：片库 Flow 每次变动不会重复起播")
    check('"verify"' in activity and "命中" in activity and "未命中" in activity,
          "命中与未命中都写 [verify] 日志（脚本的回执只认这一行）")
    coordinator = (ROOT / "app/src/main/java/com/gazereel/vr/ui/VrCoordinator.kt").read_text(encoding="utf-8")
    check("fun playByTitle(" in coordinator, "起播入口在 coordinator 里，走的是和凝视点格子同一条路")
    check("openSelected()" in coordinator, "点名起播复用 openSelected（格式识别/拒绝卡不绕过）")

    with tempfile.TemporaryDirectory() as d:
        tmp = Path(d)
        setup(tmp)

        print("· --list：不碰设备")
        r = run_script(tmp, ["--list"])
        check(r.code == 0, "--list 正常退出", f"退出码 {r.code}")
        check(not r.adb_calls, "--list 一次 adb 都没调", f"{len(r.adb_calls)} 次")
        check("V4" in r.out and "test-fisheye-circle-mono" in r.out, "--list 列出 V4 与它的片源")

        print("· 正常跑 V4：装包 → 点名 → 截图 → 回执")
        r = run_script(tmp, ["V4"])
        check(r.code == 0, "命中时退出码 0", f"退出码 {r.code}\n{r.out[-400:]}")
        i_force = find(r.adb_calls, "am", "force-stop")
        i_start = find(r.adb_calls, "am", "start")
        check(i_force >= 0, "起播前先 force-stop 清掉上一项的进程")
        check(0 <= i_force < i_start, "force-stop 在 am start 之前", f"{i_force} vs {i_start}")
        check(
            find(r.adb_calls, "-n", "com.gazereel.vr/.ui.VerifyLaunchActivity") >= 0,
            "起播走验收跳板（VrActivity 本身不导出）",
        )
        check(
            find(r.adb_calls, "-e gazereel.clip test-fisheye-circle-mono") >= 0,
            "点名的就是那部片（不是「片库第一个」）",
        )
        check(find(r.adb_calls, "-e gazereel.item V4") >= 0, "项号一起带进去，日志才能对账")
        shot = r.shots / "v4.png"
        check(shot.exists() and shot.stat().st_size > 0, "截图按项命名落盘（v4.png）")
        sliced = (r.shots / "v4-run_log.txt")
        text = sliced.read_text(encoding="utf-8") if sliced.exists() else ""
        check("历史日志 1" not in text, "日志按行切片，不带上一项的历史", text[:120])
        check("命中" in text and "未命中" not in text, "切片里只留本项的回执")
        check("已起播" in r.out, "汇总表标记为已起播")
        check("9.9.9-fake" in r.out, "报告里的版本号取自设备实际装的包")

        print("· 未命中：必须判红，并给出下一步")
        r = run_script(tmp, ["V4"], mode="nomatch")
        check(r.code == 2, "未命中退出码 2", f"退出码 {r.code}")
        check("片库里没有这部片" in r.out, "说清「片库里没有这部片」")
        check("已起播" not in r.out, "不把未命中记成已起播")
        check((r.shots / "v4.png").exists(), "截图仍保留（证据在，但不算数）")

        print("· 设备离线 / 装包失败：停在前一步")
        r = run_script(tmp, ["V4"], mode="offline")
        check(r.code == 1, "设备离线退出码 1", f"退出码 {r.code}")
        check("没有可用的设备" in r.out, "提示「没有可用的设备」")
        check(find(r.adb_calls, "install") < 0, "连不上设备就不装包")
        r = run_script(tmp, ["V4"], mode="installfail")
        check(r.code == 1, "装包失败退出码 1", f"退出码 {r.code}")
        check("装包失败" in r.out, "把 adb 的原话带出来")
        check(find(r.adb_calls, "am", "start") < 0, "装包失败不进起播")

        print("· --no-install / 手动项")
        r = run_script(tmp, ["--no-install", "V1"])
        check(r.code == 0, "--no-install 正常跑通", f"退出码 {r.code}")
        check(find(r.adb_calls, "install") < 0, "--no-install 真的没装包")
        r = run_script(tmp, ["V8"])
        check(r.code == 0, "V8（手动项）不因没有片源而失败", f"退出码 {r.code}")
        check(find(r.adb_calls, "am", "start") < 0, "V8 不由脚本起播")
        check("手动" in r.out, "V8 在汇总表里标为手动")

    print()
    print(f"通过 {PASSED} · 失败 {len(FAILED)}")
    if FAILED:
        print("失败项：" + "、".join(FAILED))
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
