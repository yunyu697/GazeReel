package com.gazereel.vr.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs

/**
 * V2.20 / A18：入盒引导页上的水平仪。
 *
 * 它回答的只有一个问题：**手机现在是歪的吗**。
 *
 * 不做数字、不做刻度：戴镜前那一刻用户手里拿着手机，眼睛要同时看盒子的隔板，
 * 没有余量去读一个「−3.4°」。所以只有一条轨 + 一颗珠子：
 * 珠子在正中间 = 平了，偏左偏右 = 哪边低。摆到中间时整体变青，就是「可以了」。
 *
 * 满量程 ±10°：再大的倾斜靠珠子位置已经一眼看得出来，不需要更细。
 */
class LevelGauge @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    /** 正值表示手机右侧偏低（屏幕坐标里的重力 x 分量）。 */
    var tiltDeg: Float = 0f
        set(value) {
            if (abs(field - value) < 0.05f) return
            field = value
            invalidate()
        }

    /** 稳住之后整条变青。 */
    var leveled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = Color.parseColor("#2A3C55")
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = Color.parseColor("#4D67E8F9")
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#8FA3BC")
    }

    private val bubbleRadius = 6f * density
    private val pad = 8f * density

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cy = height / 2f
        val left = pad
        val right = width - pad
        if (right <= left) return

        canvas.drawLine(left, cy, right, cy, trackPaint)

        val cx = (left + right) / 2f
        canvas.drawLine(cx, cy - 6f * density, cx, cy + 6f * density, tickPaint)

        val halfSpan = (right - left) / 2f - bubbleRadius
        val normalized = (tiltDeg / MAX_DEG).coerceIn(-1f, 1f)
        // 珠子往「高的一边」走：右侧偏低时珠子偏左，和人眼看到的水泡一致。
        val bx = cx - normalized * halfSpan
        bubblePaint.color = if (leveled) {
            Color.parseColor("#67E8F9")
        } else {
            Color.parseColor("#8FA3BC")
        }
        canvas.drawCircle(bx, cy, bubbleRadius, bubblePaint)
    }

    private companion object {
        const val MAX_DEG = 10f
    }
}
