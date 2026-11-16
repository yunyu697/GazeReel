package com.gazereel.vr.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * alpha35：**手机物理屏幕的中心线**。
 *
 * 用户原话：「我说的中心线是手机屏幕的中心线，用来方便用户把手机放进盒子的时候对准盒子的。」
 * 这一步发生在**戴镜之前**——人拿着手机、盒子还开着，靠这条线把屏幕中线对准纸盒的
 * 中隔板 / 中线，扣上盖子之后就不用再拿出来。
 *
 * 它必须是**屏幕空间**的一层 View，不能画进 `VrUiLayer` 那张 1024×512 的 UI 纹理：
 * `VrRenderer` 对 LEFT / RIGHT 各设一次 glViewport，UI 纹理每眼各画一遍，
 * 画布的 `W/2` 是「每眼视口中心」而不是手机物理屏幕中线；`Optics.lensShiftDeg()`
 * 还会让每眼光轴偏移，画布中心 ≠ 光轴 ≠ 物理中线。画进 GL 会变成每眼各一条、
 * 且都不在盒子中线上，还跟着桶形畸变弯。
 *
 * 形态照 Google Cardboard 官方 Viewer Profile 设置流程里的对齐线：一条竖直细线 +
 * 上下两个端标，其余区域完全透明，GL 画面照常透出。只在 `!gazeArmed` 时可见
 * （`VrActivity` 每帧开关），按触发键上膛后立刻收起，不干扰看片。
 *
 * 自研边界：只有一个 `onDraw()`，不进 GL、不参与头追、不注册凝视目标。
 */
class CenterLineView(context: Context) : View(context) {

    /** 深色衬底：VR 画面亮时青线也看得清。 */
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0D1526.toInt()
        strokeWidth = 10f
        alpha = 190
        style = Paint.Style.STROKE
    }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF67E8F9.toInt()   // C_ACCENT，只给「现在该对的那个」
        strokeWidth = 3f
        alpha = 235
        style = Paint.Style.STROKE
    }

    private val cap = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF67E8F9.toInt()
        strokeWidth = 5f
        alpha = 235
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val h = height.toFloat()
        if (h <= 0f) return

        canvas.drawLine(x, 0f, x, h, glow)
        canvas.drawLine(x, 0f, x, h, line)

        // 上下端标：塞手机时从盒子开口往下看，端点比通长的线更容易对准隔板。
        val arm = 26f
        canvas.drawLine(x - arm, 6f, x + arm, 6f, cap)
        canvas.drawLine(x - arm, h - 6f, x + arm, h - 6f, cap)
    }
}
