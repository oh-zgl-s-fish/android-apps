package com.zglinus.recstt.ui;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 毛玻璃/背景的诊断采集。
 *
 * <p>真机上看到的现象（模糊没了、错位、字色不对）光靠描述很难定位，
 * 所以把关键数值都记下来，从菜单一键复制到剪贴板发给开发者。
 *
 * <p>只记这些行，不记任何录音内容/路径等隐私。
 */
public final class Diag {

    private static final int MAX = 200;
    private static final List<String> LINES = new ArrayList<>();
    private static final SimpleDateFormat TS =
            new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Diag() {}

    public static synchronized void add(String tag, String msg) {
        if (LINES.size() >= MAX) LINES.remove(0);
        LINES.add(TS.format(new Date()) + "  " + tag + "  " + msg);
    }

    public static synchronized void clear() {
        LINES.clear();
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder();
        sb.append("=== recstt 诊断 ===\n");
        sb.append("屏幕: ").append(screenInfo()).append('\n');
        sb.append("--- 事件 ---\n");
        for (String l : LINES) sb.append(l).append('\n');
        return sb.toString();
    }

    private static String screenInfo() {
        Context c = appCtx;
        if (c == null) return "(未知)";
        android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
        return dm.widthPixels + "x" + dm.heightPixels + "  density=" + dm.density;
    }

    private static Context appCtx;

    public static void setContext(Context c) {
        if (c != null && appCtx == null) appCtx = c.getApplicationContext();
    }

    /** 复制到剪贴板并提示。 */
    public static void copyToClipboard(Activity a) {
        if (a == null) return;
        try {
            String text = dump();
            ClipboardManager cm =
                    (ClipboardManager) a.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("recstt-diag", text));
                Toast.makeText(a, "诊断已复制（" + text.length() + " 字符），粘给开发者即可",
                        Toast.LENGTH_LONG).show();
            }
        } catch (Throwable t) {
            Toast.makeText(a, "复制失败：" + t, Toast.LENGTH_LONG).show();
        }
    }
}
