#!/usr/bin/env python3
"""校验一键发版脚本 `tools/release.sh` 自身的正确性。

发版脚本是「没有任何人在 code review 的时候」运行的代码：它出错的结果不是报错，
而是**把一个错的包当成好包发出去**，或者**打出一个指向错代码的 tag**。
所以它需要和业务代码一样被断言护住。

这份检查的每一条，都对应一次真实踩过的坑：

  · 发版在 bump 之后被中断（超时/沙箱），留下「版本号已升但没提交」的脏
    version.properties；下次发版如果先 bump 再查工作树，就会把这个没发出去的
    版本号带进新提交和 tag。→ 判据 1：工作树检查必须在 bump 之前。
  · 提交信息里写过「283 条断言全过」，后来断言长到 323 条，这句话就成了假话，
    而且改脚本还得同步改这句话（自我指涉）。→ 判据 3：不许写死统计数字。
  · alpha8 换 SMB 主链路时，dex 核对只硬编码了 fisheye 两个符号，jcifs 的类
    到底有没有进包是另跑一遍 python 才确认的。→ 判据 4/5：符号清单外置且覆盖主链路。
  · 曾经在这里 grep dex 查版本号，误报（版本名在 AndroidManifest 的 AXML 里，
    不在 dex）。→ 判据 6：版本名必须走 aapt2，且不一致必须中止，不许「跳过当通过」。

判据：
  1) 工作树脏检查出现在 bump 之前
  2) 全量断言在构建之前，且失败即中止（不是打警告继续）
  3) 提交信息与脚本里没有写死的断言条数
  4) 产物符号清单文件存在、非空、覆盖渲染/鱼眼/SMB 三条主链路
  5) 符号核对读清单文件，不再写死 `for s in fisheye ...`
  6) 版本名核对走 aapt2，MISMATCH 分支必须 die
  7) 提交只 add version.properties，不许 git add -A
  8) tag 是 annotated（-a）且命名为 v$VERSION
  9) 产物名必须含目标版本号，否则中止
 10) 断言用通配遍历，新增 check_*.py 自动纳入发版门禁
 11) 中断可续：`--commit` 下版本号提交紧跟 bump（早于断言/构建），
     且脏版本号的提示必须给出 `--no-bump` 续发路径，不能只剩「还原重来」
"""

import re
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / "tools" / "release.sh"
SYMBOLS = ROOT / "tools" / "release-symbols.txt"

# 产物必须包含的主链路符号：值为此符号在清单里的「归属说明」，用于报错时讲清为什么。
REQUIRED_SYMBOLS = {
    "VrRenderer": "渲染主循环",
    "ProjectionMesh": "投影几何",
    "FisheyePreset": "鱼眼（2.2.0 主题）",
    "SmbShareLister": "SMB 枚举主链路",
    # 第三方库类：证明依赖真的被打进包，而不是只在编译期可见
    "DcerpcHandle": "jcifs 库类进包证据",
}


def main() -> int:
    if not SCRIPT.exists():
        print(f"FAIL 找不到 {SCRIPT}")
        return 1
    text = SCRIPT.read_text(encoding="utf-8")
    # 去掉注释行后再做「顺序」类判断，避免注释里的词干扰
    body = "\n".join(
        ln for ln in text.splitlines() if not ln.lstrip().startswith("#")
    )

    ok = True

    print("=== 1. 前置检查顺序 ===")
    pos_dirty = body.find("DIRTY=")
    pos_bump = body.find("bump_version.py")
    order_ok = pos_dirty != -1 and pos_bump != -1 and pos_dirty < pos_bump
    print(f"工作树检查在 bump 之前  {'OK' if order_ok else 'FAIL 脏版本号会被带进 tag'}")
    ok &= order_ok

    only_vp_hint = "git checkout -- version.properties" in text
    print(f"中断残留有明确恢复指引  {'OK' if only_vp_hint else 'FAIL 用户不知道该怎么恢复'}")
    ok &= only_vp_hint

    print()
    print("=== 2. 断言门禁 ===")
    pos_checks = body.find("tools/check_*.py")
    pos_build = body.find("assembleDebug")
    gate_before_build = pos_checks != -1 and pos_build != -1 and pos_checks < pos_build
    print(f"断言在构建之前  {'OK' if gate_before_build else 'FAIL 会先出包再验'}")
    ok &= gate_before_build

    fail_dies = bool(re.search(r'\[ "\$FAILED" = "0" \] \|\| die', body))
    print(f"断言失败即中止  {'OK' if fail_dies else 'FAIL 失败还在继续构建'}")
    ok &= fail_dies

    for cmd in ("tools/run-format-check.sh", "tools/run-pose-check.sh"):
        has = cmd in body
        print(f"覆盖 {cmd:28s} {'OK' if has else 'FAIL 漏了这支'}")
        ok &= has

    print()
    print("=== 3. 不写死统计数字 ===")
    # 「283 条断言」「16 条判据」这类都不许出现在发版脚本里
    hard = re.findall(r"\d+\s*条(?:断言|判据|检查)", text)
    print(f"无写死条数  {'OK' if not hard else 'FAIL ' + ', '.join(hard)}")
    ok &= not hard

    print()
    print("=== 4. 产物符号清单 ===")
    if not SYMBOLS.exists():
        print(f"清单文件存在  FAIL 缺少 {SYMBOLS.name}")
        return 1
    lines = [
        ln.strip()
        for ln in SYMBOLS.read_text(encoding="utf-8").splitlines()
        if ln.strip() and not ln.lstrip().startswith("#")
    ]
    print(f"清单文件存在且非空（{len(lines)} 项）  {'OK' if lines else 'FAIL 空清单'}")
    ok &= bool(lines)

    for sym, why in REQUIRED_SYMBOLS.items():
        has = sym in lines
        print(f"覆盖 {sym:16s}（{why}）  {'OK' if has else 'FAIL 漏了主链路'}")
        ok &= has

    print()
    print("=== 5. 符号核对读清单，不写死 ===")
    reads_file = "release-symbols.txt" in body
    print(f"从清单文件读取  {'OK' if reads_file else 'FAIL 又写回脚本里了'}")
    ok &= reads_file

    hardcoded = bool(re.search(r"for s in fisheye", body))
    print(f"无写死的符号列表  {'OK' if not hardcoded else 'FAIL for s in fisheye 又回来了'}")
    ok &= not hardcoded

    empty_guard = bool(re.search(r'COUNT.*-gt 0.*\|\| die', body))
    print(f"空清单判失败（防核对形同虚设）  {'OK' if empty_guard else 'FAIL 空清单会静默通过'}")
    ok &= empty_guard

    print()
    print("=== 6. 版本名核对 ===")
    uses_aapt = "dump badging" in body
    # 曾经用 grep dex 查版本名 → 误报（版本名在 AXML 里）
    no_dex_grep = not re.search(r"grep[^\n]*dex[^\n]*version", body, re.IGNORECASE)
    print(f"走 aapt2 dump badging  {'OK' if uses_aapt else 'FAIL'}")
    print(f"不再 grep dex 查版本名  {'OK' if no_dex_grep else 'FAIL 这是已知误报做法'}")
    ok &= uses_aapt and no_dex_grep

    # MISMATCH 分支必须在 die 之前，不能是「打印后继续」
    mm = re.search(r"MISMATCH(.*?)esac", body, re.S)
    mm_dies = bool(mm and "die" in mm.group(1))
    print(f"版本不一致即中止  {'OK' if mm_dies else 'FAIL 不一致还继续发'}")
    ok &= mm_dies

    print()
    print("=== 7. 提交范围 ===")
    add_exact = "git add version.properties" in body
    add_all = bool(re.search(r"git add -A\b", body))
    print(f"只 add version.properties  {'OK' if add_exact else 'FAIL 提交范围不明'}")
    print(f"无 git add -A  {'OK' if not add_all else 'FAIL 会把意外产物一起吞进去'}")
    ok &= add_exact and not add_all

    print()
    print("=== 8. tag ===")
    annotated = bool(re.search(r'git tag -a "v\$VERSION"', body))
    print(f"annotated tag 且命名 v$VERSION  {'OK' if annotated else 'FAIL'}")
    ok &= annotated

    push_hint = "git push origin v$VERSION" in text
    print(f"提示 tag 需单独推送  {'OK' if push_hint else 'FAIL 容易以为已发到远端'}")
    ok &= push_hint

    print()
    print("=== 9. 产物与版本号一致性 ===")
    name_guard = bool(re.search(r'\*"\$VERSION"\*', body)) and "产物名" in text
    print(f"产物名不含版本号即中止  {'OK' if name_guard else 'FAIL'}")
    ok &= name_guard

    print()
    print("=== 10. 断言遍历而非列举 ===")
    globbed = "for f in tools/check_*.py" in body
    print(f"通配遍历 check_*.py  {'OK' if globbed else 'FAIL 新脚本不会自动进门禁'}")
    ok &= globbed

    print()
    print("=== 11. 中断可续 ===")
    # 曾经连续两次在 bump 之后、构建完成之前被打断（构建耗时较长），
    # 留下「版本号已升但没提交」的脏文件，下次发版被脏检查拦住，只能人工处理。
    # 结构上的解：--commit 模式下 bump 之后立刻提交，让中断窗口里工作树保持干净。
    #
    # 只在「bump → 断言」这段区间里找 git commit：步骤 0 的报错提示文本里也提到了
    # git commit（给出续发命令），全文 find 会误命中那里。
    pos_checks = body.find("tools/check_*.py")
    between = body[pos_bump:pos_checks] if (pos_bump != -1 and pos_checks != -1) else ""
    resume_ok = pos_bump != -1 and pos_checks != -1 and pos_bump < pos_checks and "git commit" in between
    print(f"版本号提交紧跟 bump（早于断言/构建）  {'OK' if resume_ok else 'FAIL 中断仍会留脏版本号'}")
    ok &= resume_ok

    # 脏版本号的提示必须给出「保留并续发」这条路，不能只说「还原重来」
    hint = re.search(r"只有 version.properties 是脏的(.{0,700}?)\"\n", text, re.S)
    has_resume_hint = bool(hint and "--no-bump" in hint.group(1))
    print(f"脏版本号提示含 --no-bump 续发路径  {'OK' if has_resume_hint else 'FAIL 只剩「还原重来」一条路'}")
    ok &= has_resume_hint

    print()
    print("=== 12. 语法 ===")
    bash = shutil.which("bash")
    if bash:
        r = subprocess.run([bash, "-n", str(SCRIPT)], capture_output=True, text=True)
        syntax_ok = r.returncode == 0
        print(f"bash -n 解析通过  {'OK' if syntax_ok else 'FAIL ' + r.stderr.strip()[:120]}")
        ok &= syntax_ok
    else:
        print("bash -n 解析  跳过（本机无 bash）")

    print()
    print("结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
