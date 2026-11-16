#!/usr/bin/env python3
"""校验「缓冲提示」与「解码失败诊断」两条改动。

2.1.0 这一批只做两件事：

A. 缓冲提示（`VrUiLayer.drawBufferingHint`）
   控制台 8 秒淡出后，缓冲就彻底没有反馈了：画面停住、什么都没有，
   「正在读」和「卡死」在 VR 里长得一模一样。用户 19:5x 认可这条例外，
   因为它只在**画面本来就不可观看**时出现。

B. 解码失败诊断（`DecoderCaps` + `PlayerController`）
   真机 `AJVR-215`（5400×2700 VP9@59.94）稳定报 `code=4001` → `IDLE`，
   用户看到的却是「此编码当前设备不支持」，第二行还写「检查设备是否在线、
   账号是否过期」—— 网络明明没问题。现在：打开解码器回退、记录候选链、
   把「片子多大 / 本机上限多少」写进错误卡与日志。

判据都是"改回去立刻 FAIL"的那种：
  1) 缓冲提示只在控制台收起分支调用（否则和控制台板顶的「缓冲中」重复）
  2) 出现条件是 buffering 且 wantPlay（暂停后预缓冲不该冒出来）
  3) 不压字幕：提示顶边 ≥ 字幕两行的底边
  4) 不越出画布，且不注册凝视目标（它是提示，不是按钮）
  5) 解码器回退必须打开
  6) 错误码必须分五类，不许再合并成一句「此编码当前设备不支持」
  7) errorHint 必须贯穿 PlaybackState → VrUiState → 协调器 → 绘制 → 签名
  8) 能力摘要必须写进 RunLog（4001 判读全靠它）
"""

import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/gazereel/vr"
PS = SRC / "player/PlaybackState.kt"
PC = SRC / "player/PlayerController.kt"
DC = SRC / "player/DecoderCaps.kt"
US = SRC / "ui/VrUiState.kt"
CO = SRC / "ui/VrCoordinator.kt"
UI = SRC / "render/VrUiLayer.kt"
RD = SRC / "render/VrRenderer.kt"
OUT = ROOT.parent / "output/design/buffer-hint-position.png"

PITCH = -13.0        # 播放页面板 pitch
HALF_DEG = 16.7      # 画布半高对应张角


def read(p: Path) -> str:
    return p.read_text(encoding="utf-8")


def snippet(text: str, pattern: str, name: str) -> str:
    m = re.search(pattern, text, re.S)
    if not m:
        print(f"FAIL 找不到 {name}")
        sys.exit(1)
    return m.group(0)


def font(size):
    for p in ("C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/msyhbd.ttc"):
        try:
            return ImageFont.truetype(p, size)
        except OSError:
            continue
    return ImageFont.load_default()


def y_at(deg: float) -> float:
    """视线仰角 → 画布 y。与 VrUiLayer.gazePoint 同一映射。"""
    return 256.0 - (deg - PITCH) / HALF_DEG * 256.0


def deg_at(y: float) -> float:
    return PITCH + (256.0 - y) / 256.0 * HALF_DEG


def draw_diagram(path: Path, pill, subtitle_bottom: float, console_box) -> None:
    """画布位置示意：画面 / 地面 / 字幕 / 控制台 / 缓冲提示，四者互不打架。"""
    W, H = 1024, 512
    pad_r = 300
    img = Image.new("RGB", (W + pad_r, H + 40), (20, 21, 25))
    d = ImageDraw.Draw(img)
    d.text((20, 10), "播放页画布 1024×512（面板 pitch −13°，纵轴 ±16.7°）",
           font=font(16), fill=(220, 226, 236))

    oy = 34
    # 画面（2D 银幕）下沿 −9.8° → 画布 y≈207；之上是画面，之下是地面。
    screen_bottom = int(y_at(-9.8))
    d.rectangle([0, oy, W, oy + screen_bottom], fill=(38, 42, 50))
    d.rectangle([0, oy + screen_bottom, W, oy + H], fill=(15, 16, 19))
    d.line([0, oy + screen_bottom, W, oy + screen_bottom], fill=(70, 78, 92))
    d.text((14, oy + 8), "画面（银幕下沿 −9.8°）", font=font(15), fill=(150, 160, 175))

    # 角度刻度
    for deg in (4, 0, -5, -9.8, -12, -15.3, -18.5, -25, -29.5):
        y = oy + y_at(deg)
        if 0 <= y - oy <= H:
            d.line([0, y, 10, y], fill=(90, 96, 110))
            d.text((12, y - 8), f"{deg:+.1f}°", font=font(12), fill=(120, 128, 142))

    # 字幕（2 行，186–284）
    sub_top = oy + 186
    sub_bot = oy + subtitle_bottom
    d.rectangle([212, sub_top, 812, sub_bot], outline=(120, 170, 255), width=2)
    d.text((216, sub_top + 4), f"字幕 2 行 186–{subtitle_bottom:.0f}px",
           font=font(14), fill=(140, 185, 255))

    # 控制台（240–508，收起时不画）
    x0, y0, x1, y1 = console_box
    for yy in range(int(y0), int(y1), 12):
        d.line([x0, oy + yy, x1, oy + yy], fill=(74, 74, 82), width=1)
    d.rectangle([x0, oy + y0, x1, oy + y1], outline=(96, 96, 106), width=2)
    d.text((x0 + 8, oy + y0 + 6), "控制台 240–508（收起时才画缓冲提示）",
           font=font(14), fill=(150, 150, 162))

    # 缓冲提示
    px0, py0, px1, py1 = pill
    d.rectangle([px0, oy + py0, px1, oy + py1], fill=(38, 34, 26), outline=(255, 201, 107), width=3)
    d.text((px0 + 16, oy + py0 + 10), "缓冲中", font=font(17), fill=(238, 242, 248))
    d.rounded_rectangle([px0 + 32, oy + py1 - 12, px0 + 208, oy + py1 - 8], 2,
                        fill=(110, 92, 44))
    d.text((px0, oy + py1 + 6), f"缓冲提示 {py0:.0f}–{py1:.0f}px",
           font=font(14), fill=(255, 201, 107))

    # 右栏说明
    rx = W + 22
    ty = 46
    d.text((rx, ty), "为什么这条不挡内容", font=font(18), fill=(238, 242, 248))
    ty += 34
    lines = [
        f"提示落在 {deg_at(py0):.1f}° ~ {deg_at(py1):.1f}°：",
        "2D 银幕下沿（−9.8°）以下的地面，",
        "整块在画面之外。",
        "",
        "出现条件：buffering 且 wantPlay。",
        "画面本来就停着、不可观看，",
        "所以覆盖与否不改变「看不看得成」。",
        "",
        f"不压字幕：字幕底 {subtitle_bottom:.0f}px，",
        f"提示顶 {py0:.0f}px，留 {py0 - subtitle_bottom:.0f}px。",
        "",
        "控制台可见时不出：板顶已有「· 缓冲中」，",
        "两条同时出现只是噪音。",
    ]
    for line in lines:
        d.text((rx, ty), line, font=font(14), fill=(160, 168, 182))
        ty += 22

    img.save(path)


def main() -> int:
    ps, pc, dc = read(PS), read(PC), read(DC)
    us, co, ui, rd = read(US), read(CO), read(UI), read(RD)
    ok = True

    # ── A. 缓冲提示 ────────────────────────────────────────────────
    hint = snippet(ui, r"private fun drawBufferingHint\(state: VrUiState\).*?\n    \}", "drawBufferingHint")

    gate = "if (!state.buffering || !state.wantPlay) return" in hint
    print(f"出现条件 = buffering && wantPlay  {'OK' if gate else 'FAIL 暂停后预缓冲也会冒出来'}")
    ok &= gate

    player = snippet(ui, r"private fun drawPlayer\(state: VrUiState\).*?\n    \}", "drawPlayer")
    closed = re.search(r"if \(!state\.consoleVisible\) \{(.*?)return\s*\n\s*\}", player, re.S)
    in_closed = bool(closed and "drawBufferingHint(state)" in closed.group(1))
    print(f"只在控制台收起分支调用  {'OK' if in_closed else 'FAIL 会与控制台板顶重复'}")
    ok &= in_closed

    # 提示矩形与字幕底边
    m = re.search(r"val pill = RectF\(([\d.]+)f, ([\d.]+)f, ([\d.]+)f, ([\d.]+)f\)", hint)
    if not m:
        print("FAIL 解析不到缓冲提示的 RectF")
        return 1
    pill = tuple(float(v) for v in m.groups())

    sub = snippet(ui, r"private fun drawSubtitle\(value: String\).*?\n    \}", "drawSubtitle")
    top_m = re.search(r"val top = ([\d.]+)f", sub)
    pad_m = re.search(r"top \+ ([\d.]+)f \+ lines\.size \* ([\d.]+)f", sub)
    sub_top = float(top_m.group(1)) if top_m else 186.0
    sub_pad = float(pad_m.group(1)) if pad_m else 22.0
    sub_line = float(pad_m.group(2)) if pad_m else 38.0
    sub_bottom = sub_top + sub_pad + 2 * sub_line

    clear = pill[1] >= sub_bottom
    print(f"不压字幕：提示顶 {pill[1]:.0f} ≥ 字幕两行底 {sub_bottom:.0f}  "
          f"{'OK' if clear else 'FAIL 字幕会被压住'}")
    ok &= clear

    inside = 0 <= pill[0] < pill[2] <= 1024 and 0 <= pill[1] < pill[3] <= 512
    print(f"整块在画布内  {'OK' if inside else 'FAIL 会被画布裁掉'}")
    ok &= inside

    no_target = "targets +=" not in hint and "GazeTarget(" not in hint
    print(f"不注册凝视目标（是提示不是按钮）  {'OK' if no_target else 'FAIL 会被扫到'}")
    ok &= no_target

    uses_cache = "VrTheme.AccentCache" in hint
    print(f"缓冲段用 AccentCache（与进度条同一层语义）  {'OK' if uses_cache else 'FAIL'}")
    ok &= uses_cache

    # ── B. 解码诊断 ────────────────────────────────────────────────
    fallback = "setEnableDecoderFallback(true)" in pc
    print(f"解码器回退已打开  {'OK' if fallback else 'FAIL 4001 仍然一击致命'}")
    ok &= fallback

    factory = "ExoPlayer.Builder(appContext, renderersFactory)" in pc
    print(f"ExoPlayer 真的用了这个 factory  {'OK' if factory else 'FAIL 建了没用，等于没改'}")
    ok &= factory

    select_logged = "MediaCodecSelector.DEFAULT.getDecoderInfos" in pc and "候选" in pc
    print(f"记录解码器候选链  {'OK' if select_logged else 'FAIL 出事仍不知道试了谁'}")
    ok &= select_logged

    codes = [
        "ERROR_CODE_DECODER_INIT_FAILED",
        "ERROR_CODE_DECODER_QUERY_FAILED",
        "ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES",
        "ERROR_CODE_DECODING_FORMAT_UNSUPPORTED",
        "ERROR_CODE_DECODING_FAILED",
    ]
    missing = [c for c in codes if c not in pc]
    print(f"五类解码错误分码  {'OK' if not missing else 'FAIL 缺 ' + ', '.join(missing)}")
    ok &= not missing

    old = "此编码当前设备不支持" in pc
    print(f"旧的合并文案已删除  {'OK' if not old else 'FAIL 又合并成一句没信息的话'}")
    ok &= not old

    track = "player.currentTracks" in pc and "getTrackFormat" in pc
    print(f"从 currentTracks 取轨信息（4001 时 VideoSize 拿不到）  "
          f"{'OK' if track else 'FAIL 只能写「尺寸未知」'}")
    ok &= track

    chain = {
        "PlaybackState.errorHint": re.search(r"val errorHint: String\?", ps) is not None,
        "VrUiState.errorHint": re.search(r"val errorHint: String\?", us) is not None,
        "onPlaybackState 映射": "errorHint = if (error != null)" in co,
        "错误卡使用它": "state.errorHint" in ui,
        "UI 签名包含它": "state.errorHint?.hashCode()" in rd,
    }
    for name, good in chain.items():
        print(f"  {name}  {'OK' if good else 'FAIL 链路断了'}")
        ok &= good

    caps_ok = ("MediaCodecList(MediaCodecList.ALL_CODECS)" in dc
               and "startsWith(\"c2.android.\")" in dc
               and "fun diagnose(" in dc)
    print(f"DecoderCaps 枚举能力且硬解优先  {'OK' if caps_ok else 'FAIL'}")
    ok &= caps_ok

    logged = "DecoderCaps.summary()" in pc and '"codec"' in pc
    print(f"能力摘要写进 RunLog  {'OK' if logged else 'FAIL 4001 判读没了依据'}")
    ok &= logged

    print()
    draw_diagram(OUT, pill, sub_bottom, (132.0, 240.0, 892.0, 508.0))
    print(f"已生成 {OUT}")

    print()
    print("结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
