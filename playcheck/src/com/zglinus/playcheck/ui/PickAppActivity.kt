package com.zglinus.playcheck.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.zglinus.playcheck.data.Store
import android.graphics.drawable.GradientDrawable

/** 从已安装的启动器应用里挑一个加进监控清单，返回包名与名称。 */
class PickAppActivity : AppCompatActivity() {

    private data class Item(val label: String, val pkg: String)

    private var all: List<Item> = emptyList()
    private var keyword = ""
    private lateinit var listBox: LinearLayout
    private val iconCache = HashMap<String, Drawable?>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        all = loadLaunchable()
        setContentView(buildUi())
    }

    private fun loadLaunchable(): List<Item> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val found = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        return found.mapNotNull { ri ->
            val pkg = ri.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName) return@mapNotNull null
            val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault(pkg)
            Item(label, pkg)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    private fun iconOf(pkg: String): Drawable? = iconCache.getOrPut(pkg) {
        runCatching { packageManager.getApplicationIcon(pkg) }.getOrNull()
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8f), dp(10f), dp(12f), dp(6f))
        }
        head.addView(TextView(this).apply {
            text = "‹"
            textSize = 22f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(40f), dp(44f))
            setOnClickListener { finish() }
        })
        head.addView(TextView(this).apply {
            text = "选择应用"
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(head)

        val search = EditText(this).apply {
            hint = "搜索应用名或包名"
            setSingleLine()
            textSize = 14f
            setPadding(dp(12f), dp(8f), dp(12f), dp(8f))
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: android.text.Editable?) {
                    keyword = s?.toString()?.trim()?.lowercase() ?: ""
                    fillList()
                }
            })
        }
        root.addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = dp(16f); rightMargin = dp(16f)
        })

        val sv = ScrollView(this)
        listBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(8f), dp(16f), dp(24f))
        }
        sv.addView(listBox, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(sv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        fillList()
        return root
    }

    private fun fillList() {
        listBox.removeAllViews()
        val shown = all.filter {
            keyword.isEmpty() || it.label.lowercase().contains(keyword) || it.pkg.lowercase().contains(keyword)
        }
        if (shown.isEmpty()) {
            listBox.addView(TextView(this).apply { text = "没有匹配的应用"; textSize = 13f })
            return
        }
        shown.forEach { item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10f), 0, dp(10f))
                setOnClickListener {
                    setResult(RESULT_OK, Intent().putExtra("pkg", item.pkg).putExtra("label", item.label))
                    finish()
                }
            }
            row.addView(ImageView(this).apply {
                setImageDrawable(iconOf(item.pkg))
                layoutParams = LinearLayout.LayoutParams(dp(40f), dp(40f))
            })
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12f), 0, 0, 0) }
            col.addView(TextView(this).apply { text = item.label; textSize = 14f })
            col.addView(TextView(this).apply { text = item.pkg; textSize = 10f })
            row.addView(col)
            listBox.addView(row)
            listBox.addView(View(this).apply {
                setBackgroundColor(0x14000000)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
            })
        }
    }

    private fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
}
