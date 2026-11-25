package com.zglinus.recstt.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;

import com.zglinus.recstt.R;
import com.zglinus.recstt.store.Db;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/** MD3 小工具：dp、圆角、文字、图标按钮、chip、时间格式化。 */
public final class Ui {
    // ------------------------------------------------------------------ 调色板
    //
    // 这些原来是 static final 常量，只能有一套颜色。现在规则是：
    //   · 配了壁纸      → 一直用浅色板，字色由壁纸明暗决定（Bg.bgDark 那套调色）
    //   · 纯色模式      → 跟随系统深色：系统深色就整张换成 MD3 深色板，不另外调色
    // 所以改成非 final，由每个 Activity 在 super.onCreate 之前调 init() 装好，
    // 建界面时读到的就是对的。调用点（Ui.ON_SURFACE 之类）一处都不用改。
    public static int PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER,
            SECONDARY_CONTAINER, ON_SECONDARY_CONTAINER,
            SURFACE, SURFACE_CONTAINER, SURFACE_HIGH, SURFACE_HIGHEST,
            ON_SURFACE, ON_SURFACE_VARIANT,
            OUTLINE, OUTLINE_VARIANT,
            ERROR, ERROR_CONTAINER, SUCCESS, SUCCESS_CONTAINER;

    private static final int[] PALETTE_LIGHT = {
            0xFF6750A4, 0xFFFFFFFF, 0xFFEADDFF, 0xFF21005D,
            0xFFE8DEF8, 0xFF1D192B,
            0xFFFEF7FF, 0xFFF3EDF7, 0xFFECE6F0, 0xFFE6E0E9,
            0xFF1D1B20, 0xFF49454F,
            0xFF79747E, 0xFFCAC4D0,
            0xFFB3261E, 0xFFF9DEDC, 0xFF2E6B34, 0xFFC8F0C9,
    };

    private static final int[] PALETTE_DARK = {
            0xFFD0BCFF, 0xFF381E72, 0xFF4F378B, 0xFFEADDFF,
            0xFF4A4458, 0xFFE8DEF8,
            0xFF141218, 0xFF211F26, 0xFF2B2930, 0xFF36343B,
            0xFFE6E0E9, 0xFFCAC4D0,
            0xFF938F99, 0xFF49454F,
            0xFFF2B8B5, 0xFF8C1D18, 0xFF7FD48A, 0xFF1F3B24,
    };

    private static boolean darkPalette;

    static {
        applyPalette(false); // 兜底：万一有静态路径先用到颜色，也不是全 0
    }

    /** 给调色板颜色换一个 alpha（0-255）。写死 0xF2FEF7FF 这类字面量的地方都该走它。 */
    public static int alpha(int color, int a) {
        return (color & 0x00FFFFFF) | ((a & 0xFF) << 24);
    }

    public static boolean isDarkPalette() {
        return darkPalette;
    }

    /** 纯色模式且系统在深色。配了壁纸就一律 false（颜色交给壁纸）。 */
    public static boolean followSystemDark(Context c) {
        return c != null && !hasBg(c) && isSystemNight(c);
    }

    public static boolean isSystemNight(Context c) {
        if (c == null) return false;
        return (c.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * 每个 Activity 必须在 {@code super.onCreate()} **之前**调用它。
     *
     * <p>做两件事：选主题（深色主题只管窗口底色和 AlertDialog/Switch 这些系统控件），
     * 装调色板（我们自绘的控件颜色）。两处必须同一套，否则会出现"夜里一个白对话框"。
     */
    public static void init(Activity a) {
        if (a == null) return;
        boolean dark = followSystemDark(a);
        android.util.Log.i("UiInit", a.getClass().getSimpleName()
                + " hasBg=" + hasBg(a) + " night=" + isSystemNight(a) + " dark=" + dark);
        darkPalette = dark;
        applyPalette(dark);
        a.setTheme(dark ? R.style.AppTheme_Dark : R.style.AppTheme);
    }

    private static void applyPalette(boolean dark) {
        int[] p = dark ? PALETTE_DARK : PALETTE_LIGHT;
        PRIMARY = p[0];
        ON_PRIMARY = p[1];
        PRIMARY_CONTAINER = p[2];
        ON_PRIMARY_CONTAINER = p[3];
        SECONDARY_CONTAINER = p[4];
        ON_SECONDARY_CONTAINER = p[5];
        SURFACE = p[6];
        SURFACE_CONTAINER = p[7];
        SURFACE_HIGH = p[8];
        SURFACE_HIGHEST = p[9];
        ON_SURFACE = p[10];
        ON_SURFACE_VARIANT = p[11];
        OUTLINE = p[12];
        OUTLINE_VARIANT = p[13];
        ERROR = p[14];
        ERROR_CONTAINER = p[15];
        SUCCESS = p[16];
        SUCCESS_CONTAINER = p[17];
    }


    /**
     * 正文/标题用的前景色：背景偏暗时换浅色，否则深底深字看不见。
     * 调用点仍然写 Ui.ON_SURFACE，这里不动它们，改成方法就需要改几十处，
     * 所以用 onSurface() 在少数关键位置取动态值。
     */
    public static int onSurface() {
        return Bg.bgDark ? 0xFFF2EFF5 : ON_SURFACE;
    }

    /** 次要文字色，同样跟着背景走。 */
    public static int onSurfaceVariant() {
        return Bg.bgDark ? 0xFFCFC8D6 : ON_SURFACE_VARIANT;
    }

    /**
     * 直接压在背景图上的文字（顶栏标题、统计行、空态）用它。
     * 与 onSurface() 的区别：那个是"给卡片用"的（卡片有白纱，永远深色）；
     * 这个是"没有底板、直接贴背景"的，背景暗就必须换浅色。
     */
    public static int onBg() {
        return Bg.bgDark ? 0xFFF7F4FA : ON_SURFACE;
    }

    /** 压在背景上的次要文字。 */
    public static int onBgVariant() {
        return Bg.bgDark ? 0xFFDDD6E4 : ON_SURFACE_VARIANT;
    }


    /**
     * 给"直接压在背景图上"的文字自动换色。
     *
     * <p>为什么需要递归扫：手动一处处改必然漏（真机反馈"好多文字没换色"）。
     * 这里在界面搭完之后跑一遍：凡是没有毛玻璃底板、颜色又是默认深色的 TextView，
     * 统统换成 onBg()/onBgVariant()。
     *
     * <p>有毛玻璃底板的（卡片）不动 —— 卡片是白纱亮底，字本来就该深色。
     */
    public static void autoTintBgText(View v) {
        autoTint(v, false);
    }

    /**
     * @param solid 祖先已经有"自己的底"（卡片、白纱、chip）——里面的字不该跟着背景翻色
     */
    private static void autoTint(View v, boolean solid) {
        if (v == null) return;
        // 没有背景图就用 MD3 默认深色字，别跟着上一个界面的 bgDark 走
        if (v.getContext() != null && !hasBg(v.getContext())) return;
        boolean own = solid || hasOwnBackdrop(v) || CHIP_TAG.equals(v.getTag());
        if (v instanceof android.view.ViewGroup) {
            if (own) {
                // 有底的子树：先把上一轮可能误翻成浅色的字收回来，再往下就不用扫了
                retintAfterGlass(v);
                return;
            }
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                autoTint(g.getChildAt(i), false);
            }
            return;
        }
        if (own) return;
        // 图标按钮是 ImageView（用 tint 上色），不是 TextView ——
        // 之前完全没管，所以顶栏那些图标在深背景上一直是深色。
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        int cur = t.getCurrentTextColor();
        if (cur == ON_SURFACE) {
            t.setTextColor(onBg());
        } else if (cur == ON_SURFACE_VARIANT) {
            t.setTextColor(onBgVariant());
        }
    }

    /**
     * 毛玻璃底板装好之后调用：这棵子树里的文字重新回到深色。
     *
     * <p>为什么需要：autoTintBgText 在界面搭完时跑，那时列表行还没有底板，
     * 字被当成"压在背景上"改成浅色；等底板装上，浅字压白纱就是白底白字。
     * 所以有底板的子树要反向恢复。
     */
    public static void retintAfterGlass(View v) {
        if (v == null) return;
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) retintAfterGlass(g.getChildAt(i));
            return;
        }
        if (!(v instanceof TextView)) return;
        TextView t = (TextView) v;
        int cur = t.getCurrentTextColor();
        if (cur == onBg()) t.setTextColor(ON_SURFACE);
        else if (cur == onBgVariant()) t.setTextColor(ON_SURFACE_VARIANT);
    }

    /**
     * 这个 View 有没有"自己的底"——卡片、白纱、chip 这类有实底的容器。
     *
     * <p>以前这里是个 `return false` 的空壳，判定只靠 Glass 的 FrostDrawable。
     * 可模板卡片用的是 pressable(GradientDrawable)，被当成"没底"，
     * 于是卡片里的深色字被翻成浅色 —— 白卡片白字，整页看不清（真机复现）。
     *
     * <p>现在的规矩：**只要有背景、且不是全透明，就算有底**。
     * 背景壁纸是 root 底下的一层 ImageView，不占 View.background，
     * root 自己挂的是 ColorDrawable(全透明)，所以不会被误判。
     */
    private static boolean hasOwnBackdrop(View v) {
        android.graphics.drawable.Drawable d = v.getBackground();
        if (d == null) return false;
        if (Glass.isGlassBackground(d)) return true;
        if (d instanceof android.graphics.drawable.ColorDrawable) {
            // 透明色（含 Ui 里那些 0x00000000 占位底）不算底
            return ((android.graphics.drawable.ColorDrawable) d).getColor() >>> 24 != 0;
        }
        // StateListDrawable / RippleDrawable 包着的圆角实色等：一律当有底
        return true;
    }

    private Ui() {}

    /**
     * targetSdk 36 起强制 edge-to-edge，顶栏会压到状态栏下面。
     * 给内容根视图补 systemBars + 刘海的内边距（在原有 padding 上叠加，可重复调用）。
     */
    public static void applySystemBarsInsets(final Activity a, final View v) {
        final int l = v.getPaddingLeft(), t = v.getPaddingTop();
        final int r = v.getPaddingRight(), b = v.getPaddingBottom();
        v.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View view, WindowInsets insets) {
                int top, bottom, left, right;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    android.graphics.Insets bars = insets.getInsets(
                            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                    top = bars.top; bottom = bars.bottom; left = bars.left; right = bars.right;
                } else {
                    top = insets.getSystemWindowInsetTop();
                    bottom = insets.getSystemWindowInsetBottom();
                    left = insets.getSystemWindowInsetLeft();
                    right = insets.getSystemWindowInsetRight();
                }
                view.setPadding(l + left, t + top, r + right, b + bottom);
                return insets;
            }
        });
        v.requestApplyInsets();
    }

    public static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** 是否真的铺了自定义背景图（决定卡片要不要靠描边划边界）。 */
    public static boolean hasBg(Context c) {
        try {
            String p = Db.get(c).kv("bg_path", "");
            if (p == null || p.trim().isEmpty()) return false;
            return !"0".equals(Db.get(c).kv("bg_enabled", "1").trim());
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 卡片底：有背景图时用半透明表面（让图透出来），
     * 没有背景时表面和背景同色，必须补 1dp 描边，否则容器边界完全看不见。
     */
    public static GradientDrawable card(Context c, int onBgColor, float radiusDp) {
        if (hasBg(c)) return round(onBgColor, radiusDp, c);
        return round(SURFACE, radiusDp, OUTLINE_VARIANT, 1f, c);
    }

    /**
     * 给容器上毛玻璃底：有背景图就按它所在位置裁模糊图，没背景就退回描边卡。
     * 返回 true 表示已经接管（调用方别再 setBackground 覆盖）。
     */
    /** 顶栏用的毛玻璃条：圆角 0，别让状态栏和标题栏之间出现一道白杠。 */
    public static boolean glassBar(View v, int scrimAlpha) {
        Context c = v.getContext();
        if (hasBg(c)) {
            Glass.apply(v, 0f, scrimAlpha == 0 ? 0xCC : scrimAlpha);
            return true;
        }
        return false;
    }

    public static boolean glassCard(View v, float radiusDp, int scrimAlpha) {
        Context c = v.getContext();
        if (hasBg(c)) {
            // 不要再判 Glass.ready()：列表行是在 layout 阶段 build 的，
            // 那时模糊图还没生成好。判 ready 会让它直接走兜底、连队列都进不去，
            // 结果就是"毛玻璃全没了"（日志里连 Glass.flush 都没有）。
            // apply() 内部会排队，等图好了自动补上。
            Glass.apply(v, radiusDp, scrimAlpha);
            return true;
        }
        v.setBackground(card(c, SURFACE_CONTAINER, radiusDp));
        return false;
    }

    public static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable round(int color, float radiusDp, int strokeColor, float strokeDp, Context c) {
        GradientDrawable d = round(color, radiusDp, c);
        d.setStroke(Math.max(1, dp(c, strokeDp)), strokeColor);
        return d;
    }

    public static TextView tv(Context c, String text, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    public static ImageView icon(Context c, int res, int tint, float dpSize) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        int s = dp(c, dpSize);
        v.setLayoutParams(new LinearLayout.LayoutParams(s, s));
        return v;
    }

    public static View iconButton(Context c, int res, int tint, View.OnClickListener l) {
        ImageView v = icon(c, res, tint, 24);
        int pad = dp(c, 12);
        v.setPadding(pad, pad, pad, pad);
        v.setOnClickListener(l);
        v.setClickable(true);
        v.setBackground(round(0x00000000, 24, c));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 44), dp(c, 44)));
        return v;
    }

    public static TextView chip(Context c, String text, boolean selected) {
        // 未选中必须有**不透明底 + 深字**：原来只有透明底 + 灰字，
        // 压在背景图/深色面上对比度极低，三个按钮看着像"灰掉的死按钮"，
        // 点一下才变色 —— 这就是「点击后会变色」的由来。
        TextView t = tv(c, text, 13,
                selected ? ON_SECONDARY_CONTAINER : ON_SURFACE, selected);
        int ph = dp(c, 12), pv = dp(c, 7);
        t.setPadding(ph, pv, ph, pv);
        t.setBackground(selected
                ? round(SECONDARY_CONTAINER, 8, c)
                : round(SURFACE_CONTAINER, 8, OUTLINE_VARIANT, 1f, c));
        // 标记：这个控件的底色是设计好的，别让 autoTintBgText 改它的字色
        t.setTag(CHIP_TAG);
        return t;
    }

    /** chip 的标记值，autoTintBgText 见到就跳过。 */
    private static final String CHIP_TAG = "ui:chip";

    public static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    public static LinearLayout col(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    public static LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    public static LinearLayout.LayoutParams lpWeight(int w, int h, float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.weight = weight;
        return p;
    }

    public static String dur(long ms) {
        if (ms < 0) return "—";
        long s = ms / 1000;
        long h = s / 3600, m = (s % 3600) / 60, ss = s % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, ss);
        return String.format("%02d:%02d", m, ss);
    }

    public static String mdhm(long epochMs) {
        if (epochMs <= 0) return "时间未知";
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.setTimeInMillis(epochMs);
        return String.format("%02d-%02d %02d:%02d",
                c.get(java.util.Calendar.MONTH) + 1, c.get(java.util.Calendar.DAY_OF_MONTH),
                c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE));
    }

    public static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format("%02d:%02d", s / 60, s % 60);
    }
}
