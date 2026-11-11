package com.gazereel.vr.core

/**
 * V2.39：瞳距到底该改什么。
 *
 * ## 上一版为什么「界面能拖、画面一点变化都没有」
 *
 * 旧实现把瞳距当成**相机间距**：每只眼的相机沿 x 平移 ±IPD/2。这在近景物体上
 * 才是可见的——视差角 ≈ 间距 / 距离。而这里的片源铺在半径 10 单位的球面上，
 * 62mm → 75mm 只是把相机挪了 6.5mm，对应视差 `atan(0.0065 / 10) ≈ 0.04°`。
 * **0.04° 在头显里就是没有**。所以那条代码「数学上正确、视觉上无效」，
 * 用户看到的只是一个能拖的界面。
 *
 * ## 真机上瞳距调的其实是「画面要落在镜片中心」
 *
 * 纸盒的镜片间距是固定的。用户瞳距比镜片间距大时，左右两幅画面必须各自**向外**
 * 挪一点，眼睛才能透过镜片正视到画面中心；反之向内。这是设备的几何属性，
 * 与物体远近无关，所以它作用在**每只眼的光轴**上（离轴视锥），而不是相机位置。
 *
 * 换算：眼球看屏幕相当于隔着焦距 f≈45mm 的透镜，屏幕上 1mm ≈ `1/45 rad ≈ 1.27°`。
 * 取 1.2°/mm、以 63mm 为参考（纸盒 v2 的常见镜距），于是：
 * 62mm → 每眼 −0.6°（几乎没有变化，默认值不改变现有观感），
 * 75mm → 每眼 +7.2°（明显向外，用户一定看得见）。
 *
 * 正号 = 光轴朝屏幕右侧转（画面内容向左移）。左眼取正、右眼取负，
 * 于是「调大瞳距」= 两幅画面各自向外分开。
 */
object Optics {

    /** 参考镜距：瞳距等于它时每眼位移为 0。 */
    const val REFERENCE_IPD_MM = 63f

    /** 屏幕 1mm 对应的视角（度）。45mm 焦距 ≈ 1.27°，取 1.2 略保守。 */
    const val DEG_PER_MM = 1.2f

    /** 位移上限：再大就超出镜片视野，画面边缘会发虚。 */
    const val MAX_SHIFT_DEG = 9f

    /** 每只眼的光轴水平位移角（度）；正 = 光轴朝右转。 */
    fun lensShiftDeg(ipdMm: Float): Float =
        ((ipdMm - REFERENCE_IPD_MM) * 0.5f * DEG_PER_MM).coerceIn(-MAX_SHIFT_DEG, MAX_SHIFT_DEG)

    /** 带方向：左眼向外（正），右眼向外（负）。 */
    fun eyeShiftDeg(isLeft: Boolean, ipdMm: Float): Float {
        val shift = lensShiftDeg(ipdMm)
        return if (isLeft) shift else -shift
    }

    /**
     * 离轴视锥的左右边界（近平面单位）。
     *
     * 原先是 `[-near*aspect, +near*aspect]`，即光轴正对画面中心。现在把光轴转 θ：
     * 左右边界按 `near·tan(θ ∓ 半视角)` 算——不是简单地在两边各加一个常数，
     * 那样在大角度时会把视场压扁。
     */
    fun frustumLeftRight(isLeft: Boolean, aspect: Float, ipdMm: Float, near: Float): FloatArray {
        val halfH = Math.atan(aspect.toDouble())
        val theta = Math.toRadians(eyeShiftDeg(isLeft, ipdMm).toDouble())
        return floatArrayOf(
            (near * Math.tan(theta - halfH)).toFloat(),
            (near * Math.tan(theta + halfH)).toFloat()
        )
    }
}
