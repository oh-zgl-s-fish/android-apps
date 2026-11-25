package com.zglinus.playcheck.ui

import android.graphics.*
import android.graphics.drawable.Drawable
import android.view.View

/**
 * 毛玻璃卡片背景：从“已模糊的壁纸”里裁剪本卡片所在区域铺进来，
 * 再叠一层白薄层与 1dp 描边。壁纸本身是清晰的，只有卡片内部是磨砂。
 */
class GlassBg(
    private val view: View,
    private val radiusPx: Float,
    private val tintAlpha: Int,   // 白色薄层不透明度 0..255
    private val strokeAlpha: Int = 0x66,
    /** 与壁纸同步的压暗量（0..255）：卡片里的模糊底也要一起变暗。 */
    private val darkenAlpha: Int = 0,
    /** 取景锚点：平移时用锚点+布局偏移取景，避免每帧重新裁剪。 */
    private val anchor: View? = null
) : Drawable() {

    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        isFilterBitmap = true
        // 降饱和：卡片里的底图统一偏灰，不同壁纸区域看起来才是一套玻璃
        colorFilter = ColorMatrixColorFilter(
            ColorMatrix().apply { setSaturation(0.45f) }
        )
    }
    private val tint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * view.resources.displayMetrics.density
        color = Color.argb(strokeAlpha, 255, 255, 255)
    }
    private val path = Path()
    private val matrix = Matrix()

    override fun draw(canvas: Canvas) {
        val bmp = Wallpaper.blurred
        val w = bounds.width()
        val h = bounds.height()
        if (w <= 0 || h <= 0) return

        path.reset()
        path.addRoundRect(0f, 0f, w.toFloat(), h.toFloat(), radiusPx, radiusPx, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(path)

        if (bmp != null) {
            val loc = IntArray(2)
            val a = anchor
            if (a != null && view.parent === a) {
                // 取景锚点：只在“锚点就是父容器”时可用，否则会算到别的坐标系上
                a.getLocationInWindow(loc)
                loc[0] += view.left + view.translationX.toInt()
                loc[1] += view.top + view.translationY.toInt()
            } else {
                view.getLocationInWindow(loc)
            }
            // 按“这张模糊图铺满屏幕”的 CENTER_CROP 几何算，别借用清晰图那套缩放
            val screenW = view.rootView.width.takeIf { it > 0 } ?: view.resources.displayMetrics.widthPixels
            val screenH = view.rootView.height.takeIf { it > 0 } ?: view.resources.displayMetrics.heightPixels
            val sc = maxOf(screenW.toFloat() / bmp.width, screenH.toFloat() / bmp.height).takeIf { it > 0f } ?: 1f
            val offX = (screenW - bmp.width * sc) / 2f
            val offY = (screenH - bmp.height * sc) / 2f
            // 整张模糊图按“铺满屏幕”落位，再平移到本视图的局部坐标系，整幅画出去由圆角路径裁剪。
            // 旧写法按 Rect 取样再 coerceIn 夹到图边界：卡片一旦滑到屏幕左外侧，sx 变负 →
            // left 被钉死在 0，而 right 又是拿夹过的 left 算的 → 源矩形恒为 [0, w/sc]，
            // 与卡片位置无关，玻璃纹理就“冻”住不动了（滑出去的那一段正是后半程）。
            matrix.setScale(sc, sc)
            matrix.postTranslate(offX - loc[0], offY - loc[1])
            canvas.drawBitmap(bmp, matrix, bmpPaint)
        }
        if (darkenAlpha > 0) {
            tint.color = Color.argb(darkenAlpha, 0, 0, 0)
            canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tint)
        }
        tint.color = Color.argb(tintAlpha, 255, 255, 255)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), tint)
        canvas.restore()
        canvas.drawPath(path, stroke)
    }

    override fun setAlpha(a: Int) { tint.alpha = a }
    override fun setColorFilter(cf: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
}
