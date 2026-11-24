#!/usr/bin/env python3
"""校验 SMB 共享枚举已整段换成 jcifs-ng，而不是「新方案旁路 + 旧链路保留」。

2.2.0-alpha8 这批只做一件事：把 `SmbShareEnum.kt`（404 行手写 DCE/RPC + NDR32）
与 `JcifsCrossCheck.kt`（双跑对照）从链路里摘掉，默认路径走 jcifs-ng 自带的
`MsrpcShareEnum` 与 `srvsvc.ServerGetInfo`。

R10 的判据是「整段替换」而不是「可选旁路」：只要还有 `USE_XXX=false` 这类
让新代码完全不介入的开关、或者旧实现还留在仓库里当 fallback，这个检查就该 FAIL。

判据：
  1) 手写报文实现与双跑对照两个文件都已删除
  2) 全工程不再有 DCE/RPC 报文常量与手工组包（PTYPE_BIND / OP_NUM_* / writeWString）
  3) 枚举走 jcifs：`MsrpcShareEnum` + `DcerpcHandle.getHandle`
  4) 设备名走 srvsvc 的 `ServerGetInfo` 层级 100（不是 hand-written NetrWkstaGetInfo）
  5) 枚举调用在试连兜底之前（默认路径是库，兜底才是旧式试连）
  6) 兜底仍保留，且失败原因照实传进 ShareListing.reason
  7) 缓存策略没变：只有「设备权威回答」或「试连有命中」才写缓存
  8) 设备名仍是「先已认证、再 guest」，但不再自建 smbj 会话
  9) 取名字预算不超过扫描器的名字阶段预算（否则 48 路并发会把整体拖爆）
 10) jcifs 依赖仍打进包（bcprov 未被排除，否则 NTLM 哈希时 NoClassDefFoundError）
 11) 全工程无「双跑」残留
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "app" / "src" / "main" / "java" / "com" / "gazereel" / "vr" / "data"
APP = ROOT / "app" / "src" / "main" / "java"
GRADLE = ROOT / "app" / "build.gradle.kts"

LISTER = DATA / "SmbShareLister.kt"
POOL = DATA / "SmbSessionPool.kt"
PROBE = DATA / "SmbNameProbe.kt"
SCANNER = DATA / "LanSmbScanner.kt"


def read(path):
    return path.read_text(encoding="utf-8")


def grab(pattern, text, what):
    m = re.search(pattern, text)
    if not m:
        raise SystemExit(f"抓不到 {what}：{pattern}")
    return float(m.group(1))


def main():
    ok = True
    for p in (LISTER, POOL, PROBE, SCANNER):
        if not p.exists():
            raise SystemExit(f"缺文件：{p}")

    lister = read(LISTER)
    pool = read(POOL)
    probe = read(PROBE)
    scanner = read(SCANNER)
    gradle = read(GRADLE)

    all_kotlin = "\n".join(p.read_text(encoding="utf-8") for p in APP.rglob("*.kt"))

    print("=== 1. 旧实现是否真的摘掉了 ===")
    gone = not (DATA / "SmbShareEnum.kt").exists() and not (DATA / "JcifsCrossCheck.kt").exists()
    print(f"手写实现与双跑对照两文件已删除  {'OK' if gone else 'FAIL 还在仓库里'}")
    ok &= gone

    residue = [k for k in ("PTYPE_BIND", "PTYPE_BIND_ACK", "OP_NUM_NET_SHARE_ENUM",
                          "SRVSVC_UUID", "writeWString", "JcifsCrossCheck") if k in all_kotlin]
    print(f"无手写报文常量 / 对照层残留  {'OK' if not residue else 'FAIL ' + ', '.join(residue)}")
    ok &= not residue

    # 新文件自己的注释会复述「对照层已删除」这段历史，不算残留；
    # 除它以外任何地方再出现对照层痕迹，就是没删干净。
    others = "\n".join(
        p.read_text(encoding="utf-8") for p in APP.rglob("*.kt") if p != LISTER
    )
    dual = [k for k in ("双跑", "compareShares", "compareName", "JcifsCrossCheck") if k in others]
    print(f"新实现之外无对照层痕迹  {'OK' if not dual else 'FAIL ' + ', '.join(dual)}")
    ok &= not dual

    print()
    print("=== 2. 默认路径是库，不是自研协议 ===")
    uses_lib = ("MsrpcShareEnum(host)" in lister
                and "DcerpcHandle.getHandle(" in lister
                and "ncacn_np:" in lister and "pipe" in lister)
    print(f"枚举用 MsrpcShareEnum + DcerpcHandle  {'OK' if uses_lib else 'FAIL'}")
    ok &= uses_lib

    manual = "ByteArrayOutputStream" in lister or "writeLe32" in lister
    print(f"新实现里没有手工组包  {'OK' if not manual else 'FAIL 又回到拼字节了'}")
    ok &= manual is False

    level100 = bool(re.search(r"srvsvc\.ServerGetInfo\([^)]*,\s*100\s*,", lister))
    print(f"设备名用 NetrServerGetInfo 层级 100  {'OK' if level100 else 'FAIL'}")
    ok &= level100

    print()
    print("=== 3. 调用顺序与兜底 ===")
    start = pool.find("fun listShares(")
    body = pool[start:] if start >= 0 else ""
    lib_at = body.find("SmbShareLister.listShares(")
    probe_at = body.find("probeCommon(")
    ordered = lib_at >= 0 and probe_at > lib_at
    print(f"先走库枚举、再试连兜底（{lib_at} / {probe_at}）  {'OK' if ordered else 'FAIL 顺序或调用点不对'}")
    ok &= ordered

    reason_ok = "ShareListing(probed, false, reason)" in pool
    print(f"兜底仍把失败原因写进 reason  {'OK' if reason_ok else 'FAIL 用户会看到空白理由'}")
    ok &= reason_ok

    cache_ok = "if (result.authoritative || result.names.isNotEmpty())" in pool
    print(f"缓存只在权威回答或有命中时写入  {'OK' if cache_ok else 'FAIL 会把网络抖动固化 60 秒'}")
    ok &= cache_ok

    print()
    print("=== 4. 设备名链路 ===")
    two_phase = (probe.count("attempt(host,") >= 2
                 and "已认证会话没问到名字" in probe
                 and "guest(srvsvc)" in probe)
    print(f"先已认证、再 guest 兜底  {'OK' if two_phase else 'FAIL 策略被简化了'}")
    ok &= two_phase

    no_smbj = "com.hierynomus" not in probe
    print(f"取名不再自建 smbj 会话  {'OK' if no_smbj else 'FAIL 还在占片库的会话槽位'}")
    ok &= no_smbj

    name_ms = grab(r"const val NAME_TIMEOUT_MS = ([\d_]+)", lister, "NAME_TIMEOUT_MS")
    name_ms = int(name_ms)
    budget = grab(r"const val NAME_BUDGET_MS = ([\d_]+)L", scanner, "NAME_BUDGET_MS")
    in_budget = 0 < name_ms <= budget
    print(f"取名预算 {name_ms}ms ≤ 扫描预算 {budget:.0f}ms  {'OK' if in_budget else 'FAIL 会拖爆 48 路并发'}")
    ok &= in_budget

    list_ms = int(grab(r"const val LIST_TIMEOUT_MS = ([\d_]+)", lister, "LIST_TIMEOUT_MS"))
    list_ok = 0 < list_ms <= 10_000
    print(f"列共享预算 {list_ms}ms 在 10s 内  {'OK' if list_ok else 'FAIL 会让「加片库」无限等'}")
    ok &= list_ok

    print()
    print("=== 5. 依赖 ===")
    has_dep = "implementation(libs.jcifs.ng)" in gradle
    keeps_bc = "org.bouncycastle" in gradle and "exclude(group = \"org.bouncycastle\"" not in gradle
    print(f"jcifs 依赖仍在且未排除 bcprov  {'OK' if has_dep and keeps_bc else 'FAIL 认证会崩'}")
    ok &= has_dep and keeps_bc

    stale = "只用于「共享枚举 + 取名」的双跑对照" in gradle
    print(f"构建注释不再是「只做对照」  {'OK' if not stale else 'FAIL 仍是旁路口径'}")
    ok &= not stale

    print()
    print("结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
