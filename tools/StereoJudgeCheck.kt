// 抽帧判布局的离线自检（不参与 APK 构建，只在 JVM 上跑）。
//
// 为什么要有它：StereoFrameJudge 是全工程唯一「看像素下结论」的地方，
// 真机上抽不到帧、也复现不了某种片源时，只能靠合成图把边界钉死。
// 合成图按真实抽帧靶的尺寸（192×96）造，视差 / 噪声 / 黑边都按实测比例给：
// 4096×2048 的 180 SBS，每眼 2048×2048，缩进 96×96 的判据网格后，
// 40 px 的视差只剩 1.9 个网格像素——所以这里的视差都是 0–7 的小整数。
//
// 编译与运行（Windows Git Bash，Kotlin 编译器取自 Gradle 缓存）：
//   bash tools/run-stereo-check.sh
//
// 期望：九个场景全部通过；任何一条不符，进程退出码为 1。

package check

import com.gazereel.vr.core.StereoFrameJudge
import com.gazereel.vr.core.StereoLayout
import kotlin.math.roundToInt
import kotlin.math.sin

private const val W = 192
private const val H = 96

/**
 * 造一张「像画面」的图：几个大小不一的物体 + 边缘 + 一点细节纹理。
 *
 * 不用纯正弦叠加：正弦场是**平稳**的，任何一块和另一块统计上没差别，
 * mono 的左右两半也会互相「配上」——那不是判据的问题，是合成图失了真。
 * 真实画面是非平稳的：这个位置有张脸，那个位置是墙，对不上就是对不上。
 */
private fun scene(w: Int, h: Int, seed: Long): FloatArray {
    val rnd = java.util.Random(seed)
    val out = FloatArray(w * h) { 0.45f }
    // 低频光照（左上亮、右下暗）
    for (y in 0 until h) {
        for (x in 0 until w) out[y * w + x] = (0.42f + 0.18f * (x + y).toFloat() / (w + h))
    }
    // 物体：若干个软边圆斑，正负都有，模拟人物 / 家具 / 光源
    for (k in 0 until 14) {
        val cx = rnd.nextDouble() * w
        val cy = rnd.nextDouble() * h
        val sigma = 3.0 + rnd.nextDouble() * 14.0
        val amp = (rnd.nextDouble() * 0.7 - 0.35)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x - cx
                val dy = y - cy
                out[y * w + x] += (amp * Math.exp(-(dx * dx + dy * dy) / (2 * sigma * sigma))).toFloat()
            }
        }
    }
    // 细节纹理：波长短、幅度小的几条正弦，只负责给块内提供一点对比度
    val waves = Array(4) {
        doubleArrayOf(
            0.9 + rnd.nextDouble() * 1.6,
            0.9 + rnd.nextDouble() * 1.6,
            rnd.nextDouble() * 6.283,
            0.02 + rnd.nextDouble() * 0.05
        )
    }
    for (y in 0 until h) {
        for (x in 0 until w) {
            var v = 0.0
            for (k in waves) v += k[3] * sin(k[0] * x + k[1] * y + k[2])
            out[y * w + x] = (out[y * w + x] + v.toFloat()).coerceIn(0f, 1f)
        }
    }
    return out
}

private fun lumaToRgba(luma: FloatArray): ByteArray {
    val out = ByteArray(luma.size * 4)
    for (i in luma.indices) {
        val v = (luma[i] * 255f).roundToInt().coerceIn(0, 255)
        out[i * 4] = v.toByte()
        out[i * 4 + 1] = v.toByte()
        out[i * 4 + 2] = v.toByte()
        out[i * 4 + 3] = 255.toByte()
    }
    return out
}

/**
 * 造一幅 SBS 画面：右眼 = 左眼按逐块视差横向平移。
 *
 * @param disp 基础视差（网格像素）
 * @param depth 视差随画幅缓慢变化的幅度（模拟一堵斜墙 / 前后景分层）
 * @param noise 噪声强度（0..255 灰度级），模拟压缩
 * @param bars 上下黑边行数
 * @param vignette 左右两端压暗的程度（模拟镜头暗角 / 补边）
 */
private fun sbsFrame(
    disp: Int,
    depth: Double = 0.0,
    noise: Int = 0,
    bars: Int = 0,
    vignette: Double = 0.0,
    seed: Long = 7L
): ByteArray {
    val ew = W / 2
    val eye = scene(ew, H, seed)
    val luma = FloatArray(W * H)
    val rnd = java.util.Random(11)
    for (y in 0 until H) {
        for (x in 0 until ew) {
            val d = disp + (depth * sin(x * 0.05)).roundToInt()
            val sx = (x - d).coerceIn(0, ew - 1)
            var l = eye[y * ew + x]
            var r = eye[y * ew + sx]
            val dim = if (vignette > 0) {
                val edge = minOf(x, ew - 1 - x) / (ew * 0.25)
                (1.0 - vignette * (1.0 - edge.coerceIn(0.0, 1.0))).toFloat()
            } else 1f
            l *= dim
            r *= dim
            if (noise > 0) {
                l += (rnd.nextInt(2 * noise + 1) - noise) / 255f
                r += (rnd.nextInt(2 * noise + 1) - noise) / 255f
            }
            luma[y * W + x] = l.coerceIn(0f, 1f)
            luma[y * W + ew + x] = r.coerceIn(0f, 1f)
        }
    }
    for (y in 0 until H) {
        if (y < bars || y >= H - bars) for (x in 0 until W) luma[y * W + x] = 0f
    }
    return lumaToRgba(luma)
}

/** 造一幅 TAB 画面：下眼 = 上眼纵向平移。 */
private fun tabFrame(disp: Int, noise: Int): ByteArray {
    val eye = scene(W, H / 2, 21L)
    val luma = FloatArray(W * H)
    val rnd = java.util.Random(13)
    for (x in 0 until W) {
        for (y in 0 until H / 2) {
            val sy = (y - disp).coerceIn(0, H / 2 - 1)
            var t = eye[y * W + x]
            var b = eye[sy * W + x]
            if (noise > 0) {
                t += (rnd.nextInt(2 * noise + 1) - noise) / 255f
                b += (rnd.nextInt(2 * noise + 1) - noise) / 255f
            }
            luma[y * W + x] = t.coerceIn(0f, 1f)
            luma[(y + H / 2) * W + x] = b.coerceIn(0f, 1f)
        }
    }
    return lumaToRgba(luma)
}

/** mono 360：左右两半是两块互不相干的场景。 */
private fun mono360Frame(): ByteArray {
    val left = scene(W / 2, H, 101L)
    val right = scene(W / 2, H, 202L)
    val luma = FloatArray(W * H)
    for (y in 0 until H) {
        for (x in 0 until W / 2) {
            luma[y * W + x] = left[y * (W / 2) + x]
            luma[y * W + W / 2 + x] = right[y * (W / 2) + x]
        }
    }
    return lumaToRgba(luma)
}

/** mono 平面 2D：一整张连续画面，左右两半是同一场景的左右两部分（不是立体对）。 */
private fun monoFlatFrame(): ByteArray {
    val full = scene(W, H, 404L)
    return lumaToRgba(full)
}

/**
 * 平稳纹理（水面 / 草地 / 噪点）：两半统计上一样，只是相位不同。
 * 这种画面在任意位移上都能凑出高相关，必须靠「峰值独占」挡掉。
 */
private fun stationaryFrame(): ByteArray {
    val rnd = java.util.Random(31)
    val waves = Array(6) {
        doubleArrayOf(
            0.8 + rnd.nextDouble() * 2.0,
            0.8 + rnd.nextDouble() * 2.0,
            rnd.nextDouble() * 6.283,
            0.06 + rnd.nextDouble() * 0.10
        )
    }
    val luma = FloatArray(W * H) { i ->
        val x = i % W
        val y = i / W
        var v = 0.0
        for (k in waves) v += k[3] * sin(k[0] * x + k[1] * y + k[2] * (if (x < W / 2) 1.0 else 1.7))
        (0.5 + v).toFloat().coerceIn(0f, 1f)
    }
    return lumaToRgba(luma)
}

/** 平坦画面（淡入 / 天空 / 雾）：这一帧不该有判别力。 */
private fun flatFrame(): ByteArray {
    val luma = FloatArray(W * H) { i ->
        val x = i % W
        (0.30f + 0.10f * x / W)
    }
    return lumaToRgba(luma)
}

private var failures = 0

private fun check(name: String, expect: StereoLayout, minConfidence: Float, frame: ByteArray) {
    val v = StereoFrameJudge.judge(frame, W, H)
    val ok = v.layout == expect && v.confidence >= minConfidence
    if (!ok) failures++
    println("[${if (ok) "PASS" else "FAIL"}] $name → ${v.layout} conf=${"%.3f".format(v.confidence)} " +
        "(期望 $expect ≥ ${"%.2f".format(minConfidence)})")
    println("       ${v.describe()}")
}

private fun reject(name: String, frame: ByteArray) {
    val v = StereoFrameJudge.judge(frame, W, H)
    val ok = v.layout != StereoLayout.SBS && v.layout != StereoLayout.TAB && v.confidence < 0.70f
    if (!ok) failures++
    println("[${if (ok) "PASS" else "FAIL"}] $name → ${v.layout} conf=${"%.2f".format(v.confidence)}" +
        "（不许判成立体）")
    println("       ${v.describe()}")
}

fun main() {
    // 1. 视差为 0 的立体片（渲染 / 对齐过的片源）
    check("SBS 视差 0", StereoLayout.SBS, 0.70f, sbsFrame(0))
    // 2. 小视差
    check("SBS 视差 2", StereoLayout.SBS, 0.70f, sbsFrame(2))
    // 3. KAVR-332 那一类：中等视差 + 前后景分层 + 压缩噪声
    check("SBS 视差 4 分层 2 噪声 4", StereoLayout.SBS, 0.70f, sbsFrame(4, depth = 2.0, noise = 4))
    // 4. 再叠上下黑边与左右压暗（180 片常见的补边 / 暗角）
    check(
        "SBS 视差 4 + 黑边 + 暗角", StereoLayout.SBS, 0.70f,
        sbsFrame(4, depth = 1.0, noise = 4, bars = 6, vignette = 0.7)
    )
    // 5. 大视差近景 + 重压缩（8K 片源转码常见）
    check("SBS 视差 6 分层 2 噪声 8", StereoLayout.SBS, 0.70f, sbsFrame(6, depth = 2.0, noise = 8))
    // 6. 上下分幅
    check("TAB 视差 1", StereoLayout.TAB, 0.70f, tabFrame(1, 3))
    // 7. mono 360：两半是不同场景
    reject("mono 360", mono360Frame())
    // 8. mono 平面 2D：一整张连续画面
    reject("mono 平面 2D", monoFlatFrame())
    // 9. 平稳纹理（水面 / 草地）：任意位移都能凑上，靠峰值独占挡
    reject("平稳纹理（水面）", stationaryFrame())
    // 10. 平坦画面：没有判别力
    reject("平坦画面", flatFrame())

    println()
    if (failures == 0) println("全部通过") else println("失败 $failures 条")
    if (failures != 0) kotlin.system.exitProcess(1)
}
