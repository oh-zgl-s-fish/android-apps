package com.zglinus.recstt.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.zglinus.recstt.R;
import com.zglinus.recstt.model.Rec;

import java.util.ArrayList;
import java.util.List;

/**
 * 主列表的适配器：行布局全部用代码搭（工程里没有 XML 布局），convertView 复用。
 *
 * <p>行结构（左 → 右）：
 * <pre>
 * [44dp 圆角方块]  [第一行：姓名 / 号码]           [右侧状态字]
 *                  [第二行：号码 · 时间 · 时长]
 *                  [3dp 进度条（仅排队/转写中）]
 * </pre>
 *
 * <p>“有没有姓名”没有独立字段：Scanner 在模板没取到姓名时会把 displayName 回落成
 * 号码或文件名。所以判定只能靠比对 {@link #hasName(Rec)}，这里做了显式启发式，
 * 宁可把回落值当“无姓名”（灰色、不加粗），也不给用户一个假姓名。
 */
public class RecAdapter extends BaseAdapter {

    /** 点击/长按回调；row 是整行的 View，长按菜单要用它当 PopupMenu 的锚点。 */
    public interface Listener {
        void onRecClick(Rec rec, View row);

        void onRecLongClick(Rec rec, View row);
    }

    private final Context ctx;
    private final Listener listener;
    private final List<Rec> data = new ArrayList<Rec>();

    public RecAdapter(Context c, List<Rec> initial, Listener l) {
        this.ctx = c;
        this.listener = l;
        if (initial != null) {
            data.addAll(initial);
        }
    }

    // ------------------------------------------------------------------ 数据

    /** 整批换数据（内部复制一份，外部再改自己的 list 不会让列表状态错位）。 */
    public void setData(List<Rec> next) {
        data.clear();
        if (next != null) {
            data.addAll(next);
        }
        notifyDataSetChanged();
    }

    /** 当前展示的数据（只读用途，别直接改）。 */
    public List<Rec> data() {
        return data;
    }

    @Override
    public int getCount() {
        return data.size();
    }

    @Override
    public Rec getItem(int position) {
        return data.get(position);
    }

    @Override
    public long getItemId(int position) {
        return data.get(position).id;
    }

    /**
     * 这一条是不是真的解析出了姓名。
     *
     * <p>Scanner 的回落顺序是 姓名 → 号码 → 去掉扩展名的文件名，所以
     * displayName 等于号码或文件名时，说明名字其实是没解析出来的。
     */
    public static boolean hasName(Rec r) {
        if (r == null) {
            return false;
        }
        String dn = trim(r.displayName);
        if (dn.isEmpty()) {
            return false;
        }
        String phone = trim(r.phone);
        if (!phone.isEmpty() && dn.equals(phone)) {
            return false;
        }
        String base = stripExt(trim(r.fileName));
        return !(base.length() > 0 && dn.equals(base));
    }

    // ------------------------------------------------------------------ 视图

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Holder h;
        if (convertView == null) {
            h = build();
            convertView = h.root;
            convertView.setTag(h);
        } else {
            h = (Holder) convertView.getTag();
        }
        bind(h, data.get(position));
        return convertView;
    }

    private Holder build() {
        Holder h = new Holder();

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(px(16), px(10), px(16), px(10));
        row.setMinimumHeight(px(64));
        // 点按反馈用 framework 的 RippleDrawable，不引 Material。
        // 没背景图时行底色与页面同色，必须垫一层描边，否则列表项看不出边界。
        android.graphics.drawable.Drawable ripple = new RippleDrawable(
                ColorStateList.valueOf(0x336750A4), null, Ui.round(0xFFFFFFFF, 16, ctx));
        if (Ui.hasBg(ctx)) {
            // 列表行文字底下也要毛玻璃：之前只给行垫了透明 ripple，
            // 背景花纹直接透到字上，读起来费劲。
            Ui.glassCard(row, 16, 0x99);
            row.setForeground(ripple);
        } else {
            android.graphics.drawable.Drawable[] layers = {
                    Ui.round(Ui.SURFACE, 16, Ui.OUTLINE_VARIANT, 1f, ctx), ripple};
            row.setBackground(new android.graphics.drawable.LayerDrawable(layers));
        }
        h.root = row;

        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams boxLp = new LinearLayout.LayoutParams(px(44), px(44));
        box.setLayoutParams(boxLp);
        h.box = box;

        h.initial = Ui.tv(ctx, "?", 18, Ui.ON_SURFACE_VARIANT, true);
        h.initial.setGravity(Gravity.CENTER);
        h.initial.setIncludeFontPadding(false);
        box.addView(h.initial);

        h.icon = Ui.icon(ctx, R.drawable.ic_check, Ui.SUCCESS, 22);
        h.icon.setVisibility(View.GONE);
        box.addView(h.icon);

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginStart(px(12));
        col.setLayoutParams(colLp);
        h.col = col;

        h.title = Ui.tv(ctx, "", 14.5f, Ui.ON_SURFACE, true);
        h.title.setSingleLine(true);
        h.title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(h.title);

        h.meta = Ui.tv(ctx, "", 11.5f, Ui.ON_SURFACE_VARIANT, false);
        h.meta.setSingleLine(true);
        h.meta.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        metaLp.topMargin = px(3);
        col.addView(h.meta, metaLp);

        LinearLayout track = new LinearLayout(ctx);
        track.setOrientation(LinearLayout.HORIZONTAL);
        track.setWeightSum(100f);
        track.setBackground(Ui.round(Ui.SURFACE_HIGHEST, 2, ctx));
        h.track = track;

        View fill = new View(ctx);
        fill.setBackground(Ui.round(Ui.PRIMARY, 2, ctx));
        h.fill = fill;
        track.addView(fill, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT, 0f));

        LinearLayout.LayoutParams trackLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, px(3));
        trackLp.topMargin = px(6);
        track.setVisibility(View.GONE);
        col.addView(track, trackLp);

        // 右侧小字（排队/上传 %/已转写）压在背景上不好认：垫一层薄白纱
        h.status = Ui.tv(ctx, "", 12, Ui.ON_SURFACE_VARIANT, false);
        // 有背景用薄白纱，无背景用实色底：两种情况下都能看清
        h.status.setBackground(Ui.round(Ui.hasBg(ctx) ? 0x66FFFFFF : 0xFFE8E2EE, 4, ctx));
        h.status.setPadding((int) Ui.dp(ctx, 4), (int) Ui.dp(ctx, 1),
                (int) Ui.dp(ctx, 4), (int) Ui.dp(ctx, 1));
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusLp.setMarginStart(px(8));
        h.status.setLayoutParams(statusLp);

        row.addView(box);
        row.addView(col);
        row.addView(h.status);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null && h.rec != null) {
                    listener.onRecClick(h.rec, h.root);
                }
            }
        });
        row.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (listener == null || h.rec == null) {
                    return false;
                }
                listener.onRecLongClick(h.rec, h.root);
                return true;
            }
        });

        return h;
    }

    private void bind(Holder h, Rec r) {
        boolean named = hasName(r);
        String title = titleOf(r);

        h.rec = r;
        h.title.setText(title);
        h.title.setTextColor(named ? Ui.ON_SURFACE : Ui.ON_SURFACE_VARIANT);
        h.title.setTypeface(named ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

        // 第二行：号码 · MM-dd HH:mm · 时长；失败的行改成显示 error（契约要求）
        String phone = trim(r.phone);
        String meta = (phone.isEmpty() ? "未知号码" : phone)
                + " · " + Ui.mdhm(r.startMs)
                + " · " + Ui.dur(r.durMs);
        boolean failedWithReason = r.state == 4 && !trim(r.error).isEmpty();
        if (failedWithReason) {
            meta = trim(r.error);
        }
        h.meta.setText(meta);
        h.meta.setTextColor(failedWithReason ? Ui.ERROR : Ui.ON_SURFACE_VARIANT);

        // 左侧方块：已转写=勾、转写中=姓名首字、失败=警告、其他=问号
        boolean running = r.state == 1 || r.state == 2 || r.state == 5;
        int boxColor;
        if (r.state == 3) {
            boxColor = Ui.SUCCESS_CONTAINER;
            h.icon.setImageResource(R.drawable.ic_check);
            h.icon.setColorFilter(Ui.SUCCESS);
            h.icon.setVisibility(View.VISIBLE);
            h.initial.setVisibility(View.GONE);
        } else if (running) {
            boxColor = Ui.PRIMARY_CONTAINER;
            h.initial.setText(firstChar(title));
            h.initial.setTextColor(Ui.ON_PRIMARY_CONTAINER);
            h.initial.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
        } else if (r.state == 4) {
            boxColor = Ui.ERROR_CONTAINER;
            h.icon.setImageResource(R.drawable.ic_warn);
            h.icon.setColorFilter(Ui.ERROR);
            h.icon.setVisibility(View.VISIBLE);
            h.initial.setVisibility(View.GONE);
        } else {
            boxColor = Ui.SURFACE_HIGH;
            h.initial.setText("?");
            h.initial.setTextColor(Ui.ON_SURFACE_VARIANT);
            h.initial.setVisibility(View.VISIBLE);
            h.icon.setVisibility(View.GONE);
        }
        h.box.setBackground(Ui.round(boxColor, 12, ctx));

        // 右侧状态字
        int pct = Math.max(0, Math.min(100, r.progress));
        if (r.state == 3) {
            h.status.setText("已转写");
            h.status.setTextColor(Ui.SUCCESS);
        } else if (r.state == 4) {
            h.status.setText("失败");
            h.status.setTextColor(Ui.ERROR);
        } else if (r.state == 5) {
            // 上传中：以前误用 1，列表永远显示"排队"、进度恒 0，看着像卡死
            h.status.setText(pct > 0 ? "上传 " + pct + "%" : "上传中");
            h.status.setTextColor(Ui.PRIMARY);
        } else if (r.state == 1) {
            h.status.setText("排队");
            h.status.setTextColor(Ui.PRIMARY);
        } else if (r.state == 2) {
            h.status.setText(pct > 0 ? "转写 " + pct + "%" : "转写中");
            h.status.setTextColor(Ui.PRIMARY);
        } else {
            h.status.setText("未转写");
            h.status.setTextColor(Ui.ON_SURFACE_VARIANT);
        }

        // 进度条：LinearLayout(weightSum=100) + 内层 View(weight=pct)
        h.track.setVisibility(running ? View.VISIBLE : View.GONE);
        if (running && pct <= 0) {
            // 0% 也要看得见轨道在跑：给个 4% 的"起步"提示，别让条子完全空着像没动
            h.fill.setVisibility(View.VISIBLE);
        }
        if (running) {
            LinearLayout.LayoutParams flp = (LinearLayout.LayoutParams) h.fill.getLayoutParams();
            flp.width = 0;
            flp.weight = pct;
            h.fill.setLayoutParams(flp);
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 行首行文字：有姓名用姓名，否则用完整号码（没有号码才退回文件名）。 */
    public static String titleOf(Rec r) {
        if (r == null) {
            return "未知录音";
        }
        if (hasName(r)) {
            return trim(r.displayName);
        }
        String phone = trim(r.phone);
        if (!phone.isEmpty()) {
            return phone;
        }
        String dn = trim(r.displayName);
        if (!dn.isEmpty()) {
            return dn;
        }
        String fn = trim(r.fileName);
        return fn.isEmpty() ? "未知录音" : fn;
    }

    private static String firstChar(String s) {
        String t = trim(s);
        if (t.isEmpty()) {
            return "?";
        }
        int cp = t.codePointAt(0);
        return new String(Character.toChars(cp));
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private int px(float dp) {
        return Ui.dp(ctx, dp);
    }

    /** 一行的可复用部件。 */
    private static final class Holder {
        LinearLayout root;
        LinearLayout box;
        TextView initial;
        ImageView icon;
        LinearLayout col;
        TextView title;
        TextView meta;
        LinearLayout track;
        View fill;
        TextView status;
        Rec rec;
    }
}
