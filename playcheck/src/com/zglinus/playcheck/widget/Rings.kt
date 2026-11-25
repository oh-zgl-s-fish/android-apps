package com.zglinus.playcheck.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * 小部件里的环形图：RemoteViews 画不了自定义 View，
 * 所以在这里直接把圆环渲染成 Bitmap，再用 setImageViewBitmap 推给桌面。
 */
object Rings {

    fun render(sizePx: Int, progress: Float, color: Int, trackColor: Int = 0x330F1418): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val stroke = sizePx * 0.09f
        val pad = stroke / 2f + 1f
        val rect = RectF(pad, pad, sizePx - pad, sizePx - pad)

        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
        }
        p.color = trackColor
        c.drawArc(rect, 0f, 360f, false, p)

        p.color = color
        val sweep = 360f * progress.coerceIn(0f, 1f)
        if (sweep > 0.1f) c.drawArc(rect, -90f, sweep, false, p)
        return bmp
    }
}
