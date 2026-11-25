package com.zglinus.installment.ui

import com.zglinus.installment.core.*
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.R as MR
import java.text.NumberFormat

object Ui {

    /** 把 c1 朝 c2 混 t（0=c1, 1=c2），用来做同色系的浅色段 */
    fun blend(c1: Int, c2: Int, t: Float): Int {
        val a = (c1 ushr 24 and 0xFF); val r1 = c1 shr 16 and 0xFF; val g1 = c1 shr 8 and 0xFF; val b1 = c1 and 0xFF
        val r2 = c2 shr 16 and 0xFF; val g2 = c2 shr 8 and 0xFF; val b2 = c2 and 0xFF
        return (a shl 24) or
            (((r1 + (r2 - r1) * t).toInt()) shl 16) or
            (((g1 + (g2 - g1) * t).toInt()) shl 8) or
            (((b1 + (b2 - b1) * t).toInt()))
    }

    fun dp(ctx: Context, v: Number): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    fun colorOf(ctx: Context, attr: Int): Int {
        val ta = ctx.theme.obtainStyledAttributes(intArrayOf(attr))
        val c = ta.getColor(0, 0xFF000000.toInt())
        ta.recycle()
        return c
    }

    val primary: Int get() = MR.attr.colorPrimary
    val onPrimary: Int get() = MR.attr.colorOnPrimary
    val surface: Int get() = MR.attr.colorSurface
    val onSurface: Int get() = MR.attr.colorOnSurface
    val onVar: Int get() = MR.attr.colorOnSurfaceVariant
    val container: Int get() = MR.attr.colorSurfaceVariant
    val primaryContainer: Int get() = MR.attr.colorPrimaryContainer
    val onPrimaryContainer: Int get() = MR.attr.colorOnPrimaryContainer
    val secondaryContainer: Int get() = MR.attr.colorSecondaryContainer
    val onSecondaryContainer: Int get() = MR.attr.colorOnSecondaryContainer
    val tertiaryContainer: Int get() = MR.attr.colorTertiaryContainer
    val onTertiaryContainer: Int get() = MR.attr.colorOnTertiaryContainer
    val outlineVariant: Int get() = MR.attr.colorOutlineVariant
    val error: Int get() = MR.attr.colorError

    /** 当前是否浅色主题（决定系统栏图标颜色） */
    fun isLight(ctx: android.content.Context): Boolean {
        val night = ctx.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK
        return night != android.content.res.Configuration.UI_MODE_NIGHT_YES
    }

    fun money(v: Double): String = "¥" + NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 2; minimumFractionDigits = 2
    }.format(v)

    /** 金额一律两位小数 */
    fun amount(v: Double): String = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }.format(v)

    fun plain(v: Double): String = NumberFormat.getNumberInstance().apply {
        maximumFractionDigits = 2; minimumFractionDigits = 2
    }.format(v)

    fun pct(v: Double): String = String.format("%.2f%%", v * 100)

    fun text(ctx: Context, s: CharSequence, sizeSp: Float, colorAttr: Int,
             bold: Boolean = false, mono: Boolean = false): TextView =
        TextView(ctx).apply {
            text = s
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setTextColor(colorOf(ctx, colorAttr))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            if (mono) typeface = Typeface.create("monospace", if (bold) Typeface.BOLD else Typeface.NORMAL)
            includeFontPadding = false
        }

    fun card(ctx: Context, padH: Float = 14f, padV: Float = 13f): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, padH), dp(ctx, padV), dp(ctx, padH), dp(ctx, padV))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 20).toFloat()
                setColor(colorOf(ctx, container))
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10f) }
        }

    fun row(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun sectionHeader(ctx: Context, title: String, meta: String? = null): LinearLayout =
        row(ctx).apply {
            setPadding(dp(ctx, 4f), dp(ctx, 12f), dp(ctx, 4f), dp(ctx, 7f))
            addView(text(ctx, title, 13f, onSurface, bold = true))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            meta?.let { addView(text(ctx, it, 10f, onVar)) }
        }

    fun cardHeader(ctx: Context, title: String, meta: String? = null): LinearLayout =
        row(ctx).apply {
            addView(text(ctx, title, 13f, onSurface, bold = true))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            meta?.let { addView(text(ctx, it, 10f, onVar)) }
            setPadding(0, dp(ctx, 1f), 0, dp(ctx, 9))
        }

    /** 进度条：已还 / 总数 */
    fun progressBar(ctx: Context, fraction: Double, secondColor: Boolean = false): View {
        val track = GradientDrawable().apply {
            cornerRadius = dp(ctx, 4).toFloat()
            setColor(colorOf(ctx, outlineVariant))
        }
        val bar = View(ctx).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 4).toFloat()
                setColor(
                    if (secondColor) blend(colorOf(ctx, primary), colorOf(ctx, container), 0.5f)
                    else colorOf(ctx, primary)
                )
            }
        }
        val wrap = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = track
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 7f)
            )
        }
        val w = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        val filler = View(ctx)
        wrap.addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        wrap.post {
            val target = (wrap.width * fraction.coerceIn(0.0, 1.0)).toInt()
            bar.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
            bar.requestLayout()
            android.animation.ValueAnimator.ofInt(0, target).apply {
                duration = 620
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener { a ->
                    bar.layoutParams = LinearLayout.LayoutParams(
                        a.animatedValue as Int, ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    bar.requestLayout()
                }
                start()
            }
        }
        return wrap
    }

    fun kvRow(ctx: Context, left: String, right: String, strong: Boolean = false): LinearLayout =
        row(ctx).apply {
            setPadding(0, dp(ctx, 4f), 0, dp(ctx, 4f))
            addView(text(ctx, left, 11f, onVar))
            addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
            addView(text(ctx, right, if (strong) 14f else 12f, onSurface, bold = strong))
        }

    fun chip(ctx: Context, label: String, on: Boolean): TextView =
        text(ctx, label, 11f, if (on) onSecondaryContainer else onVar, bold = on).apply {
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 8f), dp(ctx, 6f), dp(ctx, 8f), dp(ctx, 6f))
            background = GradientDrawable().apply {
                cornerRadius = dp(ctx, 10).toFloat()
                if (on) setColor(colorOf(ctx, secondaryContainer))
                else {
                    setColor(0x00000000)
                    setStroke(dp(ctx, 1f), colorOf(ctx, outlineVariant))
                }
            }
        }

    fun vspace(ctx: Context, dp: Float): View = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(1, this@Ui.dp(ctx, dp))
    }
}

/** 轻量动画工具：报数滚动、列表入场、进度条生长 */
object Anim {

    fun countUp(tv: TextView, target: Double, prefix: String = "¥", dur: Long = 520) {
        val start = 0.0
        android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = dur
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val v = start + (target - start) * f
                tv.text = prefix + java.text.NumberFormat.getNumberInstance().apply {
                    maximumFractionDigits = 2; minimumFractionDigits = 2
                }.format(v)
            }
            start()
        }
    }

    /** 列表逐条淡入上浮 */
    fun staggerIn(v: View, index: Int) {
        v.alpha = 0f
        v.translationY = Ui.dp(v.context, 12f).toFloat()
        v.animate()
            .alpha(1f)
            .translationY(0f)
            .setStartDelay((index * 38L).coerceAtMost(320))
            .setDuration(260)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .start()
    }
}

/** MD3 设置页构件：分区标签 / 列表项 / 内缩分隔线 */
fun Context.sectionLabel(text: String): TextView =
    Ui.text(this, text, 14f, Ui.primary, bold = true).apply {
        setPadding(Ui.dp(this@sectionLabel, 16f), Ui.dp(this@sectionLabel, 18f),
                   Ui.dp(this@sectionLabel, 16f), Ui.dp(this@sectionLabel, 6f))
    }

/**
 * MD3 list item：单行 56dp / 双行 72dp，16dp 边距，24dp 图标，16dp 间距，
 * 标题 BodyLarge(16sp)、副标题 BodyMedium(14sp)，点得动就带 ripple。
 */
fun Context.listItem(
    iconRes: Int,
    title: String,
    support: String? = null,
    trailing: View? = null,
    onClick: (() -> Unit)? = null
): LinearLayout {
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
        val v = if (support == null) 16f else 12f
        setPadding(Ui.dp(this@listItem, 16f), Ui.dp(this@listItem, v),
                   Ui.dp(this@listItem, 16f), Ui.dp(this@listItem, v))
        minimumHeight = Ui.dp(this@listItem, if (support == null) 56f else 72f)
        onClick?.let { cb ->
            setOnClickListener { cb() }
            isClickable = true
            val ta = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
            background = ta.getDrawable(0)
            ta.recycle()
        }
    }
    val icon = android.widget.ImageView(this).apply {
        setImageResource(iconRes)
        setColorFilter(Ui.colorOf(this@listItem, Ui.onVar), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    row.addView(icon, LinearLayout.LayoutParams(Ui.dp(this, 24f), Ui.dp(this, 24f)))

    val col = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(Ui.dp(this@listItem, 16f), 0, 0, 0)
    }
    col.addView(Ui.text(this, title, 16f, Ui.onSurface))
    support?.let {
        col.addView(Ui.text(this, it, 14f, Ui.onVar).apply { setPadding(0, Ui.dp(this@listItem, 2f), 0, 0) })
    }
    row.addView(col, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

    if (trailing != null) row.addView(trailing)
    else if (onClick != null) {
        row.addView(android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_chev)
            setColorFilter(Ui.colorOf(this@listItem, Ui.onVar), android.graphics.PorterDuff.Mode.SRC_IN)
        }, LinearLayout.LayoutParams(Ui.dp(this, 24f), Ui.dp(this, 24f)))
    }
    return row
}

/** MD3 divider：1dp outlineVariant，起点内缩 16dp（不含图标列） */
fun Context.md3Divider(): View = View(this).apply {
    setBackgroundColor(Ui.colorOf(this@md3Divider, Ui.outlineVariant))
    layoutParams = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this@md3Divider, 1f)
    ).apply { marginStart = Ui.dp(this@md3Divider, 56f) }
}

@Deprecated("use md3Divider")
fun Context.insetDivider(insetDp: Float = 58f): View = md3Divider()
