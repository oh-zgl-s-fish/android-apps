package com.zglinus.installment.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.google.android.material.R as MR

/**
 * 可支配走势：2dp 折线 + 起/峰/终 三个标签。
 * 标签自带底板（卡片底色），所以会被文字「截断」，和设计稿一致。
 */
class TrendView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var points: List<Pair<String, Double>> = emptyList()

    private var reveal = 0f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 760
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a -> reveal = a.animatedValue as Float; invalidate() }
            start()
        }
    }

    private val d get() = resources.displayMetrics.density

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * d
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        pathEffect = DashPathEffect(floatArrayOf(3f * d, 3f * d), 0f)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9.5f * d
        isSubpixelText = true
    }
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val data = points
        if (data.size < 2) return

        val padL = 8f * d
        val padR = 8f * d
        val padT = 18f * d
        val padB = 22f * d
        val w = width - padL - padR
        val h = height - padT - padB

        val max = data.maxOf { it.second }
        val min = data.minOf { it.second }
        val span = (max - min).takeIf { it > 0.0 } ?: 1.0

        fun x(i: Int) = padL + w * i / (data.size - 1).toFloat()
        fun y(v: Double) = padT + (h * (1 - (v - min) / span)).toFloat()

        val peakIdx = data.indexOfFirst { it.second == max }
        val minIdx = data.indexOfFirst { it.second == min }
        val primary = Ui.colorOf(context, MR.attr.colorPrimary)
        val outline = Ui.colorOf(context, MR.attr.colorOutlineVariant)
        val onVar = Ui.colorOf(context, MR.attr.colorOnSurfaceVariant)
        val plateColor = Ui.colorOf(context, MR.attr.colorSurfaceVariant)

        // 最高 / 最低虚线（只画绘图区）
        dash.color = outline
        canvas.drawLine(padL, y(max), padL + w, y(max), dash)
        canvas.drawLine(padL, y(min), padL + w, y(min), dash)

        canvas.save()
        canvas.clipRect(0f, 0f, padL + w * reveal, height.toFloat())
        // 折线
        val path = Path()
        data.forEachIndexed { i, (_, v) ->
            if (i == 0) path.moveTo(x(0), y(v)) else path.lineTo(x(i), y(v))
        }
        line.color = primary
        canvas.drawPath(path, line)

        // 拐点
        dot.style = Paint.Style.FILL
        dot.color = primary
        for (i in 1 until data.size) {
            if (data[i].second != data[i - 1].second) canvas.drawCircle(x(i), y(data[i].second), 2.6f * d, dot)
        }

        // 起点空心
        dot.style = Paint.Style.STROKE
        dot.strokeWidth = 1.8f * d
        dot.color = primary
        canvas.drawCircle(x(0), y(data.first().second), 3.2f * d, dot)
        // 终点实心
        dot.style = Paint.Style.FILL
        canvas.drawCircle(x(data.size - 1), y(data.last().second), 3.2f * d, dot)

        // 峰值实心 + 白边
        canvas.drawCircle(x(peakIdx), y(max), 3.4f * d, dot)
        dot.style = Paint.Style.STROKE
        dot.strokeWidth = 1.6f * d
        dot.color = plateColor
        canvas.drawCircle(x(peakIdx), y(max), 3.4f * d, dot)

        canvas.restore()
        // 标签：起 / 峰 / 谷，重叠时合并成一个
        label.color = primary
        plate.color = plateColor

        fun tag(text: String, cx: Float, cy: Float, align: Float) {
            val tw = label.measureText(text)
            val th = label.textSize
            val padH = 3f * d
            val padV = 1.5f * d
            val left = when {
                align < 0f -> cx - tw - padH
                align > 0f -> cx
                else -> cx - tw / 2 - padH
            }
            canvas.drawRoundRect(
                RectF(left, cy - th + 1f * d, left + tw + padH * 2, cy + padV + 2f * d),
                3f * d, 3f * d, plate
            )
            label.textAlign = Paint.Align.LEFT
            canvas.drawText(text, left + padH, cy, label)
        }

        val lastIdx = data.size - 1
        val lastIsMin = minIdx == lastIdx
        val lastIsPeak = peakIdx == lastIdx

        // 每个索引上要写哪些标记，避免两点重合时叠字
        val marks = LinkedHashMap<Int, MutableList<String>>()
        fun mark(idx: Int, name: String) {
            marks.getOrPut(idx) { mutableListOf() }.add(name)
        }
        mark(0, "起")
        mark(peakIdx, "峰")
        mark(minIdx, "谷")
        mark(lastIdx, "终")

        marks.forEach { (idx, names) ->
            val v = data[idx].second
            val text = names.joinToString("/") + " " + Ui.amount(v)
            val above = (idx == peakIdx) || (idx == 0)
            val cy = if (above) y(v) - 7f * d else y(v) + 13f * d
            val align = when {
                idx == 0 -> 1f
                idx == lastIdx -> -1f
                else -> 0f
            }
            tag(text, x(idx), cy, align)
        }

        // 月份刻度
        label.color = onVar
        val ticks = listOf(0, 3, 6, 9, data.size - 1).distinct()
        ticks.forEachIndexed { k, i ->
            val t = data[i].first
            label.textAlign = when (k) {
                0 -> Paint.Align.LEFT
                ticks.size - 1 -> Paint.Align.RIGHT
                else -> Paint.Align.CENTER
            }
            val tx = when (k) {
                0 -> padL
                ticks.size - 1 -> padL + w
                else -> x(i)
            }
            canvas.drawText(t, tx, height - 6f * d, label)
        }
    }
}
