package com.zglinus.playcheck.ui

import android.content.Context
import android.graphics.*
import android.view.View
import com.zglinus.playcheck.core.Span
import kotlin.math.max

/** 进度环（今日 2/3）。 */
class RingView(ctx: Context) : View(ctx) {
    var ratio: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var label: String = ""
        set(v) { field = v; invalidate() }
    var ringWidth: Float = 9f
        set(v) { field = v; fg.strokeWidth = context.dpf(v); bg.strokeWidth = context.dpf(v); invalidate() }
    var subColor: Int = T.ON_VAR
        set(v) { field = v; sub.color = v; invalidate() }
    var subLabel: String = "已打卡"
        set(v) { field = v; invalidate() }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dpf(9f); color = 0x22000000 }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = context.dpf(9f); strokeCap = Paint.Cap.ROUND; color = T.PRIMARY }
    private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = T.ON_SURFACE }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = T.ON_VAR }

    var textColor: Int = T.ON_SURFACE
        set(v) { field = v; txt.color = v; invalidate() }

    fun setRingColors(ring: Int, track: Int) {
        fg.color = ring; bg.color = track; invalidate()
    }

    override fun onMeasure(w: Int, h: Int) {
        val size = resolveSize(context.dp(96f), w).coerceAtMost(context.dp(120f))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val r = max(1f, minOf(width, height) / 2f - strokePad())
        val cx = width / 2f; val cy = height / 2f
        canvas.drawCircle(cx, cy, r, bg)
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(rect, -90f, 360f * ratio, false, fg)
        txt.textSize = context.dpf(19f)
        txt.typeface = Typeface.DEFAULT_BOLD
        canvas.drawText(label, cx, cy, txt)
        sub.textSize = context.dpf(9.5f)
        canvas.drawText(subLabel, cx, cy + context.dpf(14f), sub)
    }

    private fun strokePad() = context.dpf(6f)
}

/** 近 7 天柱状图。 */
class WeekBars(ctx: Context) : View(ctx) {
    var values: List<Long> = emptyList()
        set(v) { field = v; invalidate() }
    var labels: List<String> = listOf("三", "四", "五", "六", "日", "一", "二")
    var todayIndex: Int = -1
    /** true 时用灰色系（预估这类参考数据）。 */
    var gray: Boolean = false
    /** 从该下标起改用灰色（用于"近 7 天 + 预估"合并成 8 根柱）。 */
    var grayFrom: Int = -1
    /** 最后一根柱单独指定颜色（0 表示不指定）。 */
    var lastColor: Int = 0

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(if (w == 0) context.dp(300f) else w, context.dp(78f))
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val maxV = max(1L, values.maxOrNull() ?: 1L)
        val gap = context.dpf(9f)
        val bw = (width - gap * (values.size - 1)) / values.size
        val base = height - context.dpf(18f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        values.forEachIndexed { i, v ->
            val empty = v <= 0L
            val hh = if (empty) context.dpf(2f)
            else (base * (v.toFloat() / maxV)).coerceAtLeast(context.dpf(4f))
            val isLast = i == values.size - 1
            p.color = when {
                empty && !isLast -> 0xFFE0E5DF.toInt()
                isLast && lastColor != 0 -> lastColor
                gray || (grayFrom >= 0 && i >= grayFrom) ->
                    if (i == todayIndex) 0xFF8A948D.toInt() else 0xFFCBD1CB.toInt()
                i == todayIndex -> T.PRIMARY
                else -> T.PRIMARY_C
            }
            val left = i * (bw + gap)
            canvas.drawRoundRect(left, base - hh, left + bw, base, context.dpf(5f), context.dpf(5f), p)
            p.color = if (i == todayIndex) T.PRIMARY else T.ON_VAR
            p.textSize = context.dpf(9.5f)
            p.textAlign = Paint.Align.CENTER
            canvas.drawText(labels.getOrElse(i) { "" }, left + bw / 2f, base + context.dpf(13f), p)
        }
    }
}

/** 打卡日历热力图（5 周 × 7 天）。 */
class Heatmap(ctx: Context) : View(ctx) {
    var levels: List<Int> = List(35) { 0 }   // 0..3，-1 表示未来
        set(v) { field = v; invalidate() }
    var todayIndex: Int = -1

    override fun onMeasure(w: Int, h: Int) {
        val width = if (w == 0) context.dp(300f) else resolveSize(context.dp(300f), w)
        val gap = context.dpf(6f)
        val cell = (width - gap * 6) / 7f
        val total = context.dpf(16f) + 5 * (cell + gap) + context.dpf(6f)
        setMeasuredDimension(width, total.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val head = context.dpf(16f)
        val gap = context.dpf(6f)
        val cell = ((width - gap * 6) / 7).toFloat()
        val colors = intArrayOf(0xFFD8DFD7.toInt(), 0xFFE0F1E6.toInt(), 0xFFA8D8BE.toInt(), T.PRIMARY)
        p.textSize = context.dpf(9.5f); p.textAlign = Paint.Align.CENTER; p.color = T.ON_VAR
        listOf("一", "二", "三", "四", "五", "六", "日").forEachIndexed { i, s2 ->
            canvas.drawText(s2, i * (cell + gap) + cell / 2f, context.dpf(10f), p)
        }
        levels.forEachIndexed { i, lv ->
            val r = i / 7; val c = i % 7
            val l = c * (cell + gap); val t = head + r * (cell + gap)
            if (lv < 0) {
                p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1f); p.color = T.OUTLINE_V
                canvas.drawRoundRect(l, t, l + cell, t + cell, context.dpf(7f), context.dpf(7f), p)
                p.style = Paint.Style.FILL
            } else {
                p.color = colors[lv.coerceIn(0, 3)]
                canvas.drawRoundRect(l, t, l + cell, t + cell, context.dpf(7f), context.dpf(7f), p)
            }
            if (i == todayIndex) {
                // 描边往里收：外扩会在最右列/最下行被裁掉半条线
                val inset = context.dpf(0.8f)
                p.style = Paint.Style.STROKE; p.strokeWidth = context.dpf(1.6f); p.color = T.PRIMARY
                canvas.drawRoundRect(l + inset, t + inset, l + cell - inset, t + cell - inset, context.dpf(6f), context.dpf(6f), p)
                p.style = Paint.Style.FILL
            }
        }
    }
}

/** 今日时间轴：按区间画段。 */
class Timeline(ctx: Context) : View(ctx) {
    var spans: List<Span> = emptyList()
        set(v) { field = v; invalidate() }
    var windowStart: Long = 0
    var windowEnd: Long = 1

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(if (w == 0) context.dp(300f) else w, context.dp(40f))
    }

    override fun onDraw(canvas: Canvas) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = T.SURF_HIGH
        canvas.drawRoundRect(0f, context.dpf(10f), width.toFloat(), context.dpf(20f), context.dpf(5f), context.dpf(5f), p)

        // 只玩了几分钟的话，按 24 小时铺开就是一根看不见的细线：
        // 有记录时缩放到「这段会话」的视野，最少 30 分钟窗口。
        var from = windowStart
        var to = windowEnd
        if (spans.isNotEmpty()) {
            val first = spans.minOf { it.start }
            val last = spans.maxOf { it.end }
            val pad = maxOf((last - first) / 6, 5 * 60_000L)
            from = first - pad
            to = last + pad
            if (to - from < 30 * 60_000L) {
                val mid = (from + to) / 2
                from = mid - 15 * 60_000L
                to = mid + 15 * 60_000L
            }
        }

        p.color = T.PRIMARY
        val span = (to - from).coerceAtLeast(1L).toFloat()
        spans.forEach { s ->
            val l = (s.start - from) / span * width
            // 最短给 4dp，否则几分钟的会话看不见
            val r = maxOf((s.end - from) / span * width, l + context.dpf(4f))
            canvas.drawRoundRect(l, context.dpf(10f), r, context.dpf(20f), context.dpf(5f), context.dpf(5f), p)
        }
    }
}

/**
 * 24 小时 × 每 10 分钟一格的当日热力图：24 列（小时）× 6 行（10 分钟档），共 144 格。
 * 颜色深浅 = 该 10 分钟内有前台记录的比例。
 */
class DayHeatmap(ctx: Context) : View(ctx) {
    /** 144 个格子，index = hour * 6 + 10 分钟档 */
    var levels: IntArray = IntArray(144)
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val width = if (w == 0) context.dp(320f) else w
        val gap = context.dpf(1.5f)
        val cell = (width - gap * 23) / 24f
        setMeasuredDimension(width, (cell * 6 + gap * 5 + context.dpf(14f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        if (levels.size < 144) return
        val gap = context.dpf(1.5f)
        val cell = (width - gap * 23) / 24f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val colors = intArrayOf(0xFFE7ECE7.toInt(), 0xFFCFE9DA.toInt(), 0xFF8FD4B0.toInt(), T.PRIMARY)
        for (h in 0 until 24) {
            for (b in 0 until 6) {
                val lv = levels[h * 6 + b].coerceIn(0, 3)
                p.color = colors[lv]
                val l = h * (cell + gap)
                val t = b * (cell + gap)
                canvas.drawRoundRect(l, t, l + cell, t + cell, cell * 0.22f, cell * 0.22f, p)
            }
        }
        // 每 6 小时一个刻度
        p.color = T.ON_VAR
        p.textSize = context.dpf(9f)
        p.textAlign = Paint.Align.LEFT
        listOf(0 to "0", 6 to "6", 12 to "12", 18 to "18").forEach { (h, label) ->
            val x = h * (cell + gap)
            canvas.drawText(label, x.coerceAtMost(width - context.dpf(12f)), height - context.dpf(2f), p)
        }
    }
}

/**
 * 当日 24 小时 × 10 分钟（144 格）热力图，每格颜色由调用方按“各游戏占比混色”算好传进来。
 */
class GameDayHeatmap(ctx: Context) : View(ctx) {
    var cellColors: IntArray = IntArray(144)
        set(v) { field = v; invalidate() }

    override fun onMeasure(w: Int, h: Int) {
        val width = if (w == 0) context.dp(320f) else w
        val gap = context.dpf(1.5f)
        val cell = (width - gap * 23) / 24f
        setMeasuredDimension(width, (cell * 6 + gap * 5 + context.dpf(14f)).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val gap = context.dpf(1.5f)
        val cell = (width - gap * 23) / 24f
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (h in 0 until 24) {
            for (b in 0 until 6) {
                p.color = cellColors[h * 6 + b]
                val l = h * (cell + gap)
                val t = b * (cell + gap)
                canvas.drawRoundRect(l, t, l + cell, t + cell, cell * 0.22f, cell * 0.22f, p)
            }
        }
        p.color = T.ON_VAR
        p.textSize = context.dpf(9f)
        p.textAlign = Paint.Align.LEFT
        listOf(0 to "0", 6 to "6", 12 to "12", 18 to "18").forEach { (h, label) ->
            val x = h * (cell + gap)
            canvas.drawText(label, x.coerceAtMost(width - context.dpf(12f)), height - context.dpf(2f), p)
        }
    }
}
