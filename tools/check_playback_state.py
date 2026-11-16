#!/usr/bin/env python3
"""校验「播放状态三分语义」与进度条缓冲段。

19:2x 修的是**一个布尔顶三个语义**：`playing = player.isPlaying` 在缓冲时是 false，
于是控制台的键形翻回「播放」、离开前台时漏掉暂停、回来时错说「已为你暂停」。

本脚本把修法钉成判据，改回去会立刻 FAIL：
  1) `PlaybackState` 必须有 playWhenReady / buffering / bufferedMs，且由 PlayerController 填
  2) `VrUiState` 有 wantPlay / buffering / bufferedMs，且由 `onPlaybackState` 映射
  3) 控制台播放键的**键形与键名**看 `wantPlay`，页面上不许再有 `state.playing` 决定键形
  4) 进度条三层顺序：轨道 → 缓冲段 → 已播段（缓冲段必须先用 `VrTheme.AccentCache`）
  5) 缓冲位置必须钳到 `>= played`（回退 seek 后缓冲可能落后于播放头）
  6) 「缓冲中」只在 `buffering` 为真时出现在板顶那一行
  7) 离开 / 回到前台看 `wantPlay`，不看 `playing`
  8) 渲染器的 UI 签名包含 wantPlay / buffering / 量化后的缓冲位置（否则纹理不刷新）
"""

import math
import re
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/main/java/com/gazereel/vr"
PS = SRC / "player/PlaybackState.kt"
PC = SRC / "player/PlayerController.kt"
US = SRC / "ui/VrUiState.kt"
CO = SRC / "ui/VrCoordinator.kt"
UI = SRC / "render/VrUiLayer.kt"
RD = SRC / "render/VrRenderer.kt"
OUT = ROOT.parent / "output/design/playback-buffer-bar.png"


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


def draw_bar(path: Path, played: float, buffered: float) -> None:
    """三层的示意（不是界面截图）：只证明层次与透明度分得开。"""
    W, H = 960, 190
    img = Image.new("RGB", (W, H), (25, 25, 26))
    d = ImageDraw.Draw(img)
    L, R, y = 60, 900, 104

    def seg(x0, x1, color):
        d.rounded_rectangle([x0, y - 12, x1, y + 12], 12, fill=color)

    seg(L, R, (58, 58, 64))                     # 轨道 #3A3A40
    seg(L, L + (R - L) * buffered, (110, 92, 44))  # 缓冲 #FFC96B @39% 压深色底
    seg(L, L + (R - L) * played, (255, 213, 112))  # 已播 #FFD570
    d.ellipse([L + (R - L) * played - 9, y - 9, L + (R - L) * played + 9, y + 9],
              fill=(255, 255, 255))

    d.text((60, 26), "进度条三层（示意，非界面截图）", font=font(20), fill=(255, 213, 112))
    d.text((60, 140), f"已播 {played*100:.0f}%", font=font(15), fill=(255, 213, 112))
    d.text((60, 162), f"缓冲 {buffered*100:.0f}%（恒 ≥ 已播，回退 seek 后不会倒挂）",
           font=font(15), fill=(200, 178, 120))
    img.save(path)


def main() -> int:
    ps, pc, us, co = read(PS), read(PC), read(US), read(CO)
    ui, rd = read(UI), read(RD)

    ok = True

    # 1) PlaybackState 三个字段 + PlayerController 真的填
    fields = ["playWhenReady", "buffering", "bufferedMs"]
    has_fields = all(re.search(rf"val {f}:", ps) for f in fields)
    print(f"PlaybackState 三分字段 {fields}  {'OK' if has_fields else 'FAIL'}")
    ok &= has_fields

    emit = snippet(pc, r"private fun emitState.*?\n    \}", "PlayerController.emitState")
    fills = {
        "playWhenReady": "playWhenReady = player.playWhenReady" in emit,
        "buffering": "buffering = player.playbackState == Player.STATE_BUFFERING" in emit,
        "bufferedMs": "bufferedMs = player.bufferedPosition" in emit,
    }
    for k, v in fills.items():
        print(f"  PlayerController 填 {k}  {'OK' if v else 'FAIL 没填，控制台读到默认值'}")
        ok &= v

    # 2) VrUiState + 映射
    ui_fields = all(re.search(rf"val {f}:", us) for f in ["wantPlay", "buffering", "bufferedMs"])
    print(f"VrUiState 有 wantPlay/buffering/bufferedMs  {'OK' if ui_fields else 'FAIL'}")
    ok &= ui_fields

    onpb = snippet(co, r"fun onPlaybackState\(state: PlaybackState\).*?\n    \}", "onPlaybackState")
    mapped = all(re.search(rf"{k} = state\.", onpb) for k in ["wantPlay", "buffering", "bufferedMs"])
    print(f"onPlaybackState 映射三者  {'OK' if mapped else 'FAIL 字段一路断在映射上'}")
    ok &= mapped

    # 3) 键形看 wantPlay
    player = snippet(ui, r"private fun drawPlayer\(state: VrUiState\).*?\n    \}", "drawPlayer")
    glyph_from_want = "if (state.wantPlay) Glyph.PAUSE else Glyph.PLAY" in player
    label_from_want = 'if (state.wantPlay) "暂停" else "播放"' in player
    print(f"播放键的图形看 wantPlay  {'OK' if glyph_from_want else 'FAIL 又用 playing 了'}")
    print(f"播放键的文案看 wantPlay  {'OK' if label_from_want else 'FAIL 又用 playing 了'}")
    ok &= glyph_from_want and label_from_want

    leftover = re.findall(r"state\.playing", player)
    print(f"drawPlayer 里残留 state.playing 共 {len(leftover)} 处  "
          f"{'OK' if not leftover else 'FAIL 键形仍会被缓冲翻面'}")
    ok &= not leftover

    # 4) 三层顺序与颜色
    i_track = player.find("0xFF3A3A40")
    i_cache = player.find("VrTheme.AccentCache")
    i_played = player.find("fill.color = C_ACCENT", i_cache if i_cache > 0 else 0)
    ordered = 0 < i_track < i_cache and i_cache < i_played
    print(f"绘制顺序 轨道 → 缓冲 → 已播  {'OK' if ordered else 'FAIL 层序不对会盖错'}")
    ok &= ordered

    # 5) 钳位
    clamp = re.search(r"bufferedMs\.toFloat\(\) / state\.durationMs\).*?coerceIn\(played, 1f\)",
                      player, re.S)
    print(f"缓冲位置钳到 >= played  {'OK' if clamp else 'FAIL 回退 seek 后会倒挂'}")
    ok &= bool(clamp)

    # 6) 「缓冲中」
    buf_text = 'if (state.buffering) append(" · 缓冲中")' in player
    print(f"「缓冲中」只在 buffering 时出现  {'OK' if buf_text else 'FAIL'}")
    ok &= buf_text

    # 7) 前后台看 wantPlay
    leave = snippet(co, r"fun onLeaveForeground\(\)", "onLeaveForeground")
    leave_body = co[co.find(leave): co.find(leave) + 1200]
    leave_ok = "if (uiState.wantPlay) player.pause()" in leave_body
    ret = co[co.find("fun onReturnForeground()"):]
    ret_body = ret[:1200]
    ret_ok = "!uiState.wantPlay" in ret_body and "!uiState.playing" not in ret_body
    print(f"离开前台按意图暂停  {'OK' if leave_ok else 'FAIL 缓冲时会漏暂停'}")
    print(f"回到前台按意图提示  {'OK' if ret_ok else 'FAIL 缓冲时会错说已暂停'}")
    ok &= leave_ok and ret_ok

    # 8) 渲染器签名
    key = snippet(rd, r"private fun uiSignature\(.*?\n    \}", "uiSignature")
    keyed = all(k in key for k in ("state.wantPlay.hashCode()", "state.buffering.hashCode()",
                                   "bufferedMs * 100L"))
    print(f"UI 纹理签名含三者（缓冲按 1% 量化）  {'OK' if keyed else 'FAIL 纹理会停在旧帧'}")
    ok &= keyed

    print()

    # 示意条：按快照里的 72s / 96s / 180s 举例
    dur, pos, buf = 180.0, 72.0, 96.0
    draw_bar(OUT, pos / dur, buf / dur)
    print(f"已生成 {OUT}")

    # 文案长度：板顶那行加了「 · 缓冲中」之后不能越出板宽
    x0, x1 = 132.0, 892.0
    avail = (x1 - 32) - (x0 + 32)
    head = "左右 3D · 快退/快进 2 分钟"
    px = len(head) * 19 + len(" · 缓冲中") * 19
    within = px < avail
    print(f"板顶行宽估算 ≈{px:.0f}px / 可用 {avail:.0f}px  "
          f"{'OK' if within else 'FAIL 加了「缓冲中」会越界'}")
    ok &= within

    print()
    print("结论：" + ("全部通过" if ok else "存在 FAIL"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
