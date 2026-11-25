package com.zglinus.recstt.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ScrollView;

/**
 * 毛玻璃（高斯模糊）卡片底。
 *
 * <p>两张模糊图，各管一件事：
 * <ul>
 *   <li>{@code bgBlur}：背景图整体模糊，直接当壁纸铺满（bg_blur 控制）；</li>
 *   <li>{@code cardBlur}：卡片用，通常比背景更狠一点（bg_card_blur 控制）。</li>
 * </ul>
 * 两张分开，才可能"背景糊成一片、卡片还能看清字"。
 *
 * <p>卡片绘制时按自己在屏幕上的位置，从 cardBlur 里裁对应区域画进圆角矩形 +
 * 一层白纱。截图式取景，所以卡片里透出的花纹和它背后的背景是对得上的。
 *
 * <p><b>为什么要挂滚动监听</b>：硬件加速下 ScrollView 滚动只平移 canvas，
 * 子 View 的显示列表是缓存的，{@code draw()} 不会重跑。不显式 invalidate，
 * 卡片就会一直贴着"进屏那一刻"的模糊区域 —— 滚起来全错位。
 */
public final class Glass {

    private static Bitmap bgBlur;
    private static Bitmap cardBlur;
    /** 模糊图与屏幕的对应关系：1:1 铺满，offsets 是 CENTER_CROP 的留白（负数或 0）。 */
    private static float offX = 0f, offY = 0f;
    /** 模糊图相对屏幕**需要的放大倍数**：半分辨率时是 2。 */
    private static float blurScale = 1f;
    /** cover 的逻辑尺寸（= 屏幕像素尺度），用于把屏幕坐标精确换算成模糊图像素。 */
    private static float coverW = 1f, coverH = 1f;

    /**
     * 待补队列：模糊图是**异步**生成的（要等壁纸 layout 完才有真实几何），
     * 但卡片是在 buildUi 里同步创建的。那一刻 ready() 还是 false，
     * 直接 return 的话所有卡片都会退化成纯色 —— 这就是"改拉了"的原因。
     * 这里先记下来，等图好了统一补。
     */
    private static final java.util.List<Object[]> pending = new java.util.ArrayList<>();
    /** 只记录前几次绘制，避免刷屏。 */
    private static int drawLog = 0;
    private static int clampLog = 0;
    /** 每张卡片最后一次绘制的屏幕位置（tag → "x,y"）。 */
    private static final java.util.Map<String, String> lastDrawn =
            new java.util.HashMap<>();
    private static final java.util.Map<String, String> lastMapped =
            new java.util.HashMap<>();

    private Glass() {}

    static void setSources(Bitmap background, Bitmap cards, float ox, float oy,
                           float coverW, float coverHIn) {
        Glass.coverW = coverW;
        Glass.coverH = coverHIn;
        blurScale = (cards != null && cards.getWidth() > 0) ? coverW / cards.getWidth() : 1f;
        // 不回收旧图：ImageView / 旧 Drawable 可能还在引用它，
        // 回收后壁纸会整片变空白（真机"都没有背景"就是这么来的）。
        // 交给 GC 处理，宁可多占一点内存。
        bgBlur = background;
        cardBlur = cards;
        offX = ox;
        offY = oy;
        Diag.add("Glass.set", "blurScale=" + blurScale + " off=" + ox + "," + oy
                + " cardBlur=" + (cards == null ? "null" : cards.getWidth() + "x" + cards.getHeight()));
        flushPending();
    }

    /** 把等待中的卡片补上毛玻璃。 */
    private static void flushPending() {
        if (pending.isEmpty() || !ready()) return;
        java.util.List<Object[]> copy = new java.util.ArrayList<>(pending);
        pending.clear();
        Diag.add("Glass.flush", "补 " + copy.size() + " 个");
        int deferred = 0;
        for (Object[] it : copy) {
            final View v = (View) it[0];
            final float r = ((Number) it[1]).floatValue();
            final int a = ((Number) it[2]).intValue();
            if (v == null) continue;
            if (v.getWidth() > 0 && v.getHeight() > 0) {
                apply(v, r, a);
                Ui.retintAfterGlass(v);
            } else {
                // 刚 build 出来的列表行还没 layout，宽高是 0。
                // 之前直接跳过 → 这些行永远拿不到毛玻璃（日志"共 1 张卡片"就是这么来的）。
                // 这里挂一次性 layout 监听，等它量出尺寸再补。
                deferred++;
                v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                    @Override
                    public void onLayoutChange(View view, int l, int t, int rr, int b,
                                               int ol, int ot, int or, int ob) {
                        if (view.getWidth() <= 0 || view.getHeight() <= 0) return;
                        view.removeOnLayoutChangeListener(this);
                        apply(view, r, a);
                        // 毛玻璃装好后再判一次字色：autoTint 跑在这之前，
                        // 当时这行还没有底板，字被当成"压在背景上"改成了浅色。
                        Ui.retintAfterGlass(view);
                    }
                });
            }
        }
        if (deferred > 0) Diag.add("Glass.flush", "延迟 " + deferred + " 个等 layout");
    }

    /** 清掉绘制统计（切页面时调，避免上一页的卡片混进来）。 */
    static void clearStats() {
        lastDrawn.clear();
        lastMapped.clear();
        drawLog = 0;
    }

    static void clear() {
        // 同样不 recycle，只断引用
        bgBlur = null;
        cardBlur = null;
    }

    /** 卡片能不能用毛玻璃。 */
    /** 这个 Drawable 是不是毛玻璃底（用来区分"卡片里的字"和"压在背景上的字"）。 */
    /** 把所有卡片最后一次绘制的坐标写进诊断（点菜单时调用）。 */
    static void dumpLastDraws() {
        Diag.add("Glass.dump", "共 " + lastDrawn.size() + " 张卡片");
        int i = 0;
        for (java.util.Map.Entry<String, String> e : lastDrawn.entrySet()) {
            if (i++ >= 6) break;
            Diag.add("Glass.last", "scr=" + e.getValue()
                    + "  " + lastMapped.get(e.getKey()));
        }
        Diag.add("Glass.dims", "cover=" + Math.round(coverW) + "x" + Math.round(coverH)
                + " off=" + offX + "," + offY + " blurScale=" + blurScale);
    }

    static boolean isGlassBackground(android.graphics.drawable.Drawable d) {
        return d instanceof FrostDrawable;
    }

    static boolean ready() {
        return cardBlur != null && !cardBlur.isRecycled();
    }

    /** 背景壁纸那张：已模糊，直接铺。 */
    static Bitmap wallpaper() {
        return (bgBlur != null && !bgBlur.isRecycled()) ? bgBlur : null;
    }

    static float offX() { return offX; }
    static float offY() { return offY; }

    /**
     * 把 v 的底换成「自己所在区域的模糊 + 圆角 + 白纱」。
     */
    public static void apply(final View v, final float radiusDp, final int scrimAlpha) {
        if (!ready()) {
            pending.add(new Object[]{ v, radiusDp, scrimAlpha });
            Diag.add("Glass.apply", "未就绪→排队 pending=" + pending.size());
            return;
        }
        final FrostDrawable fd = new FrostDrawable(v, radiusDp, scrimAlpha);
        fd.bind(v);
        v.setBackground(fd);
        // 摄像头：bitmap 是按矩形绘制的，圆角只体现在 drawRoundRect 的纱上，
        // 底图会溢出到圆角外（真机"毛玻璃超出容器"）。裁到圆角轮廓内。
        v.setClipToOutline(true);
        final float rd = radiusDp;
        v.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override public void getOutline(View view, android.graphics.Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        Ui.dp(view.getContext(), rd));
            }
        });
        // 尺寸定了才有正确 bounds
        if (v.getWidth() > 0 && v.getHeight() > 0) {
            v.invalidate();
        } else {
            v.post(new Runnable() {
                @Override public void run() { v.invalidate(); }
            });
        }
        watchScroll(v);
    }

    /**
     * 滚动时让卡片重画。
     *
     * <p>不能用 ScrollView.setOnScrollChangeListener：首页列表是 ListView，
     * 走 AbsListView 的 offsetChildrenTopAndBottom，根本不会触发那个回调。
     * 也不用自己往上找父容器 —— 用 ViewTreeObserver 的滚动通知，
     * 窗口里任何滚动（ListView / ScrollView / 嵌套）都会回调，容器无关。
     */
    static void watchScroll(final View v) {
        if (v.getTag(R_ID_SCROLL_HOOK) != null) return; // 已经挂过
        v.setTag(R_ID_SCROLL_HOOK, Boolean.TRUE);
        v.getViewTreeObserver().addOnScrollChangedListener(
                new android.view.ViewTreeObserver.OnScrollChangedListener() {
                    @Override
                    public void onScrollChanged() {
                        // 整个列表都在动，同行里的卡片一起重画
                        View parent = v;
                        View rootView = v.getRootView();
                        invalidateGlass(rootView != null ? rootView : parent);
                    }
                });
        // 位置第一帧还没算出来，先催一次
        v.post(new Runnable() {
            @Override public void run() { v.invalidate(); }
        });
    }

    /** 给 setTag 用一个静态 id，避免和业务 tag 撞。 */
    private static final int R_ID_SCROLL_HOOK = 0x7F0F0001;

    /** 递归让所有毛玻璃子 View 重画（滚动时调用）。 */
    private static void invalidateGlass(View root) {
        if (root == null) return;
        if (root.getBackground() instanceof FrostDrawable) root.invalidate();
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++) {
                invalidateGlass(g.getChildAt(i));
            }
        }
    }

    /** 自己所在区域那一块模糊图 + 圆角裁切 + 白纱。 */
    static final class FrostDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scrim = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final Matrix mtx = new Matrix();
        private final Bitmap srcBmp;
        private final int srcW, srcH;
        private final float radius;

        FrostDrawable(View v, float radiusDp, int scrimAlpha) {
            radius = Ui.dp(v.getContext(), radiusDp);
            // 白纱恒定：卡片永远是"亮底 + 深字"。
            // 背景深浅不影响卡片内部，字色也就不需要跟着变。
            scrim.setColor(Color.argb(scrimAlpha, 255, 255, 255));
            Bitmap src = cardBlur;
            if (src == null || src.isRecycled()) {
                srcBmp = null; srcW = 0; srcH = 0;
                return;
            }
            srcBmp = src;
            srcW = src.getWidth();
            srcH = src.getHeight();
            paint.setFilterBitmap(true);
        }

        @Override
        public void draw(Canvas canvas) {
            if (srcBmp == null) return;
            Rect b = getBounds();
            rect.set(b);

            // 实时裁剪：按卡片此刻在屏幕上的位置，直接从模糊图里取对应那一块。
            //
            // 用 drawBitmap(src, dst) 而不是 Matrix+Shader：前者每一步都是整数像素、
            // 能用日志核对；后者叠两层变换，错了看不出错在哪。
            int[] loc = getLoc();
            int scrX = loc[0] + b.left;
            int scrY = loc[1] + b.top;

            // 屏幕坐标 → 模糊图像素：减壁纸在屏幕上的原点，再按 cover→bitmap 比例缩
            float kx = srcW / coverW;
            float ky = srcH / coverH;
            int sx = Math.round((scrX - offX) * kx);
            int sy = Math.round((scrY - offY) * ky);
            int sw = Math.round(b.width() * kx);
            int sh = Math.round(b.height() * ky);

            // 每张卡片只记"最后一次"：布局过程中会画好几帧，前几帧的位置是临时的，
            // 记下来反而误导（之前就是抓了 4 个中间帧，看不出最终对不对）。
            lastDrawn.put(ownerTag, scrX + "," + scrY);
            lastMapped.put(ownerTag, "src=" + sx + "," + sy + " " + sw + "x" + sh
                    + " k=" + kx + "/" + ky);

            // 越界裁剪：只在可用范围内取，宁可少画，也不要 CLAMP 拉出一条糊边
            int cx = Math.max(0, sx), cy = Math.max(0, sy);
            int cw = Math.min(srcW - cx, sw - (cx - sx));
            int ch = Math.min(srcH - cy, sh - (cy - sy));
            if (cw <= 0 || ch <= 0) return;

            Rect srcRect = new Rect(cx, cy, cx + cw, cy + ch);
            RectF dstRect = new RectF(
                    b.left + (cx - sx) / kx, b.top + (cy - sy) / ky,
                    b.left + (cx - sx + cw) / kx, b.top + (cy - sy + ch) / ky);

            canvas.save();
            canvas.clipRect(dstRect);
            canvas.drawBitmap(srcBmp, srcRect, dstRect, paint);
            canvas.restore();
            canvas.drawRoundRect(rect, radius, radius, scrim);
        }

        private String ownerTag = "?";
        private final int[] locCache = new int[2];
        private View owner;
        private int[] getLoc() {
            if (owner == null) return locCache;
            owner.getLocationOnScreen(locCache);
            return locCache;
        }

        void bind(View v) {
            owner = v;
            ownerTag = Integer.toHexString(System.identityHashCode(v));
        }

        @Override public void setAlpha(int alpha) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /**
     * 模糊图生成。
     *
     * <p>之前是"缩到 1/18 再放大"——放大后的像素块在卡片裁切时看得很明显，
     * 真机反馈就是"分辨率好低"。
     *
     * <p>现在：先降到 1/2（省一半计算量、又保住细节），再在低分辨率上做两趟
     * 盒式模糊（水平+垂直各一遍 ×2），最后放大回原尺寸。
     * 盒式模糊是三次的近似高斯，且不会产生"块状"放大痕迹。
     */
    /**
     * 模糊图，**降到屏幕分辨率的一半**再算，输出也保持半分辨率。
     *
     * <p>比 1/18 那种狠降采样清晰得多（那个放大后是色块），
     * 又比全分辨率省 3/4 的计算量 —— 卡片按区域裁切时肉眼看不出差别。
     *
     * <p>绘制时会按 {@link #blurScale} 把它放大回屏幕尺寸，所以坐标换算必须带上这个比例。
     */
    static Bitmap blur(Bitmap src, int radiusPx) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        if (w <= 0 || h <= 0) return null;

        int sw = Math.max(1, w / 2), sh = Math.max(1, h / 2);
        Bitmap half = Bitmap.createScaledBitmap(src, sw, sh, true);
        int[] px = new int[sw * sh];
        half.getPixels(px, 0, sw, 0, 0, sw, sh);

        // 半径按同样的比例折算，模糊的视觉强度才和设置值一致
        int r = Math.max(1, Math.round(radiusPx / 2f));
        boxBlur(px, sw, sh, r, true);
        boxBlur(px, sw, sh, r, false);
        boxBlur(px, sw, sh, Math.max(1, r / 2), true);
        boxBlur(px, sw, sh, Math.max(1, r / 2), false);

        Bitmap out = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
        out.setPixels(px, 0, sw, 0, 0, sw, sh);
        if (half != src && half != out) half.recycle();
        return out;
    }

    /** 一维盒式模糊（可水平/垂直）。原地改 px。 */
    private static void boxBlur(int[] px, int w, int h, int r, boolean horizontal) {
        if (r <= 0) return;
        int len = horizontal ? w : h;   // 每行/列长度
        int lines = horizontal ? h : w; // 有多少行/列
        int[] line = new int[len];
        int div = r * 2 + 1;

        for (int i = 0; i < lines; i++) {
            // 取出一行/列
            for (int j = 0; j < len; j++) {
                line[j] = horizontal ? px[i * w + j] : px[j * w + i];
            }
            // 前缀和求滑动平均
            long a = 0, rr = 0, g = 0, b = 0;
            for (int j = -r; j <= r; j++) {
                int v = line[Math.max(0, Math.min(len - 1, j))];
                a += (v >>> 24) & 0xFF; rr += (v >> 16) & 0xFF;
                g += (v >> 8) & 0xFF; b += v & 0xFF;
            }
            for (int j = 0; j < len; j++) {
                line[j] = ((int) (a / div) << 24) | ((int) (rr / div) << 16)
                        | ((int) (g / div) << 8) | (int) (b / div);
                int out = Math.max(0, Math.min(len - 1, j - r));
                int in = Math.max(0, Math.min(len - 1, j + r + 1));
                int vo = line[out];
                int vi = (horizontal ? px[i * w + in] : px[in * w + i]);
                a += ((vi >>> 24) & 0xFF) - ((vo >>> 24) & 0xFF);
                rr += ((vi >> 16) & 0xFF) - ((vo >> 16) & 0xFF);
                g += ((vi >> 8) & 0xFF) - ((vo >> 8) & 0xFF);
                b += (vi & 0xFF) - (vo & 0xFF);
            }
            // 写回
            for (int j = 0; j < len; j++) {
                if (horizontal) px[i * w + j] = line[j];
                else px[j * w + i] = line[j];
            }
        }
    }

    /** 取背景图靠近顶部那一带的平均色。 */
    static int topColor(Bitmap src) {
        if (src == null) return 0xFF1D1B20;
        int w = src.getWidth(), h = src.getHeight();
        int bandH = Math.max(1, h / 12);
        long r = 0, g = 0, b = 0;
        int n = 0;
        int stepX = Math.max(1, w / 64);
        int stepY = Math.max(1, bandH / 8);
        for (int y = 0; y < bandH; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                int p = src.getPixel(x, y);
                r += Color.red(p); g += Color.green(p); b += Color.blue(p);
                n++;
            }
        }
        if (n == 0) return 0xFF1D1B20;
        return Color.rgb((int) (r / n), (int) (g / n), (int) (b / n));
    }

    /** 相对亮度 0..1。 */
    static double luminance(int color) {
        double r = Color.red(color) / 255.0, g = Color.green(color) / 255.0, b = Color.blue(color) / 255.0;
        r = r <= 0.03928 ? r / 12.92 : Math.pow((r + 0.055) / 1.055, 2.4);
        g = g <= 0.03928 ? g / 12.92 : Math.pow((g + 0.055) / 1.055, 2.4);
        b = b <= 0.03928 ? b / 12.92 : Math.pow((b + 0.055) / 1.055, 2.4);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    static int darken(int color, float factor) {
        return Color.rgb(
                (int) (Color.red(color) * factor),
                (int) (Color.green(color) * factor),
                (int) (Color.blue(color) * factor));
    }
}
