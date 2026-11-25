package com.zglinus.recstt.ui;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.R;
import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.model.Tpl;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.tpl.TplEngine;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 第 13 屏：模板编辑。
 *
 * <p>模板名 / 格式串（人话，等宽）/ 时间格式 / 启用开关；「高级：正则」默认折叠，
 * 里面只读展示 {@link TplEngine#formatToPattern(String)} 的结果，并允许手写正则覆盖。
 *
 * <p>「试跑最近 5 个文件」拿 {@code Db.list("", -1, 5)} 的真实文件名逐条
 * {@link TplEngine#parseWithTemplate(Tpl, String)}；全部命中才允许「保存并启用」，
 * 否则只能「存草稿」（enabled=false）。保存 = {@code Db.saveTpl} + finish。
 */
public final class TemplateEditActivity extends Activity {

    private static final String EXTRA_TPL_ID = "tpl_id";

    private static final String DEFAULT_FORMAT = "{姓名}@{号码}_{时间}";
    private static final String DEFAULT_TIME_FMT = "yyyyMMddHHmmss";

    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private Db db;

    /** 新模板为 true（id 用 custom_ + 时间戳），否则 loaded 不为 null。 */
    private boolean isNew;
    private Tpl loaded;
    private String newId;

    private EditText etName;
    private EditText etFormat;
    private EditText etTimeFmt;
    private EditText etPattern;

    private Switch swEnabled;

    private LinearLayout advBody;
    private ImageView advArrow;
    private TextView tvAutoPattern;
    private TextView tvOverrideState;

    private TextView tvRunHint;
    private TextView tvRunHeader;
    private LinearLayout runList;

    private TextView tvGate;
    private TextView btnDraft;
    private TextView btnEnable;

    /** 最近一次试跑的结果；runStale=改过字段，结果作废。 */
    private int runTotal;
    private int runMatched;
    private boolean runStale;
    private boolean hasRun;
    private boolean noFiles;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this); // 先定主题/调色板，再 super.onCreate
        super.onCreate(savedInstanceState);
        db = Db.get(this);

        String id = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_TPL_ID);
        isNew = id == null || id.trim().isEmpty() || "new".equals(id.trim());
        if (!isNew) {
            loaded = db.tpl(id.trim());
            if (loaded == null) {
                isNew = true;
            }
        }
        if (isNew) {
            newId = "custom_" + System.currentTimeMillis();
        }

        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout root = new FrameLayout(this);
        // 不能刷不透明底色：会把 Bg.attach 铺的背景图整个盖住（真机"背景没有效果"）
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        setContentView(root);
        // 顺序很重要：背景是异步铺的，bgDark 要等它算完才有值。
        // 之前 autoTint 写在 attach 之前，拿到的永远是默认 false → 小字换不了色。
        Bg.setOnReady(new Runnable() {
            @Override public void run() {
                Ui.autoTintBgText(root);
            }
        });
        Bg.attach(this, root);

        LinearLayout page = Ui.col(this);
        root.addView(page, new FrameLayout.LayoutParams(MATCH, MATCH));

        page.addView(buildTopBar(), new LinearLayout.LayoutParams(MATCH, dp(56)));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        LinearLayout form = Ui.col(this);
        form.setPadding(dp(16), dp(6), dp(16), dp(24));
        scroll.addView(form, new ScrollView.LayoutParams(MATCH, WRAP));
        page.addView(scroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        buildForm(form);
        page.addView(buildBottomBar(), new LinearLayout.LayoutParams(MATCH, WRAP));

        applyInsets(root, page);
        fillFields();
        // 缓存命中时 Bg 是同步 ready 的，上面那次 autoTint 扫的是空树。
        // 内容都建好后再扫一遍（幂等），保证顶栏标题这类直接压在背景上的字不会留成深色。
        Ui.autoTintBgText(root);
    }

    // ---------------------------------------------------------------- 顶栏

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

        TextView title = Ui.tv(this, isNew ? "新建模板" : "编辑模板", 20, Ui.ON_SURFACE, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(WRAP, WRAP);
        tp.leftMargin = dp(4);
        tp.gravity = Gravity.CENTER_VERTICAL;
        bar.addView(title, tp);

        if (!isNew && loaded != null && loaded.builtin) {
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(WRAP, WRAP);
            bp.leftMargin = dp(8);
            bp.gravity = Gravity.CENTER_VERTICAL;
            bar.addView(badge("内置", Ui.ON_SECONDARY_CONTAINER, Ui.SECONDARY_CONTAINER), bp);
        }
        return bar;
    }

    // ---------------------------------------------------------------- 表单

    private void buildForm(LinearLayout form) {
        etName = textField(form, "模板名", "例如：MIUI 通话录音", null, false,
                null);

        etFormat = textField(form, "格式串（人话）", DEFAULT_FORMAT, DEFAULT_FORMAT, true,
                "认这些占位符：{姓名} {名称} {号码} {时间} {时长}；其余字符按原样匹配。"
                        + "例：{姓名}@{号码}_{时间}");

        etTimeFmt = textField(form, "时间格式", DEFAULT_TIME_FMT, DEFAULT_TIME_FMT, true,
                "Java 时间格式，例如 yyyyMMddHHmmss、yyyy-MM-dd_HH-mm-ss；留空按内置候选格式猜。");

        // 启用开关
        LinearLayout swRow = Ui.row(this);
        swRow.setBackground(Ui.round(Ui.SURFACE_CONTAINER, 12, this));
        swRow.setPadding(dp(12), dp(10), dp(8), dp(10));
        LinearLayout swTexts = Ui.col(this);
        swTexts.addView(Ui.tv(this, "启用", 15, Ui.ON_SURFACE, false));
        swTexts.addView(Ui.tv(this, "关掉就是草稿：保存后不参与扫描匹配", 11, Ui.OUTLINE, false));
        swRow.addView(swTexts, new LinearLayout.LayoutParams(0, WRAP, 1f));

        swEnabled = new Switch(this);
        swEnabled.setChecked(true);
        tintSwitch(swEnabled);
        swEnabled.setContentDescription("启用这条模板");
        swRow.addView(swEnabled);

        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        swLp.topMargin = dp(16);
        form.addView(swRow, swLp);

        // 折叠的「高级：正则」
        LinearLayout adv = Ui.col(this);
        adv.setBackground(Ui.round(Ui.SURFACE_CONTAINER, 12, this));

        LinearLayout advHead = Ui.row(this);
        advHead.setPadding(dp(12), dp(12), dp(12), dp(12));
        advHead.setClickable(true);
        advHead.setFocusable(true);
        advHead.setBackground(pressable(Ui.round(Ui.SURFACE_CONTAINER, 12, this), 12));
        advHead.addView(Ui.tv(this, "高级：正则", 14, Ui.ON_SURFACE, true),
                new LinearLayout.LayoutParams(0, WRAP, 1f));
        advArrow = Ui.icon(this, R.drawable.ic_arrow_down, Ui.ON_SURFACE_VARIANT, 20);
        advHead.addView(advArrow);
        advHead.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAdvancedOpen(advBody.getVisibility() != View.VISIBLE);
            }
        });
        adv.addView(advHead, new LinearLayout.LayoutParams(MATCH, WRAP));

        advBody = Ui.col(this);
        advBody.setPadding(dp(12), 0, dp(12), dp(12));
        advBody.setVisibility(View.GONE);

        TextView exp = Ui.tv(this,
                "格式串会自动翻成正则，一般不用管。文件名有特殊花样时，可以在下面直接写正则覆盖它。",
                12, Ui.ON_SURFACE_VARIANT, false);
        exp.setLineSpacing(dp(3), 1f);
        advBody.addView(exp, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView autoLab = Ui.tv(this, "自动生成（只读）", 11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(MATCH, WRAP);
        alp.topMargin = dp(10);
        advBody.addView(autoLab, alp);

        tvAutoPattern = Ui.tv(this, "", 12, Ui.ON_SURFACE, false);
        tvAutoPattern.setTypeface(Typeface.MONOSPACE);
        tvAutoPattern.setPadding(dp(10), dp(8), dp(10), dp(8));
        tvAutoPattern.setBackground(Ui.round(Ui.SURFACE, 8, this));
        tvAutoPattern.setTextIsSelectable(true);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(MATCH, WRAP);
        plp.topMargin = dp(4);
        advBody.addView(tvAutoPattern, plp);

        tvOverrideState = Ui.tv(this, "", 11, Ui.OUTLINE, false);
        tvOverrideState.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(MATCH, WRAP);
        olp.topMargin = dp(6);
        advBody.addView(tvOverrideState, olp);

        etPattern = textField(advBody, "手写正则覆盖（留空 = 用自动结果）",
                "留空 = 使用自动生成的正则", null, true, null);
        watch(etPattern);

        adv.addView(advBody, new LinearLayout.LayoutParams(MATCH, WRAP));

        LinearLayout.LayoutParams advLp = new LinearLayout.LayoutParams(MATCH, WRAP);
        advLp.topMargin = dp(16);
        form.addView(adv, advLp);

        // 试跑
        TextView runBtn = Ui.tv(this, "试跑最近 5 个文件", 14, Ui.ON_SECONDARY_CONTAINER, true);
        runBtn.setGravity(Gravity.CENTER);
        runBtn.setPadding(dp(16), 0, dp(16), 0);
        runBtn.setBackground(pressable(Ui.round(Ui.SECONDARY_CONTAINER, 24, this), 24));
        runBtn.setClickable(true);
        runBtn.setFocusable(true);
        runBtn.setContentDescription("用本机最近 5 个文件名试跑");
        runBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTest();
            }
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(MATCH, dp(48));
        rlp.topMargin = dp(20);
        form.addView(runBtn, rlp);

        tvRunHint = Ui.tv(this,
                "用本机最近 5 个真实文件名试跑；全部命中才能保存并启用。",
                12, Ui.ON_SURFACE_VARIANT, false);
        tvRunHint.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        hlp.topMargin = dp(10);
        form.addView(tvRunHint, hlp);

        tvRunHeader = Ui.tv(this, "", 13, Ui.ON_SURFACE, true);
        tvRunHeader.setVisibility(View.GONE);
        LinearLayout.LayoutParams ghlp = new LinearLayout.LayoutParams(MATCH, WRAP);
        ghlp.topMargin = dp(12);
        form.addView(tvRunHeader, ghlp);

        runList = Ui.col(this);
        LinearLayout.LayoutParams rllp = new LinearLayout.LayoutParams(MATCH, WRAP);
        rllp.topMargin = dp(8);
        form.addView(runList, rllp);

        watch(etFormat);
        watch(etTimeFmt);
    }

    private EditText textField(LinearLayout parent, String label, String hint, String value,
                               boolean mono, String helper) {
        TextView lab = Ui.tv(this, label, 12, Ui.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(MATCH, WRAP);
        lp.topMargin = dp(14);
        parent.addView(lab, lp);

        EditText e = new EditText(this);
        e.setTextSize(15);
        e.setTextColor(Ui.ON_SURFACE);
        e.setHintTextColor(Ui.OUTLINE);
        e.setHint(hint);
        if (value != null) {
            e.setText(value);
        }
        e.setSingleLine(true);
        e.setMinHeight(dp(46));
        e.setPadding(dp(12), dp(10), dp(12), dp(10));
        e.setBackground(Ui.round(Ui.SURFACE_CONTAINER, 10, this));
        int type = InputType.TYPE_CLASS_TEXT;
        if (mono) {
            e.setTypeface(Typeface.MONOSPACE);
            // 正则/格式串别让输入法自动纠错塞字。
            type |= InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        }
        e.setInputType(type);

        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(MATCH, WRAP);
        ep.topMargin = dp(4);
        parent.addView(e, ep);

        if (helper != null) {
            // helper 直接压在背景图上（不在卡片里），OUTLINE 是中灰，autoTint 又不认这个色，
            // 真机上压在亮区几乎看不见。换成 ON_SURFACE_VARIANT，交给 autoTint 按背景翻色。
            TextView h = Ui.tv(this, helper, 11, Ui.ON_SURFACE_VARIANT, false);
            h.setLineSpacing(dp(2), 1f);
            LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(MATCH, WRAP);
            hp.topMargin = dp(4);
            parent.addView(h, hp);
        }
        return e;
    }

    // ---------------------------------------------------------------- 底栏

    private View buildBottomBar() {
        LinearLayout bar = Ui.col(this);
        bar.setBackgroundColor(0xCCFEF7FF);
        bar.setElevation(dp(10));
        bar.setPadding(dp(16), dp(10), dp(16), dp(12));

        tvGate = Ui.tv(this, "", 11, Ui.ON_SURFACE_VARIANT, false);
        tvGate.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(MATCH, WRAP);
        glp.bottomMargin = dp(8);
        bar.addView(tvGate, glp);

        LinearLayout row = Ui.row(this);

        btnDraft = Ui.tv(this, "存草稿", 14, Ui.PRIMARY, true);
        btnDraft.setGravity(Gravity.CENTER);
        btnDraft.setBackground(pressable(
                Ui.round(0x00000000, 24, Ui.OUTLINE_VARIANT, 1, this), 24));
        btnDraft.setClickable(true);
        btnDraft.setFocusable(true);
        btnDraft.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                save(false);
            }
        });
        row.addView(btnDraft, new LinearLayout.LayoutParams(0, dp(48), 1f));

        btnEnable = Ui.tv(this, "保存并启用", 14, Ui.ON_SURFACE_VARIANT, true);
        btnEnable.setGravity(Gravity.CENTER);
        btnEnable.setClickable(true);
        btnEnable.setFocusable(true);
        btnEnable.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!canEnable()) {
                    Toast.makeText(TemplateEditActivity.this,
                            "还没试跑到 100%，先修模板；只想留着就点「存草稿」。",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                save(true);
            }
        });
        LinearLayout.LayoutParams ep = new LinearLayout.LayoutParams(0, dp(48), 1f);
        ep.leftMargin = dp(12);
        row.addView(btnEnable, ep);

        bar.addView(row, new LinearLayout.LayoutParams(MATCH, WRAP));
        return bar;
    }

    // ---------------------------------------------------------------- 状态

    private void fillFields() {
        String name = "";
        String fmt = DEFAULT_FORMAT;
        String timeFmt = DEFAULT_TIME_FMT;
        boolean enabled = true;

        if (loaded != null) {
            name = loaded.name == null ? "" : loaded.name;
            if (loaded.format != null && !loaded.format.trim().isEmpty()) {
                fmt = loaded.format;
            }
            if (loaded.timeFmt != null && !loaded.timeFmt.trim().isEmpty()) {
                timeFmt = loaded.timeFmt;
            }
            enabled = loaded.enabled;
        }
        etName.setText(name);
        etFormat.setText(fmt);
        etTimeFmt.setText(timeFmt);
        swEnabled.setChecked(enabled);

        // 老模板可能存的是手写正则：只有和 format 自动结果不一致时才当成覆盖值，
        // 否则每次打开都凭空多出一条 override。
        if (loaded != null) {
            String auto = TplEngine.formatToPattern(fmt);
            String pat = loaded.pattern == null ? "" : loaded.pattern.trim();
            if (!pat.isEmpty() && !pat.equals(auto)) {
                etPattern.setText(pat);
            }
        }

        renderPatternAuto();
        updateGate();
    }

    private void watch(EditText e) {
        if (e == null) {
            return;
        }
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                renderPatternAuto();
                invalidateRun();
            }
        });
    }

    private void renderPatternAuto() {
        if (tvAutoPattern == null || etFormat == null) {
            return;
        }
        String auto = TplEngine.formatToPattern(text(etFormat));
        tvAutoPattern.setText(auto == null ? "—" : auto);

        String ov = text(etPattern);
        if (ov.isEmpty()) {
            tvOverrideState.setText("当前使用自动生成的正则");
            tvOverrideState.setTextColor(Ui.OUTLINE);
            return;
        }
        try {
            Pattern.compile(ov);
            tvOverrideState.setText("已用手写正则覆盖，上面的自动结果不再生效");
            tvOverrideState.setTextColor(Ui.PRIMARY);
        } catch (PatternSyntaxException e) {
            tvOverrideState.setText("手写正则无效：" + e.getDescription());
            tvOverrideState.setTextColor(Ui.ERROR);
        }
    }

    private void invalidateRun() {
        if (!hasRun) {
            return;
        }
        hasRun = false;
        runStale = true;
        runTotal = 0;
        runMatched = 0;
        noFiles = false;
        if (runList != null) {
            runList.removeAllViews();
        }
        if (tvRunHeader != null) {
            tvRunHeader.setVisibility(View.GONE);
        }
        if (tvRunHint != null) {
            tvRunHint.setVisibility(View.VISIBLE);
            tvRunHint.setText("字段有改动，请重新试跑。");
        }
        updateGate();
    }

    private boolean canEnable() {
        return !runStale && runTotal > 0 && runMatched == runTotal;
    }

    private void updateGate() {
        if (tvGate == null) {
            return;
        }
        if (runStale) {
            tvGate.setText("字段有改动，请重新试跑；未按 100% 验证前不能启用");
            tvGate.setTextColor(Ui.ON_SURFACE_VARIANT);
        } else if (runTotal > 0 && runMatched == runTotal) {
            tvGate.setText(runMatched + "/" + runTotal + " 通过 · 可以保存并启用");
            tvGate.setTextColor(Ui.SUCCESS);
        } else if (runTotal > 0) {
            tvGate.setText(runMatched + "/" + runTotal + " · 未全部通过，只能存草稿");
            tvGate.setTextColor(Ui.ERROR);
        } else if (noFiles) {
            tvGate.setText("库里没有录音文件，无法试跑；只能先存草稿");
            tvGate.setTextColor(Ui.ON_SURFACE_VARIANT);
        } else {
            tvGate.setText("先试跑最近 5 个文件；100% 通过才能启用");
            tvGate.setTextColor(Ui.ON_SURFACE_VARIANT);
        }
        styleButtons();
    }

    private void styleButtons() {
        boolean can = canEnable();
        btnEnable.setEnabled(can);
        btnEnable.setClickable(can);
        if (can) {
            btnEnable.setBackground(pressable(Ui.round(Ui.PRIMARY, 24, this), 24));
            btnEnable.setTextColor(Ui.ON_PRIMARY);
        } else {
            // 命中率不到 100%（或还没试跑）：置灰。
            btnEnable.setBackground(Ui.round(Ui.SURFACE_HIGH, 24, this));
            btnEnable.setTextColor(Ui.OUTLINE);
        }
    }

    private void setAdvancedOpen(boolean open) {
        if (advBody == null) {
            return;
        }
        advBody.setVisibility(open ? View.VISIBLE : View.GONE);
        if (advArrow != null) {
            advArrow.animate().rotation(open ? 180f : 0f).setDuration(150).start();
        }
    }

    private void openAdvanced() {
        if (advBody != null && advBody.getVisibility() != View.VISIBLE) {
            setAdvancedOpen(true);
        }
    }

    // ---------------------------------------------------------------- 试跑

    private void runTest() {
        String fmt = text(etFormat);
        if (fmt.isEmpty()) {
            Toast.makeText(this, "先填格式串，再试跑", Toast.LENGTH_SHORT).show();
            etFormat.requestFocus();
            return;
        }
        String perr = patternError();
        if (perr != null) {
            Toast.makeText(this, "正则无效：" + perr, Toast.LENGTH_LONG).show();
            openAdvanced();
            etPattern.requestFocus();
            return;
        }

        List<Rec> recs;
        try {
            recs = db.list("", -1, 5);
        } catch (RuntimeException e) {
            recs = new ArrayList<Rec>();
            Toast.makeText(this, "读不到录音列表：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        Tpl trial = buildTpl();
        runList.removeAllViews();
        int matched = 0;
        for (Rec r : recs) {
            String fn = fileNameOf(r);
            TplEngine.Hit hit = TplEngine.parseWithTemplate(trial, fn);
            if (hit != null && hit.matched) {
                matched++;
            }
            runList.addView(buildRunRow(fn, hit));
        }

        runTotal = recs.size();
        runMatched = matched;
        noFiles = runTotal == 0;
        hasRun = true;
        runStale = false;

        if (noFiles) {
            tvRunHeader.setVisibility(View.GONE);
            tvRunHint.setVisibility(View.VISIBLE);
            tvRunHint.setText("库里没有录音文件，先去主界面扫描一次再试跑。");
        } else {
            tvRunHint.setVisibility(View.GONE);
            tvRunHeader.setVisibility(View.VISIBLE);
            if (matched == runTotal) {
                tvRunHeader.setText(runTotal + "/" + runTotal + " 通过");
                tvRunHeader.setTextColor(Ui.SUCCESS);
            } else {
                tvRunHeader.setText(matched + "/" + runTotal + " · 未全部通过");
                tvRunHeader.setTextColor(Ui.ERROR);
            }
        }
        updateGate();
    }

    private View buildRunRow(String fileName, TplEngine.Hit hit) {
        boolean ok = hit != null && hit.matched;

        LinearLayout box = Ui.col(this);
        box.setPadding(dp(10), dp(8), dp(10), dp(8));
        box.setBackground(Ui.round(Ui.SURFACE_CONTAINER, 10, this));

        LinearLayout head = Ui.row(this);
        head.addView(Ui.tv(this, ok ? "✔" : "✘", 13, ok ? Ui.SUCCESS : Ui.ERROR, true));

        TextView f = Ui.tv(this, fileName, 12, Ui.ON_SURFACE, false);
        f.setTypeface(Typeface.MONOSPACE);
        f.setSingleLine(true);
        f.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0, WRAP, 1f);
        fp.leftMargin = dp(6);
        head.addView(f, fp);
        box.addView(head, new LinearLayout.LayoutParams(MATCH, WRAP));

        TextView detail = Ui.tv(this, detailText(hit), 12,
                ok ? Ui.ON_SURFACE_VARIANT : Ui.ERROR, false);
        detail.setLineSpacing(dp(2), 1f);
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(MATCH, WRAP);
        dp2.topMargin = dp(4);
        box.addView(detail, dp2);

        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(MATCH, WRAP);
        bp.bottomMargin = dp(8);
        box.setLayoutParams(bp);
        return box;
    }

    private String detailText(TplEngine.Hit hit) {
        if (hit == null) {
            return "解析失败：没有结果";
        }
        if (!hit.matched) {
            if (hit.error == null || hit.error.isEmpty() || "未命中".equals(hit.error)) {
                return "未命中：文件名对不上这条模板";
            }
            // 正则写坏了、模板缺字段之类，原因原样给出来，别糊成一句“没中”。
            return hit.error;
        }
        return "姓名 " + orDash(hit.name)
                + " · 号码 " + orDash(hit.phone)
                + " · 时间 " + timeText(hit.startMs);
    }

    private static String orDash(String s) {
        return s == null || s.trim().isEmpty() ? "—" : s.trim();
    }

    private String timeText(long ms) {
        if (ms <= 0) {
            return "未取到";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
    }

    private static String fileNameOf(Rec r) {
        if (r == null) {
            return "（无文件名）";
        }
        if (r.fileName != null && !r.fileName.trim().isEmpty()) {
            return r.fileName.trim();
        }
        if (r.path != null) {
            int i = Math.max(r.path.lastIndexOf('/'), r.path.lastIndexOf('\\'));
            String base = i >= 0 ? r.path.substring(i + 1) : r.path;
            return base.isEmpty() ? r.path : base;
        }
        return "（无文件名）";
    }

    // ---------------------------------------------------------------- 保存

    private void save(boolean enable) {
        String name = text(etName);
        if (name.isEmpty()) {
            Toast.makeText(this, "先给模板起个名字", Toast.LENGTH_SHORT).show();
            etName.requestFocus();
            return;
        }
        String fmt = text(etFormat);
        if (fmt.isEmpty()) {
            Toast.makeText(this, "格式串不能为空", Toast.LENGTH_SHORT).show();
            etFormat.requestFocus();
            return;
        }
        String perr = patternError();
        if (perr != null) {
            Toast.makeText(this, "正则无效：" + perr, Toast.LENGTH_LONG).show();
            openAdvanced();
            etPattern.requestFocus();
            return;
        }

        Tpl t = buildTpl();
        t.name = name;
        t.format = fmt;
        // 时间格式允许留空：TplEngine 会按内置候选格式猜。试跑与保存走同一个
        // buildTpl，别在这儿偷偷补默认值，否则“试跑通过、保存后不灵”。
        t.enabled = enable;
        db.saveTpl(t);

        Toast.makeText(this,
                enable ? "已保存并启用「" + name + "」" : "已存草稿「" + name + "」（暂不参与匹配）",
                Toast.LENGTH_SHORT).show();
        finish();
    }

    /** 面板值 → Tpl；试跑和保存共用，保证两边用的 pattern 完全一致。 */
    private Tpl buildTpl() {
        Tpl t = new Tpl();
        if (loaded != null) {
            t.id = loaded.id;
            t.priority = loaded.priority;
            t.builtin = loaded.builtin;
            t.scope = loaded.scope;
        } else {
            t.id = newId;
            t.priority = 100;
            t.builtin = false;
            t.scope = "audio";
        }
        t.name = text(etName);
        t.format = text(etFormat);
        t.timeFmt = text(etTimeFmt);
        String ov = text(etPattern);
        t.pattern = ov.isEmpty() ? TplEngine.formatToPattern(t.format) : ov;
        t.enabled = swEnabled.isChecked();
        return t;
    }

    /** 手写正则能编译就返回 null，否则返回可读原因。 */
    private String patternError() {
        String ov = text(etPattern);
        if (ov.isEmpty()) {
            return null;
        }
        try {
            Pattern.compile(ov);
            return null;
        } catch (PatternSyntaxException e) {
            return e.getDescription();
        }
    }

    // ---------------------------------------------------------------- 小工具

    private static String text(EditText e) {
        if (e == null || e.getText() == null) {
            return "";
        }
        return e.getText().toString().trim();
    }

    private TextView badge(String s, int fg, int bgColor) {
        TextView b = Ui.tv(this, s, 10, fg, true);
        b.setPadding(dp(6), dp(2), dp(6), dp(2));
        b.setBackground(Ui.round(bgColor, 6, this));
        return b;
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

    private void applyInsets(FrameLayout root, final LinearLayout page) {
        if (Build.VERSION.SDK_INT < 30) {
            return;
        }
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
                android.graphics.Insets bars = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                android.graphics.Insets ime = insets.getInsets(WindowInsets.Type.ime());
                page.setPadding(bars.left, bars.top, bars.right,
                        Math.max(bars.bottom, ime.bottom));
                return insets;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 背景变了就重建（编辑页也要跟着换）
        if (Bg.configChanged(this)) {
            recreate();
            return;
        }
    }
}
