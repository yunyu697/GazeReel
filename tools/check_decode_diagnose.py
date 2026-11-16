#!/usr/bin/env python3
"""2.1.0-alpha2 · 解码诊断（DecoderCaps.diagnose）静态 + 算法校验。

背景：真机 `AJVR-215`（5400×2700 VP9@59.94）报

    format_supported=NO_EXCEEDS_CAPABILITIES
    Decoder init failed: c2.qti.vp9.decoder
    Caused by: java.lang.IllegalArgumentException

旧版诊断用 `supportedWidths.upper` 比一下宽高，漏掉了**尺寸×帧率的组合上限**
和**宽高对齐**，于是给出「换 4K 以内」这种对不上现场的建议。

这个脚本做两件事：

1. 静态检查源码是否真的改成了「问平台」（isSizeSupported /
   areSizeAndRateSupported / getSupportedFrameRatesFor / widthAlignment），
   并且确认播放错误不再写 CrashLog（否则日志页会把它标成「上次崩溃」）。
2. 用 Python 复刻这套分支，喂四组假的能力数据跑一遍，把**每一支会说出什么话**
   打印出来 —— 算法写错时这里会先露馅，不用等真机。
"""

from __future__ import annotations

import math
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
KT = ROOT / "app/src/main/java/com/gazereel/vr"
DC = (KT / "player/DecoderCaps.kt").read_text(encoding="utf-8")
PC = (KT / "player/PlayerController.kt").read_text(encoding="utf-8")
UI = (KT / "render/VrUiLayer.kt").read_text(encoding="utf-8")

OK = "OK"
BAD = "FAIL"


# ---------------------------------------------------------------- 静态检查
def static_checks() -> bool:
    ok = True
    print("一、诊断走的是平台判据，不是自己比大小")
    checks = {
        "isSizeSupported": "isSizeSupported(" in DC,
        "areSizeAndRateSupported": "areSizeAndRateSupported(" in DC,
        "getSupportedFrameRatesFor": "getSupportedFrameRatesFor(" in DC,
        "widthAlignment / heightAlignment": "widthAlignment" in DC and "heightAlignment" in DC,
        "问的是 Media3 先试的那个（firstFor）": "firstFor(" in DC and "val cap = firstFor(mime)" in DC,
        "不再用 best() 判断单条轨道": "best(mime)" not in DC.split("fun diagnose(")[1],
    }
    for name, good in checks.items():
        print(f"  {name:38s} {OK if good else BAD}")
        ok &= good

    print()
    print("二、错误链与文案")
    # 只看代码，不看注释：注释里举例「本机上限 4096×2176」是说明，不是文案。
    pc_code = re.sub(r"//[^\n]*", "", PC)
    chain = {
        "PlayerController 调 diagnose": "DecoderCaps.diagnose(" in PC,
        "诊断行写进 RunLog": '"诊断 ${it.logLine}"' in PC,
        "播放错误不再写 CrashLog": "CrashLog.write" not in PC,
        "提示里不再写死某个上限尺寸": "4096×2176" not in pc_code,
        "Verdict 同时带结论与建议": "val headline" in DC and "val advice" in DC,
        "hint 由 headline + advice 组成": '"$headline；$advice"' in DC,
    }
    for name, good in chain.items():
        print(f"  {name:38s} {OK if good else BAD}")
        ok &= good

    print()
    print("三、格式来源：异常自带，不再依赖 currentTracks（alpha2 的坑）")
    src = {
        "读 ExoPlaybackException.rendererFormat": "as? ExoPlaybackException" in PC
        and "rendererFormat" in PC,
        "onTracksChanged 暂存轨道": "override fun onTracksChanged" in PC,
        "换片时清掉上一部留档": "lastVideoTrack = null" in PC,
        "不再读 player.currentTracks 找格式": "player.currentTracks" not in pc_code,
        "日志记平台判定 formatSupport": "supportName(exo?.rendererFormatSupport)" in PC,
        "能力超限时标题改口径": "画面超出本机解码上限" in PC and "exceeds" in PC,
    }
    for name, good in src.items():
        print(f"  {name:38s} {OK if good else BAD}")
        ok &= good

    print()
    print("四、跨编码建议 + 日志去重")
    misc = {
        "DecoderCaps 有 alternative()": "private fun alternative(" in DC,
        "尺寸超限分支用上它": "alternative(mime, width, height)" in DC,
        "摘要写出软解上限": "softwareFor(" in DC and "（软解 " in DC,
        "候选链只记一次": "loggedCodecChains" in PC,
    }
    for name, good in misc.items():
        print(f"  {name:38s} {OK if good else BAD}")
        ok &= good

    print()
    print("五、卡片容得下三行")
    ui = {
        "wrapText 放到 3 行": re.search(r"wrapText\(it, small, UiGrid\.spanW\(3\) - 72f, 3\)", UI) is not None,
        "三行时按钮下移": "else -> 342f" in UI,
        "行高 24px": "282f + i * 24f" in UI,
    }
    for name, good in ui.items():
        print(f"  {name:38s} {OK if good else BAD}")
        ok &= good
    return ok


# ------------------------------------------------- 算法复刻（与 Kotlin 同构）
class Cap:
    def __init__(self, name, maxw, maxh, maxfps, alignw=1, alignh=1, fps_at_size=None):
        self.name = name
        self.maxw = maxw
        self.maxh = maxh
        self.maxfps = maxfps
        self.alignw = alignw
        self.alignh = alignh
        self.fps_at_size = fps_at_size  # 该尺寸下的帧率上限（模拟 getSupportedFrameRatesFor）


def floor_to(v: int, a: int) -> int:
    if a <= 1:
        return v
    return (v // a) * a


def round_down_fps(upper: float) -> int:
    return max(15, int(math.floor(upper / 5.0) * 5))


def one_decimal(v: float) -> str:
    s = round(v * 10)
    return f"{s // 10}.{s % 10}"


def diagnose(cap: Cap, w: int, h: int, fps: float, alt=None):
    """返回 (declared, hint)，逐句对应 DecoderCaps.diagnose。

    `alt` 模拟 `alternative()`：同尺寸下另一个能解的编码，形如 ("H.265", "8192×8192")。
    """
    has_fps = fps > 0
    size_ok = (
        w <= cap.maxw
        and h <= cap.maxh
        and (cap.alignw <= 1 or w % cap.alignw == 0)
        and (cap.alignh <= 1 or h % cap.alignh == 0)
    )
    rate_ok = has_fps and size_ok and (cap.fps_at_size(w, h) if callable(cap.fps_at_size) else cap.maxfps) + 0.5 >= fps
    declared = size_ok and (not has_fps or rate_ok)
    label = "VP9"
    fps_text = f"{round(fps)}fps" if has_fps else "帧率未知"

    if declared:
        headline = f"平台声明支持这个规格（{cap.maxw}×{cap.maxh} {round(cap.maxfps)}fps），但厂商解码器配置被拒"
        advice = f"转成 H.265，或降到 {cap.maxw // 2}×{cap.maxh // 2} 一档再试"
        return True, headline, advice

    if not size_ok:
        misaligned = (cap.alignw > 1 and w % cap.alignw != 0) or (
            cap.alignh > 1 and h % cap.alignh != 0
        )
        over = w > cap.maxw or h > cap.maxh
        if misaligned and not over:
            aw, ah = floor_to(w, cap.alignw), floor_to(h, cap.alignh)
            loss = 100.0 - 100.0 * (aw * ah) / (w * h)
            return (
                False,
                f"本机 {label} 解码器要求宽高按 {cap.alignw}×{cap.alignh} 对齐，{w}×{h} 不满足",
                f"改成 {aw}×{ah} 就能播（少 {one_decimal(loss)}% 画面）",
            )
        advice = (
            f"转 {alt[0]} 可保持 {w}×{h}（本机上限 {alt[1]}），分辨率不用降"
            if alt
            else f"换 ≤{cap.maxw}×{cap.maxh} 的版本，或转成 H.265"
        )
        return False, f"画面 {w}×{h} 超过本机 {label} 上限 {cap.maxw}×{cap.maxh}", advice

    here = cap.fps_at_size(w, h) if callable(cap.fps_at_size) else cap.fps_at_size
    target = round_down_fps(here) if here else None
    if target and target < round(fps):
        advice = f"转成 {target}fps 就能播，分辨率不用降"
    else:
        advice = "转 30fps 版本，或用 H.264 / H.265 重压一遍"
    return False, f"这个尺寸下本机 {label} 最高 {round(here)}fps，此片 {fps_text}", advice


def simulate() -> bool:
    print()
    print("六、四组能力数据下，5400×2700@59.94 会得到什么话")
    w, h, fps = 5400, 2700, 59.94
    # 真机 20:22 的实测：VP9 4096×4096@200fps，而 HEVC/H.264/AV1 都是 8192×8192
    alt_hevc = ("H.265", "8192×8192")
    cases = [
        (
            "A 真机现状：VP9 只到 4096，HEVC 到 8192",
            Cap("c2.qti.vp9.decoder", 4096, 4096, 200.0),
            "转 H.265 可保持 5400×2700",
            alt_hevc,
        ),
        (
            "B 没有别的编码能解，只能降分辨率",
            Cap("c2.qti.vp9.decoder", 4096, 4096, 200.0),
            "换 ≤4096×4096 的版本",
            None,
        ),
        (
            "C 尺寸够、但 8K 只给 30fps",
            Cap("c2.qti.vp9.decoder", 8192, 4352, 60.0, fps_at_size=lambda ww, hh: 30.0),
            "最高 30fps",
            alt_hevc,
        ),
        (
            "D 8K60 但宽高需按 64 对齐",
            Cap("c2.qti.vp9.decoder", 8192, 4352, 60.0, alignw=64, alignh=64),
            "要求宽高按 64×64 对齐",
            alt_hevc,
        ),
        (
            "E 声明支持却仍被拒",
            Cap("c2.qti.vp9.decoder", 8192, 4352, 60.0, fps_at_size=lambda ww, hh: 60.0),
            "平台声明支持",
            alt_hevc,
        ),
    ]
    ok = True
    for title, cap, expect, alt in cases:
        declared, headline, advice = diagnose(cap, w, h, fps, alt)
        print(f"  {title}")
        print(f"    declared={declared}  「{headline}；{advice}」")
        good = expect in headline or expect in advice
        print(f"    {'   '}{OK if good else BAD + ' 文案与预期不符'}")
        ok &= good
        ok &= bool(advice)
    return ok


def main() -> int:
    print("解码诊断校验（2.1.0-alpha2）")
    print("=" * 66)
    ok = static_checks()
    ok &= simulate()
    print()
    print("结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
