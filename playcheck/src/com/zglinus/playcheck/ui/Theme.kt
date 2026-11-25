package com.zglinus.playcheck.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/** MD3 取色（种子 #2C6A4E），与 HTML 稿同一套令牌。 */
object T {
    const val PRIMARY = 0xFF2C6A4E.toInt()
    const val ON_PRIMARY = 0xFFFFFFFF.toInt()
    const val PRIMARY_C = 0xFFB4F1CE.toInt()
    const val ON_PRIMARY_C = 0xFF00210F.toInt()
    const val SECONDARY_C = 0xFFD0E8D8.toInt()
    const val ON_SECONDARY_C = 0xFF0B1F14.toInt()
    const val TERTIARY_C = 0xFFBFE9FB.toInt()
    const val ON_TERTIARY_C = 0xFF001F28.toInt()
    const val SURFACE = 0xFFF6FBF5.toInt()
    const val SURF_C = 0xFFEAEFE8.toInt()
    const val SURF_HIGH = 0xFFE4E9E3.toInt()
    const val ON_SURFACE = 0xFF171D18.toInt()
    const val ON_VAR = 0xFF3F4942.toInt()
    const val OUTLINE_V = 0xFFBFC9C0.toInt()
    const val WARN_C = 0xFFFFDEA6.toInt()
    const val ON_WARN_C = 0xFF261900.toInt()
}

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.tv(text: String, size: Float, color: Int, bold: Boolean = false): TextView =
    TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        includeFontPadding = false
    }

fun Context.pill(text: String, bg: Int, fg: Int): TextView = tv(text, 11f, fg, true).apply {
    setPadding(dp(11f), dp(5f), dp(11f), dp(5f))
    background = GradientDrawable().apply { cornerRadius = dpf(999f); setColor(bg) }
}

fun Context.round(bg: Int, radius: Float, stroke: Int = Color.TRANSPARENT, sw: Float = 1f): GradientDrawable =
    GradientDrawable().apply {
        cornerRadius = dpf(radius)
        setColor(bg)
        if (stroke != Color.TRANSPARENT) setStroke(dp(sw), stroke)
    }

fun Context.vgap(h: Int): View = View(this).apply {
    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
}
