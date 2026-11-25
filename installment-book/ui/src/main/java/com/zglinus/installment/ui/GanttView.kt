package com.zglinus.installment.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.tan

/**
 * 分期甘特图（自绘，自带横向拖动，不依赖 HorizontalScrollView）。
 *
 * - 横轴：月份；每行一个项目；高度由外部用 LinearLayout.LayoutParams 显式给（EXACTLY 时照用）
 * - 条内文字靠左，右侧随条右边界裁掉；条滑出左边界时文字贴可见区左缘
 * - 已过去的那段：透明底 + 30° 斜纹（1 单位实色 / 1 单位透明）
 *
 * ⚠ 尺寸类字段一律用 `dp()`，而 `dp()` 必须走 getter 读 density。
 * 1.6.5 起曾把 `private val density = resources.displayMetrics.density` 写在
 * colWidth/rowHeight/headerHeight/barHeight 之后，Kotlin 按声明顺序初始化
 * → 这些字段全部拿到 float 默认值 0f，几何整体塌成 0：
 * 竖网格全落在 x=0、表头基线 = -8dp（画到画布上方）、条 rect 高度 0、
 * clipRect 空 → 除 drawColor 的底色外一个像素都画不出来，且不抛任何异常。
 */
class GanttView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Bar(val label: String, val startMonth: Int, val monthSpan: Int)

    /** 必须早于一切 dp() 调用即可用的 getter（不是字段，不受初始化顺序影响） */
    private val density: Float get() = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    /** 保留色相、替换 alpha（0..0xFF） */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha shl 24)

    // ── 尺寸（外部可调；改了就重新测量） ────────────────────────────────
    var colWidth: Float = dp(56f)
        set(v) { field = v.coerceAtLeast(dp(12f)); requestLayout(); invalidate() }

    var rowHeight: Float = dp(34f)
        set(v) { field = v.coerceAtLeast(dp(12f)); requestLayout(); invalidate() }

    var headerHeight: Float = dp(26f)
        set(v) { field = v.coerceAtLeast(dp(14f)); requestLayout(); invalidate() }

    var barHeight: Float = dp(20f)
        set(v) { field = v.coerceAtLeast(1f); invalidate() }

    var corner: Float = dp(8f)
        set(v) { field = v; invalidate() }

    /** 条内文字左右留白 */
    var textPaddingH: Float = dp(8f)
        set(v) { field = v; invalidate() }

    // ── 数据 ───────────────────────────────────────────────────────────
    var bars: List<Bar> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }

    var baseMonth: LocalDate = LocalDate.now().withDayOfMonth(1)
        set(v) { field = v; invalidate() }

    var monthCount: Int = 14
        set(v) { field = v.coerceAtLeast(1); requestLayout(); invalidate() }

    /** 当前月在时间轴上的偏移；< 0 表示不画历史斜纹 */
    /** 本月已经过完的比例（0..1），由调用方按今天的日期算好传进来 */
    var nowMonthFraction: Float = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }

    var nowMonthOffset: Int = -1
        set(v) { field = v; invalidate() }

    // ── 颜色 ───────────────────────────────────────────────────────────
    var barColor: Int = 0xFF2E4A8F.toInt()
    var textColorOnBar: Int = 0xFFFFFFFF.toInt()
    var headerTextColor: Int = 0xFF666666.toInt()
    var gridColor: Int = 0x22666666
    var bgColor: Int = 0x00000000
    /** 当前月所在列的高亮底色 */
    var monthHighlightColor: Int = 0x1F2E4A8F

    // ── 画笔 / 复用对象 ────────────────────────────────────────────────
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hlPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = dp(10f) }
    private val barTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = dp(11f); typeface = Typeface.DEFAULT_BOLD
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = max(1f, dp(1f))
    }
    private val rect = RectF()
    private val roundPath = Path()
    private val path = Path()

    // ── 横向偏移（自己算，不依赖 HorizontalScrollView） ─────────────────
    private var offsetX = 0f

    /** 布局前就调了 scrollToMonth 时先记下，等 onSizeChanged 再落位 */
    private var pendingOffsetMonth = -1
    private var pendingAnchorFrac = 0f

    private val contentW: Float get() = colWidth * monthCount
    private fun maxOffset(): Float = max(0f, contentW - width)
    private fun clampOffset(v: Float): Float = v.coerceIn(0f, maxOffset())

    /** 内容高度（外部可直接用；外部给了 EXACTLY 高度时以外部为准） */
    fun contentHeight(): Int =
        (headerHeight + rowHeight * max(bars.size, 1) + dp(8f)).toInt().coerceAtLeast(1)

    /** 滚到某个月（进页面时停在当前月）。width 还是 0 时延后到 onSizeChanged 执行 */
    fun scrollToMonth(m: Int) = scrollToMonthAt(m, 0f)

    /**
     * 让第 m 个月落在视口 [anchorFrac] 的位置（0 = 最左，1/3 = 左起三分之一）
     */
    fun scrollToMonthAt(m: Int, anchorFrac: Float) {
        if (width == 0) {
            pendingOffsetMonth = m
            pendingAnchorFrac = anchorFrac
            requestLayout()
            return
        }
        offsetX = clampOffset(m * colWidth - anchorFrac * width)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            // 父给了精确宽度（MATCH_PARENT / 固定 dp）就用它，绝不自己撑宽
            MeasureSpec.EXACTLY -> wSize
            MeasureSpec.AT_MOST -> min(contentW.toInt(), wSize).coerceAtLeast(1)
            else -> contentW.toInt().coerceAtLeast(1)
        }
        val hSize = MeasureSpec.getSize(heightMeasureSpec)
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            // 高度由外部显式给定（LinearLayout.LayoutParams 里写死的 dp）→ 照用
            MeasureSpec.EXACTLY -> hSize
            MeasureSpec.AT_MOST -> min(contentHeight(), hSize).coerceAtLeast(1)
            else -> contentHeight()
        }
        setMeasuredDimension(w.coerceAtLeast(1), h.coerceAtLeast(1))
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val m = pendingOffsetMonth
        if (m >= 0) {
            pendingOffsetMonth = -1
            offsetX = clampOffset(m * colWidth - pendingAnchorFrac * w)
        } else {
            offsetX = clampOffset(offsetX)
        }
    }

    // ── 横向拖动 ───────────────────────────────────────────────────────
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var dragging = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // 没得滑就完全不碰手势，让父级 ScrollView 正常工作
        if (maxOffset() <= 0f) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; lastX = event.x; dragging = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging) {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    // 横向位移过阈值且明显大于纵向，才认定是横滑，纵向留给 ScrollView
                    if (abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                        dragging = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        lastX = event.x
                    }
                    return true
                }
                offsetX = clampOffset(offsetX - (event.x - lastX))
                lastX = event.x
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                if (!wasDragging && event.actionMasked == MotionEvent.ACTION_UP) performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 底色：画在 translate 之前，所以它永远铺满控件 ——
        // 这正是「底色可见、内容全无」的诊断指纹：底色可见 ⇒ onDraw 在跑，问题在偏移或尺寸。
        // 背景内衬：半透明圆角矩形，跟卡片圆角一致，不再是一块方角阴影
        if (android.graphics.Color.alpha(bgColor) != 0) {
            bgPaint.color = bgColor
            canvas.drawRoundRect(0f, 0f, w, h, dp(16f), dp(16f), bgPaint)
        }

        canvas.save()
        canvas.clipRect(0f, 0f, w, h)          // 兜底：平移后也不许画到控件外
        canvas.translate(-offsetX, 0f)

        // ── 月份表头 + 竖网格 ──────────────────────────────────────────
        headerPaint.color = headerTextColor
        gridPaint.color = gridColor
        val headerBaseline = headerHeight - dp(8f)
        for (m in 0..monthCount) {
            val x = m * colWidth
            canvas.drawLine(x, 0f, x, h, gridPaint)
            if (m < monthCount) {
                val month = baseMonth.plusMonths(m.toLong())
                val label = "${month.year % 100}/${month.monthValue}"
                val tw = headerPaint.measureText(label)
                canvas.drawText(label, x + (colWidth - tw) / 2f, headerBaseline, headerPaint)
            }
        }
        canvas.drawLine(0f, headerHeight, max(contentW, w), headerHeight, gridPaint)

        // 当前月那一列高亮（画在条下面）
        if (nowMonthOffset in 0 until monthCount) {
            val x0 = nowMonthOffset * colWidth
            hlPaint.color = monthHighlightColor
            canvas.drawRect(x0, headerHeight, x0 + colWidth, h, hlPaint)
        }

        // ── 条 ────────────────────────────────────────────────────────
        val pad = textPaddingH
        val minW = dp(6f)
        val unit = dp(7f)
        val run = (barHeight * tan(Math.PI / 6.0)).toFloat()   // 30° 斜纹的横向跨度

        bars.forEachIndexed { i, b ->
            val top = headerHeight + i * rowHeight + (rowHeight - barHeight) / 2f
            val bottom = top + barHeight
            val left = b.startMonth * colWidth + dp(2f)
            val right = (b.startMonth + b.monthSpan.coerceAtLeast(1)) * colWidth - dp(2f)
            rect.set(left, top, max(left + minW, right), bottom)

            // 已过去那段的右边界 = 当前月线，夹在条范围内
            // 交界线按今天的日期在本月中的位置放，不是死位置
            val nowX = if (nowMonthOffset >= 0)
                (nowMonthOffset + nowMonthFraction) * colWidth else rect.left
            val pastRight = nowX.coerceIn(rect.left, rect.right)

            // 已过去：半透明底 + 实色斜纹，按条本身圆角裁剪（不再出现硬边矩形）
            if (pastRight > rect.left) {
                canvas.save()
                roundPath.reset()
                roundPath.addRoundRect(rect, corner, corner, Path.Direction.CW)
                canvas.clipPath(roundPath)

                barPaint.color = withAlpha(barColor, 0x59)   // 半透明底（阴影）
                canvas.drawRect(rect, barPaint)

                barPaint.color = barColor                    // 实色斜纹压在上面
                var x = rect.left - run
                while (x < pastRight) {
                    path.reset()
                    path.moveTo(x, rect.top)
                    path.lineTo(x + unit, rect.top)
                    path.lineTo(x + unit - run, rect.bottom)
                    path.lineTo(x - run, rect.bottom)
                    path.close()
                    canvas.drawPath(path, barPaint)
                    x += unit * 2
                }
                canvas.restore()
            }

            // 斜纹 → 实色 的过渡：在交界处铺一段 alpha 0→1 的渐变，避免硬切
            if (pastRight > rect.left && rect.right > pastRight) {
                val fadeW = dp(20f)
                val from = (pastRight - fadeW).coerceAtLeast(rect.left)
                if (pastRight > from) {
                    canvas.save()
                    roundPath.reset()
                    roundPath.addRoundRect(rect, corner, corner, Path.Direction.CW)
                    canvas.clipPath(roundPath)
                    barPaint.shader = android.graphics.LinearGradient(
                        from, 0f, pastRight, 0f,
                        withAlpha(barColor, 0x00), barColor,
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    canvas.drawRect(from, rect.top, pastRight, rect.bottom, barPaint)
                    barPaint.shader = null
                    canvas.restore()
                }
            }

            // 现在与未来：实色圆角条
            if (rect.right > pastRight) {
                barPaint.color = barColor
                canvas.save()
                if (pastRight > rect.left) {
                    canvas.clipRect(pastRight, rect.top, rect.right, rect.bottom)
                }
                canvas.drawRoundRect(rect, corner, corner, barPaint)
                canvas.restore()
            }

            // 条内文字：锚在条的左端；右侧被条右边界裁，左侧随条一起滑出可见区
            val visLeft = max(offsetX, rect.left)
            val visRight = min(offsetX + w, rect.right)
            if (visRight - visLeft > pad) {
                val y = rect.centerY() - (barTextPaint.descent() + barTextPaint.ascent()) / 2f
                barTextPaint.color = textColorOnBar
                val tw = barTextPaint.measureText(b.label)
                // 锚点是「可见区」的左缘：拖到哪，文字就从那个可见左缘开始
                // 可见区放不下整句时，右端与可见区右缘保持 pad
                var tx = visLeft + pad
                if (tx + tw > visRight - pad) tx = visRight - pad - tw
                canvas.save()
                canvas.clipRect(visLeft, rect.top, visRight, rect.bottom)
                canvas.drawText(b.label, tx, y, barTextPaint)
                canvas.restore()
            }
        }

        // 「今天」竖线：全图一根，位置 = 本月列内按当天日期的比例
        if (nowMonthOffset >= 0) {
            val todayX = (nowMonthOffset + nowMonthFraction) * colWidth
            linePaint.color = (barColor and 0x00FFFFFF) or 0x99000000.toInt()
            linePaint.strokeWidth = dp(2f)
            canvas.drawLine(todayX, headerHeight, todayX, h, linePaint)
        }

        canvas.restore()
    }
}
