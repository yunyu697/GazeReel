#!/usr/bin/env python3
"""校验离线测试片源：投影公式、锚点颜色、跨投影一致性、交付文件规格。

## 它防的是什么

测试片源是**验收的尺子**。尺子本身歪了，量出来的结论全是假的，而且比没有尺子更糟：
错误会以「我在真机上验过了」的形式记账，之后没人再回头查。

所以这里不让生成器自证清白，而是做三件独立的事：

  1. **独立重算投影公式** —— 从这个脚本自己的 `theta/phi → 像素` 实现算锚点，
     再和生成器算的坐标对比。同一条公式写两遍，符号写反了必然对不上。
  2. **抽样渲染出来的像素** —— 低分辨率进程内渲染（不依赖 ffmpeg），在锚点处取色。
     颜色对不对，是「图案真的画在了世界的那一侧」的直接证据。
  3. **抽解码后的视频帧** —— 片源存在时，用 ffmpeg 解一帧再取色，顺带核规格。
     编码器把颜色压歪、把画面翻过去，这一层才抓得到。

## 判据分组

  A 静态：片源表、文档、生成器无随机、公式自洽
  B 渲染：每段的关键方位色标、接缝、圆外纯黑、全画幅四角有料、帧间有变化
  C 交付：ffprobe 规格、解码帧取色、无音轨（片源没生成就跳过，不算失败）
"""

from __future__ import annotations

import json
import math
import shutil
import subprocess
import sys
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import make_testclips as mt  # noqa: E402

DOC = ROOT / "docs" / "TESTCLIPS.md"
CLIP_DIR = ROOT.parent / "output" / "testclips"

PASSED = 0
FAILED = 0
SKIPPED = 0
TOL = 34.0   # yuv420p 4:2:0 会把高饱和色压偏，容差要够；但仍远小于标记之间的最小色差 80


def check(cond, label, detail=""):
    global PASSED, FAILED
    if cond:
        PASSED += 1
        print(f"  OK   {label}")
    else:
        FAILED += 1
        print(f"  FAIL {label}" + (f"  — {detail}" if detail else ""))
    return cond


def skip(label):
    global SKIPPED
    SKIPPED += 1
    print(f"  --   {label}（跳过）")


def sample(img, px, py, radius=3):
    """取锚点周围 (2r+1)² 的中位色，避开压缩振铃与文字描边。"""
    x0, y0 = int(round(px)), int(round(py))
    h, w = img.shape[:2]
    x0 = min(max(x0, 0), w - 1)
    y0 = min(max(y0, 0), h - 1)
    patch = img[max(0, y0 - radius):y0 + radius + 1, max(0, x0 - radius):x0 + radius + 1]
    return np.median(patch.reshape(-1, 3), axis=0)


def near(got, want, tol=TOL):
    return float(np.max(np.abs(np.asarray(got, float) - np.asarray(want, float)))) <= tol


def expect_color(img, px, py, key, label):
    want = mt.COLORS[key]
    got = sample(img, px, py)
    return check(near(got, want), label, f"期望 {want} 实际 {tuple(int(v) for v in got)}")


# ── 独立实现的投影公式（故意不复用生成器的） ────────────────────────────────
def equirect_px(span, w, h, theta, phi):
    u = 0.5 - theta / span
    return u * w, (0.5 - phi / 180.0) * h


def fisheye_px(fov, w, h, theta, phi, circle=100.0):
    """等距投影：alpha = r·FOV/2，且 cosα = cosφ·cosθ、ψ = atan2(sinφ, −cosφ·sinθ)。"""
    alpha = math.acos(max(-1.0, min(1.0, math.cos(math.radians(phi)) * math.cos(math.radians(theta)))))
    psi = math.atan2(math.sin(math.radians(phi)), -math.cos(math.radians(phi)) * math.sin(math.radians(theta)))
    r = alpha / math.radians(fov / 2.0)
    radius = 0.5 * min(w, h) * circle / 100.0
    return w / 2.0 + r * radius * math.cos(psi), h / 2.0 - r * radius * math.sin(psi), r


def fisheye_full_px(fov, w, h, theta, phi):
    """全画幅：半径按**半对角线**归一，r=1 落在四角（不是短边）。
    第一版这里直接复用了圆形画幅的半径，锚点偏到地平线上，自检当场把这处 FAIL 出来。"""
    alpha = math.acos(max(-1.0, min(1.0, math.cos(math.radians(phi)) * math.cos(math.radians(theta)))))
    psi = math.atan2(math.sin(math.radians(phi)), -math.cos(math.radians(phi)) * math.sin(math.radians(theta)))
    rho = alpha / math.radians(fov / 2.0) * math.hypot(w / h, 1.0)
    return w / 2.0 + rho * math.cos(psi) * (h / 2.0), h / 2.0 - rho * math.sin(psi) * (h / 2.0)


def group_a():
    print("\n[A] 静态：片源表与文档")
    names = [c["name"] for c in mt.CLIPS]
    check(len(names) == 6 and len(set(names)) == 6, "六段片源、名字唯一", str(names))
    check(all(c["w"] % 2 == 0 and c["h"] % 2 == 0 for c in mt.CLIPS),
          "宽高都是偶数（yuv420p 要求）")
    check(all(c.get("title") and c.get("proves") for c in mt.CLIPS), "每段都有标题与「证明什么」")
    check(all(c["proves"].startswith("V") for c in mt.CLIPS), "每段的 proves 都指向验收项编号")

    covered = {c["proves"].split("：")[0] for c in mt.CLIPS}
    check({"V1", "V2", "V3", "V4", "V5", "V7"} <= covered,
          "覆盖 V1/V2/V3/V4/V5/V7", str(sorted(covered)))

    src = (ROOT / "tools" / "make_testclips.py").read_text(encoding="utf-8")
    check("random" not in src, "生成器不含随机数（同输入必须同输出）")

    if not DOC.exists():
        check(False, "docs/TESTCLIPS.md 存在", "缺文档，验收人不知道该看什么")
    else:
        doc = DOC.read_text(encoding="utf-8")
        missing = [n for n in names if n not in doc]
        check(not missing, "文档列出全部片源文件名", f"缺 {missing}")


def group_formula():
    print("\n[B1] 公式：两条独立实现必须落在同一点")
    # 等距柱状 360：正前居中、右 90 在 3/4、正后落在接缝
    for theta, want_u, label in ((0.0, 0.5, "正前 u=0.5"), (-90.0, 0.75, "右 90 → u=0.75"),
                                 (90.0, 0.25, "左 90 → u=0.25"), (180.0, 0.0, "正后 → u=0（接缝）")):
        clip = dict(kind="equirect", w=2048, h=1024, span=360.0)
        mine, _ = equirect_px(360.0, 2048, 1024, theta, 0.0)
        theirs, _, ok = mt.forward_px(clip, theta, 0.0)
        check(ok and abs(mine - theirs) < 0.01 and abs(mine / 2048 - want_u) < 0.002,
              f"{label}", f"我的 {mine:.2f} 生成器 {theirs:.2f}")

    # 天顶：360 片顶行、180 片也在顶行（v 不随跨度变）
    clip = dict(kind="equirect", w=2048, h=1024, span=360.0)
    _, py, _ = mt.forward_px(clip, 0.0, 75.0)
    check(abs(py / 1024 - 0.0833) < 0.002, "天顶 φ=75° 落在顶行附近", f"py={py:.1f}")

    # 鱼眼：r 与 alpha 线性。FOV200 时 90° 应落在 r=0.9
    _, _, r = fisheye_px(200.0, 1024, 1024, -90.0, 0.0)
    check(abs(r - 0.9) < 1e-6, "鱼眼 FOV200：90° → r=0.9", f"r={r:.4f}")
    px, py, _ = mt.forward_px(dict(kind="fisheye", w=1024, h=1024, fov=200.0, circle=100.0), -90.0, 0.0)
    check(abs(px - 1024 / 2 - 0.9 * 512) < 0.01 and abs(py - 512) < 0.01,
          "鱼眼右 90 落在 +x 方向的 0.9 半径处", f"({px:.1f},{py:.1f})")

    # 全画幅：四角方向到光轴正好 fov/2（对角线口径）
    clip = dict(kind="fisheye_full", w=1280, h=720, fov=180.0)
    theta, phi, _ = mt.angle_grids("fisheye_full", 1280, 720, 360.0, 180.0, 100.0)
    for (x, y, name) in ((2, 2, "左上"), (1277, 2, "右上"), (2, 717, "左下"), (1277, 717, "右下")):
        t, p = float(theta[y, x]), float(phi[y, x])
        ang = math.degrees(math.acos(max(-1.0, min(1.0,
            math.cos(math.radians(p)) * math.cos(math.radians(t))))))
        check(abs(ang - 90.0) < 1.2, f"全画幅{name}角到光轴 ≈ 90°", f"实际 {ang:.2f}°")

    # 未缝双鱼眼：两圆心分别朝前 / 朝后
    clip = dict(kind="dual", w=2048, h=1024, fov=200.0)
    fpx, fpy, _ = mt.forward_px(clip, 0.0, 0.0)
    bpx, bpy, _ = mt.forward_px(clip, 180.0, 0.0)
    check(abs(fpx - 512) < 1 and abs(fpy - 512) < 1, "左圆心 = 正前方", f"({fpx:.1f},{fpy:.1f})")
    check(abs(bpx - 1536) < 1 and abs(bpy - 512) < 1, "右圆心 = 正后方", f"({bpx:.1f},{bpy:.1f})")


def anchor_points(clip, w, h, keys):
    """按缩放后的分辨率给出一组 (标签, px, py, 颜色键)。"""
    scaled = dict(clip, w=w, h=h)
    out = []
    for label, theta, phi, key in mt.ANCHOR_CANDIDATES:
        if key not in keys:
            continue
        px, py, ok = mt.forward_px(scaled, theta, phi)
        if ok and 8 <= px < w - 8 and 8 <= py < h - 8:
            out.append((label, px, py, key))
    return out


def group_render():
    print("\n[B2] 渲染帧：图案真的画在了世界的正确一侧")
    # 360：六个方位的色标
    clip = mt.CLIPS[0]
    w, h = 512, 256
    img = mt.render_frame(clip, 0.0, w, h)
    for label, px, py, key in anchor_points(clip, w, h,
                                            {"front", "right90", "left90", "back", "top", "bottom"}):
        expect_color(img, px, py, key, f"360 · {label} 颜色")

    # 接缝同一色：正后标记被切开，两侧都必须是「后」的黄
    sx, _ = equirect_px(360.0, w, h, 180.0, 0.0)
    left_edge = img[h // 2, 2]
    right_edge = img[h // 2, w - 3]
    check(near(left_edge, mt.COLORS["back"]) and near(right_edge, mt.COLORS["back"]),
          "360 接缝两侧同色（正后不裂）",
          f"左 {tuple(int(v) for v in left_edge)} 右 {tuple(int(v) for v in right_edge)}")

    # 帧间必须有变化：静止的片子会让人误判「播放器卡住」
    # 取 t=0 与 t=2（游标正好走到对面）。取 4s 会踩坑：环游周期正好是 4s，
    # 游标回到原点，只剩秒表上两个字符在变，差异只有 74 个像素 —— 第一次就是这么 FAIL 的。
    globe_small = mt.render_frame(mt.CLIPS[0], 0.0, 1024, 512)
    globe_later = mt.render_frame(mt.CLIPS[0], 2.0, 1024, 512)
    diff = int(np.count_nonzero(
        np.abs(globe_later.astype(int) - globe_small.astype(int)).max(axis=2) > 24))
    check(diff > 300, "360 帧间有变化（秒表与游标在动）", f"{diff} 像素")

    # 180 SBS：两只眼各自正前都是红，且标签不同（取反会看到别人的标签）
    clip = mt.CLIPS[1]
    w, h = 512, 256
    img = mt.render_frame(clip, 0.0, w, h)
    half = w // 2
    for name, xoff in (("左眼", 0), ("右眼", half)):
        px, py = xoff + half * 0.5, h * 0.5
        expect_color(img, px, py, "front", f"180SBS · {name}正前是红")
    band = img[int(h * 0.9):, :]
    check(band[:, :half].std() > 20 and band[:, half:].std() > 20, "180SBS · 两眼底部都有眼别标签")

    # 2D：四角色块（哪一角是什么色写死，翻转/镜像会立刻错位）
    clip = mt.CLIPS[2]
    w, h = 640, 360
    img = mt.render_frame(clip, 0.0, w, h)
    size = int(w * 0.06)
    inset = size * 0.9
    corners = (("左上", inset, inset, "left45"), ("右上", w - inset, inset, "back"),
               ("左下", inset, h - inset, "left90"), ("右下", w - inset, h - inset, "top"))
    for name, px, py, key in corners:
        expect_color(img, px, py, key, f"2D · {name}角颜色")

    # 鱼眼圆：圆心 = 前、0.9 半径处 = 右 90、圆外纯黑
    clip = mt.CLIPS[3]
    w = h = 256
    img = mt.render_frame(clip, 0.0, w, h)
    expect_color(img, w / 2, h / 2, "front", "鱼眼圆 · 圆心是正前")
    px, py, _ = fisheye_px(200.0, w, h, -90.0, 0.0)
    expect_color(img, px, py, "right90", "鱼眼圆 · 右 90 在 +x 半径 0.9")
    corner = img[2, 2]
    check(corner.max() <= 6, "鱼眼圆 · 圆外纯黑（没有卷进黑边）", str(tuple(int(v) for v in corner)))

    # 全画幅：四角必须有真实像素
    clip = mt.CLIPS[4]
    w, h = 640, 360
    img = mt.render_frame(clip, 0.0, w, h)
    dark = [img[1, 1], img[1, w - 2], img[h - 2, 1], img[h - 2, w - 2]]
    check(all(int(p.max()) > 20 for p in dark), "全画幅 · 四角有真实像素",
          str([tuple(int(v) for v in p) for p in dark]))
    expect_color(img, w / 2, h / 2, "front", "全画幅 · 中心是正前")
    px, py = fisheye_full_px(180.0, w, h, -45.0, 0.0)
    expect_color(img, px, py, "right45", "全画幅 · 右 45 色标")
    px, py = fisheye_full_px(180.0, w, h, 45.0, 0.0)
    expect_color(img, px, py, "left45", "全画幅 · 左 45 色标")

    # 双鱼眼：两圆心色不同、中间是黑
    clip = mt.CLIPS[5]
    w, h = 512, 256
    img = mt.render_frame(clip, 0.0, w, h)
    expect_color(img, w / 4, h / 2, "front", "双鱼眼 · 左圆心 = 前")
    expect_color(img, 3 * w / 4, h / 2, "back", "双鱼眼 · 右圆心 = 后")
    gap = img[h // 2, w // 2]
    check(gap.max() <= 6, "双鱼眼 · 两圆之间是黑（未缝合）", str(tuple(int(v) for v in gap)))

    # 跨投影一致：鱼眼 r=0.5（α=50°）处必须是 360 片同角度的同一颜色
    globe = mt.render_frame(mt.CLIPS[0], 0.0, 1024, 512)
    fisheye = mt.render_frame(mt.CLIPS[3], 0.0, 512, 512)
    fx = 256 + 0.5 * 256
    fy = 256
    ex = (0.5 + 50.0 / 360.0) * 1024
    ey = 256
    a = sample(globe, ex, ey)
    b = sample(fisheye, fx, fy)
    check(float(np.max(np.abs(a - b))) <= TOL,
          "跨投影一致：鱼眼 50° 半径 == 360 片同一方向",
          f"360 {tuple(int(v) for v in a)} / 鱼眼 {tuple(int(v) for v in b)}")


def ffprobe(path):
    out = subprocess.run(
        [shutil.which("ffprobe"), "-v", "error", "-show_streams", "-show_format",
         "-of", "json", str(path)], capture_output=True, text=True)
    if out.returncode != 0:
        return None
    return json.loads(out.stdout)


def grab_frame(path, w, h, t=2.0):
    cmd = [shutil.which("ffmpeg"), "-v", "error", "-ss", f"{t}", "-i", str(path),
           "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"]
    out = subprocess.run(cmd, capture_output=True)
    buf = out.stdout[:w * h * 3]
    if len(buf) < w * h * 3:
        return None
    return np.frombuffer(buf, np.uint8).reshape(h, w, 3)


def group_files():
    print("\n[C] 交付文件：规格与解码帧")
    if not CLIP_DIR.exists():
        for c in mt.CLIPS:
            skip(f"{c['name']} 未生成")
        print("  （片源是可复现产物，不入库；跑 tools/make_testclips.py 生成）")
        return

    if not shutil.which("ffprobe") or not shutil.which("ffmpeg"):
        for c in mt.CLIPS:
            skip(f"{c['name']} 需要 ffprobe")
        return

    manifest_path = CLIP_DIR / "manifest.json"
    check(manifest_path.exists(), "manifest.json 存在")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"clips": []}
    by_name = {e["name"]: e for e in manifest.get("clips", [])}

    for clip in mt.CLIPS:
        path = CLIP_DIR / clip["name"]
        if not path.exists():
            skip(f"{clip['name']} 未生成")
            continue
        info = ffprobe(path)
        video = next((s for s in info["streams"] if s["codec_type"] == "video"), None) if info else None
        audio = [s for s in info["streams"] if s["codec_type"] == "audio"] if info else []
        ok = (video is not None
              and int(video["width"]) == clip["w"] and int(video["height"]) == clip["h"]
              and video["pix_fmt"] == "yuv420p"
              and abs(float(info["format"]["duration"]) - mt.DURATION) < 0.4
              and not audio)
        check(ok, f"{clip['name']} 规格（{clip['w']}x{clip['h']} / yuv420p / "
                  f"{mt.DURATION:.0f}s / 无音轨）",
              f"{video and (video['width'], video['height'], video['pix_fmt'])} "
              f"dur={info and info['format']['duration']} audio={len(audio)}")

        frame = grab_frame(path, clip["w"], clip["h"])
        if frame is None:
            check(False, f"{clip['name']} 可解码一帧")
            continue
        entry = by_name.get(clip["name"])
        if not entry or not entry.get("anchors"):
            check(False, f"{clip['name']} manifest 里有锚点")
            continue
        # 取首尾两个锚点：一个在最前、一个在最偏的位置，能同时验「正前对」和「方向没镜像」
        picks = [entry["anchors"][0], entry["anchors"][-1]]
        for anchor in picks:
            check(near(sample(frame, anchor["px"], anchor["py"]), anchor["color"]),
                  f"{clip['name']} 解码帧 · {anchor['label']} 颜色",
                  f"期望 {anchor['color']} 实际 "
                  f"{tuple(int(v) for v in sample(frame, anchor['px'], anchor['py']))}")


def main():
    print(f"测试片源自检 · {mt.FPS} fps / {mt.DURATION:.0f}s / {len(mt.CLIPS)} 段")
    group_a()
    group_formula()
    group_render()
    group_files()

    print()
    print(f"断言 {PASSED + FAILED} 条：通过 {PASSED}，失败 {FAILED}"
          + (f"，跳过 {SKIPPED}" if SKIPPED else ""))
    print("结论：" + ("全部通过" if FAILED == 0 else "存在 FAIL"))
    return 0 if FAILED == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
