package com.zglinus.recstt.ui;

import android.app.Activity;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.svc.TranscribeService;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 记录列表 + 筛选 + 统计 + 空态 + 转写进度轮询。从 MainActivity 抽出的功能模块。
 *
 * <p>界线：只跟「列表里有哪些记录、怎么筛、怎么显示」有关；
 * 扫描、菜单、连通性都不在这里。
 */
public class RecList {

    /** 状态筛选：-1 全部 / 0 未转写 / 3 已转写。 */
    public static final int FILTER_ALL = -1;
    public static final int FILTER_NONE = 0;
    public static final int FILTER_DONE = 3;

    private static final int LIMIT = 500;

    private final Activity act;
    private final Db db;
    private final Handler handler;

    private final LinearLayout chipsRow;
    private final TextView stats;
    private final ListView list;
    private final RecAdapter adapter;
    private final LinearLayout emptyBox;
    private final TextView emptyTitle;
    private final TextView emptyHint;

    private final List<Rec> data = new ArrayList<Rec>();
    private int filter = FILTER_ALL;
    private boolean ticking;
    /** 扫描状态缓存：stats 行要区分「扫描中」和「列表统计」两种显示。 */
    private boolean scanning;
    private int scanFound;
    private String scanPath = "";

    /** 空态文案要问一句「有没有所有文件权限」。 */
    public interface Perm {
        boolean hasAllFiles();
    }

    private Perm perm;

    public RecList(Activity act, Db db, Handler handler,
                   LinearLayout chipsRow, TextView stats, ListView list, RecAdapter adapter,
                   LinearLayout emptyBox, TextView emptyTitle, TextView emptyHint) {
        this.act = act;
        this.db = db;
        this.handler = handler;
        this.chipsRow = chipsRow;
        this.stats = stats;
        this.list = list;
        this.adapter = adapter;
        this.emptyBox = emptyBox;
        this.emptyTitle = emptyTitle;
        this.emptyHint = emptyHint;
    }

    public void setPerm(Perm p) {
        this.perm = p;
    }

    public List<Rec> data() {
        return data;
    }

    public int filter() {
        return filter;
    }

    /** 搜索框内容（由调用方提供，避免这里依赖 EditText）。 */
    public interface QuerySource {
        String query();
    }

    private QuerySource querySource;

    public void setQuerySource(QuerySource q) {
        this.querySource = q;
    }

    private String query() {
        return querySource == null ? "" : querySource.query();
    }

    // ------------------------------------------------------------------ 筛选 chip

    public void buildChips(int px32, int px8) {
        chipsRow.removeAllViews();
        final String[] labels = {"全部", "未转写", "已转写"};
        final int[] filters = {FILTER_ALL, FILTER_NONE, FILTER_DONE};
        for (int i = 0; i < labels.length; i++) {
            final int f = filters[i];
            TextView chip = Ui.chip(act, labels[i], f == filter);
            chip.setGravity(android.view.Gravity.CENTER);
            LinearLayout.LayoutParams lp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px32);
            lp.rightMargin = px8;
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (filter == f) {
                        return;
                    }
                    filter = f;
                    buildChips(px32, px8);
                    reload();
                }
            });
            chipsRow.addView(chip);
        }
    }

    // ------------------------------------------------------------------ 数据

    public void reload() {
        if (db == null) {
            return;
        }
        List<Rec> fresh = db.list(query(), filter, LIMIT);
        data.clear();
        if (fresh != null) {
            data.addAll(fresh);
        }
        adapter.setData(data);
        updateStats(scanning, scanFound, scanPath);
        updateEmpty();
        ensureTick();
    }

    public void updateStats(boolean scanning, int scanFound, String scanPath) {
        this.scanning = scanning;
        this.scanFound = scanFound;
        this.scanPath = scanPath == null ? "" : scanPath;
        if (stats == null) {
            return;
        }
        if (scanning) {
            String p = scanPath == null ? "" : scanPath;
            stats.setText("扫描中 · 已找到 " + scanFound + " 个录音"
                    + (p.isEmpty() ? "" : " · " + p));
            return;
        }
        long totalMs = 0;
        int noName = 0;
        for (int i = 0; i < data.size(); i++) {
            Rec r = data.get(i);
            if (r.durMs > 0) {
                totalMs += r.durMs;
            }
            if (!RecAdapter.hasName(r)) {
                noName++;
            }
        }
        stats.setText("本机 " + data.size() + " 条 · 共 " + hoursText(totalMs) + " 小时 · "
                + noName + " 条无姓名");
    }

    private static String hoursText(long totalMs) {
        double h = totalMs / 3600000.0;
        return String.format(Locale.US, h >= 10 ? "%.0f" : "%.1f", h);
    }

    public void updateEmpty() {
        if (emptyBox == null) {
            return;
        }
        boolean empty = data.isEmpty();
        emptyBox.setVisibility(empty ? View.VISIBLE : View.GONE);
        if (!empty) {
            return;
        }
        if (perm != null && !perm.hasAllFiles()) {
            emptyTitle.setText("需要所有文件访问权限");
            emptyHint.setText("去设置 →");
            return;
        }
        boolean filtering = filter != FILTER_ALL || !query().isEmpty();
        if (!filtering) {
            emptyTitle.setText("还没扫描");
            emptyHint.setText("点右下角扫描本机");
        } else if (db != null && db.list(null, FILTER_ALL, 1).isEmpty()) {
            // 库里本来就没有东西，筛选只是空上加空
            emptyTitle.setText("还没扫描");
            emptyHint.setText("点右下角扫描本机");
        } else {
            emptyTitle.setText("没有匹配的录音");
            emptyHint.setText("换个关键词，或切回「全部」");
        }
    }

    // ------------------------------------------------------------------ 转写进度轮询

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            ticking = false;
            boolean changed = false;
            for (int i = 0; i < data.size(); i++) {
                Rec r = data.get(i);
                if (!isRunning(r.state)) {
                    continue;
                }
                // 先读库（state 的真相），再拿服务内存进度覆盖百分比（库慢，别让它倒着走）
                Rec fresh = db.byId(r.id);
                if (fresh != null) {
                    if (fresh.state != r.state) {
                        r.state = fresh.state;
                        changed = true;
                    }
                    if (isRunning(fresh.state) && fresh.progress != r.progress) {
                        r.progress = fresh.progress;
                        changed = true;
                    }
                    r.error = fresh.error;
                }
                int live = TranscribeService.liveProgress(r.id);
                if (live >= 0 && isRunning(r.state) && live != r.progress) {
                    r.progress = live;
                    changed = true;
                }
            }
            if (changed) {
                adapter.notifyDataSetChanged();
                updateStats(false, 0, "");
                updateEmpty();
            }
            ensureTick();
        }
    };

    /** 三个「还在跑」的状态：排队 1 / 转写中 2 / 上传中 5。 */
    public static boolean isRunning(int state) {
        return state == 1 || state == 2 || state == 5;
    }

    public void ensureTick() {
        if (ticking || act.isFinishing()) {
            return;
        }
        for (int i = 0; i < data.size(); i++) {
            if (isRunning(data.get(i).state)) {
                ticking = true;
                handler.postDelayed(tick, 700L);
                return;
            }
        }
    }

    public void stopTick() {
        handler.removeCallbacks(tick);
        ticking = false;
    }
}
