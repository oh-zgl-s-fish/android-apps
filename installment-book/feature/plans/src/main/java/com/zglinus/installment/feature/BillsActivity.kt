package com.zglinus.installment.feature

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.card.MaterialCardView
import com.zglinus.installment.core.*
import com.zglinus.installment.ui.*

/** 分期汇总（MD3）：按银行卡聚合，只放汇总口径 */
class BillsActivity : PageActivity() {

    override val navIndex = 1
    override val isTabPage = false


    /** 自绘甘特：横轴月份、每行一个项目、条内「名称 ¥金额」，外面套 HorizontalScrollView 供左右滑动 */
    private fun ganttCard(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(20f).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Ui.colorOf(this@BillsActivity, Ui.container))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4f).toInt(); bottomMargin = dp(10f).toInt() }
        }
        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(8))
        }

        val plans = Repo.active.sortedBy { Calc.firstDue(it.startDate, it.statementDay, it.dueDay) }
        if (plans.isEmpty()) {
            inner.addView(Ui.text(this, "暂无在还分期", 11f, Ui.onVar).apply {
                setPadding(dp(8), dp(10), dp(8), dp(10))
            })
            card.addView(inner)
            return card
        }

        // 起点取最早的首期月（能往左看到历史），当前月作为初始滚动位置
        val nowMonth = java.time.LocalDate.now().withDayOfMonth(1)
        val firsts = plans.map { Calc.firstDue(it.startDate, it.statementDay, it.dueDay).withDayOfMonth(1) }
        val baseMonth = firsts.minOrNull()?.let { if (it.isBefore(nowMonth)) it else nowMonth } ?: nowMonth
        val nowOffset = java.time.temporal.ChronoUnit.MONTHS.between(baseMonth, nowMonth).toInt().coerceAtLeast(0)

        var maxSpan = 1
        val bars = plans.map { p ->
            val first = Calc.firstDue(p.startDate, p.statementDay, p.dueDay).withDayOfMonth(1)
            val offset = java.time.temporal.ChronoUnit.MONTHS
                .between(baseMonth, first).toInt().coerceAtLeast(0)
            val span = p.periods.coerceAtLeast(1)   // 整条按总期数画，已还部分用斜纹表示
            maxSpan = maxOf(maxSpan, offset + span)
            val label = (p.title.ifBlank { "${p.bank} ${p.tail}" }) +
                "  ¥" + Ui.plain(p.principal + p.totalInterest)
            com.zglinus.installment.ui.GanttView.Bar(label, offset, span)
        }

        val base = Ui.colorOf(this@BillsActivity, Ui.primary)
        val gantt = com.zglinus.installment.ui.GanttView(this).apply {
            this.baseMonth = baseMonth
            this.monthCount = maxOf(maxSpan, nowOffset + 1) + 1
            this.bars = bars
            barColor = base
            bgColor = 0x12000000   // 半透明背景阴影（约 7% 黑），圆角随卡片
            monthHighlightColor = (Ui.colorOf(this@BillsActivity, Ui.primary) and 0x00FFFFFF) or 0x1F000000
            nowMonthOffset = nowOffset
            val today = java.time.LocalDate.now()
            nowMonthFraction = (today.dayOfMonth - 1).toFloat() /
                today.lengthOfMonth().toFloat()   // 25 号在 30 天的月里 ≈ 0.8
            textColorOnBar = Ui.colorOf(this@BillsActivity, Ui.onPrimary)
            headerTextColor = Ui.colorOf(this@BillsActivity, Ui.onVar)
            gridColor = if (Ui.isLight(this@BillsActivity))
                Ui.colorOf(this@BillsActivity, Ui.outlineVariant) and 0x55FFFFFF.toInt()
            else
                Ui.colorOf(this@BillsActivity, Ui.outlineVariant) and 0x88FFFFFF.toInt()
        }

        // 直接放进卡片：GanttView 自己处理横向拖动，避免嵌套滚动把尺寸量坏
        gantt.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, gantt.contentHeight()
        )
        inner.addView(gantt)
        // 进页面：当前月落在视口左起 1/3 处（左边留 1/3 看历史，右边 2/3 看未来）
        gantt.scrollToMonthAt(nowOffset, 1f / 3f)
        card.addView(inner)
        return card
    }

    /** 把颜色压暗，f 越大越暗 */
    private fun darken(color: Int, f: Float): Int {
        val r = (android.graphics.Color.red(color) * (1f - f)).toInt().coerceIn(0, 255)
        val g = (android.graphics.Color.green(color) * (1f - f)).toInt().coerceIn(0, 255)
        val b = (android.graphics.Color.blue(color) * (1f - f)).toInt().coerceIn(0, 255)
        return android.graphics.Color.rgb(r, g, b)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showBack()
        header("分期汇总", "")

        // 顶部 hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Ui.colorOf(this@BillsActivity, Ui.primaryContainer))
            }
        }
        hero.addView(Ui.text(this, "待还合计", 11f, Ui.onPrimaryContainer))
        hero.addView(
            Ui.text(this, Ui.money(Repo.remainingTotal), 26f, Ui.onPrimaryContainer, bold = true)
                .apply { setPadding(0, dp(4), 0, dp(2)) }
        )
        val sub = Ui.row(this)
        sub.addView(Ui.text(this, "${Repo.active.size} 笔在还", 10.5f, Ui.onPrimaryContainer))
        sub.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        sub.addView(Ui.text(this, "加权年化 ${Ui.pct(Repo.weightedApr)}", 10.5f, Ui.onPrimaryContainer))
        sub.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        sub.addView(Ui.text(this, "已付利息 ${Ui.amount(Repo.paidInterestTotal)}", 10.5f, Ui.onPrimaryContainer))
        hero.addView(sub)
        add(hero)

        // 按银行卡
        add(Ui.sectionHeader(this, "按银行卡", null))
        val banks = Repo.cards.map { it.bank }.toMutableList()
        Repo.active.map { it.bank }.distinct().forEach { if (it !in banks) banks += it }

        banks.forEach { bank ->
            val mine = Repo.active.filter { it.bank == bank }
            if (mine.isEmpty()) return@forEach
            val card = MaterialCardView(this).apply {
                radius = dp(20f).toFloat(); cardElevation = 0f
                setCardBackgroundColor(Ui.colorOf(this@BillsActivity, Ui.container))
                strokeWidth = 0
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10f).toInt() }
            }
            val inner = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
            }
            val top = Ui.row(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val name = Ui.text(this, bank, 13f, Ui.onSurface, bold = true)
            top.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            top.addView(
                Ui.text(this, "${mine.size} 笔 · ${mine.maxOf { it.remainMonths }} 个月", 10f,
                    Ui.onVar).apply { gravity = Gravity.END }
            )
            inner.addView(top)

            val paid = mine.sumOf { it.paidPeriods }
            val total = mine.sumOf { it.periods }
            inner.addView(
                Ui.progressBar(this, if (total > 0) paid.toDouble() / total else 0.0)
                    .apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(8f).toInt() }
            )

            val foot = Ui.row(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f).toInt(), 0, 0)
            }
            foot.addView(Ui.text(this, "待还 ${Ui.amount(mine.sumOf { it.remaining })}", 10.5f, Ui.onVar),
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            foot.addView(
                Ui.text(this, Ui.money(mine.sumOf { it.remaining }), 13f,
                    Ui.colorOf(this@BillsActivity, Ui.primary), bold = true)
            )
            inner.addView(foot)
            card.addView(inner)
            add(card)
        }


        // 分期项目甘特图（MPAndroidChart 的横向柱状图：横向轴是月份，纵向每行一个项目）
        add(Ui.sectionHeader(this, "分期项目甘特图", null))
        add(ganttCard())

        // 接下来 5 次扣款
        add(Ui.sectionHeader(this, "接下来 5 次扣款", null))
        val byDate = LinkedHashMap<String, MutableList<Triple<String, String, Double>>>()
        Repo.upcoming(24).forEach { t -> byDate.getOrPut(t.first) { mutableListOf() }.add(t) }

        val payCard = MaterialCardView(this).apply {
            radius = dp(20f).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Ui.colorOf(this@BillsActivity, Ui.container))
            strokeWidth = 0
        }
        val payInner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        if (byDate.isEmpty()) {
            payInner.addView(
                Ui.text(this, "没有待扣款", 11f, Ui.onVar).apply {
                    setPadding(dp(10), dp(12), dp(10), dp(12))
                }
            )
        }
        byDate.entries.take(5).forEachIndexed { i, (date, list) ->
            val r = Ui.row(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(10), dp(10), dp(10), dp(10))
                if (i % 2 == 1) background = GradientDrawable().apply {
                    cornerRadius = dp(12f).toFloat()
                    setColor(Ui.colorOf(this@BillsActivity, Ui.onSurface) and 0x14FFFFFF)
                }
            }
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(Ui.text(this, date, 11f, Ui.onSurface, bold = true))
            col.addView(
                Ui.text(this, "${list.map { it.second }.distinct().joinToString(" / ")} · ${list.size} 笔",
                    9.5f, Ui.onVar)
            )
            r.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            r.addView(
                Ui.text(this, Ui.money(list.sumOf { it.third }), 12.5f, Ui.onSurface, bold = true)
            )
            payInner.addView(r)
        }
        payCard.addView(payInner)
        add(payCard)

        // 已结束
        val ended = MaterialCardView(this).apply {
            radius = dp(20f).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Ui.colorOf(this@BillsActivity, Ui.container))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12f) }
        }
        val er = Ui.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            setOnClickListener { NavHolder.nav.openPlans() }
        }
        er.addView(
            Ui.text(this, "已结束 ${Repo.ended.size} 笔 · 利息 ${Ui.amount(Repo.ended.sumOf { it.totalInterest })}",
                11f, Ui.onVar), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        er.addView(Ui.text(this, "查看 ›", 11f, Ui.primary, bold = true))
        ended.addView(er)
        add(ended)

        // 自动还款
        val auto = MaterialCardView(this).apply {
            radius = dp(20f).toFloat(); cardElevation = 0f
            setCardBackgroundColor(Ui.colorOf(this@BillsActivity, Ui.container))
            strokeWidth = 0
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10f) }
        }
        val ar = Ui.row(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        val nextAuto = Repo.upcoming(1).firstOrNull()
        ar.addView(
            Ui.text(this, "自动还款", 11.5f, Ui.onSurface),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        )
        ar.addView(
            Ui.text(this, nextAuto?.let { "${it.first} · ${it.second}" } ?: "暂无待扣", 10f, Ui.onVar)
        )
        ar.addView(SwitchCompat(this).apply {
            isChecked = Store.autoPay
            setOnCheckedChangeListener { _, on -> Store.setAutoPay(context, on) }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { leftMargin = dp(10f).toInt() })
        auto.addView(ar)
        add(auto)
        add(Ui.vspace(this, 16f))
    }
}
