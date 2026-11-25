package com.zglinus.recstt.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.PlaybackParams;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.R;
import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.model.Seg;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.svc.TranscribeService;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 「边听边读」第 10 屏：播放器卡片 + 一整块转写正文容器。
 *
 * <p>状态机（正文区只有一种形态，避免半截界面）：
 * <ul>
 *   <li>有分段 → 逐行「时间 + 正文」，当前句整行高亮、自动跟随；</li>
 *   <li>没分段 → 空态：未转写 / 转写中 xx% / 转写失败（红字 + 重试）/ 完成但没分段；</li>
 *   <li>没分段时每 1 秒读一次 {@link Db#byId(long)}，完成即自动铺正文。</li>
 * </ul>
 *
 * <p>播放：MediaPlayer + prepareAsync；onPause 必暂停，onDestroy 必 release。
 * 倍速走 {@link MediaPlayer#setPlaybackParams(PlaybackParams)}，失败要 Toast 出来，不装作没事。
 */
public final class ReaderActivity extends Activity {

    private static final String TAG = "ReaderActivity";

    /** Intent extra：录音 id（Rec.id），与 INTERFACES.md / TranscribeService 同名。 */
    public static final String EXTRA_REC = "rec_id";

    /** 卡片/容器用 surface 的不同不透明度，保证压在自定义背景上还能读。 */
    private static final int CARD_BG = 0xE0FEF7FF; // surface 88%
    // 正文卡片底：不能写死浅色，纯色模式 + 系统深色时这里是深底浅字。
    private int bodyBg() {
        return Ui.alpha(Ui.SURFACE, 0xF2);
    }
    // 原来用 primary 20% alpha，铺在浅色卡片上几乎看不见（真机反馈"进度条没有背景"）
    private static final int TRACK_BG = 0xFFE6E0E9; // surface_highest，实色

    /** 倍速档位：1.0 → 1.25 → 1.5 → 2.0 → 回到 1.0。 */
    private static final float[] SPEEDS = {1.0f, 1.25f, 1.5f, 2.0f};
    private static final String[] SPEED_LABELS = {"1.0×", "1.25×", "1.5×", "2.0×"};

    private static final long TICK_MS = 200L;          // 播放进度 / 高亮刷新
    private static final long POLL_MS = 1000L;         // 转写状态轮询
    private static final long SCROLL_HOLD_MS = 3000L;  // 用户摸过滚动区后 3 秒不自动滚

    private static final int MENU_EXPORT = 1;
    private static final int MENU_TRANS = 2;
    private static final int MENU_DELETE = 3;

    private Db db;
    private long recId;
    private Rec rec;

    private final Handler handler = new Handler(Looper.getMainLooper());

    // 顶栏 / 播放器
    private TextView speedBtn;
    private TextView curTime;
    private TextView totalTime;
    private SeekStrip strip;
    private ImageView playBtn;

    // 正文
    private FrameLayout bodyFrame;
    private ScrollView scroll;
    private LinearLayout rowsBox;
    private LinearLayout stateBox;
    private TextView stateTitle;
    private TextView stateDetail;
    private TextView stateAction;

    private List<Seg> segs = new ArrayList<Seg>();
    private int segsSig;
    private final List<View> rowViews = new ArrayList<View>();
    private final List<TextView> rowTexts = new ArrayList<TextView>();

    // 播放器状态
    private MediaPlayer player;
    private boolean prepared;
    private boolean playWhenPrepared;
    private boolean userSeeking;
    /**
     * 跳转宽限期：seekTo() 之后播放器要几十到几百毫秒才真正跳到位，
     * 这期间 tick 读到的还是旧位置。如果不管，它会把刚点亮的那句抢回去
     * （日志实证：点第 0 句 → 立刻 HIGHLIGHT idx=1 抢回）。
     */
    private long suppressHighlightUntil = 0L;
    private static final long SEEK_SETTLE_MS = 600L;
    private boolean seekResumePlay;
    private int durationMs = -1;
    private int pendingSeekMs = -1;
    private int speedIdx = 0;
    private int curIdx = -1;
    private int highlighted = -1;
    private long suppressScrollUntil = 0L;

    // 定时器状态
    private boolean tickerRunning;
    private boolean polling;

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this); // 先定主题/调色板，再 super.onCreate
        super.onCreate(savedInstanceState);

        recId = getIntent() == null ? 0L : getIntent().getLongExtra(EXTRA_REC, 0L);
        db = Db.get(this);
        rec = recId > 0 ? db.byId(recId) : null;
        if (rec == null) {
            // 没有记录就没有界面可画：说清楚再退，不画一个空壳
            Toast.makeText(this, "找不到这条录音（可能已被删除）", Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        FrameLayout root = new FrameLayout(this);
        Bg.setOnReady(new Runnable() {
            @Override public void run() {
                Ui.autoTintBgText(root);
            }
        });
        Bg.attach(this, root); // 必须先铺背景，后面所有内容都压在它上面
        setContentView(root);
        // 界面搭完后统一扫一遍：把压在背景上、还没换色的文字改成浅色
        Ui.autoTintBgText(root);
        buildUi(root);

        // targetSdk 36 / Android 15+ 强制 edge-to-edge：给最后挂上去的内容层补状态栏/导航栏 inset，
        // 否则顶栏会钻到状态栏底下（背景层在 root 的第 0、1 个子 View，不能直接给 root 加 padding）。
        if (root.getChildCount() > 0) {
            Ui.applySystemBarsInsets(this, root.getChildAt(root.getChildCount() - 1));
        }
        // 让裁剪不挡 padding 区，底部按钮才不会被手势条压住
        View content = root.getChildAt(0);
        if (content instanceof android.view.ViewGroup) {
            ((android.view.ViewGroup) content).setClipToPadding(false);
        }

        loadSegs();
        preparePlayer();
        if (segs.isEmpty()) {
            renderState();
        }
        if (needsPolling()) {
            startPolling();
        }
        startTicker(); // 暂停态也刷一次，让高亮/时间落位
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 背景/毛玻璃配置变了：整套颜色和底板都已过期，整体重建最省事也最不会漏。
        if (Bg.configChanged(this)) {
            recreate();
            return;
        }
        if (rec == null) {
            return;
        }
        refreshFromDb();
        if (needsPolling()) {
            startPolling();
        }
        startTicker();
    }

    @Override
    protected void onPause() {
        // 契约：onPause 必须停播放，别在后台接着响
        if (rec != null) {
            pausePlayback();
            stopPolling();
            stopTicker();
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        tickerRunning = false;
        polling = false;
        releasePlayer();
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 界面搭建

    private void buildUi(FrameLayout root) {
        LinearLayout page = Ui.col(this);
        // 顶部不要再手工加状态栏：下面统一的 applySystemBarsInsets 会补。
        // 两边都加 = 顶部空出整整一条状态栏（真机看到的就是这条空白）。
        page.setPadding(dp(16), dp(4), dp(16), dp(12));
        root.addView(page, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        page.addView(buildTopBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56)));

        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(4);
        page.addView(buildPlayerCard(), cardLp);

        bodyFrame = buildBody();
        LinearLayout.LayoutParams bodyLp = Ui.lpWeight(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = dp(12);
        page.addView(bodyFrame, bodyLp);
    }

    private View buildTopBar() {
        LinearLayout bar = Ui.row(this);

        View back = Ui.iconButton(this, R.drawable.ic_back, Ui.ON_SURFACE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        finish();
                    }
                });
        back.setContentDescription("返回");
        bar.addView(back);

        TextView title = Ui.tv(this, headerName(), 17, Ui.ON_SURFACE, true);
        title.setSingleLine(true);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams tlp = Ui.lpWeight(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(6);
        bar.addView(title, tlp);

        View more = Ui.iconButton(this, R.drawable.ic_more, Ui.ON_SURFACE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMenu(v);
                    }
                });
        more.setContentDescription("更多");
        bar.addView(more);
        return bar;
    }

    private View buildPlayerCard() {
        LinearLayout card = Ui.col(this);
        if (!Ui.glassCard(card, 26, 0x80)) {
            card.setBackground(Ui.card(this, CARD_BG, 26));
        }
        card.setPadding(dp(16), dp(14), dp(16), dp(12));

        // 标题行：MM-dd HH:mm · 时长  +  倍速
        LinearLayout head = Ui.row(this);
        TextView when = Ui.tv(this, Ui.mdhm(rec.startMs) + " · " + Ui.dur(rec.durMs),
                15, Ui.ON_SURFACE, true);
        when.setSingleLine(true);
        when.setEllipsize(android.text.TextUtils.TruncateAt.END);
        head.addView(when, Ui.lpWeight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        speedBtn = Ui.tv(this, SPEED_LABELS[speedIdx], 13, Ui.ON_SECONDARY_CONTAINER, true);
        speedBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        speedBtn.setBackground(Ui.round(Ui.SECONDARY_CONTAINER, 14, this));
        speedBtn.setClickable(true);
        speedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleSpeed();
            }
        });
        head.addView(speedBtn);
        card.addView(head, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // 当前时间 / 总时长
        LinearLayout times = Ui.row(this);
        LinearLayout.LayoutParams timesLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        timesLp.topMargin = dp(10);
        curTime = Ui.tv(this, "00:00", 13, Ui.ON_SURFACE_VARIANT, false);
        totalTime = Ui.tv(this, Ui.dur(rec.durMs), 13, Ui.ON_SURFACE_VARIANT, false);
        times.addView(curTime, Ui.lpWeight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        times.addView(totalTime);
        card.addView(times, timesLp);

        // 自绘进度条：SeekBar 在自定义 LayerDrawable 下轨道会被裁掉只剩圆点，
        // 这里换成 SeekStrip（轻点跳转、横向拖动 seek），不再依赖 progressDrawable。
        strip = new SeekStrip(this);
        strip.setMax(1);
        strip.setListener(new SeekStrip.Listener() {
            @Override
            public void onMove(int posMs, boolean fromUser) {
                if (!fromUser) return;
                curTime.setText(Ui.clock(posMs));
                int idx = indexAt(posMs);
                if (idx != highlighted) {
                    applyHighlight(idx);
                }
            }

            @Override
            public void onStart(int posMs) {
                userSeeking = true;
                seekResumePlay = isActuallyPlaying();
            }

            @Override
            public void onStop(int posMs) {
                userSeeking = false;
                // 本来在播就接着播，本来就是暂停就别自作主张开播
                seekTo(posMs, seekResumePlay);
            }
        });
        LinearLayout.LayoutParams seekLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(40));
        card.addView(strip, seekLp);

        // 控制行：上一句 / 播放暂停 / 下一句 … 导出
        LinearLayout controls = Ui.row(this);
        LinearLayout.LayoutParams ctlLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ctlLp.topMargin = dp(2);
        controls.setGravity(Gravity.CENTER_VERTICAL);

        View prev = Ui.iconButton(this, R.drawable.ic_rew10, Ui.ON_SURFACE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        playPrev();
                    }
                });
        prev.setContentDescription("上一句");
        controls.addView(prev);

        playBtn = new ImageView(this);
        playBtn.setImageResource(R.drawable.ic_play);
        playBtn.setColorFilter(Ui.ON_PRIMARY);
        playBtn.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        playBtn.setPadding(dp(14), dp(14), dp(14), dp(14));
        playBtn.setBackground(Ui.round(Ui.PRIMARY, 28, this));
        playBtn.setClickable(true);
        playBtn.setContentDescription("播放");
        playBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                togglePlay();
            }
        });
        LinearLayout.LayoutParams playLp = new LinearLayout.LayoutParams(dp(56), dp(56));
        playLp.leftMargin = dp(10);
        playLp.rightMargin = dp(10);
        controls.addView(playBtn, playLp);

        View next = Ui.iconButton(this, R.drawable.ic_fwd10, Ui.ON_SURFACE,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        playNext();
                    }
                });
        next.setContentDescription("下一句");
        controls.addView(next);

        View spacer = new View(this);
        controls.addView(spacer, Ui.lpWeight(0, 1, 1f));

        View export = Ui.iconButton(this, R.drawable.ic_download, Ui.ON_SURFACE_VARIANT,
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        exportSrt();
                    }
                });
        export.setContentDescription("导出 SRT");
        controls.addView(export);

        card.addView(controls, ctlLp);
        return card;
    }

    private Drawable buildProgressDrawable() {
        GradientDrawable track = new GradientDrawable();
        track.setColor(TRACK_BG);
        track.setCornerRadius(dp(2));

        GradientDrawable fill = new GradientDrawable();
        fill.setColor(Ui.PRIMARY);
        fill.setCornerRadius(dp(2));
        ClipDrawable clip = new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL);

        LayerDrawable layers = new LayerDrawable(new Drawable[]{track, clip});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        // SeekBar 高 28dp，上下各让 12dp → 轨道可见高度 4dp（不包一层 InsetDrawable，
        // 让 SeekBar 自己看到的还是 LayerDrawable，level → ClipDrawable 裁剪才走原生那条路）
        // 左右各让 8dp：正好等于半个 16dp 拇指，轨道两端与拇指中心对齐
        layers.setLayerInset(0, dp(8), dp(12), dp(8), dp(12));
        layers.setLayerInset(1, dp(8), dp(12), dp(8), dp(12));
        return layers;
    }

    private Drawable buildThumb() {
        GradientDrawable thumb = new GradientDrawable();
        thumb.setShape(GradientDrawable.OVAL);
        thumb.setColor(Ui.PRIMARY);
        thumb.setStroke(dp(2), 0xFFFFFFFF);
        thumb.setSize(dp(16), dp(16));
        return thumb;
    }

    private FrameLayout buildBody() {
        bodyFrame = new FrameLayout(this);
        if (!Ui.glassCard(bodyFrame, 24, 0x8C)) {
            bodyFrame.setBackground(Ui.card(this, bodyBg(), 24));
        }
        // 行高亮是 8dp 圆角方块、左侧还有 3dp 竖条；不裁的话会溢出到 24dp 卡片圆角外
        bodyFrame.setClipToOutline(true);
        bodyFrame.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(24));
            }
        });

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setVerticalFadingEdgeEnabled(false);
        scroll.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent e) {
                int a = e.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_MOVE
                        || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                    // 用户碰过滚动区：3 秒内别再抢他的位置
                    suppressScrollUntil = SystemClock.uptimeMillis() + SCROLL_HOLD_MS;
                }
                return false;
            }
        });

        rowsBox = Ui.col(this);
        rowsBox.setPadding(dp(12), dp(10), dp(12), dp(10));
        // 必须是 ScrollView.LayoutParams。
        // 原来给的是 FrameLayout.LayoutParams —— ScrollView 虽然继承自 FrameLayout，
        // 但子 View 用错类型时它退回到自己的默认测量，结果 rowsBox 只算出 97px 高，
        // 4 句里只有 2 句放得下，后两句被裁掉（界面上就是"下面几句不见了"）。
        scroll.addView(rowsBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        bodyFrame.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 空态/转写中/失败：同一块位置，避免正文区一会儿有、一会儿没
        stateBox = Ui.col(this);
        stateBox.setGravity(Gravity.CENTER);
        stateBox.setPadding(dp(24), dp(24), dp(24), dp(24));
        stateBox.setVisibility(View.GONE);

        stateTitle = Ui.tv(this, "", 15, Ui.ON_SURFACE, true);
        stateTitle.setGravity(Gravity.CENTER);
        stateBox.addView(stateTitle);

        stateDetail = Ui.tv(this, "", 13, Ui.ON_SURFACE_VARIANT, false);
        stateDetail.setGravity(Gravity.CENTER);
        stateDetail.setVisibility(View.GONE);
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        detailLp.topMargin = dp(8);
        stateBox.addView(stateDetail, detailLp);

        stateAction = Ui.tv(this, "开始转写", 14, Ui.ON_PRIMARY, true);
        stateAction.setPadding(dp(20), dp(11), dp(20), dp(11));
        stateAction.setBackground(Ui.round(Ui.PRIMARY, 20, this));
        stateAction.setClickable(true);
        stateAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startTranscribe();
            }
        });
        LinearLayout.LayoutParams actionLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionLp.topMargin = dp(16);
        stateBox.addView(stateAction, actionLp);

        bodyFrame.addView(stateBox, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return bodyFrame;
    }

    // ------------------------------------------------------------------ 正文

    /** 从 Db 读一次分段；空列表也算读成功（调用方按空态处理）。 */
    private void loadSegs() {
        List<Seg> list = db.segs(recId);
        segs = prune(list);
        segsSig = segs.isEmpty() ? 0 : sigOf(segs);
        if (segs.isEmpty()) {
            rowsBox.removeAllViews();
            rowViews.clear();
            rowTexts.clear();
            scroll.setVisibility(View.GONE);
            return;
        }
        renderRows();
        scroll.setVisibility(View.VISIBLE);
        stateBox.setVisibility(View.GONE);
        int idx = indexAt(0);
        applyHighlight(idx);
        if (idx >= 0) {
            scrollToRow(idx);
        }
    }

    private void renderRows() {
        rowsBox.removeAllViews();
        rowViews.clear();
        rowTexts.clear();
        highlighted = -1;

        for (int i = 0; i < segs.size(); i++) {
            final Seg seg = segs.get(i);
            if (seg == null) {
                continue;
            }
            FrameLayout row = new FrameLayout(this);

            LinearLayout line = Ui.row(this);
            line.setGravity(Gravity.TOP);
            line.setPadding(dp(12), dp(8), dp(12), dp(8));

            // 时间戳：去掉白底块（太突兀），改成弱化的等宽小字 + 居中。
            // 靠"更小 + 更淡 + 居中"和正文分层，不靠底色抢视线。
            TextView clock = Ui.tv(this, Ui.clock(seg.startMs), 10, 0x8F6B6478, false);
            clock.setGravity(Gravity.CENTER);
            clock.setIncludeFontPadding(false);
            clock.setPadding(0, dp(2), 0, 0);
            clock.setLayoutParams(Ui.lp(dp(46), ViewGroup.LayoutParams.WRAP_CONTENT));
            line.addView(clock);

            TextView body = Ui.tv(this, seg.text == null ? "" : seg.text,
                    14, Ui.ON_SURFACE, false);
            body.setIncludeFontPadding(false);
            // 14sp 正文 + 3dp 行距 ≈ 21dp 行高
            body.setLineSpacing(dp(3), 1f);
            line.addView(body, Ui.lpWeight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            rowTexts.add(body);

            row.addView(line, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            row.setClickable(true);
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // 点哪句就跳到哪句并开播
                    seekTo(seg.startMs, true);
                }
            });

            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(4);
            rowsBox.addView(row, rowLp);
            rowViews.add(row);
        }
    }

    /** 当前播放位置落在哪一句（返回 -1 表示还没到第一句）。 */
    private int indexAt(int posMs) {
        int found = -1;
        for (int i = 0; i < segs.size(); i++) {
            Seg s = segs.get(i);
            if (s == null) {
                continue;
            }
            if (s.startMs <= posMs) {
                found = i;
            } else {
                break;
            }
        }
        return found;
    }

    private void applyHighlight(int idx) {
        if (idx == highlighted) {
            return;
        }
        if (highlighted >= 0 && highlighted < rowViews.size()) {
            setRowActive(highlighted, false);
        }
        highlighted = idx;
        if (idx >= 0 && idx < rowViews.size()) {
            setRowActive(idx, true);
        }
    }

    private void setRowActive(int idx, boolean on) {
        View row = rowViews.get(idx);
        // 高亮底色 + 左侧竖条都画在这一个 Drawable 里。
        // 不能用子 View：竖条要跟行等高就得 MATCH_PARENT，
        // 而行容器是 WRAP_CONTENT、父级又给 AT_MOST，
        // MATCH_PARENT 会先把"可用高度"吃满，行跟着长到 1322px，
        // 后面几行直接变 0 高 —— 就是"点第一句，下面几句不见了"。
        row.setBackground(on ? new RowBg(this) : null);
        if (idx < rowTexts.size()) {
            rowTexts.get(idx).setTextColor(on ? Ui.ON_PRIMARY_CONTAINER : Ui.ON_SURFACE);
        }
    }

    private void scrollToRow(int idx) {
        if (idx < 0 || idx >= rowViews.size()) {
            return;
        }
        // 内容不够一屏时根本没法滚：直接不动。
        // 否则每次点行/播放进度都会调 scrollTo(0,0)，
        // 界面上看就是"点了之后整块文字跳一下"（段数少时特别明显）。
        View content = rowsBox;
        if (content == null || content.getHeight() <= scroll.getHeight()) {
            return;
        }
        View row = rowViews.get(idx);
        int maxScroll = content.getHeight() - scroll.getHeight();
        int viewTop = scroll.getScrollY();
        int viewBottom = viewTop + scroll.getHeight();
        int rowTop = row.getTop();
        int rowBottom = row.getBottom();

        // 原来是"每次都把目标句滚到屏幕正中"。
        // 段数少/一屏刚好放得下时这么干很糟：点第一句会把整块内容往上顶，
        // 后面的句子被挤出屏幕，看着像"下面几句不见了"。
        // 改成**最小滚动**：目标句已经在视野里就一点不动；
        // 超出上下边界时才滚，而且只滚到刚好露出整行（上下各留 1/3 屏余量）。
        int margin = scroll.getHeight() / 3;
        int target = viewTop;
        if (rowTop - margin < viewTop) {
            target = rowTop - margin;              // 离上边太近，往上滚一点露出余量
        } else if (rowBottom + margin > viewBottom) {
            target = rowBottom + margin - scroll.getHeight();  // 离下边太近，往下滚一点
        }
        if (target < 0) {
            target = 0;
        }
        if (target > maxScroll) {
            target = maxScroll;
        }
        if (scroll.getScrollY() != target) {
            scroll.scrollTo(0, target);
        }
    }

    // ------------------------------------------------------------------ 播放

    private void preparePlayer() {
        releasePlayer();
        String path = rec.path;
        if (path == null || path.trim().isEmpty()) {
            Toast.makeText(this, "这条记录没有音频路径，无法播放", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            MediaPlayer mp = new MediaPlayer();
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mp.setDataSource(path);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp2) {
                    prepared = true;
                    try {
                        durationMs = mp2.getDuration();
                    } catch (Throwable t) {
                        durationMs = -1;
                    }
                    if (durationMs <= 0) {
                        durationMs = (int) Math.max(0L, rec.durMs);
                    }
                    strip.setMax(Math.max(1, durationMs));
                    totalTime.setText(Ui.dur(durationMs > 0 ? durationMs : rec.durMs));
                    applySpeed();
                    if (pendingSeekMs >= 0) {
                        int p = pendingSeekMs;
                        pendingSeekMs = -1;
                        try {
                            mp2.seekTo(p);
                        } catch (Throwable t) {
                            Log.w(TAG, "补 seek 失败", t);
                        }
                    }
                    if (playWhenPrepared) {
                        playWhenPrepared = false;
                        startPlayback();
                    } else {
                        startTicker();
                    }
                }
            });
            mp.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
                @Override
                public void onCompletion(MediaPlayer mp2) {
                    setPlayIcon(false);
                    stopTicker();
                    int end = durationMs > 0 ? durationMs : 0;
                    curTime.setText(Ui.clock(end));
                    if (durationMs > 0) {
                        strip.setProgress(durationMs);
                    }
                    int last = segs.isEmpty() ? -1 : segs.size() - 1;
                    applyHighlight(last);
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp2, int what, int extra) {
                    prepared = false;
                    setPlayIcon(false);
                    stopTicker();
                    // 播放失败也要说话，免得用户以为是在缓冲
                    Toast.makeText(ReaderActivity.this,
                            "播放失败：音频读不出来（what=" + what + " extra=" + extra + "）",
                            Toast.LENGTH_LONG).show();
                    return true;
                }
            });
            player = mp;
            mp.prepareAsync();
        } catch (Throwable t) {
            releasePlayer();
            Toast.makeText(this, "播放器初始化失败：" + reason(t), Toast.LENGTH_LONG).show();
        }
    }

    private void releasePlayer() {
        prepared = false;
        if (player != null) {
            try {
                player.release();
            } catch (Throwable t) {
                Log.w(TAG, "release 播放器失败", t);
            }
            player = null;
        }
    }

    private void cycleSpeed() {
        speedIdx = (speedIdx + 1) % SPEEDS.length;
        speedBtn.setText(SPEED_LABELS[speedIdx]);
        applySpeed();
    }

    private void applySpeed() {
        if (player == null || !prepared) {
            return;
        }
        boolean wasPaused = !isActuallyPlaying();
        try {
            player.setPlaybackParams(new PlaybackParams().setSpeed(SPEEDS[speedIdx]));
            if (wasPaused && isActuallyPlaying()) {
                // 个别机型在暂停状态设倍速会顺手开播，这里按回暂停，不让它替用户按播放
                player.pause();
                setPlayIcon(false);
                stopTicker();
            }
        } catch (Throwable t) {
            Log.w(TAG, "设置倍速失败 speed=" + SPEEDS[speedIdx], t);
            Toast.makeText(this, "这台设备不支持 " + SPEED_LABELS[speedIdx] + " 倍速",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void togglePlay() {
        if (!prepared) {
            // 还在 prepareAsync：记下来，准备好就开播
            playWhenPrepared = true;
            if (player == null) {
                preparePlayer();
            }
            return;
        }
        try {
            if (player.isPlaying()) {
                player.pause();
                setPlayIcon(false);
                stopTicker();
            } else {
                player.start();
                setPlayIcon(true);
                startTicker();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "播放失败：" + reason(t), Toast.LENGTH_LONG).show();
        }
    }

    private void startPlayback() {
        if (!prepared) {
            playWhenPrepared = true;
            if (player == null) {
                preparePlayer();
            }
            return;
        }
        try {
            player.start();
            setPlayIcon(true);
            startTicker();
        } catch (Throwable t) {
            Toast.makeText(this, "播放失败：" + reason(t), Toast.LENGTH_LONG).show();
        }
    }

    private void pausePlayback() {
        if (player != null) {
            try {
                if (player.isPlaying()) {
                    player.pause();
                }
            } catch (Throwable t) {
                Log.w(TAG, "暂停失败", t);
            }
        }
        setPlayIcon(false);
        stopTicker();
    }

    private void setPlayIcon(boolean playing) {
        if (playBtn == null) {
            return;
        }
        playBtn.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
        playBtn.setContentDescription(playing ? "暂停" : "播放");
    }

    /** 跳转到某毫秒；autoPlay=true 时跳完接着播（点行/上一句/下一句都走这里）。 */
    private void seekTo(int ms, boolean autoPlay) {
        int target = Math.max(0, ms);
        if (durationMs > 0 && target > durationMs) {
            target = durationMs;
        }
        curTime.setText(Ui.clock(target));
        int idx = indexAt(target);
        // 点的这一句立即点亮，并给播放器 600ms 跳位时间，
        // 期间 tick 不许用旧进度改高亮
        suppressHighlightUntil = SystemClock.uptimeMillis() + SEEK_SETTLE_MS;
        curIdx = idx;              // 让 tick 的"变了才更新"判断基于新位置
        applyHighlight(idx);
        if (idx >= 0) {
            scrollToRow(idx);
        }
        if (player == null || !prepared) {
            pendingSeekMs = target;
            if (autoPlay) {
                playWhenPrepared = true;
            }
            return;
        }
        try {
            player.seekTo(target);
        } catch (Throwable t) {
            Log.w(TAG, "seek 失败", t);
        }
        if (durationMs > 0) {
            strip.setProgress(target);
        }
        if (autoPlay) {
            startPlayback();
        } else {
            startTicker();
        }
    }

    private boolean isActuallyPlaying() {
        if (player == null || !prepared) {
            return false;
        }
        try {
            return player.isPlaying();
        } catch (Throwable t) {
            return false;
        }
    }

    private int currentPos() {
        if (player != null && prepared) {
            try {
                return player.getCurrentPosition();
            } catch (Throwable t) {
                Log.w(TAG, "取播放位置失败", t);
            }
        }
        return 0;
    }

    private void playPrev() {
        int pos = currentPos();
        int idx = indexAt(pos);
        int target = 0;
        if (idx >= 0 && segs.get(idx) != null) {
            // 已经播了 1.5 秒以上：先回本句开头；否则退到上一句
            if (pos - segs.get(idx).startMs > 1500 || idx == 0) {
                target = segs.get(idx).startMs;
            } else {
                target = segs.get(idx - 1).startMs;
            }
        }
        seekTo(target, true);
    }

    private void playNext() {
        int idx = indexAt(currentPos());
        int target;
        if (idx + 1 < segs.size() && segs.get(idx + 1) != null) {
            target = segs.get(idx + 1).startMs;
        } else {
            target = durationMs > 0 ? durationMs : currentPos();
        }
        seekTo(target, true);
    }

    // ------------------------------------------------------------------ 200ms 定时器

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            tickerRunning = false;
            if (isFinishing() || isDestroyed() || player == null) {
                return;
            }
            boolean playing = false;
            try {
                playing = player.isPlaying();
            } catch (Throwable t) {
                Log.w(TAG, "取播放状态失败", t);
            }
            if (!playing) {
                return; // 暂停就停表，回来再开
            }
            try {
                int pos = player.getCurrentPosition();
                if (!userSeeking) {
                    curTime.setText(Ui.clock(pos));
                    if (durationMs > 0) {
                        strip.setProgress(pos);
                    }
                }
                if (SystemClock.uptimeMillis() < suppressHighlightUntil) {
                    // 刚跳转，播放器位置还没跟上：这轮不动高亮
                    startTicker();
                    return;
                }
                int idx = indexAt(pos);
                if (idx != curIdx) {
                    curIdx = idx;
                    applyHighlight(idx);
                    // 用户刚摸过滚动区：3 秒内只换高亮，不抢滚动位置
                    if (SystemClock.uptimeMillis() >= suppressScrollUntil) {
                        scrollToRow(idx);
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "刷新播放进度失败", t);
            }
            startTicker();
        }
    };

    private void startTicker() {
        if (tickerRunning) {
            return;
        }
        tickerRunning = true;
        handler.postDelayed(tick, TICK_MS);
    }

    private void stopTicker() {
        handler.removeCallbacks(tick);
        tickerRunning = false;
    }

    // ------------------------------------------------------------------ 转写状态轮询

    /** 要不要每 1 秒看一次 Db：没正文，或者正在（重新）转写。 */
    private boolean needsPolling() {
        return rec != null && (segs.isEmpty() || rec.state == 1 || rec.state == 2 || rec.state == 5);
    }

    private void startPolling() {
        if (polling) {
            return;
        }
        polling = true;
        handler.postDelayed(poll, POLL_MS);
    }

    private void stopPolling() {
        handler.removeCallbacks(poll);
        polling = false;
    }

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            polling = false;
            if (isFinishing() || isDestroyed()) {
                return;
            }
            refreshFromDb();
            if (needsPolling()) {
                startPolling();
            }
        }
    };

    private void refreshFromDb() {
        Rec fresh = db.byId(recId);
        if (fresh == null) {
            // 记录在别处被删了：别让界面继续演一个不存在的东西
            stopPolling();
            Toast.makeText(ReaderActivity.this, "这条记录已被删除", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        int prevState = rec.state;
        rec = fresh;

        List<Seg> list = prune(db.segs(recId));
        if (!list.isEmpty()) {
            int sig = sigOf(list);
            if (segs.isEmpty() || sig != segsSig) {
                segs = list;
                segsSig = sig;
                renderRows();
                scroll.setVisibility(View.VISIBLE);
                stateBox.setVisibility(View.GONE);
                int idx = indexAt(currentPos());
                applyHighlight(idx);
                if (idx >= 0) {
                    scrollToRow(idx);
                }
                return;
            }
        }

        if (segs.isEmpty()) {
            renderState();
            return;
        }
        // 正文还在，但（重新）转写失败了：正文照旧，失败也要说出来
        if (rec.state == 4 && prevState != 4) {
            String err = rec.error == null ? "" : rec.error.trim();
            Toast.makeText(ReaderActivity.this,
                    err.isEmpty() ? "重新转写失败（服务端没给原因）" : "重新转写失败：" + err,
                    Toast.LENGTH_LONG).show();
        }
    }

    /** 去掉 null 分段：保证行下标和 segs 下标一一对应（indexAt/高亮都按这个前提走）。 */
    private static List<Seg> prune(List<Seg> in) {
        List<Seg> out = new ArrayList<Seg>();
        if (in != null) {
            for (int i = 0; i < in.size(); i++) {
                if (in.get(i) != null) {
                    out.add(in.get(i));
                }
            }
        }
        return out;
    }

    /** 分段指纹：句数 + 文本长度和，够用来判断「是不是换了一批结果」。 */
    private static int sigOf(List<Seg> list) {
        int sig = list.size();
        for (int i = 0; i < list.size(); i++) {
            Seg s = list.get(i);
            if (s != null && s.text != null) {
                sig = sig * 31 + s.text.length();
            }
        }
        return sig;
    }

    /** 空态/转写中/失败：正文区唯一的一段文案 + 一个动作按钮。 */
    private void renderState() {
        if (stateBox == null) {
            return;
        }
        stateBox.setVisibility(View.VISIBLE);
        scroll.setVisibility(View.GONE);
        stateAction.setVisibility(View.VISIBLE);
        stateDetail.setVisibility(View.VISIBLE);

        String err = rec.error == null ? "" : rec.error.trim();
        int st = rec.state;

        if (st == 4) {
            stateTitle.setText("转写失败");
            stateTitle.setTextColor(Ui.ERROR);
            stateDetail.setTextColor(Ui.ERROR);
            stateDetail.setText(err.isEmpty() ? "服务端没有给出原因，请重试" : err);
            stateAction.setText("重试");
            stateAction.setBackground(Ui.round(Ui.PRIMARY, 20, this));
        } else if (st == 1 || st == 2 || st == 5) {
            // 5 = 上传中：之前漏了它，上传阶段掉进最后的 else，
            // 显示成"还没有转写 + 开始转写按钮"，看着像压根没开始。
            int pct = rec.progress;
            if (pct <= 0) {
                int live = TranscribeService.liveProgress(recId);
                if (live >= 0) {
                    pct = live;
                }
            }
            pct = Math.max(0, Math.min(100, pct));
            stateTitle.setTextColor(Ui.ON_SURFACE);

            // 分阶段显示，按钮一律藏掉，不给"再点一次"的机会
            String phase;
            String detail;
            if (st == 5) {
                if (pct >= 99) {
                    // 字节已发完，但服务端还没回 202（它要落盘、排队）
                    phase = "等服务器接收…";
                    detail = "音频已发完，服务器正在接收并创建任务";
                } else {
                    phase = "上传中 " + pct + "%";
                    detail = "正在把音频传到服务器，传完自动开始转写";
                }
            } else if (st == 1) {
                phase = "排队中";
                detail = "服务器正在处理别的任务，轮到你就开始";
            } else if (pct >= 99) {
                phase = "收尾：取结果…";
                detail = "转写已完成，正在把文字取回来";
            } else {
                phase = "转写中 " + pct + "%";
                detail = "转写完成会自动出现在这里，可以先去忙别的";
            }
            stateTitle.setText(phase);
            stateDetail.setTextColor(Ui.ON_SURFACE_VARIANT);
            stateDetail.setText(detail);
            stateAction.setVisibility(View.GONE);
        } else if (st == 3) {
            stateTitle.setTextColor(Ui.ON_SURFACE);
            stateTitle.setText("转写完成，但没有分段");
            stateDetail.setTextColor(Ui.ON_SURFACE_VARIANT);
            stateDetail.setText("服务端返回了空结果，可以重新转写一次");
            stateAction.setText("重新转写");
            stateAction.setBackground(Ui.round(Ui.PRIMARY, 20, this));
        } else {
            stateTitle.setTextColor(Ui.ON_SURFACE);
            stateTitle.setText("还没有转写");
            stateDetail.setTextColor(Ui.ON_SURFACE_VARIANT);
            stateDetail.setText("开始转写后，正文会按句出现在这里");
            stateAction.setText("开始转写");
            stateAction.setBackground(Ui.round(Ui.PRIMARY, 20, this));
        }
    }

    private void startTranscribe() {
        if (rec.path == null || rec.path.trim().isEmpty()) {
            Toast.makeText(this, "这条记录没有音频路径，没法转写", Toast.LENGTH_LONG).show();
            return;
        }
        TranscribeService.start(this, recId);
        // 立刻把本地状态推到“排队”，不等第一次轮询，界面有即时反馈
        rec.state = 1;
        rec.progress = 0;
        rec.error = null;
        if (segs.isEmpty()) {
            // 有正文时别把正文换成“转写中”，进度只在空态区出现
            renderState();
        }
        startPolling();
        Toast.makeText(this, segs.isEmpty() ? "已开始转写" : "已开始重新转写，完成后正文会更新",
                Toast.LENGTH_SHORT).show();
    }

    // ------------------------------------------------------------------ 顶栏菜单

    private void showMenu(View anchor) {
        PopupMenu pm = new PopupMenu(this, anchor);
        pm.getMenu().add(0, MENU_EXPORT, 0, "导出 SRT");
        pm.getMenu().add(0, MENU_TRANS, 1, "转写");
        pm.getMenu().add(0, MENU_DELETE, 2, "删除记录");
        pm.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(android.view.MenuItem item) {
                switch (item.getItemId()) {
                    case MENU_EXPORT:
                        exportSrt();
                        return true;
                    case MENU_TRANS:
                        startTranscribe();
                        return true;
                    case MENU_DELETE:
                        confirmDelete();
                        return true;
                    default:
                        return false;
                }
            }
        });
        pm.show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setTitle("删除这条记录？")
                .setMessage("转写正文会一起删掉，音频文件本身不动。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        doDelete();
                    }
                })
                .show();
    }

    private void doDelete() {
        // 正在转就先停，免得服务回头往一条已删记录上写状态
        if (rec.state == 1 || rec.state == 2 || rec.state == 5
                || TranscribeService.liveProgress(recId) >= 0) {
            TranscribeService.cancel(this, recId);
        }
        stopPolling();
        stopTicker();
        releasePlayer();
        try {
            db.delete(recId);
        } catch (Throwable t) {
            Toast.makeText(this, "删除失败：" + reason(t), Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "已删除记录", Toast.LENGTH_SHORT).show();
        finish();
    }

    // ------------------------------------------------------------------ 导出 SRT

    private void exportSrt() {
        if (segs == null || segs.isEmpty()) {
            Toast.makeText(this, "还没有转写正文，先转写再导出", Toast.LENGTH_LONG).show();
            return;
        }
        final String body = Srt.build(segs);
        if (body.isEmpty()) {
            Toast.makeText(this, "分段里没有可导出的文字", Toast.LENGTH_LONG).show();
            return;
        }
        final String name = srtName();
        // MediaStore 写盘放后台线程，主线程只负责 Toast
        new Thread(new Runnable() {
            @Override
            public void run() {
                String shown = null;
                String fail = null;
                Uri uri = null;
                try {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "application/x-subrip");
                    // 契约要求的落点：Download/Transcribe/<文件名>.srt
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH,
                            Environment.DIRECTORY_DOWNLOADS + "/Transcribe");
                    uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    if (uri == null) {
                        throw new IOException("系统拒绝创建文件（MediaStore 返回空）");
                    }
                    OutputStream os = getContentResolver().openOutputStream(uri);
                    if (os == null) {
                        throw new IOException("打不开写入流");
                    }
                    try {
                        os.write(body.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    } finally {
                        os.close();
                    }
                    String real = queryDisplayName(uri, name);
                    shown = Environment.DIRECTORY_DOWNLOADS + "/Transcribe/" + real;
                } catch (Throwable t) {
                    fail = reason(t);
                    // 写坏了就把半截条目删掉，别在 Download 里留个 0 字节的壳
                    if (uri != null) {
                        try {
                            getContentResolver().delete(uri, null, null);
                        } catch (Throwable t2) {
                            Log.w(TAG, "清理不完整的 srt 失败", t2);
                        }
                    }
                }
                final String okPath = shown;
                final String err = fail;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (err != null) {
                            Toast.makeText(ReaderActivity.this, "导出失败：" + err,
                                    Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(ReaderActivity.this, "已导出：" + okPath,
                                    Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }, "recstt-export").start();
    }

    /** 同名文件已存在时 MediaStore 会自动改名，这里读回真正的名字，Toast 才不撒谎。 */
    private String queryDisplayName(Uri uri, String fallback) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DISPLAY_NAME}, null, null, null);
            if (c != null && c.moveToFirst()) {
                String s = c.getString(0);
                if (s != null && !s.trim().isEmpty()) {
                    return s.trim();
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "读回导出文件名失败", t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return fallback;
    }

    private String srtName() {
        String base = clean(rec.displayName);
        if (base.isEmpty()) {
            base = clean(rec.phone);
        }
        if (base.isEmpty()) {
            base = clean(rec.fileName);
        }
        if (base.isEmpty()) {
            base = "转写";
        }
        int dot = base.lastIndexOf('.');
        if (dot > 0 && base.length() - dot <= 6) {
            base = base.substring(0, dot); // 只砍 .m4a/.mp3 这种短扩展名
        }
        base = sanitizeFileName(base);
        if (base.length() > 80) {
            base = base.substring(0, 80);
        }
        return base + ".srt";
    }

    private static String clean(String s) {
        return s == null ? "" : s.trim();
    }

    /** 文件名里不能出现的字符换成下划线；顺带压掉空名。 */
    private static String sanitizeFileName(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?'
                    || c == '"' || c == '<' || c == '>' || c == '|'
                    || c == '\n' || c == '\r' || c == '\t') {
                sb.append('_');
            } else {
                sb.append(c);
            }
        }
        String out = sb.toString().trim();
        return out.isEmpty() ? "转写" : out;
    }

    // ------------------------------------------------------------------ 小工具

    private String headerName() {
        String name = clean(rec.displayName);
        if (name.isEmpty()) {
            name = clean(rec.phone);
        }
        if (name.isEmpty()) {
            name = clean(rec.fileName);
        }
        return name.isEmpty() ? "录音" : name;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private int statusBarPx() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private static String reason(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        if (m != null && !m.trim().isEmpty()) {
            return m.trim();
        }
        return t.getClass().getSimpleName();
    }

    /**
     * 当前句的高亮底：14% 主色圆角 + 左侧 3dp 主色竖条。
     *
     * 竖条曾经是一个高 MATCH_PARENT 的子 View。行容器是 WRAP_CONTENT，
     * 但在 ScrollView 里拿到的父级高度规格是 AT_MOST，MATCH_PARENT 子 View
     * 会先把"可分配高度"吃满（实测 1274px），行容器再 wrap 上去，
     * 一行就从 97px 变成 1322px，其余行被挤成 0 —— 看上去像后面几句消失了。
     * 改成画在背景里：行高只由文字决定，与父级规格无关。
     */
    private static final class RowBg extends Drawable {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final float stripeW;
        private final float vInset;

        RowBg(Context c) {
            radius = Ui.dp(c, 8);
            stripeW = Ui.dp(c, 3);
            vInset = Ui.dp(c, 8);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            p.setColor(0x24D0BCFF);
            canvas.drawRoundRect(b.left, b.top, b.right, b.bottom, radius, radius, p);
            p.setColor(Ui.PRIMARY);
            float half = stripeW / 2f;
            canvas.drawRoundRect(b.left, b.top + vInset, b.left + stripeW, b.bottom - vInset,
                    half, half, p);
        }

        @Override
        public void setAlpha(int alpha) { }

        @Override
        public void setColorFilter(ColorFilter cf) { }

        @Override
        public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
