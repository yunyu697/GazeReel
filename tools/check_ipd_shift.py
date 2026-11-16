"""
验算 applyIpdShift 的符号与幅度（不需要真机）。

做的事：用与 Android Matrix.perspectiveM / Cardboard SDK 相同的列主序透视投影，
在 m[8] 上做和 VrRenderer.applyIpdShift 一样的改动，然后把「视轴上的点」投影一遍，
看 ndc.x 往哪边移 —— 以此确认「调大瞳距 = 两幅画面各自向外分开」，而不是反向。

运行：python tools/check_ipd_shift.py
"""

import math

# 与工程一致（core/Optics.kt）
REFERENCE_IPD_MM = 63.0
DEG_PER_MM = 1.2
MAX_SHIFT_DEG = 9.0


def lens_shift_deg(ipd_mm: float) -> float:
    return max(-MAX_SHIFT_DEG, min(MAX_SHIFT_DEG, (ipd_mm - REFERENCE_IPD_MM) * 0.5 * DEG_PER_MM))


def eye_shift_deg(is_left: bool, ipd_mm: float) -> float:
    s = lens_shift_deg(ipd_mm)
    return s if is_left else -s


def perspective(fov_y_deg: float, aspect: float, near: float, far: float):
    """标准透视投影，列主序 m[col*4+row]。"""
    f = 1.0 / math.tan(math.radians(fov_y_deg) / 2)
    m = [0.0] * 16
    m[0] = f / aspect
    m[5] = f
    m[10] = (far + near) / (near - far)
    m[11] = -1.0
    m[14] = (2 * far * near) / (near - far)
    return m


def apply_ipd_shift(m, deg: float):
    """与 VrRenderer.applyIpdShift 逐行一致。"""
    if deg == 0.0:
        return m
    tan_half_x = 1.0 / m[0]
    ndc_shift = math.tan(math.radians(deg)) / tan_half_x
    m = list(m)
    m[8] += ndc_shift
    return m


def project_axis_point(m, x=0.0, z=-2.0):
    """把视轴附近一点 (x, 0, z) 投到 ndc，返回 ndc.x。"""
    cx = m[0] * x + m[4] * 0.0 + m[8] * z + m[12] * 1.0
    cw = m[3] * x + m[7] * 0.0 + m[11] * z + m[15] * 1.0
    return cx / cw


def deg_of_ndc(m, ndc_x):
    """ndc.x → 角度（度）。tan(halfFovX) = 1/m[0]。"""
    tan_half = 1.0 / m[0]
    return math.degrees(math.atan(ndc_x * tan_half))


def main():
    # 真机靶 2656×1220，每眼约 1328×1220；Cardboard 垂直 FOV 取 90° 量级做压力测试
    fov_y = 90.0
    aspect = 1328.0 / 1220.0
    base = perspective(fov_y, aspect, 0.1, 100.0)

    print(f"每眼宽高比 {aspect:.3f} · 垂直 FOV {fov_y}° · tan(halfFovX) = {1/base[0]:.4f}")
    print()
    print(f"{'IPD':>6} {'左眼角':>8} {'左眼画面':>9} {'右眼角':>8} {'右眼画面':>9} {'两幅分离':>9}")
    print("-" * 62)

    rows = []
    for ipd in (50.0, 55.0, 58.0, 62.0, 63.0, 66.0, 70.0, 75.0):
        line = [f"{ipd:>6.1f}"]
        ndc = {}
        for is_left in (True, False):
            deg = eye_shift_deg(is_left, ipd)
            m = apply_ipd_shift(base, deg)
            ndc_x = project_axis_point(m)
            # 画面内容移动的角度：ndc 方向正 = 内容右移
            content = deg_of_ndc(base, ndc_x)
            ndc[is_left] = content
            line.append(f"{deg:>+8.2f}")
            line.append(f"{content:>+9.2f}")
        # 向外分离量 = 右眼内容右移 − 左眼内容左移（右眼为正、左眼为负，相减即总分离）
        sep = ndc[False] - ndc[True]
        line.append(f"{sep:>+9.2f}")
        rows.append((ipd, ndc[True], ndc[False], sep))
        print(" ".join(line))

    print()
    print("判定（全部用**画面内容的实际位移**，不用角度代劳）：")
    ok = True
    # 1) 63mm 处应几乎为零位移（默认值不改变现有观感）
    m63l = apply_ipd_shift(base, eye_shift_deg(True, 63.0))
    d63 = abs(deg_of_ndc(base, project_axis_point(m63l)))
    print(f"  63mm 左眼画面位移 = {d63:.3f}°（应 ≈ 0）  {'OK' if d63 < 0.01 else 'FAIL'}")
    ok &= d63 < 0.01
    # 2) 调大瞳距 → 左眼内容向左移（画面位移为负）、右眼向右移（正），两幅向外分开
    m70l = apply_ipd_shift(base, eye_shift_deg(True, 70.0))
    m70r = apply_ipd_shift(base, eye_shift_deg(False, 70.0))
    l70 = deg_of_ndc(base, project_axis_point(m70l))
    r70 = deg_of_ndc(base, project_axis_point(m70r))
    cond = l70 < 0 < r70
    print(f"  70mm 左眼画面 {l70:+.2f}°（应负=左移）/ 右眼画面 {r70:+.2f}°（应正=右移）  "
          f"{'OK' if cond else 'FAIL'}")
    ok &= cond
    # 3) 单调：分离量随瞳距单调增
    seps = [r[3] for r in rows]
    mono = all(b >= a - 1e-9 for a, b in zip(seps, seps[1:]))
    print(f"  分离量随瞳距单调递增  {'OK' if mono else 'FAIL'}")
    ok &= mono
    # 4) 幅度上限
    worst = abs(eye_shift_deg(True, 40.0))
    cond2 = worst <= MAX_SHIFT_DEG + 1e-6
    print(f"  极端瞳距每眼位移 {worst:.2f}° ≤ 上限 {MAX_SHIFT_DEG}°  {'OK' if cond2 else 'FAIL'}")
    ok &= cond2

    print()
    print("全部通过" if ok else "存在 FAIL，不要交付")


if __name__ == "__main__":
    main()
