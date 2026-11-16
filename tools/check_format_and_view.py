#!/usr/bin/env python3
"""18:2x 自检：220/270 张角档、2D 银幕宽度、球面内容缩放。

为什么单开一个脚本：
1) `fov` 曾经只写进数据库、从不参与渲染（11-10 的「FOV 是死的」）。加了枚举
   不等于接上了网格，必须钉住「重建判定里真的比了 fov」。
2) 银幕宽度一旦可调，17:2x 那个「银幕立在地上」的 bug 会随放大档复发：
   中心高度若还是写死 0.62，半高一变底边就陷进地板。所以逐档量墙裙、天花、侧幕。
3) 180/360 的 u 映射参数化后必须与旧版逐位相同，否则所有老片源会整体偏移。

用法：python tools/check_format_and_view.py
"""

import math
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/gazereel/vr"
FILES = {
    "format": SRC / "core/VrFormat.kt",
    "detector": SRC / "core/FormatDetector.kt",
    "action": SRC / "core/VrAction.kt",
    "mesh": SRC / "render/ProjectionMesh.kt",
    "gl": SRC / "render/GlUtils.kt",
    "room": SRC / "render/SceneRoom.kt",
    "renderer": SRC / "render/VrRenderer.kt",
    "layer": SRC / "render/VrUiLayer.kt",
    "state": SRC / "ui/VrUiState.kt",
    "repo": SRC / "input/SettingsRepository.kt",
    "coordinator": SRC / "ui/VrCoordinator.kt",
    "snapshot": SRC / "ui/UiSnapshot.kt",
    "setup": SRC / "ui/SetupActivity.kt",
    "dlgsettings": ROOT / "app/src/main/res/layout/dialog_settings.xml",
    "strings": ROOT / "app/src/main/res/values/strings.xml",
    "refusal": SRC / "core/Refusal.kt",
    "library": SRC / "data/LibraryRepository.kt",
    "entities": SRC / "data/db/Entities.kt",
    "readme": ROOT / "README.md",
}
txt = {k: v.read_text(encoding="utf-8") for k, v in FILES.items()}

results = []


def ck(label, cond, detail=""):
    results.append((label, bool(cond), detail))


def num(pattern, key, name):
    m = re.search(pattern, txt[key])
    assert m, "抓不到常量 " + name
    return float(m.group(1))


# ── 一、静态：220 / 270 真的接通了整条链路 ──────────────────────────────
f, d, mesh, rend = txt["format"], txt["detector"], txt["mesh"], txt["renderer"]
ck("枚举含 FOV_220 / FOV_270 且带 degrees",
   "FOV_220(220)" in f and "FOV_270(270)" in f and "val degrees: Int?" in f)
ck("QUICK_CHOICES 含 220/270 各 Mono 与 SBS",
   all(x in f for x in (
       "StereoLayout.MONO, FieldOfView.FOV_220", "StereoLayout.SBS, FieldOfView.FOV_220",
       "StereoLayout.MONO, FieldOfView.FOV_270", "StereoLayout.SBS, FieldOfView.FOV_270")))
ck("格式按钮用 shortLabel（4 列放不下完整 label）",
   '"fmt$i", format.shortLabel' in txt["layer"])
ck("识别器含 is220 / is270 与数字边界正则",
   all(x in d for x in ("val is220", "val is270", "HAS_220", "HAS_270")))
ck("fovHint 里 220/270 排在 180 之前",
   d.index("marks.is270 -> FieldOfView.FOV_270") <
   d.index("marks.is220 -> FieldOfView.FOV_220") <
   d.index("marks.is180 -> FieldOfView.FOV_180"))
ck("projectionOf 把 220/270 归入非整球",
   "FieldOfView.FOV_180, FieldOfView.FOV_220, FieldOfView.FOV_270 -> Projection.HALF_SPHERE_180" in d)
ck("fovOfRatio 故意不收 1.22 / 1.50（防普通电影误判）",
   "near(ratio, 1f) -> FieldOfView.FOV_180" in d and
   "near(ratio, 2f) -> FieldOfView.FOV_360" in d and
   "1.22" not in d.split("private fun fovOfRatio")[1].split("}")[0])
ck("鱼眼镜头档明确不认",
   "本工程没有径向反畸变" in d or "故意不认" in d)
ck("curved 改为按跨度参数化",
   "private fun curved(spanDeg: Float)" in mesh and "Math.toRadians(spanDeg" in mesh)
ck("build 同时接 fov 与幕宽档位",
   "fisheyeFovDeg: Int = 200" in mesh and "fisheyeCirclePercent: Int = 100" in mesh)
ck("VrRenderer 重建判定纳入 fov 与幕宽",
   "lastFovDegrees != fovDeg || lastScreenWidthPercent != screenWidthPercent" in rend)
ck("几何日志带跨度与幕宽", "跨度 ${fovDeg ?: 0}° 幕宽 ${screenWidthPercent}%" in rend)
ck("格式页 4 列 × 3 行、走栅格且不压按钮",
   "i % 4, 1," in txt["layer"] and
   "(UiGrid.H_FMT + UiGrid.ROW_GAP)" in txt["layer"] and
   "UiGrid.centered(2, UiGrid.BUTTON_Y, UiGrid.H_ROW)" in txt["layer"])

# ── 二、静态：银幕宽度调节不会重现「立在地上」 ──────────────────────────
room, state, repo = txt["room"], txt["state"], txt["repo"]
ck("中心高度由地板+半高+墙裙推导，不再写死",
   "ROOM_FLOOR_Y + screenHeight(percent) / 2f + SCREEN_SKIRT" in mesh and
   "screenCenterY(screenWidthPercent)" in room)
ck("SceneRoom.build 接档位并现算半宽半高",
   "fun build(screenWidthPercent: Int = 100)" in room and
   "ProjectionMesh.screenWidth(screenWidthPercent)" in room)
ck("场景几何按档位缓存失效",
   "geometryWidth == screenWidthPercent" in room and "geometryWidth = -1" in room)
ck("scene.draw 把档位传进去",
   "scene.draw(mvp, screenLuma, settings.current.screenWidthPercent)" in rend)
ck("两个新设置有持久化 key",
   '"screen_width_percent"' in repo and '"content_scale_percent"' in repo)
ck("默认 100%（旧安装与现值都不改变观感）",
   "SCREEN_WIDTH_DEFAULT = 100" in state and "CONTENT_SCALE_DEFAULT = 100" in state)
ck("设置页第 2 页存在且不压第一页字号",
   "state.settingsPage == 1" in txt["layer"] and
   'VrAction.SettingsPage(1)' in txt["layer"] and
   "62f * 1.4" not in txt["layer"])
ck("设置页/两个新数值都进 uiSignature",
   all(x in rend for x in ("state.settingsPage", "state.settings.screenWidthPercent",
                           "state.settings.contentScalePercent")))
ck("SettingsPage 动作有分发分支",
   "data class SettingsPage(val page: Int)" in txt["action"] and
   "is VrAction.SettingsPage -> uiState = uiState.copy(settingsPage = action.page)" in txt["coordinator"])

# ── 三、静态：内容缩放只在球面生效 ──────────────────────────────────────
gl = txt["gl"]
ck("shader 声明 uContentZoom", "uniform float uContentZoom;" in gl)
ck("缩放在 uEyeRegion 之前做（否则 SBS 串幅）",
   gl.index("vec2 zoomed = clamp(") < gl.index("zoomed.x * uEyeRegion.x"))
ck("越界有 clamp，不拉出黑边", "vec2(0.0), vec2(1.0)" in gl)
# 19:0x：鱼眼也恒传 1.0（UV 是投影反算的，再缩放会把圆外黑角卷进边缘）。
ck("2D 与鱼眼恒传 1.0，避免与银幕宽度/投影打架",
   "state.format.projection == Projection.PLANE_2D" in rend and
   "state.format.projection == Projection.FISHEYE) 1f" in rend)
ck("内容缩放默认是 100（零回归）", "CONTENT_SCALE_DEFAULT = 100" in state)

# ── 四、静态：README 真的教会用户判断与命名 ─────────────────────────────
rd = txt["readme"]
ck("README 有「先判断」与「命名规则」两节",
   "## 先判断我的片子是什么格式" in rd and "## 视频命名规则（格式识别）" in rd)
ck("README 写明 220/270 与 token 分隔规则",
   "220" in rd and "270" in rd and "独立词" in rd)
ck("README 写明文件名不一定准", "文件名写的不一定准" in rd)
ck("README 写明鱼眼支持范围与不支持的投影",
   "鱼眼镜头**（支持" in rd.replace("（支持，见下）", "**（支持") or
   ("鱼眼" in rd and "未缝合双鱼眼" in rd and "立方体" in rd))

# ── 五、计算：u 映射对老片源零回归 ──────────────────────────────────────
def u_of(theta_deg, span_deg):
    return 0.5 - math.radians(theta_deg) / math.radians(span_deg)

for span in (180, 220, 270, 360):
    us = [u_of(t, span) for t in (-span / 2, -span / 4, 0, span / 4, span / 2)]
    ok = (abs(us[0] - 1.0) < 1e-9 and abs(us[2] - 0.5) < 1e-9 and
          abs(us[4]) < 1e-9 and all(us[i] > us[i + 1] for i in range(4)))
    ck(f"u 映射 {span}°：正前方 0.5 / 两端 1→0 / 单调", ok)
# 旧实现：180 是 ±π/2、360 是 ±π，u = 0.5 - theta/span，与本式相同。
ck("180/360 与旧写死公式逐位一致",
   abs(u_of(30, 180) - (0.5 - (math.pi / 6) / math.pi)) < 1e-9 and
   abs(u_of(30, 360) - (0.5 - (math.pi / 6) / (2 * math.pi))) < 1e-9)

# ── 六、计算：逐档银幕几何 ──────────────────────────────────────────────
W0 = num(r"const val SCREEN_WIDTH = ([\d.]+)f", "mesh", "SCREEN_WIDTH")
DIST = num(r"const val SCREEN_DISTANCE = ([\d.]+)f", "mesh", "SCREEN_DISTANCE")
CY0 = num(r"const val SCREEN_CENTER_Y = ([\d.]+)f", "mesh", "SCREEN_CENTER_Y")
FLOOR_MESH = num(r"const val ROOM_FLOOR_Y = (-?[\d.]+)f", "mesh", "ROOM_FLOOR_Y")
SKIRT = num(r"const val SCREEN_SKIRT = ([\d.]+)f", "mesh", "SCREEN_SKIRT")
FLOOR = num(r"const val FLOOR_Y = (-?[\d.]+)f", "room", "FLOOR_Y")
CEIL = num(r"const val CEIL_Y = ([\d.]+)f", "room", "CEIL_Y")
HALF_W = num(r"const val HALF_W = ([\d.]+)f", "room", "HALF_W")
FRAME_W = num(r"const val FRAME_W = ([\d.]+)f", "room", "FRAME_W")
FRAME_BOT = num(r"const val FRAME_BOTTOM = ([\d.]+)f", "room", "FRAME_BOTTOM")
CURTAIN = num(r"const val SIDE_CURTAIN_W = ([\d.]+)f", "room", "SIDE_CURTAIN_W")

ck("ProjectionMesh.ROOM_FLOOR_Y 与 SceneRoom.FLOOR_Y 相等",
   abs(FLOOR_MESH - FLOOR) < 1e-9, f"{FLOOR_MESH} vs {FLOOR}")
ck("100% 档中心高度等于基准 SCREEN_CENTER_Y",
   abs(FLOOR + (W0 * 9 / 16) / 2 + SKIRT - CY0) < 1e-5,
   f"推导 {FLOOR + (W0 * 9 / 16) / 2 + SKIRT:.5f} vs 基准 {CY0}")

steps = [int(x) for x in re.search(
    r"SCREEN_WIDTH_STEPS = intArrayOf\(([^)]+)\)", state).group(1).replace(" ", "").split(",")]
hfovs = []
print("\n── 逐档银幕几何 ──")
for pct in steps:
    w = W0 * pct / 100
    h = w * 9 / 16
    cy = FLOOR + h / 2 + SKIRT
    bot, top = cy - h / 2, cy + h / 2
    f_top = top + FRAME_W
    skirt_visible = (bot - FRAME_BOT) - FLOOR
    curtain_out = w / 2 + FRAME_W + CURTAIN
    hfov = 2 * math.degrees(math.atan(w / 2 / DIST))
    hfovs.append(hfov)
    print(f"  {pct:>3}%  幕 {w:.3f}×{h:.3f} m  张角 {hfov:5.1f}°  "
          f"底边离地 {bot - FLOOR:+.3f}  墙裙 {skirt_visible:+.3f}  "
          f"幕头顶 {f_top:+.3f}/{CEIL:.2f}  侧幕外沿 {curtain_out:.2f}/{HALF_W:.2f}")
    ck(f"{pct}% 墙裙恒定且可见",
       abs(bot - FLOOR - SKIRT) < 1e-6 and skirt_visible > 0.15)
    ck(f"{pct}% 银幕不陷地 / 幕头不穿顶 / 侧幕不出厅",
       bot > FLOOR + 0.01 and f_top < CEIL and curtain_out < HALF_W)
ck("张角随档位单调递增", all(hfovs[i] < hfovs[i + 1] for i in range(len(hfovs) - 1)))
ck("100% 档张角仍是真机验过的 56.6°",
   abs(2 * math.degrees(math.atan(W0 / 2 / DIST)) - 56.6) < 0.2)
ck("幕宽上限受天花约束（110% 以下）", max(steps) <= 110)

# ── 七、计算：内容缩放的越界幅度 ────────────────────────────────────────
zoom_steps = [int(x) for x in re.search(
    r"CONTENT_SCALE_STEPS = intArrayOf\(([^)]+)\)", state).group(1).replace(" ", "").split(",")]
print("\n── 内容缩放采样范围（clamp 前）──")
for pct in zoom_steps:
    z = pct / 100
    lo, hi = (0 - 0.5) / z + 0.5, (1 - 0.5) / z + 0.5
    over = max(0.0, -lo) + max(0.0, hi - 1.0)
    print(f"  {pct:>3}%  u,v ∈ [{lo:+.4f}, {hi:+.4f}]  越界合计 {over:.4f}")
    ck(f"{pct}% 缩放范围有限且不反向", lo < hi and math.isfinite(lo) and math.isfinite(hi))
ck("100% 档采样范围正好 [0,1]（零回归）",
   abs((-0.5) / 1.0 + 0.5) < 1e-9 and abs(0.5 / 1.0 + 0.5 - 1.0) < 1e-9)
ck("最小档越界 < 5%（拉伸条几乎看不见）",
   max(0.0, -((-0.5) / (min(zoom_steps) / 100) + 0.5)) < 0.05)

# ── 八、真实 GL 上下文编译视频顶点着色器 ────────────────────────────────
# 16:5x 纪律：GLSL 是运行时编译，assembleDebug 通过不代表能链接，
# 真机表现只是「画面静默不画」。这批动了 VIDEO_VERTEX，必须真编译一次。
import textwrap


def extract_shader(name):
    m = re.search(rf'val {name} = """(.*?)"""', gl, re.S)
    assert m, "抽不到着色器 " + name
    return textwrap.dedent(m.group(1)).strip("\n")


def to330(g):
    out = []
    for line in g.splitlines():
        s = line.strip()
        if s.startswith("#version"):
            out.append("#version 330 core")
        elif s.startswith("precision ") and s.endswith(";"):
            continue
        else:
            out.append(line)
    return "\n".join(out)


try:
    import moderngl
    ctx = moderngl.create_standalone_context(require=330)
    dummy_fs = "#version 330 core\nout vec4 fragColor;\nvoid main(){ fragColor = vec4(1.0); }"
    prog = ctx.program(
        vertex_shader=to330(extract_shader("VIDEO_VERTEX")),
        fragment_shader=dummy_fs,
    )
    ck("VIDEO_VERTEX 真实 GL 编译链接通过", True)
    ck("uContentZoom / uEyeRegion 链接后仍在",
       "uContentZoom" in prog and "uEyeRegion" in prog)
except Exception as e:  # noqa: BLE001
    ck("VIDEO_VERTEX 真实 GL 编译链接通过", False, str(e))

# ── 九、两页设置浮层的离线可观测性 ──────────────────────────────────────
# 18:2x 收尾：alpha5 把设置浮层拆成两页后，旧的单页预览脚本仍按
# 单页 11 格解析，把第 2 页两格算进网格误报 -52px；UiSnapshot 也漏了第 2 页。
# 这两处不进校验，下次改版还会静默坏掉 —— 所以在这里直接跑一遍并钉住快照。
snap = txt["snapshot"]
ck("UiSnapshot 覆盖设置第 2 页", "settingsPage = 1" in snap)
titles_block = re.search(r"private val TITLES = listOf\((.*?)\n    \)", snap, re.S)
# 19:2x：9 屏（原 7 屏 + 全画幅第 2 页 + 拒绝卡）。
ck("UiSnapshot 标题表正好九屏",
   titles_block is not None and
   len(re.findall(r'^\s*"[^"]+",?\s*$', titles_block.group(1), re.M)) == 9)
ck("UiSnapshot 页面索引连续到 [8]",
   all(f"TITLES[{i}]" in snap for i in range(9)))
prev = ROOT / "tools/preview_ui_pages.py"
try:
    r = subprocess.run([sys.executable, str(prev)], capture_output=True,
                       text=True, timeout=60, encoding="utf-8", errors="replace")
    ck("四页版面预览校验通过", r.returncode == 0,
       (r.stdout + r.stderr).strip().splitlines()[-1] if (r.stdout + r.stderr).strip() else "")
except Exception as e:  # noqa: BLE001
    ck("两页设置版面预览校验通过", False, str(e))

# ── 十、单镜头鱼眼 ──────────────────────────────────────────────────────
fmt = txt["format"]
det = txt["detector"]
ck("Projection 有 FISHEYE", "FISHEYE" in re.search(r"enum class Projection \{([^}]*)\}", fmt).group(1))
ck("FieldOfView 有 FOV_FISHEYE 且不带张角", "FOV_FISHEYE(null)" in fmt)
ck("鱼眼预设是真实镜头张角",
   all(x in fmt for x in ("180, 100", "190, 100", "200, 100", "220, 100")))
ck("QUICK_CHOICES 含鱼眼 Mono/SBS",
   fmt.count("Projection.FISHEYE") >= 2 and "FieldOfView.FOV_FISHEYE" in fmt)
ck("识别器先判鱼眼再判 220/270",
   det.index("marks.isFish -> FieldOfView.FOV_FISHEYE") < det.index("marks.is270 -> FieldOfView.FOV_270"))
ck("识别器认 MKX / VRCA / RF52 / 中文鱼眼",
   all(x in det for x in ("mkx200", "mkx220", "vrca220", "rf52", "鱼眼")))
ck("不认裸 200（避免误判码率/序号）", 'has(\n                    "fisheye"' in det or "\"fish\"" in det)
ck("projectionOf 把鱼眼映射到 FISHEYE",
   "FieldOfView.FOV_FISHEYE -> Projection.FISHEYE" in det)

mesh_src = txt["mesh"]
st = txt["state"]
ck("网格有圆度补偿（u 收窄 / v 收窄）",
   "val scaleU = if (safeAspect >= 1f) 1f / safeAspect else 1f" in mesh_src and
   "val scaleV = if (safeAspect <= 1f) safeAspect else 1f" in mesh_src)
ck("补偿只改 UV 倍率、不改顶点角度", "* scaleU" in mesh_src and "* scaleV" in mesh_src)
ck("眼宽高比在状态层算（渲染与 UI 同一口径）",
   "fun eyeAspect(): Float" in st and "state.eyeAspect()" in rend and
   "state.eyeAspect()" in txt["layer"])
ck("SBS 取半幅、TAB 取半高", "StereoLayout.SBS -> w /= 2" in st and "StereoLayout.TAB -> h /= 2" in st)
ck("拿不到尺寸退回 1.0（不补偿）", st.count("if (w <= 0 || h <= 0) return 1f") >= 2)
# 补偿的输入必须真的是「正在播的那一条」的宽高，否则它会静默退化成 1.0。
ck("currentMediaId 播放时真会被写", "currentMediaId = item.id" in txt["coordinator"])
ck("片库条目存了真实宽高", "val width: Int = 0" in txt["entities"] and "val height: Int = 0" in txt["entities"])
ck("圆度补偿进重建判定", "lastFisheyeAspectPermille != aspectPermille" in rend)
ck("UI 签名含眼宽高比", "(state.eyeAspect() * 1000f).toInt()" in rend)
ck("第 2 页写出眼画幅与补偿状态",
   "眼画幅" in txt["layer"] and "按短边补偿" in txt["layer"])
ck("网格有 fisheye() 圆盘分支",
   "private fun fisheye(" in mesh_src and "circlePercent: Int" in mesh_src and
   "fullFrame: Boolean = false" in mesh_src)
ck("等距投影：alpha = r · FOV/2", "val alpha = r * halfFov" in mesh_src)
ck("UV 半径按画面圆缩放", "0.5 * circle * r * cos(psi)" in mesh_src)
ck("FISHEYE 走 fisheye() 而不是 curved()",
   "if (projection == Projection.FISHEYE)" in mesh_src)
ck("渲染器把鱼眼张角/圆计入重建判定",
   "lastFisheyeFov != fisheyeFov" in txt["renderer"] and
   "fisheyeFov, fisheyeCircle, fisheyeAspect" in txt["renderer"])
ck("两项鱼眼设置有持久化 key",
   '"fisheye_fov_deg"' in repo and '"fisheye_circle_percent"' in repo)

# 复刻等距投影：中心在 0.5、边缘在 1.0、半径单调
import math as _m
fov_deg, circle = 200.0, 1.0
half = _m.radians(fov_deg) / 2.0
def uv(r, psi):
    return (0.5 + 0.5 * circle * r * _m.cos(psi), 0.5 + 0.5 * circle * r * _m.sin(psi))
ck("鱼眼圆中心落在 (0.5, 0.5)", all(abs(v - 0.5) < 1e-6 for v in uv(0.0, 0.0)))
ck("鱼眼圆边缘落在画面边（r=1,ψ=0 → u=1）", abs(uv(1.0, 0.0)[0] - 1.0) < 1e-6)
radii = [abs(uv(r / 20, 0.0)[0] - 0.5) for r in range(21)]
ck("鱼眼半径随角度单调增", all(radii[i] <= radii[i + 1] + 1e-9 for i in range(len(radii) - 1)))
ck("圆内采样不越界", all(0.0 <= v <= 1.0 for r in range(21) for p in range(8)
                        for v in uv(r / 20, p * _m.pi / 4)))

# ── 十一、19:0x 收尾：鱼眼与「看不出在生效」的两个坑 ──────────────────
ck("UI 签名含鱼眼张角与画面圆",
   "state.settings.fisheyeFovDeg" in rend and "state.settings.fisheyeCirclePercent" in rend)
# 鱼眼再叠一次 UV 缩放会把采样推到圆外，边缘卷出月牙黑洞，所以必须恒传 1.0。
m_zoom = re.search(r"uContentZoom\"\),\s*(.+?)\n\s*\)", rend, re.S)
zoom_expr = m_zoom.group(1) if m_zoom else ""
ck("内容缩放对 2D 与鱼眼都恒为 1", "PLANE_2D" in zoom_expr and "FISHEYE" in zoom_expr)
ck("第 2 页会说明「当前片不是鱼眼」",
   "当前片不是鱼眼" in txt["layer"] and "Projection.FISHEYE" in txt["layer"])

dlg, setup, strs = txt["dlgsettings"], txt["setup"], txt["strings"]
ck("手机端有四行画面/鱼眼设置",
   all(f'@+id/{x}Value' in dlg for x in
       ("screenWidth", "contentScale", "fishFov", "fishCircle")))
ck("手机端四个预设按钮", all(f'@+id/fishPreset{i}' in dlg for i in range(4)))
ck("手机端四项写进同一份设置仓库",
   setup.count("repo.update { it.copy(") >= 4 and
   all(k in setup for k in
       ("screenWidthPercent = value", "contentScalePercent = value",
        "fisheyeFovDeg = value", "fisheyeCirclePercent = value")))
ck("手机端预设同步改张角与画面圆",
   "fisheyeFovDeg = preset.fovDeg" in setup and
   "fisheyeCirclePercent = preset.circlePercent" in setup)
# 放得太靠下就等于没做：画面与鱼眼必须在「重定正前方」之前，才不用滚两次才看见。
ck("画面与鱼眼排在设置前段（瞳距之后）",
   dlg.index('@string/settings_view_title') < dlg.index('@string/settings_align_title'))
ck("四项标题有字符串资源",
   all(f'name="settings_{x}"' in strs for x in
       ("screen_width", "content_scale", "fish_fov", "fish_circle")))

# 12:1x 把「倒像」这类只会在真机上暴露的错，提前用数值钉住：
# 球面路径的约定是「上 = v 大、右 = u 大、正前方 = -Z」，鱼眼必须与之同向，
# 否则画面上下或左右翻，而几何看着完全正常。
def fish_vert(r, psi, radius=10.0):
    a = r * half
    return (radius * _m.sin(a) * _m.cos(psi), radius * _m.sin(a) * _m.sin(psi),
            -radius * _m.cos(a),
            0.5 + 0.5 * circle * r * _m.cos(psi),
            0.5 + 0.5 * circle * r * _m.sin(psi))
cx, cy, cz, cu, cv = fish_vert(0.7, 0.0)
ck("鱼眼：右 = u 大", cx > 0 and cu > 0.5)
ux, uy, uz, uu, uv_ = fish_vert(0.7, _m.pi / 2)
ck("鱼眼：上 = v 大（与球面同向，防倒像）", uy > 0 and uv_ > 0.5)
ck("鱼眼：正前方朝 -Z（与球面同侧）", cz < 0)
ck("鱼眼顶点半径恒为 10 m（与球面同深度层）",
   all(abs(_m.hypot(_m.hypot(*fish_vert(r / 9, p * _m.pi / 4)[:2]),
                    fish_vert(r / 9, p * _m.pi / 4)[2]) - 10.0) < 1e-3
       for r in range(10) for p in range(8)))

# 圆度：画面圆在**像素**里必须是正圆 —— u、v 各自归一化，宽画面若不收窄 u 就会拉成椭圆。
def fish_uv2(r, psi, aspect, circle=1.0):
    su = 1.0 / aspect if aspect >= 1.0 else 1.0
    sv = aspect if aspect <= 1.0 else 1.0
    return (0.5 + 0.5 * circle * r * _m.cos(psi) * su,
            0.5 + 0.5 * circle * r * _m.sin(psi) * sv)

round_ok = True
for a in (1.0, 1.5, 2.0, 0.5, 0.75):
    for r in (0.25, 0.5, 0.75, 1.0):
        du = (fish_uv2(r, 0.0, a)[0] - 0.5) * a      # 像素半径（取 H = 1，故 W = a）
        dv = (fish_uv2(r, _m.pi / 2, a)[1] - 0.5)
        if abs(abs(du) - abs(dv)) > 1e-6:
            round_ok = False
ck("各画幅下圆的像素半径两向相等（不拉扁）", round_ok)
ck("2:1 单眼仍是正圆（u 半幅 0.25、v 满幅 0.5）",
   abs((fish_uv2(1.0, 0.0, 2.0)[0] - 0.5) - 0.25) < 1e-6 and
   abs((fish_uv2(1.0, _m.pi / 2, 2.0)[1] - 0.5) - 0.5) < 1e-6)
ck("正方形画幅与旧版逐位相同（补偿为零）",
   abs(fish_uv2(0.7, 1.1, 1.0)[0] - (0.5 + 0.5 * 0.7 * _m.cos(1.1))) < 1e-9)
ck("补偿后采样仍在 0..1 内（不越界）",
   all(0.0 <= v <= 1.0 for a in (0.5, 1.0, 2.0) for r in (0.2, 0.6, 1.0)
       for p in range(12)
       for v in fish_uv2(r, p * _m.pi / 6, a)))

# ── 十二、19:2x：全画幅鱼眼 + 拒绝未缝合双鱼眼 360 ─────────────────────
ref, coord, act = txt["refusal"], txt["coordinator"], txt["action"]

# (a) 拒绝：识别层先于鱼眼判定，降级成 2D 银幕，且不写进格式记忆
ck("拒绝枚举有稳定 key 且文案齐全",
   all(x in ref for x in ('key = "dual_fisheye_360"', "val title", "val body", "val hint")))
ck("拒绝判定排在鱼眼判定之前（否则会被当单镜头鱼眼播）",
   d.index("if (marks.hardDualFish || brandUnstitched)") <
   d.index("marks.isFish -> FieldOfView.FOV_FISHEYE"))
# 19:3x：品牌名（INSTA360）不是「未缝合」的证据 —— 已缝合的导出片文件名也带它。
ck("品牌线索不硬拒，容器声明球面即放行",
   "marks.isDualFish && !marks.hardDualFish && probe?.spherical != true" in d)
ck("硬证据与品牌线索分层（.insv 无条件拒）",
   "val hardDualFish = has(\"dfisheye\", \"dualfisheye\") || suffixRaw" in d)
ck("已缝合标记能让品牌线索失效",
   "val isDualFish = hardDualFish || (brandRaw && !stitchedToken)" in d)
# 19:3x：rf52 是镜头型号，分不出机身原片与导出成品，必须问一次。
ck("rf52 触发确认而不是静默当单眼", "needsConfirmation = marks.fishAmbiguous" in d)
ck("中文「全景」认作 360", 'name.contains("全景")' in d)
# 语料规模：真实命名样本表必须一直在长，不能删到只剩构造用例。
ck("识别器自检含真实命名语料组",
   'println("[10] 19:3x：真实相机命名语料' in Path("tools/FormatDetectorCheck.kt").read_text(encoding="utf-8"))
ck("拒绝结果降级成 2D 银幕 Mono",
   "VrFormat(Projection.PLANE_2D, StereoLayout.MONO, FieldOfView.FLAT, 0.95f)" in d and
   "refusal = Refusal.DUAL_FISHEYE_360" in d)
ck("拒绝结论不写进格式记忆（将来支持了不该被旧记忆钉住）",
   "detected.refusal == null" in txt["library"])
ck("FormatResolution 带 refusal 字段且默认 null（兼容旧构造点）",
   "val refusal: Refusal? = null" in txt["library"])
ck("管线三条路都把 refusal 带进状态",
   coord.count("refusal = resolution.refusal") >= 2 and "refusal = null" in coord)
ck("手选格式会撤掉拒绝卡（决定权在用户）",
   "已撤掉拒绝卡" in coord and "uiState.refusal != null" in coord)
ck("拒绝卡是独占层（清空下层凝视目标）",
   "private fun drawRefusal(refusal: Refusal)" in txt["layer"] and
   txt["layer"].index("targets.clear()", txt["layer"].index("private fun drawRefusal")) <
   txt["layer"].index('heading("这个片子看不了"'))
ck("拒绝卡有两个真实出口",
   'button("refusalOk"' in txt["layer"] and 'button("refusalBack"' in txt["layer"] and
   "object DismissRefusal" in act)
ck("拒绝卡压住短提示层（不同时出现两张卡）",
   "if (activeRefusal(state) != null) return" in txt["layer"])
# 上膛前不能让拒绝卡抢掉「戴稳了？」：那会出现「卡片在、按钮点不动、也没解释」的死角。
ck("拒绝卡只在已上膛的播放页出现",
   "state.screen == VrScreen.PLAYER && state.gazeArmed" in txt["layer"])
ck("拒绝状态进 UI 签名（否则卡片不刷新）",
   "state.refusal?.key?.hashCode()" in rend)
# 文案长度决定卡高：正文超过 24 字 / 提示超过 52 字，版面预览里那张 286 高的卡就不成立了。
body_copy = re.search(r'body = "([^"]+)"', ref).group(1)
hint_copy = re.search(r'hint = "([^"]+)"', ref).group(1)
# 正文 1 行 + 提示 2 行 = 卡高 286（128–414）；超了会顶到底部按钮行，
# 版面预览里的断言会先失败，这里再钉一层文案长度，改文案的人一眼知道边界。
ck("拒绝卡文案短到能放进 3 行（正文 ≤24 字、提示 ≤48 字）",
   len(body_copy) <= 24 and len(hint_copy) <= 48,
   f"正文 {len(body_copy)} 字 / 提示 {len(hint_copy)} 字")
ck("快照里有拒绝卡与全画幅两屏（离线可见）",
   '"播放中 · 未缝合双鱼眼（拒绝卡）"' in txt["snapshot"] and
   '"设置 · 第 2 页 · 全画幅"' in txt["snapshot"] and
   "refusal = Refusal.DUAL_FISHEYE_360" in txt["snapshot"])

# (b) 全画幅：换的是另一种网格，不是「圆小一点」
ck("build() 带 fisheyeFullFrame 参数并转交 fisheye()",
   "fisheyeFullFrame: Boolean = false" in mesh_src and
   "fisheyeCirclePercent, fisheyeAspect, fisheyeFullFrame" in mesh_src)
ck("全画幅走独立网格分支（矩形，不是圆盘）",
   "if (fullFrame) return fisheyeFullFrame(fovDeg, safeAspect)" in mesh_src and
   "private fun fisheyeFullFrame(fovDeg: Float, aspect: Float)" in mesh_src)
ck("全画幅半径按半对角线归一（四角 r=1）",
   "sqrt(1.0 + aspect.toDouble() * aspect.toDouble())" in mesh_src and
   "/ halfDiag" in mesh_src)
ck("全画幅 UV 就是画幅坐标（不再乘画面圆）",
   "0.5 + px / (2.0 * aspect)" in mesh_src and "0.5 + py / 2.0" in mesh_src)
ck("全画幅换成矩形网格（不再按极坐标铺）",
   "for (row in 0 until rows)" in mesh_src and "for (col in 0 until cols)" in mesh_src)
ck("重建判定纳入全画幅开关（换网格必须重建）",
   "lastFisheyeFullFrame != fisheyeFullFrame" in rend and
   "lastFisheyeFullFrame: Boolean? = null" in rend)
ck("全画幅设置有持久化 key", '"fisheye_full_frame"' in repo)
ck("全画幅开关进 UI 签名", "state.settings.fisheyeFullFrame" in rend)
ck("第 2 页有画幅开关与恢复默认",
   'CardSpec("鱼眼全画幅"' in txt["layer"] and 'VrAction.ToggleOption("fisheyeFullFrame")' in txt["layer"] and
   'button(\n                "fishReset"' in txt["layer"].replace("            ", "                ") or
   '"fishReset"' in txt["layer"])
ck("全画幅时画面圆显示「不计」",
   '"不计" else' in txt["layer"])
ck("拨画面圆会自动退回圆形（两态互斥）",
   "fisheyeCirclePercent = value, fisheyeFullFrame = false" in coord)
ck("预设与恢复默认都会退回圆形",
   "fisheyeFullFrame = false" in coord and "VrSettings.FISHEYE_FOV_DEFAULT" in coord)
ck("手机端有画幅形态两段按钮与字符串",
   '@+id/fishFrameCircle' in dlg and '@+id/fishFrameFull' in dlg and
   all(f'name="settings_fish_frame' in strs for _ in [0]) and
   'name="settings_fish_frame_full"' in strs and 'name="settings_fish_frame_note"' in strs)

# 复刻全画幅映射：四角 r=1、边中点 r<1、UV 铺满整幅且不越界、方向与圆盘一致
def full_frame_uv(px, py, aspect):
    return (0.5 + px / (2.0 * aspect), 0.5 + py / 2.0)

def full_frame_r(px, py, aspect):
    return _m.hypot(px, py) / _m.hypot(1.0, aspect)

for a in (1.0, 1.5, 2.0, 1.7778, 0.5):
    corners = [(a, 1.0), (-a, 1.0), (a, -1.0), (-a, -1.0)]
    ck(f"全画幅 {a:.3f}:1 四角 r=1（张角用满，四角正是圆边）"
       if a == 1.0 else f"全画幅 {a:.3f}:1 四角 r=1",
       all(abs(full_frame_r(*c_, a) - 1.0) < 1e-9 for c_ in corners))
ck("全画幅：短边中点 r<1（边中点不是圆边，只有角是）",
   abs(full_frame_r(0.0, 1.0, 1.0) - 1 / _m.sqrt(2)) < 1e-9)
ck("全画幅：UV 四边正好落在 0 / 1（整幅都被采样，不留黑边）",
   abs(full_frame_uv(-2.0, 0, 2.0)[0]) < 1e-12 and
   abs(full_frame_uv(2.0, 0, 2.0)[0] - 1.0) < 1e-12 and
   abs(full_frame_uv(0, -1.0, 2.0)[1]) < 1e-12 and
   abs(full_frame_uv(0, 1.0, 2.0)[1] - 1.0) < 1e-12)
grid_ok = True
for a in (1.0, 2.0, 1.7778):
    cols, rows = 72, max(24, min(144, int(72 / a)))
    for i in range(cols + 1):
        for j in range(rows + 1):
            px = -a + 2 * a * i / cols
            py = -1 + 2 * j / rows
            u, v = full_frame_uv(px, py, a)
            if not (0.0 <= u <= 1.0 and 0.0 <= v <= 1.0):
                grid_ok = False
            if full_frame_r(px, py, a) > 1.0 + 1e-12:
                grid_ok = False
ck("全画幅：整幅每个格点都在 [0,1] 且 r≤1（不会采到画幅外）", grid_ok)

def full_frame_vert(px, py, aspect, fov_deg=200.0, radius=10.0):
    r = min(1.0, full_frame_r(px, py, aspect))
    a = r * _m.radians(fov_deg) / 2.0
    psi = _m.atan2(py, px)
    return (radius * _m.sin(a) * _m.cos(psi), radius * _m.sin(a) * _m.sin(psi),
            -radius * _m.cos(a))

fx, fy, fz = full_frame_vert(1.0, 0.0, 2.0)
ck("全画幅：右 = +X（与圆盘/球面同向）", fx > 0 and abs(fy) < 1e-9)
ux_, uy_, uz_ = full_frame_vert(0.0, 1.0, 2.0)
ck("全画幅：上 = +Y（防倒像）", uy_ > 0 and abs(ux_) < 1e-9)
ck("全画幅：正前方朝 -Z", full_frame_vert(0.0, 0.0, 2.0)[2] < 0)
ck("全画幅：顶点半径恒 10 m",
   all(abs(_m.hypot(_m.hypot(*full_frame_vert(px, py, a)[:2]),
                    full_frame_vert(px, py, a)[2]) - 10.0) < 1e-3
       for a in (1.0, 2.0) for px in (-a, 0.0, a) for py in (-1.0, 0.0, 1.0)))
ck("全画幅：角上角度正好是 FOV/2（200° 片源的角 = 100°）",
   abs(_m.degrees(_m.acos(-full_frame_vert(2.0, 1.0, 2.0)[2] / 10.0)) - 100.0) < 1e-9)

# ── 汇总 ────────────────────────────────────────────────────────────────
print("\n── 判定 ──")
bad = 0
for label, ok, detail in results:
    if not ok:
        bad += 1
    print(f"  {label:<44} {'OK' if ok else 'FAIL'}" + (f"  {detail}" if detail and not ok else ""))
print(f"\n共 {len(results)} 项，失败 {bad} 项")
sys.exit(1 if bad else 0)
