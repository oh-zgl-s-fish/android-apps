package com.zglinus.recstt.ui;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.R;
import com.zglinus.recstt.model.Rec;
import com.zglinus.recstt.model.Tpl;
import com.zglinus.recstt.net.Endpoints;
import com.zglinus.recstt.scan.Scanner;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.svc.TranscribeService;
import com.zglinus.recstt.wg.Tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 主列表（设计稿第 09 屏）。
 *
 * <p>层级：root(FrameLayout) → Bg 背景（Image + 遮罩，最底）→ 内容层 → FAB。
 * 内容层是竖向 LinearLayout：顶栏 64dp / 连通性条 / 搜索框 / 状态 chip / 统计行 / 列表。
 *
 * <p>连通性条：局域网与备用线路各一行，探测走 {@link Endpoints#probe}，必须离开主线程；
 * 失败要带原因（Probe.error），不许只写「失败」。
 *
 * <p>数据只走 Db；转写进度既看 Db 里的 state，也看 TranscribeService.liveProgress
 * （服务每 1.5s 才写一次库，纯读库会让百分比看起来很卡）。
 */
public class MainActivity extends Activity implements RecAdapter.Listener {

    private static final int REQ_STORAGE = 41;

    /** 主界面这层探测超过 10 秒就不算新了，onResume 时补一次（别每次回来都打服务器）。 */

    private static final int MENU_TPL = 1;
    private static final int MENU_APPEAR = 2;
    private static final int MENU_SETTINGS = 3;
    private static final int MENU_DIAG = 4;

    private static final int MENU_TRANSCRIBE = 11;
    private static final int MENU_DELETE = 12;

    /** 列表一次最多拉多少条；本机录音几千条时别把主线程卡死。 */
    private static final int LIST_LIMIT = 500;

    private FrameLayout root;
    private LinearLayout content;

    private TextView stats;
    private EditText search;
    private LinearLayout searchBox;
    private LinearLayout chipsRow;

    private ListView list;
    private RecAdapter adapter;
    private LinearLayout emptyBox;
    private TextView emptyTitle;
    private TextView emptyHint;

    private LinearLayout fab;
    private TextView fabLabel;

    // ---- 连通性条（标题栏与搜索框之间）----
    /** 第二行右端「连 WG」：只有「有 wg.conf 且隧道没在跑」时才出现。 */
    /** 第二行下方那行红字：连接失败的原因，6 秒后自己消失。 */

    /** 探测在子线程跑，同一时刻只允许一个。 */
    /** 连隧道要等最多 10 秒：这期间第二行显示「正在连隧道…」，按钮也先藏起来。 */
    /** Tunnel.* 全是阻塞调用（running() 还要连 25344），状态只在后台读，主线程只读缓存。 */
    /** 连上后如果正有一次探测在跑，等它结束再补一次，保证那次一定是经隧道的。 */
    /** 上次探测时用的地址：地址被改过后旧结果不能再挂在新的 host 上。 */

    private Db db;
    private List<Rec> data = new ArrayList<Rec>();
    /** 连通性条：独立功能模块。 */
    private ConnBar conn;
    /** 列表 + 筛选 + 统计 + 转写进度轮询。 */
    private RecList recs;
    /** 扫描本机 + 存储权限。 */
    private ScanFlow scan;
    /** 记录上的动作：点开 / 长按菜单。 */
    private RecActions actions;
    /** 顶栏交互：搜索框 / 更多菜单。 */
    private MainMenu menu;
    /** -1=全部 0=未转写 3=已转写。 */
    private int stateFilter = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean ticking;

    private volatile boolean scanning;
    private volatile int scanFound;
    private volatile String scanPath = "";

    // ------------------------------------------------------------------ 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this); // 先定主题/调色板，再 super.onCreate
        super.onCreate(savedInstanceState);

        // db 必须先于 buildContent：模块要在建界面时就拿到它，
        // 否则 reload() 里的空值保护会把整段静默跳过（列表和统计都空着）。
        db = Db.get(this);

        root = new FrameLayout(this);
        // 背景是异步铺的（要等壁纸 layout 才有真实几何），
        // 所以文字换色不能只在下面同步跑一次，得等它就绪再来一遍。
        Bg.setOnReady(new Runnable() {
            @Override public void run() {
                Ui.autoTintBgText(root);
            }
        });
        // 自定义背景（图 + 模糊 + 遮罩）必须最先挂上，后面的内容才在它上面
        Bg.attach(this, root);

        buildContent();

        setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // 界面搭完后统一扫一遍：压在背景上、还没换色的文字改成浅色
        Ui.autoTintBgText(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 外观页改完背景回来要整体重建：
        // 只在原地 detach/attach 是不够的 —— 卡片图标/字色/chip 的底色全是建界面时
        // 算好的，背景一换就全过期（"毛玻璃切换了但没重绘"就是这个）。
        // recreate 会重跑 Ui.init（主题+调色板）和 Bg.attach（重铺背景、重算毛玻璃）。
        if (Bg.configChanged(this)) {
            recreate();
            return;
        }
        Ui.autoTintBgText(root); // 背景换了，文字颜色跟着重算
        // 进程被杀过时，state=1/2 的活任务靠这一步重新挂上轮询（TranscribeService 的约定）
        TranscribeService.resumePending(this);
        // 从阅读页/设置页回来要看到最新状态（转写完成、背景换了都靠这一下）
        if (recs != null) {
            recs.reload();
        }
        // 连通性模块自己处理：地址变了作废旧结果、重读隧道状态、必要时补一次静默探测
        if (conn != null) {
            conn.onResumeSync();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 进度轮询只在看得见的时候跑
        if (recs != null) {
            recs.stopTick();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 搭界面

    private void buildContent() {
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.TRANSPARENT);
        // 状态栏：统一走 WindowInsets。硬编码 status_bar_height 在 Android 15+ 会上双重计算
        // （系统强制 edge-to-edge + 我们再加一次），真机上就是标题下面那条多余空白。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            getWindow().setDecorFitsSystemWindows(false);
            Ui.applySystemBarsInsets(this, content);
        } else {
            content.setPadding(0, statusBarHeight(), 0, 0);
        }
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶栏 64dp ----
        LinearLayout bar = Ui.row(this);
        bar.setPadding(px(16), 0, px(8), 0);
        content.addView(bar, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, px(64)));

        TextView title = Ui.tv(this, "录音转写", 22, Ui.onBg(), true);
        bar.addView(title, Ui.lpWeight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        bar.addView(Ui.iconButton(this, R.drawable.ic_search, Ui.onBgVariant(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        menu.toggleSearch();
                    }
                }));
        bar.addView(Ui.iconButton(this, R.drawable.ic_more, Ui.onBgVariant(),
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        menu.showMore(v);
                    }
                }));

        // ---- 连通性条（标题栏下面这块原来空着）----
        conn = new ConnBar(this, handler);
        LinearLayout connWrap = new LinearLayout(this);
        connWrap.setOrientation(LinearLayout.VERTICAL);
        conn.attach(connWrap);
        LinearLayout.LayoutParams connLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        connLp.leftMargin = px(16);
        connLp.rightMargin = px(16);
        connLp.bottomMargin = px(10);
        content.addView(connWrap, connLp);

        // ---- 搜索框（默认收起）----
        searchBox = Ui.row(this);
        searchBox.setPadding(px(16), 0, px(16), px(10));
        searchBox.setVisibility(View.GONE);

        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14);
        search.setTextColor(Ui.onBg());
        search.setHint("搜姓名 / 尾号 / 日期");
        search.setHintTextColor(Ui.onBgVariant());
        search.setGravity(Gravity.CENTER_VERTICAL);
        search.setBackground(Ui.round(Ui.SURFACE_HIGH, 24, this));
        search.setPadding(px(16), 0, px(16), 0);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setLayoutParams(Ui.lpWeight(0, px(46), 1f));
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                if (recs != null) {
                    recs.reload();
                }
            }
        });
        searchBox.addView(search);
        content.addView(searchBox, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 状态 chip 行（高 32dp、间距 8dp）----
        chipsRow = Ui.row(this);
        chipsRow.setPadding(px(16), 0, px(16), px(10));
        content.addView(chipsRow, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 统计行（12sp、左 20）----
        stats = Ui.tv(this, "", 12, Ui.onBgVariant(), false);
        stats.setPadding(px(20), 0, px(20), px(10));
        content.addView(stats, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 列表 + 空态 ----
        FrameLayout listArea = new FrameLayout(this);
        content.addView(listArea, Ui.lpWeight(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        list = new ListView(this);
        // 行间 6dp：透明分隔条比给每行加 margin 便宜，也不会破坏点击
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(px(6));
        list.setClipToPadding(false);
        list.setPadding(px(10), 0, px(10), px(96)); // 别让最后一行被 FAB 压住
        list.setVerticalScrollBarEnabled(false);
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        listArea.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        adapter = new RecAdapter(this, data, this);
        list.setAdapter(adapter);

        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        // 空态贴着统计行往下 24dp；垂直居中会在上面留一大片空白（用户就是嫌这个）
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyBox.setPadding(px(32), 0, px(32), px(24));
        emptyBox.setVisibility(View.GONE);
        emptyBox.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scan != null && !scan.hasAllFiles()) {
                    scan.openAllFilesSettings();
                } else {
                    Toast.makeText(MainActivity.this, "点右下角的「扫描本机」开始扫描",
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        emptyTitle = Ui.tv(this, "还没扫描", 16, Ui.onBg(), true);
        emptyTitle.setGravity(Gravity.CENTER);
        emptyBox.addView(emptyTitle);

        emptyHint = Ui.tv(this, "点右下角扫描本机", 13, Ui.onBgVariant(), false);
        emptyHint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hintLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = px(6);
        emptyBox.addView(emptyHint, hintLp);

        FrameLayout.LayoutParams emptyLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP);
        emptyLp.topMargin = px(24);
        listArea.addView(emptyBox, emptyLp);

        // ---- 右下 FAB：扫描本机（ic_scan_local，不是条码扫描框）----
        fab = Ui.row(this);
        fab.setGravity(Gravity.CENTER);
        fab.setBackground(Ui.round(Ui.PRIMARY_CONTAINER, 16, this));
        fab.setPadding(px(18), 0, px(18), 0);
        fab.setMinimumHeight(px(56));
        fab.setClickable(true);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (scan != null) {
                    scan.onFabClick();
                }
            }
        });

        ImageView fabIcon = Ui.icon(this, R.drawable.ic_scan_local, Ui.ON_PRIMARY_CONTAINER, 20);
        fab.addView(fabIcon);

        fabLabel = Ui.tv(this, "扫描本机", 14, Ui.ON_PRIMARY_CONTAINER, true);
        LinearLayout.LayoutParams labelLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelLp.setMarginStart(px(8));
        fab.addView(fabLabel, labelLp);

        FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, px(56), Gravity.BOTTOM | Gravity.END);
        fabLp.rightMargin = px(20);
        fabLp.bottomMargin = px(26);
        root.addView(fab, fabLp);

        initModules();
    }

    /** 界面组件都建好了，再把四个功能模块接上（模块只拿自己需要的东西）。 */
    private void initModules() {
        // 列表：筛选 / 统计 / 空态 / 转写进度
        recs = new RecList(this, db, handler, chipsRow, stats, list, adapter,
                emptyBox, emptyTitle, emptyHint);
        recs.setQuerySource(new RecList.QuerySource() {
            @Override
            public String query() {
                return search == null ? "" : search.getText().toString().trim();
            }
        });
        recs.setPerm(new RecList.Perm() {
            @Override
            public boolean hasAllFiles() {
                return scan != null && scan.hasAllFiles();
            }
        });

        // 扫描：右下角按钮 + 存储权限
        scan = new ScanFlow(this, db, fabLabel, new ScanFlow.Ui() {
            @Override
            public void onScanProgress(boolean scanning, int found, String path) {
                recs.updateStats(scanning, found, path);
            }

            @Override
            public void onScanDone(int count, String error) {
                recs.updateStats(false, 0, "");
            }

            @Override
            public void toast(String msg) {
                Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show();
            }

            @Override
            public void reload() {
                recs.reload();
            }
        }, new ScanFlow.PermissionRequester() {
            @Override
            public void requestPermissions(String[] perms, int code) {
                MainActivity.this.requestPermissions(perms, code);
            }
        });

        // 记录动作：点开阅读页 / 长按菜单
        actions = new RecActions(this, db, handler, new RecActions.Ui() {
            @Override
            public void reload() {
                recs.reload();
            }
        });

        // 顶栏：搜索框 / 更多菜单（必须在 searchBox 建好之后）
        menu = new MainMenu(this, searchBox, search, new MainMenu.Ui() {
            @Override
            public void reload() {
                recs.reload();
            }
        });

        recs.buildChips(px(32), px(8));
        recs.reload();
    }

    // ---- RecAdapter.Listener：转给 RecActions，Activity 自己不干具体的活 ----

    @Override
    public void onRecClick(Rec rec, View row) {
        if (actions != null) {
            actions.onClick(rec);
        }
    }

    @Override
    public void onRecLongClick(Rec rec, View row) {
        if (actions != null) {
            actions.onLongClick(rec, row);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (scan != null) {
            scan.onPermissionResult(requestCode, permissions, grants);
        }
        if (recs != null) {
            recs.updateEmpty();
        }
    }

    // ------------------------------------------------------------------ 连通性条

    /**
     * 标题栏下面那条卡片：第一行局域网、第二行备用线路，右侧「自检」，整块可点。
     *
     * <p>高度按内容走，最小 72dp —— 两条都不通时会多一行提示，硬定 72dp 会截字。
     */
    // ------------------------------------------------------------------ 转写中每秒刷新

    // ------------------------------------------------------------------ 交互

    // ------------------------------------------------------------------ 扫描 / 权限

    // ------------------------------------------------------------------ 小工具

    private int px(float dp) {
        return Ui.dp(this, dp);
    }

    private static String fileNameOf(String path) {
        if (path == null) {
            return "";
        }
        int i = path.lastIndexOf('/');
        return i >= 0 && i + 1 < path.length() ? path.substring(i + 1) : path;
    }




    /** 状态栏高度：主题把 statusBarColor 设成透明，内容得自己让开。 */
    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (id > 0) {
            try {
                return getResources().getDimensionPixelSize(id);
            } catch (Exception ignore) {
                // 有的 ROM 没这个 dimen，走下面的兜底
            }
        }
        return px(24);
    }
}
