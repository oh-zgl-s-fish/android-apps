package com.zglinus.installment.ui

import android.graphics.PorterDuff
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.graphics.drawable.GradientDrawable
import androidx.appcompat.app.AppCompatActivity
import com.zglinus.installment.core.Repo
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

abstract class PageActivity : AppCompatActivity() {

    protected lateinit var content: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var subtitleView: TextView

    abstract val navIndex: Int

    /** 底栏 tab 页（返回先回首页）；二级页设 false，返回回上一级 */
    open val isTabPage: Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_page)
        findViewById<View>(R.id.page_root)?.let { root ->
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // 顶部留状态栏；底部不挡内容，让底栏自己吃手势条高度 → 沉浸小白条
            v.setPadding(bars.left, bars.top, bars.right, 0)
            val nav = findViewById<View>(R.id.nav)
            nav.layoutParams = nav.layoutParams.apply { height = Ui.dp(this@PageActivity, 68f) + bars.bottom }
            nav.setPadding(nav.paddingLeft, nav.paddingTop, nav.paddingRight, bars.bottom)
            androidx.core.view.WindowCompat.getInsetsController(window, v).apply {
                isAppearanceLightStatusBars = Ui.isLight(window.decorView.context)
                isAppearanceLightNavigationBars = Ui.isLight(window.decorView.context)
            }
            insets
        }
        }
        content = findViewById(R.id.content)
        if (Store.mcpEnabled) McpHost.ensureRunning(Store.mcpPort, Store.mcpToken, Store.mcpLocalOnly)
        // 加锁：未解锁就压一层锁屏（锁屏自己不再触发）
        if (Store.lockEnabled && !LockState.unlocked && this !is LockActivity) {
            startActivity(android.content.Intent(this, LockActivity::class.java))
        }


        titleView = findViewById(R.id.title)
        subtitleView = findViewById(R.id.subtitle)
        buildNav()
        window.setBackgroundDrawableResource(android.R.color.transparent)
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isTabPage && navIndex != 0) {
                    switchTab { NavHolder.nav.openDashboard() }
                } else {
                    finish()
                }
            }
        })
        syncTopKpi()

        findViewById<View>(R.id.fab).setOnClickListener { NavHolder.nav.openNewPlan() }
    }

    protected fun showBack() {
        findViewById<View>(R.id.backBtn).apply {
            visibility = View.VISIBLE
            setOnClickListener { finish() }
        }
    }

    protected fun header(title: String, subtitle: String? = null) {
        titleView.text = title
        subtitleView.text = subtitle ?: ""
        subtitleView.visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    protected fun dp(v: Number): Int = Ui.dp(this, v.toFloat())

    private fun buildNav() {
        val nav = findViewById<LinearLayout>(R.id.nav)
        data class Item(val label: String, val icon: Int, val action: (() -> Unit))
        val items = listOf(
            Item("仪表盘", R.drawable.ic_dash) { switchTab { NavHolder.nav.openDashboard() } },
            Item("分期", R.drawable.ic_card) { switchTab { NavHolder.nav.openPlans() } },
            Item("账户", R.drawable.ic_savings) { switchTab { NavHolder.nav.openAccounts() } },
            Item("AI", R.drawable.ic_ai) { switchTab { NavHolder.nav.openAi() } },
            Item("我的", R.drawable.ic_person) { switchTab { NavHolder.nav.openProfile() } }
        )
        items.forEachIndexed { i, item ->
            val on = i == navIndex
            val cell = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            }
            val iv = ImageView(this).apply {
                setImageResource(item.icon)
                setColorFilter(Ui.colorOf(this@PageActivity, if (on) Ui.primary else Ui.onVar), PorterDuff.Mode.SRC_IN)
                setPadding(dp(9), dp(5), dp(9), dp(5))
                if (on) background = GradientDrawable().apply {
                    cornerRadius = dp(16).toFloat()
                    setColor(Ui.colorOf(this@PageActivity, Ui.primaryContainer))
                }
            }
            cell.addView(iv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            cell.addView(Ui.vspace(this, 3f))
            cell.addView(Ui.text(this, item.label, 10.5f, if (on) Ui.onSurface else Ui.onVar, bold = on).apply { gravity = Gravity.CENTER })
            cell.setOnClickListener { item.action() }
            nav.addView(cell)
        }
    }

    override fun onResume() {
        super.onResume()
        syncTopKpi()
    }

    protected fun add(v: View) = content.addView(v)

    /** 顶栏：待还金额 + 已还比例环 */
    private fun syncTopKpi() {
        val remaining = findViewById<android.widget.TextView>(R.id.topRemaining)
        val ring = findViewById<RingView>(R.id.topRing)
        val totalPeriods = Repo.active.sumOf { it.periods }
        val paidPeriods = Repo.active.sumOf { it.paidPeriods }
        val ratio = if (totalPeriods == 0) 0f else paidPeriods.toFloat() / totalPeriods
        remaining.text = Ui.amount(Repo.remainingTotal)
        ring.fraction = ratio
    }

    /** 首页/分期页才显示新建悬浮按钮 */
    protected fun showFab() {
        findViewById<View>(R.id.fab).visibility = View.VISIBLE
    }

    /**
     * 切 tab：淡入淡出（不做左右推入），并把当前 tab 自己结束掉，
     * 避免每切一次就往返回栈压一层、导致「回不到首页」。
     */
    private fun switchTab(open: () -> Unit) {
        open()
        finish()
        overridePendingTransition(R.anim.fade_in_fast, R.anim.fade_out_fast)
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.fade_in_fast, R.anim.fade_out_fast)
    }
}
