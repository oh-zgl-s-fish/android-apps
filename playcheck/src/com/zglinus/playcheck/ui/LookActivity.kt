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

class LookActivity : BaseActivity() {

    override fun buildScreen(): View = screenLook()

// ---------------- 外观 ----------------

    private fun screenLook(): LinearLayout {
        val box = column()
        box.addView(headerRow("WIDGET", "外观与壁纸", false, back = true))
        val app = Store.appearance()

        val prev = card()
        prev.addView(label("小部件预览 · 4×2", 11f, T.ON_VAR))
        prev.addView(label("今日打卡  2/3 已打卡", 20f, T.ON_SURFACE, true))
        box.addView(prev)

        box.addView(label("壁纸", 13f, T.ON_SURFACE, true).apply { setPadding(dp(4f), dp(16f), 0, dp(6f)) })
        box.addView(MaterialButton(this).apply {
            text = if (app.wallpaperUri == null) "选一张壁纸" else "换一张壁纸"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
                }, 7)
            }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        if (app.wallpaperUri != null) {
            box.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "清除壁纸"
                setOnClickListener {
                    Store.saveAppearance(app.copy(wallpaperUri = null))
                    Wallpaper.clear()
                    reloadWallpaper(true)
                    render()
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8f) })
        }

        box.addView(label("背景", 13f, T.ON_SURFACE, true).apply { setPadding(dp(4f), dp(16f), 0, dp(6f)) })
        box.addView(sliderRow("壁纸压暗 ${app.darken}%", app.darken.toFloat(), 0f, 60f) { v ->
            Store.saveAppearance(Store.appearance().copy(darken = v.toInt())); reloadWallpaper(true); render()
        })
        box.addView(sliderRow("模糊半径 ${app.blurRadius}", app.blurRadius.toFloat(), 4f, 40f) { v ->
            Store.saveAppearance(Store.appearance().copy(blurRadius = v.toInt())); reloadWallpaper(true); render()
        })

        val c = card()
        c.addView(switchRow("毛玻璃卡片", "关掉用实色", app.glass) { on ->
            Store.saveAppearance(Store.appearance().copy(glass = on)); render()
        })
        box.addView(c)
        return box
    }

    private fun sliderRow(title: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6f), 0, 0) }
        box.addView(label(title, 11f, T.ON_VAR))
        box.addView(Slider(this).apply {
            valueFrom = from
            valueTo = to
            stepSize = 1f
            this.value = value.coerceIn(from, to)
            addOnChangeListener { _, v, fromUser -> if (!fromUser) return@addOnChangeListener }
            addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
                override fun onStartTrackingTouch(slider: Slider) {}
                override fun onStopTrackingTouch(slider: Slider) { onChange(slider.value) }
            })
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    
}
