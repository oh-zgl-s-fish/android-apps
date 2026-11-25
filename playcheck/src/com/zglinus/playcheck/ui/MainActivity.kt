package com.zglinus.playcheck.ui

import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.zglinus.playcheck.R
import com.zglinus.playcheck.core.*
import com.zglinus.playcheck.data.Store
import com.zglinus.playcheck.engine.Aggregator
import com.zglinus.playcheck.engine.KeepAlive
import com.zglinus.playcheck.engine.Notifier
import com.zglinus.playcheck.engine.Schedule
import com.zglinus.playcheck.platform.AndroidUsage

/** 首页：今日打卡。统计、外观、设置是独立 Activity，从首页按钮进入。 */
class MainActivity : BaseActivity() {

    /** 0 = 时长卡，1 = 今日热力图（右滑切换）。 */
    private var heroPage = 0
    /** 当前筛选行实例，点筛选时只换这一行。 */
    private var chipHost: LinearLayout? = null
    /** 时长卡里随筛选变化的两个数字：环里的 x/y 和“N 个游戏在用”。 */
    private var heroRing: View? = null
    private var heroSub: android.widget.TextView? = null

    override val hasSticky: Boolean get() = true

    override fun buildSticky(): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val rules = Store.rules()
        val streak = runCatching { Store.streak(System.currentTimeMillis(), rules.refreshHour) }.getOrDefault(0)
        val done = verdicts.count { it.done }
        val total = verdicts.sumOf { it.totalMs }
        val threshold = rules.thresholdMin * 60_000L

        box.addView(homeHeader())
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, dp(10f))
        }
        top.addView(label("${dateMeta()} · 每日 ${rules.refreshHour.toString().padStart(2, '0')}:00 刷新", 11f, onWallDim()),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(pill28("🔥 连续 $streak 天", 0xFFFFE6B8.toInt(), 0xFF7A5200.toInt()))
        box.addView(top)
        // 两张卡叠放，拖动时同步平移（中间留 10dp 间隙），35% 宽度松手翻页
        val heroHost = FrameLayout(this).apply { clipChildren = true; clipToPadding = false }
        // 先按角色造两张卡，再按当前页决定叠放顺序；滑动逻辑只认角色，不认实例
        val durationCard = heroGradient(done, verdicts.size, total, threshold, heroHost)
        val heatCard = heroHeat(heroHost)
        val a = if (heroPage == 0) durationCard else heatCard
        val b = if (heroPage == 0) heatCard else durationCard
        heroHost.addView(a, FrameLayout.LayoutParams(MATCH, MATCH))
        heroHost.addView(b, FrameLayout.LayoutParams(MATCH, MATCH))
        val gap = dp(10f)
        // 重建时先把另一张藏起来，等摆好位置再显示，否则会有一帧两张叠在一起（点筛选就闪一下）
        b.visibility = View.INVISIBLE
        heroHost.post {
            val full = (heroHost.width + gap).toFloat()
            // 热力图常驻右侧：page 0 时停在 +full，翻过去才归零
            b.translationX = if (heroPage == 0) full else -full
            a.translationX = 0f
            b.visibility = View.VISIBLE
        }
        attachHeroSwipe(heroHost, durationCard, heatCard, gap)
        box.addView(heroHost, LinearLayout.LayoutParams(MATCH, WRAP))
        val chips = chipRow()
        chipHost = chips
        box.addView(chips, LinearLayout.LayoutParams(MATCH, WRAP).apply {
            topMargin = dp(14f); bottomMargin = dp(6f)
        })
        return box
    }

    override fun buildScreen(): View = screenToday()

// ---------------- 今日 ----------------

    private fun screenToday(): LinearLayout {
        val box = column()
        val rules = Store.rules()
        val streak = runCatching { Store.streak(System.currentTimeMillis(), rules.refreshHour) }.getOrDefault(0)
        val done = verdicts.count { it.done }
        val total = verdicts.sumOf { it.totalMs }
        val threshold = rules.thresholdMin * 60_000L

        box.addView(gameList(), LinearLayout.LayoutParams(MATCH, WRAP))
        // 说明栏已按要求去掉

        val missing = verdicts.filter { !it.done }
        if (missing.isNotEmpty() && isLate()) box.addView(manualCard(missing.first()))

        box.addView(weekCard())
        box.addView(serviceLine())
        return box
    }

    /** 首屏时长卡：薄荷→浅蓝渐变，42sp 数字，104dp 粗环（设计稿 01）。 */
    private fun heroGradient(done: Int, total: Int, totalMs: Long, threshold: Long, anchor: View? = null): View {
        val glassy = Wallpaper.has() && Store.appearance().glass
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (!glassy) {
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(0xFFC9F2DB.toInt(), 0xFFBFE9FB.toInt())
                ).apply { cornerRadius = dpf(28f) }
            }
            setPadding(dp(18f), dp(22f), dp(18f), dp(20f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        if (glassy) {
            card.background = glassBg(card, 28f, anchor)
        }
        val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        left.addView(label("今日游戏时长", 11.5f, 0xFF1F5B42.toInt(), true))
        left.addView(timeBig(totalMs, 42f, 0xFF0F1418.toInt()))
        val sub = label(
            "首开 ${verdicts.mapNotNull { it.firstOpen }.minOrNull()?.let { Fmt.hhmm(it) } ?: "--:--"} · $total 个游戏在用",
            11.5f, 0xFF3C5A4B.toInt()
        ).apply { setPadding(0, dp(5f), 0, 0) }
        heroSub = sub
        left.addView(sub)
        card.addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(10f) })
        val ring = RingView(this).apply {
            ratio = if (total == 0) 0f else done.toFloat() / total
            label = "$done/$total"
            subLabel = "已打卡"
            ringWidth = 12f
            setRingColors(T.PRIMARY, 0x59FFFFFF)
            textColor = 0xFF0F1418.toInt()
            subColor = 0xFF2C5A45.toInt()
        }
        heroRing = ring
        card.addView(ring, LinearLayout.LayoutParams(dp(104f), dp(104f)))
        return card
    }

    /** 只替换筛选行本身，时长卡/热力图不重建（重建会重新取景，闪一下）。 */
    private fun refreshChips() {
        val old = chipHost ?: return
        // 筛选行的父容器是 buildSticky() 返回的那个 box，不是 stickyHost 本身；
        // 之前查 stickyHost.indexOfChild(old) 恒为 -1 → 静默返回 → 高亮永远停在“全部”。
        val parent = old.parent as? android.view.ViewGroup ?: return
        val idx = parent.indexOfChild(old)
        if (idx < 0) return
        val lp = old.layoutParams
        val row = chipRow()
        parent.removeViewAt(idx)
        parent.addView(row, idx, lp)
        chipHost = row
    }

    /** 筛选变了：只改时长卡里的两个数字，卡本身不重建。 */
    private fun updateHeroNumbers() {
        val opened = verdicts.count { it.done }
        val total = verdicts.size
        (heroRing as? RingView)?.let {
            it.ratio = if (total == 0) 0f else opened.toFloat() / total
            it.label = "$opened/$total"
            it.invalidate()
        }
        heroSub?.text = "首开 ${verdicts.mapNotNull { it.firstOpen }.minOrNull()?.let { Fmt.hhmm(it) } ?: "--:--"} · " +
            "$total 个游戏在用"
    }

    /** 只重建下方的列表，顶部时长卡/热力图保持原样（重建会重新取景，闪一下）。 */
    private fun refreshList() {
        content.removeAllViews()
        content.addView(buildScreen(), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun chipRow(): LinearLayout {
        val opened = verdicts.count { it.done }
        val short = verdicts.count { it.state == State.RUNNING }
        val none = verdicts.count { it.state == State.NOT_OPENED }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(
            Triple("全部", verdicts.size, 0),
            Triple("已打卡", opened, 1),
            Triple("时间不够", short, 2),
            Triple("没打开", none, 3)
        ).forEach { (t, n, i) ->
            val on = filter == i
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8f), 0, dp(5f), 0)
                background = if (Wallpaper.has() && Store.appearance().glass) {
                    val g = glassBgChip(this)
                    if (on) {
                        // 选中态要看得出来：玻璃上再叠一层薄荷色
                        android.graphics.drawable.LayerDrawable(arrayOf(
                            g,
                            GradientDrawable().apply {
                                cornerRadius = dpf(999f); setColor(0x5520A06B)
                            }
                        ))
                    } else g
                } else {
                    GradientDrawable().apply {
                        cornerRadius = dpf(999f)
                        setColor(if (on) 0xFFDDEFE2.toInt() else Color.TRANSPARENT)
                        if (!on) setStroke(dp(1f), T.OUTLINE_V)
                    }
                }
                setOnClickListener { filter = i; refreshChips(); updateHeroNumbers(); refreshList() }
            }
            pill.addView(label(t, 10.5f, if (on) T.PRIMARY else onWallText(), on))
            pill.addView(label(n.toString(), 9.5f, if (on) T.PRIMARY else onWallDim(), true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(if (on) 0x332C6A4E else 0x14000000)
                }
                layoutParams = LinearLayout.LayoutParams(dp(16f), dp(16f)).apply { leftMargin = dp(4f) }
            })
            row.addView(pill, LinearLayout.LayoutParams(WRAP, dp(28f)).apply { rightMargin = dp(6f) })
        }
        return row
    }

    /**
     * 跟手拖动：两页同步平移，中间留 gap；横移超过 35% 宽度松手翻页。
     * 翻页完成后只重排两张卡的位置，不重建视图——重建会导致切换瞬间闪一下。
     */
    private fun attachHeroSwipe(host: View, duration: View, heat: View, gap: Int) {
        var startX = 0f
        var dragging = false
        val full = { (host.width + gap).toFloat() }

        // 几何：时长卡在 page 0、热力图停在它右边（+full）。左划 = 把时长卡推出去、热力图从右边进来。
        // 按“角色”而不是“实例”摆位：render 重建时 a/b 的实例顺序会随 heroPage 变，旧写法会摆反。
        fun place(page: Int) {
            duration.translationX = if (page == 0) 0f else -full()
            heat.translationX = if (page == 0) full() else 0f
            duration.alpha = 1f
            heat.alpha = 1f
            // 离屏那张直接隐藏：两张半透明玻璃叠在一起会闪
            duration.visibility = if (page == 0) View.VISIBLE else View.INVISIBLE
            heat.visibility = if (page == 1) View.VISIBLE else View.INVISIBLE
        }

        host.setOnTouchListener { v, e ->
            val current = if (heroPage == 0) duration else heat
            val other = if (heroPage == 0) heat else duration
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = e.rawX; dragging = false
                    duration.visibility = View.VISIBLE
                    heat.visibility = View.VISIBLE
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - startX
                    if (kotlin.math.abs(dx) > dp(8f)) dragging = true
                    if (dragging) {
                        current.translationX = dx.coerceIn(-v.width.toFloat(), v.width.toFloat())
                        other.translationX = current.translationX +
                            (if (heroPage == 0) full() else -full())
                        // 硬件加速下改 translation 只重放显示列表，不会重新执行 GlassBg.draw()；
                        // 不主动 invalidate，拖动全程玻璃取景是冻的（松手动画因为有 invalidate 才是动的）
                        current.invalidate()
                        other.invalidate()
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    val threshold = v.width * 0.35f
                    val dx = current.translationX
                    val next = when {
                        heroPage == 0 && dx < -threshold -> 1
                        heroPage == 1 && dx > threshold -> 0
                        else -> heroPage
                    }
                    if (next != heroPage) {
                        // 翻页：0->1 时长卡出左、热力图归位；1->0 热力图出右、时长卡归位
                        if (next == 1) slideTo(listOf(duration, heat), listOf(-full(), 0f), 180) {
                            heroPage = 1; place(1)
                        } else slideTo(listOf(heat, duration), listOf(full(), 0f), 180) {
                            heroPage = 0; place(0)
                        }
                    } else {
                        // 没到阈值：各自弹回
                        if (heroPage == 0) slideTo(listOf(duration, heat), listOf(0f, full()), 160)
                        else slideTo(listOf(heat, duration), listOf(0f, -full()), 160)
                    }
                    true
                }
                else -> false
            }
        }
    }

    /** 用 ValueAnimator 驱动位移，每帧同步重绘两张卡（玻璃要跟着平移重新取景）。 */
    private fun slideTo(views: List<View>, targets: List<Float>, duration: Long, end: (() -> Unit)? = null) {
        val starts = views.map { it.translationX }
        val anim = android.animation.ValueAnimator.ofFloat(0f, 1f)
        anim.duration = duration
        anim.interpolator = android.view.animation.DecelerateInterpolator()
        anim.addUpdateListener {
            val f = it.animatedFraction
            views.forEachIndexed { i, v ->
                v.translationX = starts[i] + (targets[i] - starts[i]) * f
                v.invalidate()
            }
        }
        anim.addListener(object : android.animation.AnimatorListenerAdapter() {
            override fun onAnimationEnd(a: android.animation.Animator) { end?.invoke() }
        })
        anim.start()
    }

    /** 今日时间分布卡：24×10 分钟热力图，格子颜色按各游戏占用时长比例混色。 */
    private fun heroHeat(anchor: View? = null): View {
        val glassy = Wallpaper.has() && Store.appearance().glass
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
            if (!glassy) background = GradientDrawable().apply {
                cornerRadius = dpf(28f); setColor(T.SURF_C)
            }
        }
        // 取景锚点用 heroHost：和时长卡同一条路径，平移时靠“锚点位置+自身偏移”取景
        if (glassy) card.background = glassBg(card, 28f, anchor)

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        head.addView(label("今日时间分布 · 10 分钟/格", 11.5f, 0xFF1F5B42.toInt(), true),
            LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(label("← 时长", 10.5f, T.ON_VAR))
        card.addView(head)

        card.addView(GameDayHeatmap(this).apply {
            cellColors = mixedCellColors()
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })

        val totalMs = verdicts.sumOf { it.totalMs }
        card.addView(label("共 ${Fmt.duration(totalMs)} · ${verdicts.count { it.totalMs > 0 }} 个游戏有过前台",
            10.5f, T.ON_VAR).apply { setPadding(0, dp(6f), 0, 0) })
        return card
    }

    /** 144 格颜色：同格有多个游戏时按占用时长比例混色。 */
    private fun mixedCellColors(): IntArray {
        val out = IntArray(144) { 0xFFE7ECE7.toInt() }
        val rules = Store.rules()
        val now = System.currentTimeMillis()
        val c = java.util.Calendar.getInstance().apply {
            timeInMillis = now
            set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
        }
        val dayStart = c.timeInMillis
        val dayEnd = dayStart + 86_400_000L
        val keyA = DayClock.dayKey(dayStart + 60_000L, rules.refreshHour)
        val keyB = DayClock.dayKey(dayEnd - 60_000L, rules.refreshHour)
        val mapA = Store.day(keyA)
        val mapB = Store.day(keyB)

        val perGame = HashMap<String, List<Span>>()
        verdicts.forEach { v ->
            val spans = ArrayList<Span>()
            mapA[v.game.pkg]?.spans?.forEach { spans.add(it) }
            mapB[v.game.pkg]?.spans?.forEach { spans.add(it) }
            if (spans.isNotEmpty()) perGame[v.game.pkg] = spans
        }

        val bucket = 10 * 60_000L
        for (b in 0 until 144) {
            val from = dayStart + b * bucket
            val to = from + bucket
            var r = 0.0; var g = 0.0; var bl = 0.0; var w = 0.0
            perGame.forEach { (pkg, spans) ->
                val overlap = spans.sumOf { maxOf(0L, minOf(it.end, to) - maxOf(it.start, from)) }
                if (overlap <= 0L) return@forEach
                val color = IconColors.of(this, pkg)
                r += android.graphics.Color.red(color) * overlap
                g += android.graphics.Color.green(color) * overlap
                bl += android.graphics.Color.blue(color) * overlap
                w += overlap
            }
            if (w > 0) {
                val mix = android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (bl / w).toInt())
                // 覆盖不满一格时向浅色靠，保持“深浅=停留比例”的读法
                val coverage = (w / bucket).coerceIn(0.0, 1.0).toFloat()
                out[b] = blend(mix, 0xFFF3F6F3.toInt(), 1f - (0.35f + 0.65f * coverage))
            }
        }
        return out
    }

    private fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        return android.graphics.Color.rgb(
            (android.graphics.Color.red(a) * (1 - k) + android.graphics.Color.red(b) * k).toInt(),
            (android.graphics.Color.green(a) * (1 - k) + android.graphics.Color.green(b) * k).toInt(),
            (android.graphics.Color.blue(a) * (1 - k) + android.graphics.Color.blue(b) * k).toInt()
        )
    }

    private fun gameList(): View {
        val shown = verdicts.filter {
            when (filter) {
                1 -> it.done
                2 -> it.state == State.RUNNING
                3 -> it.state == State.NOT_OPENED
                else -> true
            }
        }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (shown.isEmpty()) list.addView(label("还没有游戏，去设置里加一个", 11f, T.ON_VAR))
        // 右列必须整列同宽：逐行按各自文案算，就会出现「未打卡 / 已打卡 / 手动补卡」三行三宽。
        // 宽度按「全部游戏」里最宽的那一行算，切筛选时列宽也不会跳。
        val blockW = statusBlockWidth()
        shown.forEach { v -> list.addView(gameRow(v, blockW)) }
        return list
    }

    /** 用 TextView 自己的排版引擎量宽度（paint.measureText 会低估 CJK 回退字形），并按状态色的 padding 补足。 */
    private fun statusBlockWidth(): Int {
        var w = 0
        for (v in verdicts) {
            val st = statusOf(v)
            w = maxOf(w, textWidthAt(st.text, 11.5f, true) + dp(24f), textWidthAt(st.hint, 10.5f, false))
        }
        return if (w > 0) w else dp(72f)
    }

    private fun textWidthAt(t: String, size: Float, bold: Boolean): Int {
        val probe = TextView(this).apply {
            text = t
            textSize = size
            includeFontPadding = false
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }
        val spec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        probe.measure(spec, spec)
        return probe.measuredWidth
    }

    private fun gameRow(v: Verdict, blockW: Int): View {
        // 只有“点一下”才进二级。普通 View 挂 OnClickListener 后，横向拖动松手同样会触发 click
        // （View 本身不因横向位移取消点击，只有父容器拦截才会），所以这里自己记位移：
        // 超过 touchSlop 就判成滑动，不当点击。touch 监听返回 false，滚动与点击都不受影响。
        val slop = android.view.ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var moved = false
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            // 头像、文字、状态胶囊三块按行中线对齐；左右不再多缩 2dp，与卡片同一条 16dp 网格
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, dp(12f))
            setOnTouchListener { _, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        downX = e.x; downY = e.y; moved = false
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        if (kotlin.math.abs(e.x - downX) > slop || kotlin.math.abs(e.y - downY) > slop) moved = true
                    }
                }
                false
            }
            setOnClickListener {
                if (moved) return@setOnClickListener
                startActivity(android.content.Intent(this@MainActivity, DetailActivity::class.java).putExtra("pkg", v.game.pkg))
            }
        }
        row.addView(avatarBadged(v.game, v.done, 40f))
        val mid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12f), 0, dp(10f), 0)
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        mid.addView(label(v.game.label.ifEmpty { v.game.pkg }, 14f, onWallText(), true))
        mid.addView(label(
            "首开 ${v.firstOpen?.let { Fmt.hhmm(it) } ?: "--:--"} · 累计 ${Fmt.duration(v.totalMs)}",
            10.5f, onWallDim()
        ).apply { setPadding(0, dp(1f), 0, dp(6f)) })
        // 每行的进度线：已达标满格，进行中按比例
        val ratio = if (v.thresholdsMs == 0L) 0f else (v.totalMs.toFloat() / v.thresholdsMs).coerceIn(0f, 1f)
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
        }
        line.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(2f); setColor(if (v.done) T.PRIMARY else 0xFF9A6B00.toInt()) }
        }, LinearLayout.LayoutParams(0, dp(3f), (ratio * 100f).coerceAtLeast(1f)))
        line.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(2f); setColor(0xFFE0E5DF.toInt()) }
        }, LinearLayout.LayoutParams(0, dp(3f), ((1f - ratio) * 100f).coerceAtLeast(0f)))
        mid.addView(line)
        row.addView(mid)

        // 右侧：第一行「打没打卡」，第二行「怎么打卡」。
        // 玻璃分两套：卡片/本周条/图标胶囊用 glassBg（白纱 39%~69%），
        // 筛选胶囊与状态胶囊这种小面积用 glassBgChip（白纱 10%、描边 0x22），否则就是一圈高光。
        val st = statusOf(v)
        val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val chip = TextView(this).apply {
            text = st.text
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(st.fg)
            gravity = Gravity.CENTER
            setPadding(dp(12f), 0, dp(12f), 0)
        }
        // 小胶囊走 glassBgChip（白纱 10%、描边 0x22）：有玻璃但不发亮，状态色叠在玻璃上
        chip.background = if (Wallpaper.has() && Store.appearance().glass) {
            android.graphics.drawable.LayerDrawable(
                arrayOf(
                    glassBgChip(chip),
                    GradientDrawable().apply { cornerRadius = dpf(999f); setColor(st.glassTint) }
                )
            )
        } else {
            GradientDrawable().apply { cornerRadius = dpf(999f); setColor(st.bg) }
        }
        val hint = label(st.hint, 10.5f, onWallDim()).apply { gravity = Gravity.CENTER }
        // 整列同一个宽度 + 固定 25dp 高：四行状态胶囊左右边缘完全对齐，行高也不上下跳
        right.addView(chip, LinearLayout.LayoutParams(blockW, dp(25f)))
        right.addView(hint, LinearLayout.LayoutParams(blockW, WRAP).apply { topMargin = dp(3f) })
        row.addView(right)
        return row
    }

    /** 一行游戏的「状态 + 下一步」：状态词固定四种，动作句直说还差多少/玩多久。
     *  bg 是无壁纸时的实色，glassTint 是有毛玻璃时叠在玻璃上的状态色。 */
    private data class StatusChip(
        val text: String,
        val hint: String,
        val bg: Int,
        val glassTint: Int,
        val fg: Int
    )

    private fun statusOf(v: Verdict): StatusChip {
        val remain = (v.remainingMs / 60_000L).coerceAtLeast(1)
        val need = v.thresholdsMs / 60_000L
        return when (v.state) {
            State.DONE -> StatusChip("✓ 已打卡", "今日已达标", T.PRIMARY_C, 0x5520A06B, T.ON_PRIMARY_C)
            State.MANUAL -> StatusChip(
                "✓ 手动补卡",
                "补于 ${v.manualAt?.let { Fmt.hhmm(it) } ?: "--:--"}",
                T.PRIMARY_C, 0x5520A06B, T.ON_PRIMARY_C
            )
            State.RUNNING -> StatusChip("还差 $remain 分", "再玩 $remain 分即达标", T.WARN_C, 0x66C98A1E, T.ON_WARN_C)
            State.NOT_OPENED -> StatusChip("未打卡", "玩满 $need 分即可", 0xFFF1F4F0.toInt(), 0x40FFFFFF, T.ON_VAR)
        }
    }


    private fun manualCard(v: Verdict): View {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg(24f)
            setPadding(dp(16f), dp(17f), dp(16f), dp(17f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14f) }
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(label("今晚可补", 13f, T.ON_SURFACE, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(label("22:00 后才出现", 10f, T.ON_VAR))
        c.addView(top)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10f), 0, 0)
        }
        row.addView(avatarBadged(v.game, false, 40f))
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(11f), 0, 0, 0) }
        mid.addView(label(v.game.label.ifEmpty { v.game.pkg }, 14f, T.ON_SURFACE, true))
        mid.addView(label("前台 ${v.totalMs / 60_000L} 分 · 门槛 ${v.thresholdsMs / 60_000L} 分", 10.5f, T.ON_VAR))
        row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(TextView(this).apply {
            text = "手动完成"
            textSize = 12f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(T.ON_PRIMARY_C)
            setPadding(dp(14f), 0, dp(14f), 0)
            background = GradientDrawable().apply { cornerRadius = dpf(10f); setColor(T.PRIMARY_C) }
            setOnClickListener { confirmManual(v) }
        }, LinearLayout.LayoutParams(WRAP, dp(34f)))
        c.addView(row)
        return c
    }

    private fun weekCard(): View {
        val days = last7()
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = if (Wallpaper.has() && Store.appearance().glass) {
                glassBg(this, 16f)
            } else {
                GradientDrawable().apply { cornerRadius = dpf(16f); setColor(T.SURF_C) }
            }
            setPadding(dp(12f), dp(10f), dp(12f), dp(10f))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14f) }
        }
        c.addView(label("本周", 10f, T.ON_VAR))
        val strip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12f), 0, dp(12f), 0)
        }
        days.forEach { ok ->
            strip.addView(View(this).apply {
                background = GradientDrawable().apply {
                    cornerRadius = dpf(4f)
                    setColor(if (ok) T.PRIMARY else 0xFFE0E5DF.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(0, dp(14f), 1f).apply { rightMargin = dp(4f) }
            })
        }
        c.addView(strip, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.addView(label("${days.count { it }}/7", 10.5f, T.PRIMARY, true))
        return c
    }

    private fun serviceLine(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(12f), 0, dp(4f))
        }
        row.addView(View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(T.PRIMARY) }
            layoutParams = LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { rightMargin = dp(6f) }
        })
        row.addView(label(
            if (Store.keepAlive()) "前台服务常驻 · ${Fmt.hhmm(Store.lastSettle())} 已结算 · 心跳正常" else "常驻已关 · 由定时兜底",
            10f, onWallDim()
        ))
        return row
    }

    /** 顶栏：左边 eyebrow + 标题，右边三个入口图标（统计 / 外观 / 设置）。 */
    private fun homeHeader(): View {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(2f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label("PLAYCHECK", 9.5f, onWallDim()))
        col.addView(label("今日打卡", 22f, onWallText(), true))
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        val iconPill = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(3f), dp(3f), dp(3f), dp(3f))
        }
        if (Wallpaper.has() && Store.appearance().glass) {
            iconPill.background = glassBg(iconPill, 999f)
        }
        listOf(
            R.drawable.ic_stats to StatsActivity::class.java,
            R.drawable.ic_look to LookActivity::class.java,
            R.drawable.ic_settings to SettingsActivity::class.java
        ).forEach { (icon, target) ->
            iconPill.addView(MaterialButton(this).apply {
                    text = ""
                    setIconResource(icon)
                    iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
                    iconPadding = 0
                    backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                    iconTint = android.content.res.ColorStateList.valueOf(onWallText())
                    elevation = 0f
                    insetTop = 0
                    insetBottom = 0
                    minWidth = 0
                    iconSize = dp(18f)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener { startActivity(android.content.Intent(this@MainActivity, target)) }
                }, LinearLayout.LayoutParams(dp(32f), dp(32f)))
        }
        head.addView(iconPill, LinearLayout.LayoutParams(WRAP, WRAP))
        return head
    }

    private fun dateMeta(): String {
        val c = java.util.Calendar.getInstance()
        val w = listOf("日", "一", "二", "三", "四", "五", "六")[c.get(java.util.Calendar.DAY_OF_WEEK) - 1]
        return "${c.get(java.util.Calendar.MONTH) + 1}月${c.get(java.util.Calendar.DAY_OF_MONTH)}日 周$w"
    }

    private fun last7(): List<Boolean> {
        val rules = Store.rules()
        val now = System.currentTimeMillis()
        val games = Store.games()
        return (6 downTo 0).map { back ->
            val key = DayClock.dayKey(now - back * 86_400_000L, rules.refreshHour)
            Judge.judgeAll(Store.day(key), games, rules).any { it.done }
        }
    }

    
}

/** 首页顶部两页：0=时长卡，1=今日热力图。 */
private class HeroPager(
    private val durationCard: () -> View,
    private val heatCard: () -> View
) : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {

    override fun getItemCount(): Int = 2

    override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): androidx.recyclerview.widget.RecyclerView.ViewHolder {
        val v = if (viewType == 0) durationCard() else heatCard()
        v.layoutParams = android.view.ViewGroup.LayoutParams(MATCH_W, MATCH_H)
        return object : androidx.recyclerview.widget.RecyclerView.ViewHolder(v) {}
    }

    override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) = Unit

    override fun getItemViewType(position: Int): Int = position

    companion object {
        private const val MATCH_W = android.view.ViewGroup.LayoutParams.MATCH_PARENT
        private const val MATCH_H = android.view.ViewGroup.LayoutParams.MATCH_PARENT
    }
}
