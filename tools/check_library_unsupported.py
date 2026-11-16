#!/usr/bin/env python3
"""校验片库「本机解不了」标记：离线探测口径、判定边界、角标几何、重绘签名。

20:2x：真机 VP9 硬解上限 4096×4096，而片源 5400×2700 VP9@59.94 必报 4001。
标记的数据来源是 **stsd**（真实 mime + 宽高），不是文件名；判定只认平台明确
说不支持的那一支。本脚本从源码抓证据，并用真机实测数字复刻判定边界。

判据分两组：静态（源码里有没有、有没有走错路）+ 逻辑（用 Python 复刻
`isUnsupported` 喂真机能力数字，把每条边界该说什么打印出来）。
"""

import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def src(path):
    return (ROOT / path).read_text(encoding="utf-8")


META = src("app/src/main/java/com/gazereel/vr/core/SphericalMetadata.kt")
DC = src("app/src/main/java/com/gazereel/vr/player/DecoderCaps.kt")
STATE = src("app/src/main/java/com/gazereel/vr/ui/VrUiState.kt")
COORD = src("app/src/main/java/com/gazereel/vr/ui/VrCoordinator.kt")
UI = src("app/src/main/java/com/gazereel/vr/render/VrUiLayer.kt")
RD = src("app/src/main/java/com/gazereel/vr/render/VrRenderer.kt")
DB = src("app/src/main/java/com/gazereel/vr/data/db/AppDatabase.kt")
SNAP = src("app/src/main/java/com/gazereel/vr/ui/UiSnapshot.kt")

ok = True


def check(name, cond, detail=""):
    global ok
    suffix = f" · {detail}" if detail else ""
    print(f"{name}  {'OK' if cond else 'FAIL'}{suffix}")
    if not cond:
        ok = False


print("── 一、stsd 探测拿得到真实 mime ──")
check("Probe 有 mime 字段", bool(re.search(r"val mime: String\? = null", META)))
check("dimensionsOf 返回 (宽, 高, mime) 三元组",
      bool(re.search(r"private fun dimensionsOf\(buf: ByteArray\): Triple<Int, Int, String\?>", META)))
for fourcc, mime in (("avc1", "video/avc"), ("hvc1", "video/hevc"),
                     ("vp09", "video/x-vnd.on2.vp9"), ("av01", "video/av01")):
    check(f"4CC {fourcc} → {mime}",
          bool(re.search(r'"' + re.escape(fourcc) + r'"[^\n]*->\s*"' + re.escape(mime) + r'"', META)))
check("仍排除音轨 stsd（mp4a）", '"mp4a"' in META)
check("parse 把 mime 带进 Probe", "mime = dims?.third" in META)
check("describe 打得出 mime（日志可对账）", 'append(" mime=")' in META)
check("探测不依赖 ExoPlayer（可离线）", "androidx.media3" not in META)

print()
print("── 二、判定只认「平台明确说不支持」 ──")
check("isUnsupported 存在", "fun isUnsupported(" in DC)
check("判据是 declaredSupported == false",
      bool(re.search(r"fun isUnsupported\([^)]*\): Boolean =\s*"
                     r"diagnose\([^\n]*\)\?\.declaredSupported == false", DC, re.S)))
check("fps 默认 0（帧率未知只判尺寸）", "fps: Float = 0f" in DC)
check("没把 alternative 改成 public", "private fun alternative(" in DC)
check("diagnose 内部没被换成 best() 判定",
      "best(mime)" not in DC.split("fun diagnose(", 1)[1])

print()
print("── 三、不写库 / 不碰 Room 迁移 ──")
check("AppDatabase 仍是 version 3", bool(re.search(r"version\s*=\s*3\b", DB)))
check("没有新增 MIGRATION_3_4", "MIGRATION_3_4" not in DB)
check("VrUiState 有 unsupportedKeys: Set<String>",
      "val unsupportedKeys: Set<String> = emptySet()" in STATE)
check("Entities 没新增字段（零迁移风险）",
      "unsupported" not in src("app/src/main/java/com/gazereel/vr/data/db/Entities.kt").lower())

print()
print("── 四、后台按当前页探测 ──")
check("有 probeUnsupportedForCurrentPage", "fun probeUnsupportedForCurrentPage()" in COORD)
calls = len(re.findall(r"probeUnsupportedForCurrentPage\(\)", COORD)) - 1
check("触发点覆盖 载入 / 翻页 / 进叠（3 处）", calls >= 3, f"实际 {calls} 处")
check("只探当前页 + 下一页（2 页封顶）", "PAGE_SIZE * 2" in COORD)
check("用 buildRef 构造 MediaRef（主线程先做）", bool(re.search(r"val jobs = batch\.mapNotNull.*buildRef", COORD)))
check("用 FormatProber.probe（带缓存与超时）", bool(re.search(r"p\.probe\(ref\)", COORD)))
check("用 DecoderCaps.isUnsupported 判定", "DecoderCaps.isUnsupported(" in COORD)
check("每部只问一次（失败也算问过）", "unsupportedProbed.add(key)" in COORD)
check("结果只 copy 进内存集合", "uiState.copy(unsupportedKeys =" in COORD)

print()
print("── 五、角标几何与重绘 ──")
check("drawLibrary 传 unsupported", "unsupported = mediaKeyOf(item) in state.unsupportedKeys" in UI)
check("cell 有 unsupported 参数", "unsupported: Boolean = false" in UI)
check("角标放左上角", 'box.left + 8f, box.top + 4f' in UI)
check("角标用 Danger 色", "C_DANGER" in UI and "VrTheme.Danger" in UI)
tag = re.search(r'val tag = "([^"]+)"', UI)
check("文案 ≤3 字（157px 卡片放不下长文案）", bool(tag) and len(tag.group(1)) <= 3,
      f"实际「{tag.group(1) if tag else '?'}」")
# 右上「续 mm:ss」胶囊与左上「解不了」胶囊不能叠：卡片 157px 宽。
card_w = 157.0
left_end = 8 + (3 * 14 + 14)          # 左上：3 字 ×14px + 14px padding
right_start = card_w - 8 - (8 * 7 + 14)  # 右上：8 字符 ×7px + 14px padding
check("左右两枚角标不重叠", left_end < right_start,
      f"左止 {left_end:.0f}px / 右起 {right_start:.0f}px，间隙 {right_start-left_end:.0f}px")
check("角标不压底边续播进度条", "box.top + 24f" in UI and "box.bottom - 8f" in UI)
check("uiSignature 纳入 unsupportedKeys", "state.unsupportedKeys.hashCode()" in RD)
check("快照有 5.4K VP9 样例", '5400, 2700, "video/x-vnd.on2.vp9"' in SNAP)
check("快照把该样例放进 unsupportedKeys",
      "unsupportedKeys = setOf(mediaKeyOf(items[2]))" in SNAP)

print()
print("── 六、真机数字复刻判定边界 ──")
# 真机 [codec] 硬解能力：VP9 4096×4096，其余三种 8192×8192。
CAPS = {
    "video/x-vnd.on2.vp9": 4096,
    "video/hevc": 8192,
    "video/avc": 8192,
    "video/av01": 8192,
}


def diagnose(mime, w, h, fps=0.0, vendor_reject=False):
    """复刻 DecoderCaps.diagnose 的四支，返回 declaredSupported（None=信息不足）。"""
    if mime is None or w <= 0 or h <= 0:
        return None
    if mime not in CAPS:
        return False                      # 本机没有这个编码的解码器
    if vendor_reject:
        return True                       # 平台声明支持，厂商解码器配置被拒
    limit = CAPS[mime]
    return w <= limit and h <= limit      # fps=0 → 只判尺寸


def is_unsupported(verdict):
    # 只有「明确 false」才标；True（厂商拒配）和 None（信息不足）都不标。
    return verdict is False


cases = [
    ("真机坏片 VP9 5400×2700", "video/x-vnd.on2.vp9", 5400, 2700, {}, True),
    ("同编码 4K 4096×2048", "video/x-vnd.on2.vp9", 4096, 2048, {}, False),
    ("转 H.265 保持 5400×2700", "video/hevc", 5400, 2700, {}, False),
    ("AV1 5400×2700", "video/av01", 5400, 2700, {}, False),
    ("平台声明支持但厂商拒配", "video/hevc", 5400, 2700, {"vendor_reject": True}, False),
    ("WebM 没 stsd → mime 未知", None, 5400, 2700, {}, False),
    ("探测失败 → 宽高为 0", "video/x-vnd.on2.vp9", 0, 0, {}, False),
]
for name, mime, w, h, extra, want in cases:
    verdict = diagnose(mime, w, h, **extra)
    got = is_unsupported(verdict)
    print(f"  {name:<28} declared={str(verdict):<5} → {'标' if got else '不标'}")
    check(f"  {name}", got == want, f"期望 {'标' if want else '不标'}")

print()
print("结论：" + ("全部通过" if ok else "存在 FAIL"))
sys.exit(0 if ok else 1)
