package com.zglinus.recstt.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.zglinus.recstt.store.Db;

/** 自定义背景：铺图（可选模糊）+ 半透明黑色遮罩。所有界面在最底层调用 attach()。 */
public final class Bg {
    private Bg() {}

    /** 清掉上一次铺的背景层（含模糊图与遮罩），用于设置变更后重铺。 */
    /** 无背景时把毛玻璃缓存与状态栏恢复成默认。 */
    public static void reset(Activity a) {
        Glass.clear();
        bgDark = false;
        if (a != null && a.getWindow() != null) {
            a.getWindow().setStatusBarColor(Color.TRANSPARENT);
            a.getWindow().setNavigationBarColor(Ui.SURFACE);
            View dv = a.getWindow().getDecorView();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                a.getWindow().getInsetsController().setSystemBarsAppearance(
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            } else {
                dv.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            }
        }
    }

    public static void detach(FrameLayout root) {
        // 背景层永远在 index 0/1（attach 时 addView(view, 0) 和 addView(sc, 1)），
        // 之前写成只看最后两个子 View，等于永远删不到 → 旧图一直压在新图上面。
        for (int i = root.getChildCount() - 1; i >= 0; i--) {
            View v = root.getChildAt(i);
            if (v != null && "recstt-bg".equals(v.getTag())) {
                root.removeViewAt(i);
            }
        }
    }

    /** 背景就绪回调。onCreate 里 autoTint 跑得太早（bgDark 还没算），靠这个补救。 */
    public static void setOnReady(Runnable r) {
        onReady = r;
    }

    public static void attach(Activity a, FrameLayout root) {
        // 先归零：bgDark 是静态的，会从上一个界面残留下来。
        // 主界面深色背景设了 true，切到无背景的模板页没重置 → 深色字被改成浅色、
        // 落在白底上就看不见（真机反馈"模板设置里面字的颜色错了"）。
        // 纯色模式 + 系统深色 → 整屏就是深底，bgDark 必须为 true，
        // 否则状态栏图标会按"浅底"配成深色，压在深色底上看不见。
        bgDark = Ui.followSystemDark(a);
        // 这里 DecorView 可能还没建好 —— 真机直接 NPE：
        // PhoneWindow.getInsetsController() 内部拿到 null DecorView。
        // 等这一帧的 layout 走完再设图标。
        final Activity act0 = a;
        root.post(new Runnable() {
            @Override public void run() {
                applySystemBarIcons(act0, bgDark);
            }
        });
        Glass.clear();
        Glass.clearStats();
        Diag.setContext(a);
        Diag.add("Bg.attach", "act=" + a.getClass().getSimpleName());
        // 记下这次的配置指纹：onResume 会拿它比对，一样就不重复铺
        try {
            Db dbx = Db.get(a);
            lastFingerprint = dbx.kv("bg_path", "") + "|" + dbx.kv("bg_enabled", "1")
                    + "|" + dbx.kv("bg_blur", "24") + "|" + dbx.kv("bg_card_blur", "16")
                    + "|" + dbx.kv("bg_scrim", "45");
        } catch (Throwable ignored) { }
        // 统一在这里开 edge-to-edge：背景图必须铺到状态栏和导航栏底下，
        // 否则状态栏那一条是默认底色，看着就是"背景没铺满"。
        // 之前只有 Main/Templates/TemplateEdit 各自调过，Reader/Settings/Appear 漏了。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            a.getWindow().setDecorFitsSystemWindows(false);
            a.getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }
        Db db = Db.get(a);
        String path = db.kv("bg_path", "");
        String enabled = db.kv("bg_enabled", "1");
        Diag.add("Bg.cfg", "hasPath=" + (path != null && !path.isEmpty())
                + " enabled=" + enabled
                + " blur=" + db.kv("bg_blur", "24") + " cardBlur=" + db.kv("bg_card_blur", "16"));
        if (path == null || path.isEmpty()) { Diag.add("Bg", "无背景路径 → 返回"); return; }
        if ("0".equals(enabled)) { Diag.add("Bg", "背景已关闭 → 返回"); return; }

        int blur = intOf(db.kv("bg_blur", "24"), 24);
        int scrim = intOf(db.kv("bg_scrim", "45"), 45);

        BitmapFactory.Options o = new BitmapFactory.Options();
        // 按屏幕宽度取样，别一律压到 1/2：先糊一层再叠模糊 = 糊上加糊
        o.inSampleSize = 1;
        o.inPreferredConfig = Bitmap.Config.RGB_565; // 背景图不需要 alpha，省一半内存
        Bitmap bmp;
        try {
            bmp = BitmapFactory.decodeFile(path, o);
        } catch (Throwable t) {
            bmp = null;
        }
        if (bmp == null) {
            Diag.add("Bg.decode", "解码失败（返回 null）");
            return;
        }
        Diag.add("Bg.decode", "位图=" + bmp.getWidth() + "x" + bmp.getHeight());

        // 壁纸：原图 + RenderEffect 模糊（这版是真机能显示、能模糊的那版）
        ImageView iv = new ImageView(a);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bmp);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blur > 0) {
            float px = blur * a.getResources().getDisplayMetrics().density;
            iv.setRenderEffect(RenderEffect.createBlurEffect(px, px, Shader.TileMode.CLAMP));
        }
        iv.setTag("recstt-bg");
        root.addView(iv, 0, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 等壁纸 layout 完，用它**实际**的 imageMatrix 反推几何，
        // 再生成卡片模糊图 —— 自己复算 CENTER_CROP 一定会有细微偏差（对齐问题就是这么来的）。
        final Activity act = a;
        final Bitmap srcBmp = bmp;
        iv.post(new Runnable() {
            @Override public void run() {
                if (iv.getWidth() == 0 || iv.getHeight() == 0) {
                    Diag.add("Bg.post", "壁纸未 layout（" + iv.getWidth() + "x" + iv.getHeight() + "）");
                }
                android.graphics.Matrix m = iv.getImageMatrix();
                float[] v = new float[9];
                m.getValues(v);
                // imageMatrix 是「位图坐标 → View 坐标」：scale + translate
                float imgScale = v[android.graphics.Matrix.MSCALE_X];
                float imgDx = v[android.graphics.Matrix.MTRANS_X];
                float imgDy = v[android.graphics.Matrix.MTRANS_Y];
                int[] ivLoc = new int[2];
                iv.getLocationOnScreen(ivLoc);
                Diag.add("Bg.matrix", "imgScale=" + imgScale
                        + " iv=" + iv.getWidth() + "x" + iv.getHeight()
                        + " dx=" + imgDx + " dy=" + imgDy
                        + " ivLoc=" + ivLoc[0] + "," + ivLoc[1]);
                installGlass(act, srcBmp, imgScale, ivLoc[0] + imgDx, ivLoc[1] + imgDy);
            }
        });

        View sc = new View(a);
        int alpha = Math.max(0, Math.min(255, Math.round(255f * scrim / 100f)));
        sc.setBackgroundColor(Color.argb(alpha, 22, 18, 30));
        sc.setTag("recstt-bg");
        root.addView(sc, 1, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    /**
     * 背景变化后：重算整屏模糊图、按顶部颜色配状态栏。
     * 状态栏用半透明深色而不是背景原色——原色太亮时白字看不见，
     * 这里先压暗到能压住白字，再让图标颜色跟亮度走。
     */
    private static void installGlass(Activity a, Bitmap bmp,
                                     float imgScale, float imgLeft, float imgTop) {
        try {
            float density = a.getResources().getDisplayMetrics().density;

            // 状态栏/导航栏透明，背景一路铺到屏幕最上/最下
            a.getWindow().setStatusBarColor(Color.TRANSPARENT);
            a.getWindow().setNavigationBarColor(Color.TRANSPARENT);

            int top = Glass.topColor(bmp);
            bgDark = Glass.luminance(top) < 0.45;
            applySystemBarIcons(a, bgDark);

            // 两张图完全独立：背景模糊设 0 只代表"壁纸不糊"，
            // 不能让卡片也一起失去模糊（之前 early-return 就是这么错的）。
            int bgBlurDp = intOf(Db.get(a).kv("bg_blur", "24"), 24);
            int cardBlurDp = intOf(Db.get(a).kv("bg_card_blur", "16"), 16);
            if (cardBlurDp <= 0) {
                Glass.clear();
                return;
            }

            // 壁纸在屏幕上实际是：位图坐标 * imgScale + (imgLeft, imgTop)
            // 卡片模糊图按同一几何生成，两者才能严丝合缝对上。
            int dw = Math.max(1, Math.round(bmp.getWidth() * imgScale));
            int dh = Math.max(1, Math.round(bmp.getHeight() * imgScale));
            Bitmap cover = Bitmap.createScaledBitmap(bmp, dw, dh, true);

            // 卡片模糊图：绘制时按卡片所在位置实时裁剪（见 FrostDrawable.draw）
            int cardRadiusPx = Math.max(1, Math.round(cardBlurDp * density));
            Bitmap cardBitmap = Glass.blur(cover, cardRadiusPx);

            // 背景模糊图只在需要时生成（目前壁纸用 RenderEffect，这里留作备用）
            Bitmap bgBitmap = null;
            if (bgBlurDp > 0) {
                bgBitmap = Glass.blur(cover, Math.max(1, Math.round(bgBlurDp * density)));
            }

            if (cover != bmp && cover != cardBitmap && cover != bgBitmap) cover.recycle();
            Diag.add("Bg.ready", "bgDark=" + bgDark + " → 通知换色");
            if (onReady != null) a.runOnUiThread(onReady);

            Diag.add("Glass.sources", "cover=" + dw + "x" + dh
                    + " cardBitmap=" + cardBitmap.getWidth() + "x" + cardBitmap.getHeight()
                    + " left=" + imgLeft + " top=" + imgTop);
            Glass.setSources(bgBitmap != null ? bgBitmap : cardBitmap, cardBitmap, imgLeft, imgTop, dw, dh);
        } catch (Throwable t) {
            Diag.add("Bg.FATAL", String.valueOf(t));
            android.util.Log.w("RecSttBg", "毛玻璃/状态栏处理失败", t);
        }
    }

    /** 状态栏/导航栏图标：深底配白图标，浅底配深图标。 */
    private static void applySystemBarIcons(Activity a, boolean darkBg) {
        if (a == null || a.getWindow() == null) return;
        try {
            applySystemBarIcons0(a, darkBg);
        } catch (Throwable t) {
            // 装饰性设置失败不该让界面挂掉，但必须留痕
            Diag.add("Bg.icons", "设置系统栏图标失败：" + t);
        }
    }

    private static void applySystemBarIcons0(Activity a, boolean darkBg) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
                    | android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS;
            android.view.WindowInsetsController c = a.getWindow().getInsetsController();
            if (c != null) c.setSystemBarsAppearance(darkBg ? 0 : mask, mask);
        } else {
            View dv = a.getWindow().getDecorView();
            dv.setSystemUiVisibility(darkBg ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private static String lastFingerprint = "";
    /** 背景铺好（bgDark 已定）后的回调：文字换色要等这一刻。 */
    private static Runnable onReady;

    /** 背景配置（路径 + 开关 + 三个参数）是否和上次铺的那次不同。 */
    public static boolean configChanged(Activity a) {
        if (a == null) return false;
        try {
            Db db = Db.get(a);
            String fp = db.kv("bg_path", "") + "|" + db.kv("bg_enabled", "1")
                    + "|" + db.kv("bg_blur", "24") + "|" + db.kv("bg_card_blur", "16")
                    + "|" + db.kv("bg_scrim", "45");
            if (fp.equals(lastFingerprint)) return false;
            lastFingerprint = fp;
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 当前背景是否偏暗：标题/正文要据此换浅色字，否则深底深字看不见。 */
    static boolean bgDark = false;

    /** 无背景时回到默认（浅色底、深色字）。 */
    static void setBgDark(boolean dark) {
        bgDark = dark;
    }

    private static int intOf(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }
}
