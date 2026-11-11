package check

import com.gazereel.vr.core.Optics
import com.gazereel.vr.core.PoseMath
import com.gazereel.vr.core.Projection
import com.gazereel.vr.render.ProjectionMesh
import kotlin.math.abs

/**
 * V2.41：姿态与球面几何的离线自检。
 *
 * 变化的原因：V2.37–V2.40 这里验的是 `PoseMath.flipPitch`，而那个函数在数学上
 * 就不是旋转（「只反俯仰」= 关于水平面的镜像，det = −1），真机上表现为
 * 「点一下反了，抬头低头画面在旋转」。它已经被整段删除，自检也随之改向：
 *
 * 1. 姿态的分解/重建必须自洽（日志里的 yaw/pitch/roll 才可信）；
 * 2. 任何姿态都必须是合法旋转（det = +1），这条能挡住任何「镜像式」修法再溜回来；
 * 3. 球面网格的两条命根子：正前方落在 u = 0.5（SBS 接缝那个坑）、球顶落在
 *    v = 1 且「上下翻转」正好把它变成 v = 0（只差一个符号，不产生旋转）。
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

private fun near(a: Float, b: Float, tol: Float) = abs(a - b) <= tol

fun main() {
    // 1) 分解与重建自洽：poseOf → yawPitchRollDeg 必须原样回来。
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
            near(ypr[0], yaw, 0.05f) && near(ypr[1], pitch, 0.05f) && near(ypr[2], roll, 0.05f),
            "yaw ${deg(ypr[0])} · pitch ${deg(ypr[1])} · roll ${deg(ypr[2])}"
        )
    }

    // 2) 任何姿态都必须是右手正交（det = +1）。
    //    「只反俯仰」那种镜像修法会在这一条上现原形，所以它被永久锁在这里。
    for ((yaw, pitch, roll) in angles) {
        val pose = PoseMath.poseOf(yaw, pitch, roll)
        check(
            "合法旋转 yaw=$yaw pitch=$pitch roll=$roll",
            PoseMath.isProperRotation(pose),
            "det = +1"
        )
    }

    // 3) 符号约定：抬头为正、向右为正。日志和 UI 的准星都吃这两个符号。
    run {
        val up = PoseMath.poseOf(0f, 30f, 0f)
        val right = PoseMath.poseOf(30f, 0f, 0f)
        check(
            "抬头为正 / 向右为正",
            near(PoseMath.gazePitchDeg(up), 30f, 0.05f) &&
                near(PoseMath.gazeYawDeg(right), 30f, 0.05f),
            "抬头 ${deg(PoseMath.gazePitchDeg(up))}° · 右转 ${deg(PoseMath.gazeYawDeg(right))}°"
        )
    }

    // 4) 球面网格：正前方 u = 0.5 —— 这就是「打开就是左右接缝」那个坑的判据。
    //    每顶点 5 个 float：x y z u v；取与视线方向最接近的那个顶点比。
    fun vertexNearest(g: ProjectionMesh.Geometry, tx: Float, ty: Float, tz: Float): FloatArray {
        val b = g.vertices
        var best = FloatArray(5)
        var bestDot = -2f
        for (i in 0 until g.vertexCount) {
            val o = i * 5
            val x = b.get(o); val y = b.get(o + 1); val z = b.get(o + 2)
            val len = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
            val dot = (x * tx + y * ty + z * tz) / len
            if (dot > bestDot) {
                bestDot = dot
                best = floatArrayOf(x, y, z, b.get(o + 3), b.get(o + 4))
            }
        }
        return best
    }

    val mesh = ProjectionMesh()
    for (flip in listOf(false, true)) {
        val geo = mesh.build(Projection.HALF_SPHERE_180, flip)
        val front = vertexNearest(geo, 0f, 0f, -1f)
        check(
            "180 半球正前方 u=0.5（flipV=$flip）",
            near(front[3], 0.5f, 0.02f),
            "u=${String.format(java.util.Locale.US, "%.3f", front[3])}"
        )
        val top = vertexNearest(geo, 0f, 1f, 0f)
        val wantV = if (flip) 0f else 1f
        check(
            "180 半球球顶 v=$wantV（flipV=$flip）",
            near(top[4], wantV, 0.02f),
            "v=${String.format(java.util.Locale.US, "%.3f", top[4])}"
        )
        // 左右：等距柱状里 u 随偏航单调增，所以「向右看」必须落在 u > 0.5，
        // 「向左看」必须落在 u < 0.5。反了就是左右镜像，接缝问题会以另一种形式回来。
        val rightDir = vertexNearest(geo, 1f, 0f, 0f)
        val leftDir = vertexNearest(geo, -1f, 0f, 0f)
        check(
            "180 半球左右不镜像（flipV=$flip）",
            rightDir[3] > 0.5f && leftDir[3] < 0.5f,
            "右 u=${String.format(java.util.Locale.US, "%.3f", rightDir[3])} · " +
                "左 u=${String.format(java.util.Locale.US, "%.3f", leftDir[3])}"
        )
    }

    // 5) 瞳距：默认值几乎不动、拉到两头要明显、左右眼方向相反。
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
        val center = (bounds[0] + bounds[1]) / 2f
        check(
            "离轴视锥向外偏",
            center > 0.01f && bounds[1] > bounds[0],
            "left=${bounds[0]} right=${bounds[1]} center=${center}"
        )
    }

    println()
    println("通过 $pass · 失败 $fail")
    if (fail > 0) throw IllegalStateException("自检未通过")
}
