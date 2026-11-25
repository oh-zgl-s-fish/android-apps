package com.zglinus.playcheck.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.zglinus.playcheck.data.Appearance
import kotlin.math.max

/**
 * 壁纸只算一次：读图 -> 缩到 1/8 做 box blur -> 放大，得到“预渲染毛玻璃”底图。
 * 界面拿它当窗口背景，卡片再用半透明 surface 叠上去。
 */
object Wallpaper {
    /** 清晰的原图：铺满窗口当背景。 */
    var sharp: Bitmap? = null
        private set
    /** 预模糊副本：只给玻璃卡片做背衬。 */
    var blurred: Bitmap? = null
        private set
    /** 壁纸整体是否偏暗：暗→白字，亮→深字。 */
    var isDark: Boolean = false
        private set

    /** 与 ImageView(CENTER_CROP) 一致的几何：屏幕坐标 → 位图像素。 */
    var coverScale: Float = 1f
        private set
    var coverOffX: Float = 0f
        private set
    var coverOffY: Float = 0f
        private set
    private var loadedUri: String? = null
    private var loadedRadius: Int = -1
    private var loadedDarken: Int = -1

    fun has(): Boolean = sharp != null

    /** 清掉壁纸：位图与缓存关系一起断掉，否则 ImageView 还挂着旧图。 */
    fun clear() {
        sharp = null
        blurred = null
        isDark = false
        loadedUri = null
        loadedRadius = -1
        loadedDarken = -1
    }

    /** 换了图、改了模糊半径或压暗，都要重算——否则界面看着“没重绘”。 */
    fun ensure(ctx: Context, app: Appearance, w: Int, h: Int, force: Boolean = false) {
        val uri = app.wallpaperUri
        if (uri.isNullOrEmpty()) { sharp = null; blurred = null; isDark = false; loadedUri = null; loadedRadius = -1; loadedDarken = -1; return }
        if (!force && sharp != null && loadedUri == uri &&
            loadedRadius == app.blurRadius && loadedDarken == app.darken) return
        loadedRadius = app.blurRadius
        loadedDarken = app.darken
        val src = load(ctx, uri, w, h) ?: run { sharp = null; blurred = null; loadedUri = null; return }
        sharp = src
        blurred = blur(src, app.blurRadius)
        isDark = luminance(src) < 0.45f
        // CENTER_CROP：按屏幕尺寸算缩放与留白，卡片取景才对得上
        coverScale = maxOf(w.toFloat() / src.width, h.toFloat() / src.height)
        coverOffX = (w - src.width * coverScale) / 2f
        coverOffY = (h - src.height * coverScale) / 2f
        loadedUri = uri
    }

    private fun load(ctx: Context, uri: String, w: Int, h: Int): Bitmap? = runCatching {
        val parsed = android.net.Uri.parse(uri)
        val probe = ctx.contentResolver.openInputStream(parsed) ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(probe, null, opts)
        probe.close()
        var scale = 1
        while (opts.outWidth / (scale * 2) >= w && opts.outHeight / (scale * 2) >= h) scale *= 2
        val input = ctx.contentResolver.openInputStream(parsed) ?: return null
        val bmp = BitmapFactory.decodeStream(input, null, BitmapFactory.Options().apply { inSampleSize = scale })
        input.close()
        bmp
    }.getOrNull()

    /** 缩到 32×32 采样求相对亮度（0..1）。 */
    private fun luminance(src: Bitmap): Float {
        val small = Bitmap.createScaledBitmap(src, 32, 32, true)
        val px = IntArray(32 * 32)
        small.getPixels(px, 0, 32, 0, 0, 32, 32)
        var sum = 0f
        for (c in px) {
            val r = (c shr 16) and 0xff
            val g = (c shr 8) and 0xff
            val b = c and 0xff
            sum += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
        }
        return sum / px.size
    }

    private fun blur(src: Bitmap, radius: Int): Bitmap {
        val small = Bitmap.createScaledBitmap(src, max(2, src.width / 8), max(2, src.height / 8), true)
        val out = small.copy(Bitmap.Config.ARGB_8888, true)
        boxBlur(out, max(1, radius / 4))
        return Bitmap.createScaledBitmap(out, src.width, src.height, true)
    }

    private fun boxBlur(bmp: Bitmap, r: Int) {
        val w = bmp.width; val h = bmp.height
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val tmp = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (dx in -r..r) {
                val xx = (x + dx).coerceIn(0, w - 1)
                val c = src[y * w + xx]
                a += c ushr 24; rr += (c shr 16) and 0xff; gg += (c shr 8) and 0xff; bb += c and 0xff; n++
            }
            tmp[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
        }
        for (y in 0 until h) for (x in 0 until w) {
            var a = 0; var rr = 0; var gg = 0; var bb = 0; var n = 0
            for (dy in -r..r) {
                val yy = (y + dy).coerceIn(0, h - 1)
                val c = tmp[yy * w + x]
                a += c ushr 24; rr += (c shr 16) and 0xff; gg += (c shr 8) and 0xff; bb += c and 0xff; n++
            }
            src[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((gg / n) shl 8) or (bb / n)
        }
        bmp.setPixels(src, 0, w, 0, 0, w, h)
    }
}
