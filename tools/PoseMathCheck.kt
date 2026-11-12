package check

import com.gazereel.vr.core.HeadPose
import com.gazereel.vr.core.Optics
import com.gazereel.vr.core.PoseMath
import com.gazereel.vr.core.Projection
import com.gazereel.vr.render.ProjectionMesh
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * V2.44：头追坐标约定 / 球面几何 / 瞳距光学的离线自检。
 *
 * 只编 app 里的真代码（HeadPose / PoseMath / Optics / ProjectionMesh），不需要设备、
 * 不需要 Gradle。这一版要钉死的是**坐标系**，不再是「pitch 取反」：
 *
 * 真因（离线数值复现，见 build/pose_probe.py 与 pose_probe_fix2.py）：
 * `SensorManager.getRotationMatrixFromVector()` 输出的是 Android ENU 世界
 * （X 东 / Y 北 / Z 天）的 device→world 矩阵，而全工程按 OpenGL 的 Y-up / -Z 前
 * 解读。旧链路缺 ENU→Y-up 那一层转换，正常戴镜时按 Y-up 分解出的 pitch 恒为 ±90°
 * ——万向节死锁。alpha30 的 `withInvertedPitch` 在死锁点做欧拉分解重建，抬头 15°
 * 就让 yaw/roll 跳 180°，真机看到的就是「开关把画面左右翻转」，而俯仰依旧反。
 *
 * 修法照 Google Cardboard SDK `head_tracker.cc` 的 `EkfToHeadTrackerRotations`：
 * `headPose = C · R · S`，C = Rx(-90°) 把 ENU 的天映成渲染的上、北映成渲染的前。
 * C 是 det=+1 的合法旋转，不是镜像，所以不倒像、不左右翻、不引入 roll。
 *
 * 断言分六组：
 * 1. 生产代码里的 C 常量确实等于 Rx(-90°)，四条基向量映射正确，det=+1；
 * 2. 四个面板朝向下，中性姿态都是单位阵、pitch/roll = 0；同时对照旧链路确实死锁；
 * 3. 用户站四个方位，戴稳时 pitch/roll 恒 0（不再随方位乱跳）；
 * 4. **核心**：抬头→球面上半、低头→下半、右转→u 增、左转→u 减，五个方位全一致，
 *    roll 恒 0、det 恒 +1；这组直接对应真机那两条反馈；
 * 5. 连续俯仰扫描不漂 roll；纯偏航时「上」仍竖直，recenterYaw 绕的是真正的竖直轴；
 * 6. 球面网格与瞳距的既有契约不回归。
 */
private var pass = 0
private var fail = 0

private fun check(name: String, ok: Boolean, detail: String) {
    if (ok) {
        pass++
        println("PASS $name · $detail")
    } else {
        fail++
        println("FAIL $name · $detail")
    }
}

private fun deg(v: Float): String = String.format(java.util.Locale.US, "%7.2f", v)
private fun f2(v: Double): String = String.format(java.util.Locale.US, "%.3f", v)

private fun near(a: Float, b: Float, tol: Float) = abs(a - b) <= tol
private fun near(a: Double, b: Double, tol: Double) = abs(a - b) <= tol

// ── 自检侧的 3×3 行主序矩阵工具（不进 App）────────────────────────────
private fun rad(d: Double): Double = Math.toRadians(d)

private fun mul(a: DoubleArray, b: DoubleArray): DoubleArray {
    val out = DoubleArray(9)
    for (r in 0 until 3) for (c in 0 until 3) {
        var sum = 0.0
        for (k in 0 until 3) sum += a[r * 3 + k] * b[k * 3 + c]
        out[r * 3 + c] = sum
    }
    return out
}

private fun tr(a: DoubleArray): DoubleArray =
    doubleArrayOf(a[0], a[3], a[6], a[1], a[4], a[7], a[2], a[5], a[8])

private fun rx(t: Double): DoubleArray {
    val c = cos(t); val s = sin(t)
    return doubleArrayOf(1.0, 0.0, 0.0, 0.0, c, -s, 0.0, s, c)
}

@Suppress("unused")
private fun ry(t: Double): DoubleArray {
    val c = cos(t); val s = sin(t)
    return doubleArrayOf(c, 0.0, s, 0.0, 1.0, 0.0, -s, 0.0, c)
}

private fun rz(t: Double): DoubleArray {
    val c = cos(t); val s = sin(t)
    return doubleArrayOf(c, -s, 0.0, s, c, 0.0, 0.0, 0.0, 1.0)
}

private fun det3(a: DoubleArray): Double =
    a[0] * (a[4] * a[8] - a[5] * a[7]) -
        a[1] * (a[3] * a[8] - a[5] * a[6]) +
        a[2] * (a[3] * a[7] - a[4] * a[6])

private fun identity3() = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)

private fun maxErr(a: DoubleArray, b: DoubleArray): Double {
    var m = 0.0
    for (i in 0 until 9) m = maxOf(m, abs(a[i] - b[i]))
    return m
}

/** 行主序 3×3 → 列主序 4×4（平移为 0），用于喂给 PoseMath。 */
private fun toPose(a: DoubleArray): FloatArray {
    val out = FloatArray(16)
    for (r in 0 until 3) for (c in 0 until 3) out[c * 4 + r] = a[r * 3 + c].toFloat()
    out[15] = 1f
    return out
}

/** 列主序 4×4 → 行主序 3×3。 */
private fun toRow3(m: FloatArray): DoubleArray = doubleArrayOf(
    m[0].toDouble(), m[4].toDouble(), m[8].toDouble(),
    m[1].toDouble(), m[5].toDouble(), m[9].toDouble(),
    m[2].toDouble(), m[6].toDouble(), m[10].toDouble()
)

/** 行主序 3×3 → getRotationMatrixFromVector 的 9 元素行主序 float。 */
private fun row9(a: DoubleArray): FloatArray = FloatArray(9) { a[it].toFloat() }

/** 调用生产代码：device→ENU 矩阵 + 面板朝向 → 渲染世界的头追姿态。 */
private fun headPose(r: DoubleArray, rot: Int): FloatArray = HeadPose.headPose(row9(r), rot)

/** 列主序 4×4 的第 1 列（屏幕「上」在世界中的方向）。 */
private fun upOf(pose: FloatArray): DoubleArray =
    doubleArrayOf(pose[4].toDouble(), pose[5].toDouble(), pose[6].toDouble())

/** 视线 = -第 3 列；等距柱状球面 (u, v)，与 ProjectionMesh 的约定一致。 */
private fun sphereUv(h: DoubleArray): DoubleArray {
    val f = doubleArrayOf(-h[2], -h[5], -h[8])
    val n = sqrt(f[0] * f[0] + f[1] * f[1] + f[2] * f[2])
    val phi = asin((f[1] / n).coerceIn(-1.0, 1.0))
    val theta = atan2(-f[0], -f[2])
    return doubleArrayOf(0.5 - theta / Math.PI, phi / Math.PI + 0.5)
}

/** C = Rx(-90°)：ENU(东,北,天) → 渲染(右,前,上)。 */
private val C = rx(rad(-90.0))
private val C_INV = tr(C)

/**
 * 中性 device→ENU：用户朝北、手机横屏（ROTATION_90）塞进 Cardboard。
 * 三列 = device +X(下=-天) / +Y(东) / +Z(南=-北) 在 ENU 里的方向。
 */
private val R0 = doubleArrayOf(
    0.0, 1.0, 0.0,
    0.0, 0.0, -1.0,
    -1.0, 0.0, 0.0
)

private data class Motion(val label: String, val kind: String, val delta: Double)

private val MOTIONS = listOf(
    Motion("抬头", "pitch", 25.0),
    Motion("低头", "pitch", -25.0),
    Motion("右转", "yaw", 25.0),
    Motion("左转", "yaw", -25.0)
)

val HEADINGS = listOf(0.0, 45.0, 90.0, 180.0, 270.0)

/** 该面板朝向下、物理上「屏幕正对用户、Y-up 中性」的 device→ENU 矩阵：C·R·S = I ⇒ R = C⁻¹·Sᵀ。 */
private fun baseNeutral(rot: Int): DoubleArray =
    mul(C_INV, tr(toRow3(HeadPose.screenToDevice(rot))))

/** 用户换一个方位站着 = 整机绕 ENU 天轴转 −hdg。 */
private fun neutralR(hdg: Double, rot: Int = HeadPose.ROTATION_90): DoubleArray =
    mul(rz(rad(-hdg)), baseNeutral(rot))

/** 绕「此刻用户的右轴」俯仰，或绕天轴偏航；再左乘到中性姿态上。 */
private fun rotatedR(hdg: Double, motion: Motion, rot: Int = HeadPose.ROTATION_90): DoubleArray {
    val neutral = neutralR(hdg, rot)
    val q = if (motion.kind == "pitch") {
        mul(mul(rz(rad(-hdg)), rx(rad(motion.delta))), rz(rad(hdg)))
    } else {
        rz(rad(-motion.delta))
    }
    return mul(q, neutral)
}

/** recenter 之后参考系里的姿态：O = 中性姿态的转置，H = O · P。 */
private fun inReference(r: DoubleArray, hdg: Double, rot: Int = HeadPose.ROTATION_90): DoubleArray {
    val o = tr(toRow3(headPose(neutralR(hdg, rot), rot)))
    return mul(o, toRow3(headPose(r, rot)))
}

fun main() {
    println("=== V2.44 头追坐标自检（编译的是 app 里的真代码）===")
    println()

    // ── 1. 生产代码里的 ENU→Y-up 常量 ────────────────────────────────
    // 喂单位阵 + ROTATION_0，headPose 就只剩 C（R=I、S=I）。
    val cProd = toRow3(HeadPose.headPose(row9(identity3()), HeadPose.ROTATION_0))
    check(
        "HeadPose 的 C = Rx(-90°) 且 det=+1（合法旋转，不是镜像）",
        maxErr(cProd, C) < 1e-5 && near(det3(cProd), 1.0, 1e-5),
        "maxErr=${f2(maxErr(cProd, C))} · det=${f2(det3(cProd))}"
    )
    check(
        "C 基向量：东→右 / 北→前 / 天→上",
        // cProd 是行主序，列 j = (cProd[j], cProd[3+j], cProd[6+j])。
        near(cProd[0], 1.0, 1e-5) && near(cProd[3], 0.0, 1e-5) && near(cProd[6], 0.0, 1e-5) &&
            near(cProd[1], 0.0, 1e-5) && near(cProd[4], 0.0, 1e-5) && near(cProd[7], -1.0, 1e-5) &&
            near(cProd[2], 0.0, 1e-5) && near(cProd[5], 1.0, 1e-5) && near(cProd[8], 0.0, 1e-5),
        "列0=(1,0,0) 列1=(0,0,-1) 列2=(0,1,0)"
    )

    check(
        "screenToDevice(90) 与探针里的物理中性姿态 R0 一致",
        maxErr(baseNeutral(HeadPose.ROTATION_90), R0) < 1e-5,
        "maxErr=${f2(maxErr(baseNeutral(HeadPose.ROTATION_90), R0))}"
    )

    // ── 2. 四个面板朝向：中性姿态都必须是单位阵 ──────────────────────
    for (rot in 0..3) {
        val s = toRow3(HeadPose.screenToDevice(rot))
        check(
            "screenToDevice(ROTATION_${rot * 90}) det=+1",
            near(det3(s), 1.0, 1e-5),
            "det=${f2(det3(s))}"
        )
        // C·R·S = I  ⇒  R = C⁻¹·Sᵀ
        val rNeutral = mul(C_INV, tr(s))
        val pose = headPose(rNeutral, rot)
        check(
            "ROTATION_${rot * 90} 中性姿态 = 单位阵",
            maxErr(toRow3(pose), identity3()) < 1e-5,
            "maxErr=${f2(maxErr(toRow3(pose), identity3()))}"
        )
        val ypr = PoseMath.yawPitchRollDeg(pose)
        check(
            "ROTATION_${rot * 90} 中性 pitch/roll = 0",
            near(ypr[1], 0f, 0.05f) && near(ypr[2], 0f, 0.05f) && PoseMath.isProperRotation(pose),
            "yaw ${deg(ypr[0])}° · pitch ${deg(ypr[1])}° · roll ${deg(ypr[2])}°"
        )
        // 对照：去掉 C（alpha24–alpha30 的旧链路），中性姿态就是万向节死锁。
        val old = PoseMath.yawPitchRollDeg(toPose(mul(rNeutral, s)))
        check(
            "对照：旧链路 ROTATION_${rot * 90} 中性即死锁（|pitch|≈90°）",
            abs(old[1]) > 80f,
            "旧 pitch=${deg(old[1])}° ← 这就是四轮开关全错的根因"
        )
    }

    // ── 3. 换方位站着，戴稳时 pitch/roll 必须恒 0 ─────────────────────
    for (hdg in HEADINGS) {
        val pose = headPose(neutralR(hdg), HeadPose.ROTATION_90)
        val ypr = PoseMath.yawPitchRollDeg(pose)
        check(
            "方位 ${hdg.toInt()}° 戴稳 · pitch/roll 恒 0",
            near(ypr[1], 0f, 0.05f) && near(ypr[2], 0f, 0.05f) && PoseMath.isProperRotation(pose),
            "yaw ${deg(ypr[0])}° · pitch ${deg(ypr[1])}° · roll ${deg(ypr[2])}°"
        )
    }

    // ── 4. 核心：抬头/低头/左右转在五个方位都要对 ─────────────────────
    println()
    println("--- 头动响应（recenter 后视线落在球面的 u/v）---")
    for (hdg in HEADINGS) {
        for (m in MOTIONS) {
            val r = rotatedR(hdg, m)
            val pose = headPose(r, HeadPose.ROTATION_90)
            val h = inReference(r, hdg)
            val uv = sphereUv(h)
            val ypr = PoseMath.yawPitchRollDeg(toPose(h))
            val proper = PoseMath.isProperRotation(toPose(h)) && near(det3(h), 1.0, 1e-5)
            val uOk = when (m.label) {
                "右转" -> uv[0] > 0.55
                "左转" -> uv[0] < 0.45
                else -> near(uv[0], 0.5, 0.02)
            }
            val vOk = when (m.label) {
                "抬头" -> uv[1] > 0.55
                "低头" -> uv[1] < 0.45
                else -> near(uv[1], 0.5, 0.02)
            }
            val pitchWant = when (m.kind) {
                "pitch" -> m.delta.toFloat()
                else -> 0f
            }
            check(
                "方位 ${hdg.toInt()}° ${m.label} ${m.delta.toInt()}°",
                uOk && vOk && proper && near(ypr[2], 0f, 0.05f) && near(ypr[1], pitchWant, 0.1f),
                "u=${f2(uv[0])} v=${f2(uv[1])} · pitch=${deg(ypr[1])}° roll=${deg(ypr[2])}° det=${f2(det3(h))}"
            )
            // 纯偏航时「上」仍必须竖直，否则 recenterYaw 的 rotY 会把地平线拧歪。
            if (m.kind == "yaw") {
                val up = upOf(pose)
                check(
                    "方位 ${hdg.toInt()}° ${m.label} · 世界「上」仍竖直",
                    near(up[0], 0.0, 1e-5) && near(up[1], 1.0, 1e-5) && near(up[2], 0.0, 1e-5),
                    "up=(${f2(up[0])}, ${f2(up[1])}, ${f2(up[2])})"
                )
            }
        }
    }

    // ── 4b. 四种面板朝向（含 ROTATION_270）在同一物理头动下都要对 ─────
    // 锁横屏后 getRotation() 可能是 90 也可能是 270，取决于机型与插盒方向；
    // 这一组专门防止「只在一个朝向下对」——前几轮效果随方位时好时坏就栽在这里。
    for (rot in 0..3) {
        var ok = true
        val parts = mutableListOf<String>()
        for (m in MOTIONS) {
            val r = rotatedR(0.0, m, rot)
            val h = inReference(r, 0.0, rot)
            val uv = sphereUv(h)
            val ypr = PoseMath.yawPitchRollDeg(toPose(h))
            val good = when (m.label) {
                "抬头" -> uv[1] > 0.55
                "低头" -> uv[1] < 0.45
                "右转" -> uv[0] > 0.55
                else -> uv[0] < 0.45
            } && near(ypr[2], 0f, 0.05f) && PoseMath.isProperRotation(toPose(h))
            ok = ok && good
            parts.add("${m.label} u=${f2(uv[0])}/v=${f2(uv[1])}")
        }
        check("ROTATION_${rot * 90} 四种头动响应全部正确", ok, parts.joinToString(" · "))
    }

    // ── 5. 连续俯仰扫描：roll 不漂、det 恒 +1、pitch 线性 ─────────────
    run {
        var maxRoll = 0f
        var maxPitchErr = 0f
        var allProper = true
        for (hdg in HEADINGS) {
            for (p in -75..75 step 5) {
                val m = Motion("扫描", "pitch", p.toDouble())
                val r = rotatedR(hdg, m)
                val h = inReference(r, hdg)
                val ypr = PoseMath.yawPitchRollDeg(toPose(h))
                maxRoll = maxOf(maxRoll, abs(ypr[2]))
                maxPitchErr = maxOf(maxPitchErr, abs(ypr[1] - p.toFloat()))
                allProper = allProper && PoseMath.isProperRotation(toPose(h))
            }
        }
        check(
            "连续俯仰 155 帧 × 5 方位 · 不滚不偏",
            maxRoll < 0.05f && maxPitchErr < 0.1f && allProper,
            "roll 最大 ${deg(maxRoll)}° · pitch 最大偏差 ${deg(maxPitchErr)}° · 全部 det=+1"
        )
    }

    // PoseMath 自身的分解/重建与符号约定仍在（日志和准星都吃这两个符号）。
    val angles = listOf(
        Triple(0f, 0f, 0f), Triple(30f, 12f, 0f), Triple(-45f, -20f, 0f),
        Triple(120f, 8f, 0f), Triple(-170f, 25f, 0f), Triple(90f, -10f, 0f),
        Triple(15f, 5f, 12f), Triple(-60f, 18f, -9f), Triple(179f, 0f, 3f)
    )
    for ((yaw, pitch, roll) in angles) {
        val pose = PoseMath.poseOf(yaw, pitch, roll)
        val ypr = PoseMath.yawPitchRollDeg(pose)
        check(
            "分解自洽 yaw=$yaw pitch=$pitch roll=$roll",
            near(ypr[0], yaw, 0.05f) && near(ypr[1], pitch, 0.05f) &&
                near(ypr[2], roll, 0.05f) && PoseMath.isProperRotation(pose),
            "yaw ${deg(ypr[0])} · pitch ${deg(ypr[1])} · roll ${deg(ypr[2])}"
        )
    }
    run {
        val up = PoseMath.poseOf(0f, 30f, 0f)
        val right = PoseMath.poseOf(30f, 0f, 0f)
        check(
            "符号约定：抬头为正 / 向右为正",
            near(PoseMath.gazePitchDeg(up), 30f, 0.05f) &&
                near(PoseMath.gazeYawDeg(right), 30f, 0.05f),
            "抬头 ${deg(PoseMath.gazePitchDeg(up))}° · 右转 ${deg(PoseMath.gazeYawDeg(right))}°"
        )
    }

    // ── 6. 球面网格与瞳距的既有契约 ──────────────────────────────────
    fun vertexNearest(g: ProjectionMesh.Geometry, tx: Float, ty: Float, tz: Float): FloatArray {
        val b = g.vertices
        var best = FloatArray(5)
        var bestDot = -2f
        for (i in 0 until g.vertexCount) {
            val o = i * 5
            val x = b.get(o); val y = b.get(o + 1); val z = b.get(o + 2)
            val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
            val dot = (x * tx + y * ty + z * tz) / len
            if (dot > bestDot) {
                bestDot = dot
                best = floatArrayOf(x, y, z, b.get(o + 3), b.get(o + 4))
            }
        }
        return best
    }

    val mesh = ProjectionMesh()
    run {
        val geo = mesh.build(Projection.HALF_SPHERE_180)
        val front = vertexNearest(geo, 0f, 0f, -1f)
        check(
            "180 半球正前方 u=0.5（接缝判据）",
            near(front[3], 0.5f, 0.02f),
            "u=${f2(front[3].toDouble())}"
        )
        val top = vertexNearest(geo, 0f, 1f, 0f)
        val bottom = vertexNearest(geo, 0f, -1f, 0f)
        check(
            "180 半球球顶 v=1 / 球底 v=0（内容不翻转）",
            near(top[4], 1f, 0.02f) && near(bottom[4], 0f, 0.02f),
            "v顶=${f2(top[4].toDouble())} · v底=${f2(bottom[4].toDouble())}"
        )
        val rightDir = vertexNearest(geo, 1f, 0f, 0f)
        val leftDir = vertexNearest(geo, -1f, 0f, 0f)
        check(
            "180 半球左右不镜像",
            rightDir[3] > 0.5f && leftDir[3] < 0.5f,
            "右 u=${f2(rightDir[3].toDouble())} · 左 u=${f2(leftDir[3].toDouble())}"
        )
    }

    run {
        val def = Optics.lensShiftDeg(62f)
        val max = Optics.lensShiftDeg(75f)
        val min = Optics.lensShiftDeg(50f)
        check(
            "瞳距位移幅度",
            abs(def) < 1f && max > 6f && min < -6f,
            "62mm ${deg(def)}° · 75mm ${deg(max)}° · 50mm ${deg(min)}°"
        )
        val l = Optics.eyeShiftDeg(true, 75f)
        val r = Optics.eyeShiftDeg(false, 75f)
        check("左右眼反向", l * r < 0f && abs(l) == abs(r), "左 ${deg(l)}° · 右 ${deg(r)}°")
        val bounds = Optics.frustumLeftRight(true, 1f, 75f, 0.1f)
        check(
            "离轴视锥向外偏",
            (bounds[0] + bounds[1]) / 2f > 0.01f && bounds[1] > bounds[0],
            "left=${bounds[0]} right=${bounds[1]}"
        )
    }

    println()
    println("通过 $pass · 失败 $fail")
    if (fail > 0) throw IllegalStateException("自检未通过")
}
