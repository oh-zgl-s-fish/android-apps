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

class SettingsActivity : BaseActivity() {

    override fun buildScreen(): View = screenSettings()

// ---------------- 设置 ----------------

    private fun screenSettings(): LinearLayout {
        val box = column()
        box.addView(headerRow("SETTINGS", "设置", false, back = true))
        val rules = Store.rules()

        box.addView(label("打卡条件", 13f, T.ON_SURFACE, true).apply { setPadding(dp(4f), dp(4f), 0, dp(6f)) })
        val c = card()
        c.addView(thresholdRow(rules.thresholdMin))
        c.addView(divider())
        c.addView(stepper("换日时刻", "${rules.refreshHour}:00",
            onMinus = { Store.saveRules(rules.copy(refreshHour = (rules.refreshHour - 1).coerceIn(0, 12))); render() },
            onPlus = { Store.saveRules(rules.copy(refreshHour = (rules.refreshHour + 1).coerceIn(0, 12))); render() }))
        c.addView(divider())
        c.addView(switchRow("允许手动完成", "只在当天有效", rules.allowManual) { Store.saveRules(rules.copy(allowManual = it)) })
        c.addView(divider())
        c.addView(switchRow("常驻通知", "关掉退回定时兜底", Store.keepAlive()) {
            Store.keepAlive(it); if (it) KeepAlive.start(this) else KeepAlive.stop(this); render()
        })
        box.addView(c)

        box.addView(label("监控的游戏 · ${Store.games().size} 个", 13f, T.ON_SURFACE, true).apply { setPadding(dp(4f), dp(16f), 0, dp(6f)) })
        val gc = card()
        Store.games().forEachIndexed { i, g ->
            if (i > 0) gc.addView(divider())
            val installed = AndroidUsage(this).installed(listOf(g.pkg)).isNotEmpty()
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(8f), 0, dp(8f))
            }
            row.addView(avatar(g, 34f))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10f), 0, 0, 0) }
            col.addView(label(g.label.ifEmpty { g.pkg }, 13f, T.ON_SURFACE, true))
            col.addView(label("${g.pkg} · ${if (installed) "已安装" else "未安装"}", 10f, T.ON_VAR))
            row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            val hasCustom = Store.iconUri(g.pkg) != null
            row.addView(textBtn(if (hasCustom) "恢复图标" else "换图标") {
                if (hasCustom) {
                    Store.saveIcon(g.pkg, null); render()
                } else {
                    pendingIconPkg = g.pkg
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "image/*"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                    }, 12)
                }
            }, LinearLayout.LayoutParams(WRAP, dp(36f)))
            gc.addView(row)
        }
        box.addView(gc)
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "从应用列表添加"
            setOnClickListener { startActivityForResult(Intent(this@SettingsActivity, PickAppActivity::class.java), 11) }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })

        box.addView(label("权限", 13f, T.ON_SURFACE, true).apply { setPadding(dp(4f), dp(16f), 0, dp(6f)) })
        val pc = card()
        pc.addView(permRow("使用情况访问", hasUsageAccess()) { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) })
        pc.addView(divider())
        pc.addView(permRow("通知", hasNotifications()) { openNotificationSettings() })
        pc.addView(divider())
        pc.addView(permRow("精确闹钟", hasExactAlarm()) { openExactAlarm() })
        box.addView(pc)

        box.addView(MaterialButton(this).apply {
            text = "立即汇总一次"
            setOnClickListener { toast("已触发"); reloadAsync() }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16f) })
        box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "发一条测试提醒"
            setOnClickListener {
                Thread {
                    runCatching { Aggregator.alertIfNeeded(this@SettingsActivity, System.currentTimeMillis() + 3_600_000L) }
                    ui.post { toast("已尝试发送（当天只发一次）") }
                }.start()
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })

        box.addView(label("版本 ${versionTag()} · 只读使用记录，不联网 · 换日 ${rules.refreshHour}:00 · 汇总 30 分钟一次", 10f, T.ON_VAR)
            .apply { setPadding(dp(4f), dp(14f), 0, dp(8f)) })
        return box
    }

    /** 版本号从包信息读，别写死：发版要对得上「版本名 + versionCode + 提交号」三点。 */
    private fun versionTag(): String = runCatching {
        val pi = packageManager.getPackageInfo(packageName, 0)
        "${pi.versionName} (${pi.longVersionCode})"
    }.getOrDefault("?")

    private var pendingIconPkg: String? = null

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            11 -> {
                val pkg = data?.getStringExtra("pkg") ?: return
                val label = data.getStringExtra("label") ?: AndroidUsage(this).label(pkg) ?: ""
                if (Store.games().none { it.pkg == pkg }) {
                    Store.saveGames(Store.games() + Game(pkg, label))
                    reloadAsync()
                    toast("已添加 $label")
                }
            }
            12 -> {
                val uri = data?.data ?: return
                val pkg = pendingIconPkg ?: return
                runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                Store.saveIcon(pkg, uri.toString())
                render()
                toast("图标已换")
            }
        }
    }

    /** 步进随时长自动放大：<10 分按 1，<60 按 5，<240 按 15，再往上按 30。 */
    private fun stepOf(v: Int): Int = when {
        v < 10 -> 1
        v < 60 -> 5
        v < 240 -> 15
        else -> 30
    }

    private fun applyThreshold(minutes: Int) {
        Store.saveRules(Store.rules().copy(thresholdMin = minutes.coerceIn(1, 600)))
        render()
    }

    /** 右侧数值槽：固定 88dp，数字 + 单位都在里面，保证两行左边缘对齐。 */
    private fun valueSlot(content: View): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        wrap.addView(content)
        return wrap
    }

    /** 门槛：数字框可直接输入，两侧 ± 按当前量级自动选步长。 */
    private fun thresholdRow(current: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
        }
        row.addView(label("前台时长门槛", 14f, T.ON_SURFACE), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(textBtn("−") { applyThreshold(current - stepOf(current)) }, LinearLayout.LayoutParams(dp(40f), dp(40f)))

        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText(current.toString())
            setSelection(text.length)
            gravity = Gravity.CENTER
            textSize = 14f
            includeFontPadding = false
            setTextColor(T.PRIMARY)
            background = null
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(dp(44f), WRAP)
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { v, actionId, _ ->
                if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                    val n = v.text.toString().toIntOrNull()
                    if (n != null) applyThreshold(n) else render()
                    true
                } else false
            }
            setOnFocusChangeListener { v, has ->
                if (!has) {
                    val n = (v as android.widget.EditText).text.toString().toIntOrNull()
                    if (n != null && n != current) applyThreshold(n) else render()
                }
            }
        }

        val slot = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(88f), WRAP)
        }
        slot.addView(input)
        slot.addView(label("分", 12f, T.ON_VAR))
        row.addView(slot)

        row.addView(textBtn("＋") { applyThreshold(current + stepOf(current)) }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        return row
    }

    private fun stepper(title: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
        }
        row.addView(label(title, 14f, T.ON_SURFACE), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(textBtn("−") { onMinus() }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        // 数值列定宽 + 居中，两行的数字才对得上
        row.addView(label(value, 14f, T.PRIMARY, true).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(88f), WRAP))
        row.addView(textBtn("＋") { onPlus() }, LinearLayout.LayoutParams(dp(40f), dp(40f)))
        return row
    }

    private fun permRow(title: String, granted: Boolean, click: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56f)
            setOnClickListener { click() }
        }
        row.addView(label(title, 13f, T.ON_SURFACE), LinearLayout.LayoutParams(0, WRAP, 1f))
        row.addView(chipText(if (granted) "已授权" else "去开启",
            if (granted) T.PRIMARY_C else T.WARN_C,
            if (granted) T.ON_PRIMARY_C else T.ON_WARN_C))
        return row
    }

    
}
