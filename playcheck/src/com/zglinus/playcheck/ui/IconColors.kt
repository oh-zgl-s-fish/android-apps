package com.zglinus.playcheck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable

/** 从应用图标里取一个能当强调色用的主题色（去掉接近白/黑的像素后取平均，并提饱和）。 */
object IconColors {

    private val cache = HashMap<String, Int>()

    fun of(ctx: Context, pkg: String, fallback: Int = 0xFF2C6A4E.toInt()): Int {
        cache[pkg]?.let { return it }
        val c = runCatching { compute(ctx, pkg) }.getOrNull() ?: fallback
        cache[pkg] = c
        return c
    }

    private fun compute(ctx: Context, pkg: String): Int {
        val d: Drawable = ctx.packageManager.getApplicationIcon(pkg)
        val bmp = when (d) {
            is BitmapDrawable -> d.bitmap
            else -> Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); d.setBounds(0, 0, 32, 32); d.draw(c)
            }
        }
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        // 只累计“够艳”的像素：直接平均会把互补色调成灰
        val hsv = FloatArray(3)
        var r = 0.0; var g = 0.0; var b = 0.0; var n = 0
        var best = 0; var bestScore = -1f
        for (c in px) {
            if (Color.alpha(c) < 160) continue
            Color.colorToHSV(c, hsv)
            if (hsv[1] < 0.35f || hsv[2] < 0.15f || hsv[2] > 0.95f) continue
            val score = hsv[1] * hsv[2]
            if (score > bestScore) { bestScore = score; best = c }
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        if (n == 0) return if (best != 0) vivid(best) else 0xFF2C6A4E.toInt()

        val avg = Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        // 平均后再往“最艳的那个像素”拉一点，避免被杂色冲淡
        val mixed = Color.rgb(
            (Color.red(avg) * 2 + Color.red(best)) / 3,
            (Color.green(avg) * 2 + Color.green(best)) / 3,
            (Color.blue(avg) * 2 + Color.blue(best)) / 3
        )
        return vivid(mixed)
    }

    /** 抬饱和度/压明度，保证能压在浅色卡片上。 */
    private fun vivid(color: Int): Int {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] = hsv[1].coerceIn(0.62f, 0.90f)
        hsv[2] = hsv[2].coerceIn(0.42f, 0.68f)
        return Color.HSVToColor(hsv)
    }
}
