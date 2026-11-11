package check

import com.gazereel.vr.core.Optics
import com.gazereel.vr.core.PoseMath
import kotlin.math.abs

/**
 * V2.39：俯仰翻转的离线自检。
 *
 * 上一版（V2.37）用 `android.opengl.Matrix.rotateM` 拼分解式，真机上「打开开关没有任何变化」，
 * 而开发机跑不了那条路径，只能推演——推演已经错过一次。现在翻转是纯 Kotlin，
 * 于是可以在这里逐例验：**俯仰必须精确反号，偏航与横滚必须一位不差**。
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
    // 1) 纯抬头 / 纯低头：翻转后俯仰精确反号，偏航不变。
    for (pitch in listOf(5f, 20f, 30f, -20f, -45f, 60f, 85f)) {
        val pose = PoseMath.poseOf(0f, pitch, 0f)
        val flipped = PoseMath.flipPitch(pose)
        val y0 = PoseMath.gazeYawDeg(pose)
        val y1 = PoseMath.gazeYawDeg(flipped)
        val p0 = PoseMath.gazePitchDeg(pose)
        val p1 = PoseMath.gazePitchDeg(flipped)
        check(
            "抬头 $pitch°",
            near(p1, -p0, 0.05f) && near(y1, y0, 0.05f) && PoseMath.isProperRotation(flipped),
            "pitch ${deg(p0)} -> ${deg(p1)} · yaw ${deg(y0)} -> ${deg(y1)}"
        )
    }

    // 2) 转 + 抬 + 横滚同时存在：只反俯仰，偏航与横滚不许动。
    val combos = listOf(
        Triple(40f, 20f, 0f),
        Triple(-30f, -15f, 0f),
        Triple(120f, 8f, 0f),
        Triple(-170f, 25f, 0f),
        Triple(90f, -10f, 0f)
    )
    for ((yaw, pitch, roll) in combos) {
        val pose = PoseMath.poseOf(yaw, pitch, roll)
        val flipped = PoseMath.flipPitch(pose)
        val y0 = PoseMath.gazeYawDeg(pose)
        val y1 = PoseMath.gazeYawDeg(flipped)
        val p0 = PoseMath.gazePitchDeg(pose)
        val p1 = PoseMath.gazePitchDeg(flipped)
        check(
            "yaw=$yaw pitch=$pitch roll=$roll",
            near(p1, -p0, 0.05f) && near(y1, y0, 0.05f) && PoseMath.isProperRotation(flipped),
            "pitch ${deg(p0)} -> ${deg(p1)} · yaw ${deg(y0)} -> ${deg(y1)}"
        )
    }

    // 3) 横滚必须保留：直接再分解一次，比对 roll 角度。
    for ((yaw, roll) in listOf(0f to 12f, 35f to -18f, -80f to 25f, 150f to -7f)) {
        val pose = PoseMath.poseOf(yaw, 15f, roll)
        val flipped = PoseMath.flipPitch(pose)
        val a = PoseMath.yawPitchRollDeg(pose)
        val b = PoseMath.yawPitchRollDeg(flipped)
        check(
            "横滚保留 yaw=$yaw roll=$roll",
            near(a[2], b[2], 0.05f) && near(a[0], b[0], 0.05f) && near(b[1], -a[1], 0.05f) &&
                PoseMath.isProperRotation(flipped),
            "roll ${deg(a[2])} -> ${deg(b[2])} · yaw ${deg(a[0])} -> ${deg(b[0])} · " +
                "pitch ${deg(a[1])} -> ${deg(b[1])}"
        )
    }

    // 4) 二次翻转必须回到原姿态（对合性）：这条直接排除「翻了等于没翻」。
    val pose = PoseMath.poseOf(37f, -22f, 9f)
    val twice = PoseMath.flipPitch(PoseMath.flipPitch(pose))
    var maxDiff = 0f
    for (i in 0 until 16) maxDiff = maxOf(maxDiff, abs(pose[i] - twice[i]))
    check("翻两次回原位", maxDiff < 1e-4f, "最大元素差 $maxDiff")

    // 5) 翻转确实改变了姿态（不是恒等）——这是上一版「没有任何变化」的反例。
    val changed = PoseMath.flipPitch(PoseMath.poseOf(0f, 20f, 0f))
    var diff = 0f
    val base = PoseMath.poseOf(0f, 20f, 0f)
    for (i in 0 until 16) diff = maxOf(diff, abs(base[i] - changed[i]))
    check("翻转不是恒等", diff > 0.1f, "最大元素差 $diff")

    // 6) 瞳距：默认值几乎不动、拉到两头要明显、左右眼方向相反。
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
