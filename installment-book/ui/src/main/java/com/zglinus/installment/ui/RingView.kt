package com.zglinus.installment.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MR

/** 顶栏小环：中心写百分比（已还比例） */
class RingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var fraction: Float = 0f
        set(value) { field = value.coerceIn(0f, 1f); invalidate() }

    private val d get() = resources.displayMetrics.density
    private val stroke get() = 5f * d

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * d
        isFakeBoldText = true
    }

    private var progress = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.animation.ValueAnimator.ofFloat(0f, fraction).apply {
            duration = 620
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a -> progress = a.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = (minOf(width, height) - stroke) / 2f
        val cx = width / 2f
        val cy = height / 2f
        val box = RectF(cx - r, cy - r, cx + r, cy + r)

        track.strokeWidth = stroke
        track.color = Ui.colorOf(context, MR.attr.colorOutlineVariant)
        canvas.drawArc(box, 0f, 360f, false, track)

        arc.strokeWidth = stroke
        arc.color = Ui.colorOf(context, MR.attr.colorPrimary)
        canvas.drawArc(box, -90f, 360f * progress, false, arc)

        text.color = Ui.colorOf(context, MR.attr.colorOnSurface)
        val label = "${(progress * 100).toInt()}%"
        canvas.drawText(label, cx, cy + text.textSize * 0.36f, text)
    }
}
