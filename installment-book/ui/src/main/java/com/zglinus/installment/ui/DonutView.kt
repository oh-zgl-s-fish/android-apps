package com.zglinus.installment.ui

import com.zglinus.installment.core.*
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MR

/** 圆角分段环形：段间留缝，环心写合计 */
class DonutView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Seg(val value: Double, val color: Int)

    var segments: List<Seg> = emptyList()
    var centerTop: String = ""
    var centerBottom: String = ""

    private var progress = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 680
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a -> progress = a.animatedValue as Float; invalidate() }
            start()
        }
    }

    private val strokeW: Float get() = 16f * resources.displayMetrics.density

    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 半径要扣掉描边一半，否则环外侧会被画布裁成圆角方块
        val pad = strokeW / 2f + dp(1f)
        val size = (minOf(width, height).toFloat() - pad * 2)
        val r = size / 2f
        val cx = width / 2f
        val cy = height / 2f
        val box = RectF(cx - r, cy - r, cx + r, cy + r)

        val total = segments.sumOf { it.value }.takeIf { it > 0 } ?: return
        // 段间空隙：视觉 8dp，换算成角度
        // 圆头会向两侧各外伸 strokeW/2，可见缝 = 设定缝 - strokeW
        val gapPx = strokeW + dp(2.5f)
        val gapDeg = (gapPx / (2 * Math.PI * r) * 360).toFloat().coerceIn(2f, 30f)
        var start = -90f
        segments.forEach { seg ->
            val sweep = (seg.value / total * 360f).toFloat()
            ring.color = seg.color
            ring.strokeWidth = strokeW
            val from = start + gapDeg / 2f
            val to = start + sweep - gapDeg / 2f
            canvas.drawArc(box, from, ((to - from).coerceAtLeast(1f)) * progress, false, ring)
            start += sweep
        }

        textPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurface)
        textPaint.textSize = dp(14f).toFloat()
        canvas.drawText(centerTop, cx, cy + dp(2f), textPaint)
        subPaint.color = themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant)
        subPaint.textSize = dp(9.5f).toFloat()
        canvas.drawText(centerBottom, cx, cy + dp(14f), subPaint)
    }

    private fun themeColor(attr: Int): Int = Ui.colorOf(context, attr)

    private fun dp(v: Float): Float = v * resources.displayMetrics.density
}
