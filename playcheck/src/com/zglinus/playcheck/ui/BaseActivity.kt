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

/**
 * 公共外壳：壁纸背景、滚动容器、insets、崩溃可见、数据装载、通用控件。
 * 各页面是独立 Activity，只实现 buildScreen()。
 */
abstract class BaseActivity : AppCompatActivity() {

    private val glassViews = ArrayList<View>()
    /** 固定在顶部、不参与滚动的区域（首页的头部 + 时长卡 + 筛选）。 */
    protected lateinit var stickyHost: LinearLayout
    protected open val hasSticky: Boolean get() = false
    protected open fun buildSticky(): View? = null
    protected lateinit var content: LinearLayout
    protected lateinit var bg: ImageView
    private lateinit var rootView: FrameLayout
    /** 顶部渐变压暗层：只跟“当前有没有壁纸”走，不跟 onCreate 走。 */
    private var scrimView: View? = null

    protected var verdicts: List<Verdict> = emptyList()
    protected var dayMap: Map<String, GameDay> = emptyMap()
    protected var filter = 0
    /** 首轮结算是否已经回来。二级页首帧 onResume 时 verdicts 必定是空的，不能据此判“没有这个游戏”。 */
    protected var dataReady = false
    /** 结算抛异常时记下类型名，别把“失败”显示成“没有数据”。 */
    protected var settleError: String? = null

    protected val ui = Handler(Looper.getMainLooper())

    protected abstract fun buildScreen(): View

    /** 能不能返回上一页：二级页面 true，根页面 false（让系统做 back-to-home）。 */
    protected open val inAppBack: Boolean get() = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Crash.install(this)
        // 状态栏/导航栏跟着壁纸走：透明 + 图标明暗按壁纸亮度
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)   // 壁纸铺到状态栏后面
            window.isNavigationBarContrastEnforced = false
        }
        try {
            Store.init(this)
            Notifier.ensureChannels(this)
            setContentView(buildRoot())
        } catch (t: Throwable) {
            showFatal(t)
            return
        }
        Crash.lastStack(this)?.let { Crash.showDialog(this, it) }
        Schedule.ensure(this)
        KeepAlive.start(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 9)
        reloadAsync()
    }

    override fun onResume() {
        super.onResume()
        // 外观页可能刚换/清了壁纸，回到本页要重取一次（有缓存，代价很小）
        if (this::bg.isInitialized) reloadWallpaper()
        // 回到前台无条件重画一次：文字配色、玻璃取景、筛选态都以最新壁纸/外观为准，
        // 之前靠“状态比较”判断，条件一漏就表现为“不重开 App 颜色不对”。
        render()
        reloadAsync()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7 || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        Store.saveAppearance(Store.appearance().copy(wallpaperUri = uri.toString()))
        reloadWallpaper()
        render()
        toast("壁纸已换")
    }

    protected fun render() {
        if (hasSticky) {
            stickyHost.removeAllViews()
            buildSticky()?.let { stickyHost.addView(it, LinearLayout.LayoutParams(MATCH, WRAP)) }
        }
        content.removeAllViews()
        content.addView(buildScreen(), LinearLayout.LayoutParams(MATCH, WRAP))
    }

// ---------------- 骨架 ----------------

    /** 状态栏/导航栏图标：暗壁纸用白图标，亮壁纸用深图标。 */
    private fun applyBarIcons() {
        if (android.os.Build.VERSION.SDK_INT < 30) return
        // onCreate 阶段 DecorView 可能还没 attach，getWindowInsetsController() 会 NPE；
        // 挪到下一帧，并整体兜住异常
        window.decorView.post {
            runCatching {
                val c = window.insetsController ?: return@runCatching
                val light = !(Wallpaper.has() && Wallpaper.isDark)
                val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                    android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
                c.setSystemBarsAppearance(if (light) mask else 0, mask)
            }
        }
    }

    protected fun buildRoot(): View {
        val root = FrameLayout(this)
        rootView = root
        // 窗口背景透明：二级页是半透明主题，能透出底下的页面
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        root.clipToOutline = true
        bg = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(T.SURFACE)
        }
        root.addView(bg, FrameLayout.LayoutParams(MATCH, MATCH))
        syncScrim()

        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        stickyHost = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), 0)
        }
        col.addView(stickyHost, LinearLayout.LayoutParams(MATCH, WRAP))

        val sv = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setPadding(0, 0, 0, dp(12f))
        }
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(4f), dp(16f), dp(16f))
        }
        sv.addView(content, ViewGroup.LayoutParams(MATCH, WRAP))
        sv.setOnScrollChangeListener { _, _, _, _, _ ->
            for (v in glassViews) v.invalidate()
        }
        col.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))


        root.addView(col, FrameLayout.LayoutParams(MATCH, MATCH))
        ViewCompat.setOnApplyWindowInsetsListener(col) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        reloadWallpaper()
        return root
    }

    /** 顶部渐变压暗层跟壁纸有无同步：设了壁纸补上、清了壁纸摘掉。之前只在 onCreate 建一次，
     *  运行中换壁纸它不跟——顶部那块看着“没重绘”。 */
    private fun syncScrim() {
        if (!this::rootView.isInitialized) return
        val want = Wallpaper.has()
        if (want && scrimView == null) {
            val scrim = View(this).apply {
                background = android.graphics.drawable.GradientDrawable(
                    android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                    intArrayOf(0x73000000, 0x33000000, 0x00000000)
                )
            }
            scrimView = scrim
            rootView.addView(scrim, 1, FrameLayout.LayoutParams(MATCH, dp(180f)))
        } else if (!want && scrimView != null) {
            rootView.removeView(scrimView)
            scrimView = null
        }
    }

    /** 重新解码并重绘壁纸；force=true 时无视缓存（改模糊半径/压暗后必须）。 */
    protected fun reloadWallpaper(force: Boolean = false) {
        val app = Store.appearance()
        Wallpaper.ensure(this, app, resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels, force)
        bg.setImageBitmap(Wallpaper.sharp)
        applyBarIcons()
        bg.setBackgroundColor(T.SURFACE)
        syncScrim()
        // 压暗用 colorFilter 打在壁纸上：只影响那张图，卡片与文字不受牵连
        bg.colorFilter = if (Wallpaper.has() && app.darken > 0) {
            android.graphics.PorterDuffColorFilter(
                Color.argb(app.darken * 255 / 100, 0, 0, 0),
                android.graphics.PorterDuff.Mode.SRC_ATOP
            )
        } else null
    }

    protected fun reloadAsync() {
        Thread {
            val r = runCatching { Aggregator.settle(this) }
            val rules = Store.rules()
            val key = DayClock.dayKey(System.currentTimeMillis(), rules.refreshHour)
            val d = Store.day(key)
            ui.post {
                r.onSuccess { verdicts = it }.onFailure { settleError = it.javaClass.simpleName }
                dayMap = d
                dataReady = true
                render()
            }
        }.start()
    }

    /** 有壁纸时，压在图上的一级/二级文字改用白色。 */
    /** 压在壁纸上的字（无容器）：有背景就白字，不自动切换。 */
    protected fun onWallText(): Int = if (Wallpaper.has()) Color.WHITE else T.ON_SURFACE
    protected fun onWallDim(): Int = if (Wallpaper.has()) 0xB3FFFFFF.toInt() else T.ON_VAR

    protected fun headerRow(eyebrow: String, title: String, actions: Boolean, @Suppress("UNUSED_PARAMETER") back: Boolean = false): View {
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6f), 0, dp(2f))
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(eyebrow, 9.5f, onWallDim()))
        col.addView(label(title, 22f, onWallText(), true))
        head.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (actions) head.addView(iconAction("↻") { reloadAsync() })
        return head
    }

    protected fun iconAction(t: String, click: () -> Unit): TextView = label(t, 18f, T.ON_VAR).apply {
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
        setOnClickListener { click() }
    }

    protected fun switchRow(title: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
        }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(label(title, 13f, T.ON_SURFACE))
        col.addView(label(sub, 11f, T.ON_VAR))
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(MaterialSwitch(this).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, b -> onChange(b) }
        })
        return row
    }

    protected fun showFatal(t: Throwable) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(72f), dp(20f), dp(20f))
            setBackgroundColor(T.SURFACE)
        }
        box.addView(label("开游启动失败", 18f, T.ON_SURFACE, true))
        box.addView(label("把下面这段发我：", 11f, T.ON_VAR).apply { setPadding(0, dp(8f), 0, dp(8f)) })
        box.addView(ScrollView(this).apply {
            addView(label(android.util.Log.getStackTraceString(t), 9f, T.ON_VAR).apply { typeface = Typeface.MONOSPACE })
        }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(box)
    }

// ---------------- 组件 ----------------

    protected fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    /** 卡片：竖向 LinearLayout + MD3 圆角描边背景（CardView 是 FrameLayout，子 View 会重叠）。 */
    /** 卡片背景：玻璃时返回半透明占位，真正绘制由 card() 换成 GlassBg。 */
    protected fun cardBg(radius: Float): android.graphics.drawable.Drawable =
        com.google.android.material.shape.MaterialShapeDrawable().apply {
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dpf(radius))
                .build()
            val app = Store.appearance()
            val over = Wallpaper.has() && app.glass
            if (over) {
                // 半透明底 + 运行时再叠一张“模糊壁纸裁剪”的背景（见 withGlass）
                fillColor = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
                strokeWidth = 0f
            } else {
                fillColor = android.content.res.ColorStateList.valueOf(T.SURF_C)
                strokeWidth = dpf(1f)
                strokeColor = android.content.res.ColorStateList.valueOf(T.OUTLINE_V)
            }
        }

    protected fun pill28(t: String, bg: Int, fg: Int): TextView {
        val v = label(t, 11.5f, fg, true)
        v.gravity = Gravity.CENTER
        v.setPadding(dp(12f), 0, dp(12f), 0)
        v.layoutParams = LinearLayout.LayoutParams(WRAP, dp(28f))
        v.background = if (Wallpaper.has() && Store.appearance().glass) {
            glassBg(v, 999f)   // 天数胶囊和其它容器同一套磨砂
        } else {
            GradientDrawable().apply { cornerRadius = dpf(999f); setColor(bg) }
        }
        return v
    }

    /** 大数字 + 单位小字：mm:ss 的冒号左边是分、右边是秒，单位写“分:秒”。 */
    protected fun timeBig(ms: Long, size: Float, color: Int = T.ON_SURFACE): TextView {
        val t = Fmt.mmss(ms)
        val sp = android.text.SpannableString("$t 分:秒")
        sp.setSpan(android.text.style.RelativeSizeSpan(15f / size), t.length, sp.length, 0)
        sp.setSpan(android.text.style.ForegroundColorSpan(if (color == T.ON_SURFACE) T.ON_VAR else 0xCC1F5B42.toInt()),
            t.length, sp.length, 0)
        return TextView(this).apply {
            text = sp
            textSize = size
            setTextColor(color)
            setTypeface(typeface, Typeface.BOLD)
            setLetterSpacing(-0.02f)
            setPadding(0, dp(6f), 0, 0)
        }
    }

    /** 头像 + 完成角标（设计稿 g-av 的绿勾）。 */
    protected fun avatarBadged(g: Game, done: Boolean, size: Float): View {
        val box = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        }
        box.addView(avatar(g, size), FrameLayout.LayoutParams(dp(size), dp(size)))
        if (done) {
            box.addView(label("✓", 8f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(T.PRIMARY) }
            }, FrameLayout.LayoutParams(dp(15f), dp(15f)).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                rightMargin = -dp(2f)
                bottomMargin = -dp(2f)
            })
        }
        return box
    }

    /** 细进度条：用 weight 拆两段，不用测量回调。 */
    protected fun progressBar(heightDp: Int, ratio: Float): View {
        val h = dp(heightDp.toFloat())
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            weightSum = 100f
            layoutParams = LinearLayout.LayoutParams(MATCH, h).apply { topMargin = dp(12f) }
        }
        row.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(999f); setColor(T.PRIMARY) }
        }, LinearLayout.LayoutParams(0, MATCH, (ratio * 100f).coerceIn(0.01f, 100f)))
        row.addView(View(this).apply {
            background = GradientDrawable().apply { cornerRadius = dpf(999f); setColor(0x22000000) }
        }, LinearLayout.LayoutParams(0, MATCH, ((1f - ratio) * 100f).coerceIn(0.0f, 100f)))
        return row
    }

    protected fun filledCard(fill: Int): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = com.google.android.material.shape.MaterialShapeDrawable().apply {
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dpf(24f))
                .build()
            fillColor = android.content.res.ColorStateList.valueOf(fill)
        }
        setPadding(dp(18f), dp(16f), dp(18f), dp(16f))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6f) }
    }

    /** 全稿统一的玻璃白纱不透明度（跟随设置里的透明度）。 */
    protected fun glassAlpha(): Int =
        (80 + Store.appearance().tintAlpha * 255 / 100).coerceIn(100, 175)

    /** 统一玻璃背景：白纱与描边只有一处定义，圆角按控件类型传。 */
    protected fun glassBg(v: View, radiusDp: Float, anchor: View? = null): GlassBg =
        GlassBg(
            v, dpf(radiusDp), glassAlpha(),
            darkenAlpha = Store.appearance().darken * 255 / 100,
            anchor = anchor
        )

    /** 小胶囊（筛选 / 状态）专用玻璃：白纱 26/255（约 10%），描边 0x22，几乎不提亮。
     *  大卡片那套白纱是 39%~69%，铺在整张卡上叫磨砂，铺在 25dp 的小胶囊上就是一圈高光。 */
    protected fun glassBgChip(v: View, radiusDp: Float = 999f): GlassBg =
        GlassBg(
            v, dpf(radiusDp), 26,
            strokeAlpha = 0x22,
            darkenAlpha = Store.appearance().darken * 255 / 100
        )

    protected fun card(glass: Boolean = true): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val over = Wallpaper.has() && Store.appearance().glass
        background = com.google.android.material.shape.MaterialShapeDrawable().apply {
            shapeAppearanceModel = com.google.android.material.shape.ShapeAppearanceModel.builder()
                .setAllCorners(com.google.android.material.shape.CornerFamily.ROUNDED, dpf(24f))
                .build()
            fillColor = android.content.res.ColorStateList.valueOf(
                if (over) Color.argb(232, 255, 255, 255) else T.SURF_C
            )
            strokeWidth = dpf(1f)
            strokeColor = android.content.res.ColorStateList.valueOf(if (over) 0x40FFFFFF else T.OUTLINE_V)
        }
        setPadding(dp(16f), dp(14f), dp(16f), dp(14f))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10f) }
        val app = Store.appearance()
        if (glass && Wallpaper.has() && app.glass) {
            background = glassBg(this, 24f)
            glassViews.add(this)
        }
    }

    protected fun divider(): View = View(this).apply {
        setBackgroundColor(if (Wallpaper.has()) 0x22000000 else 0x14000000)
        layoutParams = LinearLayout.LayoutParams(MATCH, 1).apply { topMargin = dp(4f); bottomMargin = dp(4f) }
    }

    protected fun textBtn(t: String, click: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = t
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, 0)
        setTextColor(T.PRIMARY)
        backgroundTintList = android.content.res.ColorStateList.valueOf(Color.TRANSPARENT)
        elevation = 0f
        insetTop = 0
        insetBottom = 0
        minWidth = 0
        setOnClickListener { click() }
    }

    protected fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    /** 用户自己选的图标：从 URI 解码成小位图。 */
    protected fun customIcon(pkg: String, size: Float): android.graphics.drawable.Drawable? {
        val uri = Store.iconUri(pkg) ?: return null
        return runCatching {
            val input = contentResolver.openInputStream(android.net.Uri.parse(uri)) ?: return null
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 2 }
            val bmp = android.graphics.BitmapFactory.decodeStream(input, null, opts)
            input.close()
            android.graphics.drawable.BitmapDrawable(resources, bmp)
        }.getOrNull()
    }

    protected fun chipText(text: String, bg: Int, fg: Int): TextView = label(text, 11f, fg, true).apply {
        setPadding(dp(10f), dp(5f), dp(10f), dp(5f))
        background = GradientDrawable().apply { cornerRadius = dpf(999f); setColor(bg) }
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
    }

    protected fun avatar(g: Game, size: Float): View {
        val usage = AndroidUsage(this)
        val custom = customIcon(g.pkg, size)
        val icon = custom ?: usage.icon(g.pkg)
        return if (icon != null) {
            ImageView(this).apply {
                setImageDrawable(icon)
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
                clipToOutline = true
                background = GradientDrawable().apply { cornerRadius = dpf(size / 2.9f) }
            }
        } else {
            label((g.label.ifEmpty { g.pkg }).take(1).uppercase(), 15f, T.ON_PRIMARY_C, true).apply {
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
                background = GradientDrawable().apply { cornerRadius = dpf(size / 2.9f); setColor(T.PRIMARY_C) }
            }
        }
    }


    protected fun isLate(): Boolean = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) >= 22

    protected fun confirmManual(v: Verdict) {
        AlertDialog.Builder(this)
            .setTitle("手动完成今天的打卡？")
            .setMessage("会记成「手动补卡」：留存操作时间与当时前台时长，统计里与自动达标分开。")
            .setPositiveButton("手动完成") { _, _ ->
                Thread { Aggregator.manual(this, v.game.pkg); ui.post { toast("已补卡"); reloadAsync() } }.start()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    protected fun addGameDialog() {
        val input = EditText(this).apply { hint = "com.example.game" }
        AlertDialog.Builder(this).setTitle("加一个游戏").setView(input)
            .setPositiveButton("添加") { _, _ ->
                val pkg = input.text.toString().trim()
                if (pkg.isNotEmpty() && Store.games().none { it.pkg == pkg }) {
                    val list = Store.games().toMutableList()
                    list.add(Game(pkg, AndroidUsage(this).label(pkg) ?: ""))
                    Store.saveGames(list)
                    reloadAsync()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    protected fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    protected fun hasUsageAccess(): Boolean = runCatching {
        val appOps = getSystemService(AppOpsManager::class.java)!!
        val mode = if (Build.VERSION.SDK_INT >= 29)
            appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        else appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), packageName)
        mode == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    protected fun hasNotifications(): Boolean = if (Build.VERSION.SDK_INT >= 33)
        checkSelfPermission("android.permission.POST_NOTIFICATIONS") == android.content.pm.PackageManager.PERMISSION_GRANTED
    else true

    protected fun hasExactAlarm(): Boolean = if (Build.VERSION.SDK_INT >= 31)
        getSystemService(android.app.AlarmManager::class.java)?.canScheduleExactAlarms() == true else true

    protected fun openNotificationSettings() {
        runCatching {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    protected fun openExactAlarm() {
        if (Build.VERSION.SDK_INT >= 31) runCatching {
            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
        }
    }

    companion object {
        protected const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        protected const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    }

}
