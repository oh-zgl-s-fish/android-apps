package com.zglinus.recstt.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.R;
import com.zglinus.recstt.model.Tpl;
import com.zglinus.recstt.store.Db;

import java.util.ArrayList;
import java.util.List;

/**
 * 第 12 屏：匹配模板列表。
 *
 * <p>分「内置 / 我加过的」两组；每行第一行模板名（内置带「内置」标签、停用的自定义
 * 带「草稿」标签），第二行是等宽字体的匹配串（显示 {@code tpl.format}，没有 format
 * 的老模板退回显示 pattern），第三行小字状态，右侧 Switch 直接改 enabled 并落库。
 *
 * <p>点条目进编辑页（extra {@code tpl_id}，新建传 {@code "new"}）；长按自定义模板
 * 确认后删除，内置模板只提示不能删；右下 FAB 新建。
 */
public final class TemplatesActivity extends Activity {

    private static final String EXTRA_TPL_ID = "tpl_id";

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Db db;
    private ScrollView scroll;
    private LinearLayout listCol;
    private View fab;
    private FrameLayout root;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this); // 先定主题/调色板，再 super.onCreate
        super.onCreate(savedInstanceState);
        db = Db.get(this);

        root = new FrameLayout(this);
        // 不能刷不透明底色：会把 Bg.attach 铺的背景图整个盖住（真机"背景没有效果"）
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        setContentView(root);

        // 自定义背景在最底层；没有配背景时什么都不加。
        Bg.setOnReady(new Runnable() {
            @Override public void run() {
                Ui.autoTintBgText(root);
            }
        });
        Bg.attach(this, root);

        LinearLayout page = Ui.col(this);
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));

        page.addView(buildTopBar(), new LinearLayout.LayoutParams(MATCH, dp(56)));

        TextView hint = Ui.tv(this, "模板用看得懂的样子写；正则收在高级里",
                13, Ui.ON_SURFACE_VARIANT, false);
        hint.setLineSpacing(dp(2), 1f);
        hint.setPadding(dp(16), dp(2), dp(16), dp(10));
        page.addView(hint, new LinearLayout.LayoutParams(MATCH, WRAP));

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        listCol = Ui.col(this);
        listCol.setPadding(dp(16), 0, dp(16), dp(96));
        scroll.addView(listCol, new ScrollView.LayoutParams(MATCH, WRAP));
        page.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        fab = buildFab();
        root.addView(fab, fabParams());

        applyInsets(root, page, fab);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 背景/毛玻璃配置变了：整套颜色和底板都已过期，整体重建最省事也最不会漏。
        if (Bg.configChanged(this)) {
            recreate();
            return;
        }
        // 从编辑页回来、或者删完/开关改完，都重新读库，保证和 Db 一致。
        render();
    }

    // ---------------------------------------------------------------- 顶栏 / FAB

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);
        bar.setPadding(dp(4), 0, dp(8), 0);

        View back = Ui.iconButton(this, R.drawable.ic_back, Ui.ON_SURFACE, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        back.setContentDescription("返回");
        bar.addView(back);

        TextView title = Ui.tv(this, "匹配模板", 20, Ui.onBg(), true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tp.leftMargin = dp(4);
        tp.gravity = Gravity.CENTER_VERTICAL;
        bar.addView(title, tp);
        return bar;
    }

    private View buildFab() {
        LinearLayout box = Ui.row(this);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(dp(18), 0, dp(20), 0);
        box.setBackground(pressable(Ui.round(Ui.PRIMARY_CONTAINER, 16, this), 16));
        box.setElevation(dp(6));
        box.setClickable(true);
        box.setFocusable(true);
        box.setContentDescription("新建模板");

        box.addView(Ui.icon(this, R.drawable.ic_plus, Ui.ON_PRIMARY_CONTAINER, 20));
        TextView label = Ui.tv(this, "新建模板", 14, Ui.ON_PRIMARY_CONTAINER, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(WRAP, WRAP);
        lp.leftMargin = dp(8);
        box.addView(label, lp);

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor("new");
            }
        });
        return box;
    }

    private FrameLayout.LayoutParams fabParams() {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(WRAP, dp(56),
                Gravity.BOTTOM | Gravity.END);
        p.setMargins(0, 0, dp(20), dp(24));
        return p;
    }

    // ---------------------------------------------------------------- 列表

    private void render() {
        if (listCol == null) {
            return;
        }
        final int keepY = scroll.getScrollY();
        listCol.removeAllViews();

        List<Tpl> all = db.templates();
        List<Tpl> builtin = new ArrayList<Tpl>();
        List<Tpl> custom = new ArrayList<Tpl>();
        for (Tpl t : all) {
            if (t == null) {
                continue;
            }
            if (t.builtin) {
                builtin.add(t);
            } else {
                custom.add(t);
            }
        }

        addGroup("内置", builtin,
                "内置模板一条都没有？数据库可能没建好，重装一次试试。");
        addGroup("我加过的", custom,
                "还没有自定义模板。点右下角「新建模板」，照文件名写一条。");

        // 内容随时会重建（开关一拨就整列重来），所以每次重建后都重新扫一遍字色。
        // autoTintBgText 现在是幂等的：有底的卡片子树会被跳过，并顺手把误翻成浅色的字收回来。
        Ui.autoTintBgText(root);

        // 重建后别把用户滚到的位置弄丢。
        scroll.post(new Runnable() {
            @Override
            public void run() {
                scroll.scrollTo(0, keepY);
            }
        });
    }

    private void addGroup(String title, List<Tpl> tpls, String emptyText) {
        LinearLayout head = Ui.row(this);
        head.setPadding(0, dp(14), 0, dp(6));
        head.addView(Ui.tv(this, title, 13, Ui.ON_SURFACE_VARIANT, true),
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        head.addView(Ui.tv(this, tpls.size() + " 条", 11, Ui.ON_SURFACE_VARIANT, false));
        listCol.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        if (tpls.isEmpty()) {
            TextView empty = Ui.tv(this, emptyText, 12, Ui.ON_SURFACE_VARIANT, false);
            empty.setLineSpacing(dp(3), 1f);
            empty.setPadding(dp(2), dp(2), dp(2), dp(10));
            listCol.addView(empty, new LinearLayout.LayoutParams(MATCH, WRAP));
            return;
        }
        for (Tpl t : tpls) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
            lp.bottomMargin = dp(8);
            listCol.addView(buildRow(t), lp);
        }
    }

    private View buildRow(final Tpl t) {
        final LinearLayout card = Ui.row(this);
        card.setBackground(pressable(Ui.round(Ui.SURFACE_CONTAINER, 16, this), 16));
        card.setPadding(dp(14), dp(12), dp(8), dp(12));
        card.setClickable(true);
        card.setFocusable(true);

        LinearLayout texts = Ui.col(this);

        // 第一行：模板名 + 标签
        LinearLayout titleRow = Ui.row(this);
        TextView name = Ui.tv(this, displayName(t), 16, Ui.ON_SURFACE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(name, new LinearLayout.LayoutParams(0, WRAP, 1f));
        if (t.builtin) {
            titleRow.addView(badge("内置", Ui.ON_SECONDARY_CONTAINER, Ui.SECONDARY_CONTAINER),
                    badgeParams());
        } else if (!t.enabled) {
            titleRow.addView(badge("草稿", Ui.ERROR, Ui.ERROR_CONTAINER), badgeParams());
        }
        texts.addView(titleRow, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 第二行：匹配串（等宽，直接显示 format）
        TextView fmt = Ui.tv(this, formatText(t), 13, Ui.ON_SURFACE_VARIANT, false);
        fmt.setTypeface(Typeface.MONOSPACE);
        fmt.setSingleLine(true);
        fmt.setEllipsize(TextUtils.TruncateAt.END);
        fmt.setPadding(0, dp(5), 0, 0);
        texts.addView(fmt, new LinearLayout.LayoutParams(MATCH, WRAP));

        // 第三行：状态小字
        TextView status = Ui.tv(this, statusText(t), 11,
                t.enabled ? Ui.ON_SURFACE_VARIANT : Ui.OUTLINE, false);
        status.setPadding(0, dp(6), 0, 0);
        texts.addView(status, new LinearLayout.LayoutParams(MATCH, WRAP));

        card.addView(texts, new LinearLayout.LayoutParams(0, WRAP, 1f));

        // 右侧启用开关：变化即落库
        final Switch sw = new Switch(this);
        sw.setChecked(t.enabled);
        tintSwitch(sw);
        sw.setContentDescription((t.enabled ? "停用模板 " : "启用模板 ") + displayName(t));
        sw.setPadding(0, 0, 0, 0);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton buttonView, boolean checked) {
                if (t.enabled == checked) {
                    return;
                }
                t.enabled = checked;
                db.saveTpl(t);
                Toast.makeText(TemplatesActivity.this,
                        checked ? "已启用「" + displayName(t) + "」"
                                : "已停用「" + displayName(t) + "」，不参与新扫描",
                        Toast.LENGTH_SHORT).show();
                if (listCol != null) {
                    listCol.post(new Runnable() {
                        @Override
                        public void run() {
                            render();
                        }
                    });
                }
            }
        });
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(WRAP, WRAP);
        swLp.leftMargin = dp(8);
        card.addView(sw, swLp);

        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openEditor(t.id);
            }
        });
        card.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (t.builtin) {
                    Toast.makeText(TemplatesActivity.this, "内置模板不能删",
                            Toast.LENGTH_SHORT).show();
                } else {
                    confirmDelete(t);
                }
                return true;
            }
        });
        return card;
    }

    private void confirmDelete(final Tpl t) {
        new AlertDialog.Builder(this)
                .setTitle("删除模板")
                .setMessage("删除「" + displayName(t) + "」？\n\n"
                        + "已经入库的录音不受影响，只是以后再扫描时不再用这条模板。删了就找不回来。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface dialog, int which) {
                        db.deleteTpl(t.id);
                        Toast.makeText(TemplatesActivity.this,
                                "已删除「" + displayName(t) + "」", Toast.LENGTH_SHORT).show();
                        render();
                    }
                })
                .show();
    }

    private void openEditor(String tplId) {
        Intent i = new Intent(this, TemplateEditActivity.class);
        i.putExtra(EXTRA_TPL_ID, tplId == null ? "new" : tplId);
        startActivity(i);
    }

    // ---------------------------------------------------------------- 小工具

    private static String displayName(Tpl t) {
        if (t == null || t.name == null || t.name.trim().isEmpty()) {
            return "未命名模板";
        }
        return t.name.trim();
    }

    private static String formatText(Tpl t) {
        if (t.format != null && !t.format.trim().isEmpty()) {
            return t.format.trim();
        }
        // 老模板/手写模板可能只有正则，退回显示正则，总比空白强。
        if (t.pattern != null && !t.pattern.trim().isEmpty()) {
            return t.pattern.trim();
        }
        return "（这条模板没有格式串）";
    }

    private static String statusText(Tpl t) {
        if (t.builtin) {
            return t.enabled ? "内置 · 优先级 " + t.priority
                    : "内置 · 优先级 " + t.priority + " · 已停用";
        }
        if (t.enabled) {
            return "自定义 · 优先级 " + t.priority + " · 参与匹配";
        }
        return "草稿 · 不参与匹配（启用后才会用到）";
    }

    private TextView badge(String text, int fg, int bgColor) {
        TextView b = Ui.tv(this, text, 10, fg, true);
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        b.setBackground(Ui.round(bgColor, 6, this));
        return b;
    }

    private LinearLayout.LayoutParams badgeParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(WRAP, WRAP);
        p.leftMargin = dp(8);
        p.gravity = Gravity.CENTER_VERTICAL;
        return p;
    }

    private void tintSwitch(Switch sw) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{},
        };
        int[] thumb = new int[]{Ui.PRIMARY, Ui.OUTLINE_VARIANT};
        int[] track = new int[]{Ui.PRIMARY_CONTAINER, Ui.SURFACE_HIGHEST};
        sw.setThumbTintList(new ColorStateList(states, thumb));
        sw.setTrackTintList(new ColorStateList(states, track));
    }

    private Drawable pressable(Drawable content, float radiusDp) {
        return new RippleDrawable(ColorStateList.valueOf(0x336750A4),
                content, Ui.round(0xFFFFFFFF, radiusDp, this));
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private void applyInsets(final FrameLayout root, final LinearLayout page, final View fabView) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        // Android 15 起强制 edge-to-edge，自己吃掉 systemBars 的 inset。
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                page.setPadding(bars.left, bars.top, bars.right, bars.bottom);

                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) fabView.getLayoutParams();
                lp.bottomMargin = dp(24) + bars.bottom;
                lp.rightMargin = dp(20) + bars.right;
                fabView.setLayoutParams(lp);
                return insets;
            }
        });
    }
}
