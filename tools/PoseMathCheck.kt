package check

import com.gazereel.vr.core.Optics
import com.gazereel.vr.core.PoseMath
import com.gazereel.vr.core.Projection
import com.gazereel.vr.render.ProjectionMesh
import kotlin.math.abs

/**
 * V2.43：姿态与球面几何的离线自检（只编工程里的真代码，不需要设备）。
 *
 * 这一版验的核心是 `PoseMath.withInvertedPitch`：真机「抬头看到地板」要改的是
 * **姿态的俯仰响应**，不是画面内容。V2.41 曾把它判成「镜像不可能实现」，
 * 又把修法挪到球面 v 取补上，结果真机「整个画面都倒过来了」。这里把结论钉死：
 *
 * 1. 分解/重建自洽（yaw 符号与 gazeYawDeg 一致）；
 * 2. 翻转后仍是合法旋转（det = +1）——这一条直接排除倒像与画面旋转；
 * 3. yaw 与 roll 逐度不变、pitch 精确取反，连 89° 俯仰都不退化；
 * 4. 翻转两次回到原姿态；
 * 5. 球面网格不再有翻转参数：正前方 u = 0.5、球顶 v = 1、左右不镜像。
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
    //    这条能挡住「直接改矩阵元素」的镜像式修法，也保证画面不倒、不 roll。
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
    run {
        val geo = mesh.build(Projection.HALF_SPHERE_180)
        val front = vertexNearest(geo, 0f, 0f, -1f)
        check(
            "180 半球正前方 u=0.5（接缝判据）",
            near(front[3], 0.5f, 0.02f),
            "u=${String.format(java.util.Locale.US, "%.3f", front[3])}"
        )
        val top = vertexNearest(geo, 0f, 1f, 0f)
        check(
            "180 半球球顶 v=1（不再有内容翻转）",
            near(top[4], 1f, 0.02f),
            "v=${String.format(java.util.Locale.US, "%.3f", top[4])}"
        )
        val bottom = vertexNearest(geo, 0f, -1f, 0f)
        check(
            "180 半球球底 v=0",
            near(bottom[4], 0f, 0.02f),
            "v=${String.format(java.util.Locale.US, "%.3f", bottom[4])}"
        )
        // 等距柱状里 u 随偏航单调增：向右看 u>0.5、向左看 u<0.5。
        val rightDir = vertexNearest(geo, 1f, 0f, 0f)
        val leftDir = vertexNearest(geo, -1f, 0f, 0f)
        check(
            "180 半球左右不镜像",
            rightDir[3] > 0.5f && leftDir[3] < 0.5f,
            "右 u=${String.format(java.util.Locale.US, "%.3f", rightDir[3])} · " +
                "左 u=${String.format(java.util.Locale.US, "%.3f", leftDir[3])}"
        )
    }

    // 4b) 俯仰响应反向：这是 V2.43 的核心断言。
    //     上一版判断「只反俯仰是镜像、做不到」是错的：Ry(y)·Rx(−p)·Rz(r)
    //     是三个旋转的乘积，det 恒 +1。真机「画面在转」的真因是 yaw 符号，
    //     不是这个操作。下面逐条锁死，防止再被错误结论带偏。
    val flipCases = listOf(
        Triple(0f, 0f, 0f), Triple(0f, 30f, 0f), Triple(0f, -30f, 0f),
        Triple(40f, 25f, 0f), Triple(-40f, 25f, 15f), Triple(40f, -25f, 15f),
        Triple(120f, 50f, 20f), Triple(0f, 30f, 30f),
        Triple(0f, 85f, 10f), Triple(0f, -85f, 10f), Triple(90f, 45f, 0f)
    )
    for ((yaw, pitch, roll) in flipCases) {
        val pose = PoseMath.poseOf(yaw, pitch, roll)
        val flipped = PoseMath.withInvertedPitch(pose)
        val a = PoseMath.yawPitchRollDeg(flipped)
        check(
            "俯仰反向 yaw=$yaw p=$pitch r=$roll",
            near(a[0], yaw, 0.05f) && near(a[1], -pitch, 0.05f) &&
                near(a[2], roll, 0.05f) && PoseMath.isProperRotation(flipped),
            "yaw ${deg(a[0])} · pitch ${deg(a[1])} · roll ${deg(a[2])} · det=+1"
        )
        val twice = PoseMath.withInvertedPitch(flipped)
        var maxErr = 0f
        for (k in 0 until 16) maxErr = maxOf(maxErr, abs(twice[k] - pose[k]))
        check(
            "反两次回到原姿态 yaw=$yaw p=$pitch",
            maxErr < 0.002f,
            "maxErr=$maxErr"
        )
    }

    // 4c) 连续抬头序列：roll 必须恒定，不能随 pitch 漂移。
    //     真机「抬头低头画面在旋转」就是 roll 在动，这条专门盯它。
    run {
        var maxRollErr = 0f
        var allProper = true
        for (p in -60..60 step 3) {
            val pose = PoseMath.poseOf(20f, p.toFloat(), 8f)
            val f = PoseMath.withInvertedPitch(pose)
            val a = PoseMath.yawPitchRollDeg(f)
            maxRollErr = maxOf(maxRollErr, abs(a[2] - 8f), abs(a[0] - 20f))
            allProper = allProper && PoseMath.isProperRotation(f)
        }
        check(
            "连续抬头 41 帧 · 姿态不滚不偏",
            maxRollErr < 0.05f && allProper,
            "yaw/roll 最大偏差 ${deg(maxRollErr)}° · 全部 det=+1"
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
