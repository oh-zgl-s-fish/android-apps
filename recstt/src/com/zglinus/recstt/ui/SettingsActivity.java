package com.zglinus.recstt.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.net.SttClient;
import com.zglinus.recstt.scan.Scanner;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.wg.Tunnel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 设计稿第 04 屏：设置（服务器 / 转写 / 权限 / 保存）。
 *
 * <p>kv 键严格按 INTERFACES.md：server_url、server_url_wg、token、roots、lang。
 * 改动先进输入框，点「保存」才落库；脏着按返回会弹确认，不会静默丢改动。
 */
public final class SettingsActivity extends Activity {

    private static final int REQ_NOTIFICATION = 7201;
    private static final int REQ_STORAGE = 7202;
    private static final int REQ_WG_CONF = 7203;

    /** wg.conf 是几段键值，正常情况下不到 2KB；64KB 只是防呆上限。 */
    private static final int WG_CONF_MAX = 64 * 1024;

    private Db db;

    private EditText etLan;
    private EditText etWg;
    private EditText etToken;
    private EditText etRoots;

    private TextView checkBtn;
    private TextView lanLine;
    private TextView altLine;

    private TextView allFilesState;
    private TextView allFilesBtn;
    private TextView notifState;
    private TextView notifBtn;

    // ---- 内置 WireGuard（不申请 VPN）----
    private TextView wgState;
    private TextView wgError;
    private TextView wgImportBtn;
    private TextView wgConnectBtn;
    private TextView wgDisconnectBtn;
    private TextView wgDeleteBtn;

    /** Tunnel.* 全是阻塞调用（start 最长 10 秒），状态只在后台读，界面只读这几个缓存。 */
    private volatile boolean wgHasConf;
    private volatile boolean wgRunning;
    private long wgConfBytes;
    private String wgStatusText = "";
    private String wgLastError = "";
    private boolean wgBusy;
    private boolean wgConnecting;
    private boolean wgRefreshing;

    private boolean dirty = false;
    private boolean checking = false;

    // ---------------------------------------------------------------- 生命周期

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Ui.init(this); // 先定主题/调色板，再 super.onCreate
        super.onCreate(savedInstanceState);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        db = Db.get(this);

        FrameLayout root = new FrameLayout(this);
        // 不能刷不透明底色：会把 Bg.attach 铺的背景图整个盖住（真机"背景没有效果"）
        root.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        Bg.setOnReady(new Runnable() {
            @Override public void run() {
                Ui.autoTintBgText(root);
            }
        });
        Bg.attach(this, root); // 和主界面保持同一套背景

        LinearLayout page = Ui.col(this);
        page.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(page);

        page.addView(topBar(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        page.addView(sv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout content = Ui.col(this);
        content.setPadding(dp(16), dp(6), dp(16), dp(28));
        sv.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        buildWgCard(content);
        buildServerCard(content);
        buildTranscribeCard(content);
        buildPermCard(content);
        buildSaveButton(content);

        setContentView(root);
        // 界面搭完后统一扫一遍：把压在背景上、还没换色的文字改成浅色
        Ui.autoTintBgText(root);
        refreshPerms();
        refreshWgStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 背景/毛玻璃配置变了：整套颜色和底板都已过期，整体重建最省事也最不会漏。
        if (Bg.configChanged(this)) {
            recreate();
            return;
        }
        if (db != null) {
            refreshPerms();
            // 从文件选择器/后台回来，隧道状态和 wg.conf 大小都可能变了
            refreshWgStatus();
        }
    }

    // ---------------------------------------------------------------- 顶栏

    private View topBar() {
        LinearLayout bar = Ui.row(this);
        // 顶栏不铺任何底：让它完全透出背景图，
        // 标题颜色由 Ui.onBg() 按背景明暗决定（深底配浅字、浅底配深字）。
        bar.setBackgroundColor(0x00000000);
        // 顶部让开状态栏由 applySystemBarsInsets 统一处理，这里只留内容高度
        bar.setMinimumHeight(dp(56));
        bar.setPadding(dp(6), 0, dp(12), 0);

        int res = drawableId("ic_back");
        if (res != 0) {
            View back = Ui.iconButton(this, res, Ui.onBg(), new View.OnClickListener() {
                @Override public void onClick(View v) { onBackPressed(); }
            });
            back.setContentDescription("返回");
            bar.addView(back);
        } else {
            TextView back = Ui.tv(this, "←", 22, Ui.onBg(), false);
            back.setGravity(Gravity.CENTER);
            back.setContentDescription("返回");
            back.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
            back.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { onBackPressed(); }
            });
            bar.addView(back);
        }

        TextView title = Ui.tv(this, "设置", 20, Ui.onBg(), true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(4);
        bar.addView(title, tlp);
        return bar;
    }

    // ---------------------------------------------------------------- 内置 WireGuard

    /**
     * 「内置 WireGuard（不申请 VPN）」：wireproxy 子进程 + 本机 HTTP 代理，不建 tun、不要授权。
     * 状态行、导入 .conf、连接/断开、删除配置、说明。
     */
    private void buildWgCard(LinearLayout content) {
        sectionTitle(content, "内置 WireGuard（不申请 VPN）");
        LinearLayout card = card(content);

        wgState = Ui.tv(this, "读取隧道状态…", 13, Ui.ON_SURFACE_VARIANT, false);
        wgState.setLineSpacing(0f, 1.15f);
        card.addView(wgState, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        wgError = Ui.tv(this, "", 12, Ui.ERROR, false);
        wgError.setVisibility(View.GONE);
        wgError.setClickable(true);
        wgError.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) { return copyWgError(); }
        });
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.topMargin = dp(6);
        card.addView(wgError, elp);

        wgImportBtn = wgButton("导入 .conf");
        wgImportBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { pickWgConf(); }
        });
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ilp.topMargin = dp(12);
        card.addView(wgImportBtn, ilp);

        LinearLayout btnRow = Ui.row(this);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dp(8);
        card.addView(btnRow, rowLp);

        wgConnectBtn = wgButton("连接");
        wgConnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { connectWg(); }
        });
        btnRow.addView(wgConnectBtn, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wgDisconnectBtn = wgButton("断开");
        wgDisconnectBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { disconnectWg(); }
        });
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        dlp.leftMargin = dp(8);
        btnRow.addView(wgDisconnectBtn, dlp);

        wgDeleteBtn = wgButton("删除配置");
        wgDeleteBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { confirmClearWg(); }
        });
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        delLp.topMargin = dp(8);
        card.addView(wgDeleteBtn, delLp);

        TextView note = Ui.tv(this,
                "隧道只在 App 内用，不接管系统流量，所以不需要 VPN 授权；App 退出隧道会断",
                11, Ui.OUTLINE, false);
        note.setLineSpacing(0f, 1.2f);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(10);
        card.addView(note, nlp);

        TextView copyHint = Ui.tv(this, "连接失败的原因显示在上面，长按红字可复制。",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chlp.topMargin = dp(4);
        card.addView(copyHint, chlp);

        applyWgState();
    }

    private TextView wgButton(String text) {
        TextView t = Ui.tv(this, text, 14, Ui.PRIMARY, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        return t;
    }

    /** 只画状态，不碰 Tunnel：所有判决都用后台读回来的缓存。 */
    private void applyWgState() {
        if (wgState == null) {
            return;
        }
        String status = (wgStatusText == null || wgStatusText.isEmpty())
                ? (wgRunning ? "隧道已连接" : "隧道未连接") : wgStatusText;
        String conf = wgHasConf
                ? "wg.conf · " + wgConfBytes + " 字节"
                : "wg.conf 未导入";
        wgState.setText(status + " · " + conf);
        wgState.setTextColor(wgRunning ? Ui.SUCCESS : Ui.ON_SURFACE_VARIANT);

        boolean idle = !wgBusy;
        wgImportBtn.setEnabled(idle);
        wgImportBtn.setText(wgBusy ? "处理中…" : "导入 .conf");
        wgImportBtn.setTextColor(idle ? Ui.PRIMARY : Ui.OUTLINE);
        wgImportBtn.setBackground(Ui.round(0x00000000, 12,
                idle ? Ui.PRIMARY : Ui.OUTLINE_VARIANT, 1, this));

        boolean canConnect = idle && wgHasConf && !wgRunning;
        wgConnectBtn.setEnabled(canConnect);
        wgConnectBtn.setText(wgConnecting ? "连接中…" : (wgRunning ? "已连接" : "连接"));
        wgConnectBtn.setTextColor(canConnect ? Ui.ON_PRIMARY : Ui.OUTLINE);
        wgConnectBtn.setBackground(Ui.round(canConnect ? Ui.PRIMARY : Ui.SURFACE_HIGH,
                12, this));

        boolean canDisconnect = idle && wgRunning;
        wgDisconnectBtn.setEnabled(canDisconnect);
        wgDisconnectBtn.setTextColor(canDisconnect ? Ui.PRIMARY : Ui.OUTLINE);
        wgDisconnectBtn.setBackground(Ui.round(0x00000000, 12,
                canDisconnect ? Ui.PRIMARY : Ui.OUTLINE_VARIANT, 1, this));

        boolean canDelete = idle && wgHasConf;
        wgDeleteBtn.setEnabled(canDelete);
        wgDeleteBtn.setTextColor(canDelete ? Ui.ERROR : Ui.OUTLINE);
        wgDeleteBtn.setBackground(Ui.round(0x00000000, 12,
                canDelete ? Ui.ERROR : Ui.OUTLINE_VARIANT, 1, this));
    }

    /** 后台读「有没有配置 / 进程在不在 / 端口通不通」；status() 与 conf 字节数一起带回来。 */
    private void refreshWgStatus() {
        if (wgRefreshing || wgBusy) {
            return;
        }
        wgRefreshing = true;
        new Thread(new Runnable() {
            @Override public void run() {
                final boolean has = Tunnel.hasConf(SettingsActivity.this);
                final boolean running = Tunnel.running();
                final String status = Tunnel.status();
                final long bytes = has ? Tunnel.confFile(SettingsActivity.this).length() : 0L;
                postUi(new Runnable() {
                    @Override public void run() {
                        wgRefreshing = false;
                        wgHasConf = has;
                        wgRunning = running;
                        wgStatusText = status;
                        wgConfBytes = Math.max(0L, bytes);
                        if (isFinishing()) {
                            return;
                        }
                        applyWgState();
                    }
                });
            }
        }, "wg-status").start();
    }

    /** 先试 text/plain，系统里没有能开它的 App 就退回任意类型（有些文件管理器只认后者）。 */
    private void pickWgConf() {
        if (wgBusy) {
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        try {
            startActivityForResult(i, REQ_WG_CONF);
            return;
        } catch (ActivityNotFoundException e) {
            // 走下面的兜底
        }
        Intent any = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        any.addCategory(Intent.CATEGORY_OPENABLE);
        any.setType("*/*");
        try {
            startActivityForResult(any, REQ_WG_CONF);
        } catch (ActivityNotFoundException e2) {
            Toast.makeText(this, "系统里没有能选文件的 App", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_WG_CONF) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return; // 用户取消，不吵他
        }
        final Uri uri = data.getData();
        wgBusy = true;
        wgError.setVisibility(View.GONE);
        applyWgState();
        new Thread(new Runnable() {
            @Override public void run() {
                importWgConf(uri);
            }
        }, "wg-import").start();
    }

    /** 读 ContentResolver 流（上限 64KB）→ 校验 [Interface]/[Peer] → 落盘。全在子线程。 */
    private void importWgConf(Uri uri) {
        String error = null;
        String text = null;
        try {
            text = readConfStream(uri);
        } catch (Exception e) {
            error = "读取配置文件失败：" + messageOf(e);
        }
        if (error == null && text != null) {
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            if (!text.contains("[Interface]") || !text.contains("[Peer]")) {
                error = "配置不像 WireGuard：缺 [Interface] 或 [Peer]";
            }
        }
        boolean wasRunning = false;
        if (error == null) {
            try {
                // 旧配置上跑着的隧道必须先停，不然界面说新配置、进程还是旧配置
                wasRunning = Tunnel.running();
                Tunnel.stop();
                Tunnel.writeConf(SettingsActivity.this, text);
            } catch (Exception e) {
                error = "保存配置失败：" + messageOf(e);
            }
        }
        final String err = error;
        final boolean wasUp = wasRunning;
        postUi(new Runnable() {
            @Override public void run() {
                wgBusy = false;
                wgConnecting = false;
                if (isFinishing()) {
                    return;
                }
                applyWgState();
                if (err != null) {
                    showWgError(err);
                } else {
                    wgLastError = "";
                    wgError.setVisibility(View.GONE);
                    Toast.makeText(SettingsActivity.this,
                            wasUp ? "已导入配置；隧道已断开，点「连接」用新配置"
                                    : "已导入 WireGuard 配置",
                            Toast.LENGTH_LONG).show();
                }
                refreshWgStatus();
            }
        });
    }

    private String readConfStream(Uri uri) throws Exception {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new Exception("打不开这个文件");
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (bos.size() + n > WG_CONF_MAX) {
                    throw new Exception("配置超过 64KB，不像是 WireGuard 配置文件");
                }
                bos.write(buf, 0, n);
            }
            if (bos.size() == 0) {
                throw new Exception("文件是空的");
            }
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            try { in.close(); } catch (Exception ignored) { }
        }
    }

    private void connectWg() {
        if (wgBusy) {
            return;
        }
        if (!wgHasConf) {
            Toast.makeText(this, "还没导入 WireGuard 配置", Toast.LENGTH_LONG).show();
            return;
        }
        wgBusy = true;
        wgConnecting = true;
        wgError.setVisibility(View.GONE);
        applyWgState();
        new Thread(new Runnable() {
            @Override public void run() {
                final String why = Tunnel.start(SettingsActivity.this);
                postUi(new Runnable() {
                    @Override public void run() {
                        wgBusy = false;
                        wgConnecting = false;
                        if (isFinishing()) {
                            return;
                        }
                        if (why == null) {
                            wgHasConf = true;
                            wgRunning = true;
                            applyWgState();
                            Toast.makeText(SettingsActivity.this, "隧道已连上",
                                    Toast.LENGTH_SHORT).show();
                        } else {
                            applyWgState();
                            showWgError(why);
                        }
                        refreshWgStatus();
                    }
                });
            }
        }, "wg-start").start();
    }

    private void disconnectWg() {
        if (wgBusy) {
            return;
        }
        wgBusy = true;
        applyWgState();
        new Thread(new Runnable() {
            @Override public void run() {
                Tunnel.stop();
                postUi(new Runnable() {
                    @Override public void run() {
                        wgBusy = false;
                        wgRunning = false;
                        wgStatusText = "隧道未连接";
                        if (isFinishing()) {
                            return;
                        }
                        applyWgState();
                        Toast.makeText(SettingsActivity.this, "隧道已断开",
                                Toast.LENGTH_SHORT).show();
                        refreshWgStatus();
                    }
                });
            }
        }, "wg-stop").start();
    }

    private void confirmClearWg() {
        if (wgBusy) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除 WireGuard 配置")
                .setMessage("会先断开隧道，再删掉 wg.conf。删了要重新导入才能再连。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { clearWg(); }
                })
                .show();
    }

    private void clearWg() {
        wgBusy = true;
        applyWgState();
        new Thread(new Runnable() {
            @Override public void run() {
                Tunnel.clearConf(SettingsActivity.this);
                postUi(new Runnable() {
                    @Override public void run() {
                        wgBusy = false;
                        wgHasConf = false;
                        wgRunning = false;
                        wgConfBytes = 0L;
                        wgStatusText = "隧道未连接";
                        wgLastError = "";
                        if (isFinishing()) {
                            return;
                        }
                        wgError.setVisibility(View.GONE);
                        applyWgState();
                        Toast.makeText(SettingsActivity.this, "已删除配置",
                                Toast.LENGTH_SHORT).show();
                        refreshWgStatus();
                    }
                });
            }
        }, "wg-clear").start();
    }

    /** 失败原因原话上屏 + Toast LONG；长按复制。 */
    private void showWgError(String why) {
        String msg = (why == null || why.trim().isEmpty())
                ? "隧道没起来，也没留下原因" : why.trim();
        wgLastError = msg;
        if (wgError != null) {
            wgError.setText(msg);
            wgError.setVisibility(View.VISIBLE);
        }
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
    }

    private boolean copyWgError() {
        String s = wgLastError == null ? "" : wgLastError.trim();
        if (s.isEmpty() && wgError != null) {
            s = wgError.getText().toString().trim();
        }
        if (s.isEmpty()) {
            return false;
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "这个系统没有剪贴板服务", Toast.LENGTH_SHORT).show();
            return true;
        }
        cm.setPrimaryClip(ClipData.newPlainText("wireguard", s));
        Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        return true;
    }

    private static String messageOf(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        return m == null || m.trim().isEmpty() ? t.getClass().getSimpleName() : m.trim();
    }

    // ---------------------------------------------------------------- 服务器

    private void buildServerCard(LinearLayout content) {
        sectionTitle(content, "服务器");
        LinearLayout card = card(content);

        card.addView(fieldLabel("局域网地址"));
        etLan = input(db.kv("server_url", "http://192.0.2.10:54087"),
                "http://192.0.2.10:54087", InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI, 1);
        card.addView(etLan, inputParams());

        card.addView(fieldLabel("备用地址（公网 / 内置隧道）"));
        etWg = input(db.kv("server_url_wg", "http://198.51.100.10:54087"),
                "http://198.51.100.10:54087", InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI, 1);
        card.addView(etWg, inputParams());

        TextView altNote = Ui.tv(this,
                "局域网不通时用它；内置隧道连上后会自动走这里（也可填公网反代地址）",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams anlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        anlp.topMargin = dp(4);
        card.addView(altNote, anlp);

        card.addView(fieldLabel("Token（可空）"));
        etToken = input(db.kv("token", ""), "不填就不带 Authorization",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD, 1);
        card.addView(etToken, inputParams());

        TextView note = Ui.tv(this, "地址不用带 /v2，会自动补 http:// 并去掉尾部斜杠；自检按输入框当前值测，不用先保存。",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        card.addView(note, nlp);

        checkBtn = Ui.tv(this, "连通性自检", 14, Ui.ON_PRIMARY, true);
        checkBtn.setGravity(Gravity.CENTER);
        checkBtn.setPadding(0, dp(12), 0, dp(12));
        checkBtn.setBackground(Ui.round(Ui.PRIMARY, 12, this));
        checkBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { runSelfCheck(); }
        });
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(12);
        card.addView(checkBtn, blp);

        lanLine = resultLine("局域网 未自检");
        altLine = resultLine("备用 未自检");
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        card.addView(lanLine, rlp);
        LinearLayout.LayoutParams rlp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp2.topMargin = dp(4);
        card.addView(altLine, rlp2);
    }

    private TextView resultLine(String text) {
        return Ui.tv(this, text, 13, Ui.ON_SURFACE_VARIANT, false);
    }

    /** 后台依次探两个地址，结果逐行落到 lanLine / altLine。 */
    private void runSelfCheck() {
        if (checking) {
            return;
        }
        final String lan = text(etLan);
        final String alt = text(etWg);
        final String token = text(etToken);

        checking = true;
        checkBtn.setEnabled(false);
        checkBtn.setText("自检中…");
        checkBtn.setTextColor(Ui.ON_SURFACE_VARIANT);
        checkBtn.setBackground(Ui.round(Ui.SURFACE_HIGH, 12, this));

        lanLine.setText("局域网 检测中…");
        lanLine.setTextColor(Ui.ON_SURFACE_VARIANT);
        altLine.setText("备用 检测中…");
        altLine.setTextColor(Ui.ON_SURFACE_VARIANT);

        new Thread(new Runnable() {
            @Override public void run() {
                final Probe a = probe(lan, token);
                postUi(new Runnable() {
                    @Override public void run() { showProbe("局域网", lanLine, a); }
                });
                final Probe b = probe(alt, token);
                postUi(new Runnable() {
                    @Override public void run() { showProbe("备用", altLine, b); }
                });
                postUi(new Runnable() {
                    @Override public void run() { endSelfCheck(); }
                });
            }
        }, "stt-selfcheck").start();
    }

    private void endSelfCheck() {
        checking = false;
        checkBtn.setEnabled(true);
        checkBtn.setText("连通性自检");
        checkBtn.setTextColor(Ui.ON_PRIMARY);
        checkBtn.setBackground(Ui.round(Ui.PRIMARY, 12, this));
    }

    private void showProbe(String tag, TextView line, Probe p) {
        if (p.ok) {
            line.setText(tag + " ✓ " + p.ms + "ms" + (Tunnel.running() ? "（隧道）" : ""));
            line.setTextColor(Ui.SUCCESS);
        } else {
            line.setText(tag + " ✗ " + p.why);
            line.setTextColor(Ui.ERROR);
        }
    }

    private static final class Probe {
        final boolean ok;
        final long ms;
        final String why;

        Probe(boolean ok, long ms, String why) {
            this.ok = ok;
            this.ms = ms;
            this.why = why;
        }
    }

    private Probe probe(String url, String token) {
        if (url == null || url.trim().isEmpty()) {
            return new Probe(false, 0, "未填地址");
        }
        long t0 = SystemClock.elapsedRealtime();
        try {
            SttClient c = new SttClient(url.trim(), token).withReadTimeout(8000);
            // 隧道在跑就必须经隧道测，否则"隧道起来了、自检还报连不上"
            if (Tunnel.running()) {
                c.withHttpProxy("127.0.0.1", Tunnel.LOCAL_PORT);
            }
            boolean ok = c.health();
            long ms = SystemClock.elapsedRealtime() - t0;
            return ok ? new Probe(true, ms, null) : new Probe(false, ms, "服务器说没准备好");
        } catch (IOException e) {
            long ms = SystemClock.elapsedRealtime() - t0;
            String why;
            if (SttClient.isTransportError(e)) {
                why = "连不上";
            } else if (e instanceof SttClient.SttException) {
                why = ((SttClient.SttException) e).human;
            } else {
                String m = e.getMessage();
                why = (m == null || m.trim().isEmpty()) ? "出错" : m.trim();
            }
            if (why.length() > 40) {
                why = why.substring(0, 40) + "…";
            }
            return new Probe(false, ms, why);
        }
    }

    // ---------------------------------------------------------------- 转写

    private void buildTranscribeCard(LinearLayout content) {
        sectionTitle(content, "转写");
        LinearLayout card = card(content);

        LinearLayout langRow = Ui.row(this);
        langRow.addView(Ui.tv(this, "语言", 15, Ui.ON_SURFACE, false),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView zh = Ui.tv(this, "中文（zh）", 13, Ui.ON_SECONDARY_CONTAINER, true);
        zh.setPadding(dp(10), dp(6), dp(10), dp(6));
        zh.setBackground(Ui.round(Ui.SECONDARY_CONTAINER, 8, this));
        langRow.addView(zh, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(langRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView langNote = Ui.tv(this, "本版本固定中文，只读；多语言下版本再说。",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams lnlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lnlp.topMargin = dp(4);
        card.addView(langNote, lnlp);

        TextView rootsLabel = fieldLabel("扫描目录（每行一个）");
        LinearLayout.LayoutParams rllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rllp.topMargin = dp(16);
        card.addView(rootsLabel, rllp);

        String roots = db.kv("roots", join(Scanner.defaultRoots()));
        etRoots = input(roots, "/storage/emulated/0/Recordings", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 5);
        etRoots.setGravity(Gravity.TOP | Gravity.START);
        etRoots.setHorizontallyScrolling(false);
        card.addView(etRoots, inputParams());

        TextView rootsNote = Ui.tv(this,
                "扫描会递归往下找 m4a/mp3/wav 等录音文件；目录写错不会报错，只是扫不到东西。",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams rnlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rnlp.topMargin = dp(6);
        card.addView(rootsNote, rnlp);

        // 初始值都设完再挂监听，避免一进页面就被标脏
        watch(etLan);
        watch(etWg);
        watch(etToken);
        watch(etRoots);
    }

    // ---------------------------------------------------------------- 权限

    private void buildPermCard(LinearLayout content) {
        sectionTitle(content, "权限");
        LinearLayout card = card(content);

        allFilesState = Ui.tv(this, "", 12, Ui.ON_SURFACE_VARIANT, false);
        allFilesBtn = permButton();
        allFilesBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestAllFiles(); }
        });
        card.addView(permRow("所有文件访问", allFilesState, allFilesBtn),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(divider(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1)));

        notifState = Ui.tv(this, "", 12, Ui.ON_SURFACE_VARIANT, false);
        notifBtn = permButton();
        notifBtn.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { requestNotification(); }
        });
        card.addView(permRow("通知", notifState, notifBtn),
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView note = Ui.tv(this,
                "没有「所有文件访问」时，扫描目录基本是空的；没有通知权限，转写完成/失败不会提醒。",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(12);
        card.addView(note, nlp);
    }

    private LinearLayout permRow(String title, TextView state, TextView button) {
        LinearLayout row = Ui.row(this);
        LinearLayout texts = Ui.col(this);
        texts.addView(Ui.tv(this, title, 15, Ui.ON_SURFACE, false));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(2);
        texts.addView(state, slp);
        row.addView(texts, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.leftMargin = dp(8);
        row.addView(button, blp);
        return row;
    }

    private TextView permButton() {
        TextView t = Ui.tv(this, "去授权", 13, Ui.PRIMARY, true);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setBackground(Ui.round(0x00000000, 10, Ui.OUTLINE_VARIANT, 1, this));
        return t;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(0x33CAC4D0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(1));
        lp.topMargin = dp(12);
        lp.bottomMargin = dp(12);
        v.setLayoutParams(lp);
        return v;
    }

    private void requestAllFiles() {
        if (hasAllFiles()) {
            Toast.makeText(this, "已允许所有文件访问", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent i = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            try {
                startActivity(i);
            } catch (ActivityNotFoundException e) {
                try {
                    startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                } catch (ActivityNotFoundException e2) {
                    Toast.makeText(this, "系统没有这个设置页，去应用权限里手动开",
                            Toast.LENGTH_LONG).show();
                }
            }
        } else {
            requestPermissions(new String[]{android.Manifest.permission.READ_EXTERNAL_STORAGE},
                    REQ_STORAGE);
        }
    }

    private void requestNotification() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "已允许通知", Toast.LENGTH_SHORT).show();
                return;
            }
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATION);
        } else {
            try {
                startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(this, "系统没有通知设置页", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private boolean hasAllFiles() {
        // isExternalStorageManager 是 API 30 才有的；minSdk 29，必须挡住，
        // 否则 Android 10 上会直接 NoSuchMethodError 把设置页干掉。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Throwable t) {
                return false;
            }
        }
        return checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private boolean notificationsOn() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        return nm != null && nm.areNotificationsEnabled();
    }

    private void refreshPerms() {
        boolean all = hasAllFiles();
        if (allFilesState != null) {
            allFilesState.setText(all ? "已允许"
                    : "未允许 · 扫描读不到 MIUI 录音目录");
            allFilesState.setTextColor(all ? Ui.SUCCESS : Ui.ON_SURFACE_VARIANT);
            allFilesBtn.setText(all ? "已允许" : "去授权");
            allFilesBtn.setTextColor(all ? Ui.SUCCESS : Ui.PRIMARY);
        }

        boolean notif = notificationsOn();
        if (notifState != null) {
            if (notif) {
                notifState.setText("已允许");
            } else if (Build.VERSION.SDK_INT >= 33) {
                notifState.setText("未允许 · 转写完成/失败不会提醒");
            } else {
                notifState.setText("未允许 · 去系统设置打开");
            }
            notifState.setTextColor(notif ? Ui.SUCCESS : Ui.ON_SURFACE_VARIANT);
            notifBtn.setText(notif ? "已允许" : "去授权");
            notifBtn.setTextColor(notif ? Ui.SUCCESS : Ui.PRIMARY);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refreshPerms();
        if (requestCode == REQ_NOTIFICATION) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            Toast.makeText(this, granted ? "通知已允许" : "通知没允许，转写完成时不会提醒",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- 保存 / 返回

    private void buildSaveButton(LinearLayout content) {
        TextView save = Ui.tv(this, "保存", 15, Ui.ON_PRIMARY, true);
        save.setGravity(Gravity.CENTER);
        save.setPadding(0, dp(14), 0, dp(14));
        save.setBackground(Ui.round(Ui.PRIMARY, 14, this));
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(false); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        content.addView(save, lp);

        TextView note = Ui.tv(this, "点「保存」才写库；没保存就返回会先问一句。",
                11, Ui.OUTLINE, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nlp.topMargin = dp(8);
        content.addView(note, nlp);
    }

    private void save(boolean finishAfter) {
        db.kvPut("server_url", text(etLan));
        db.kvPut("server_url_wg", text(etWg));
        db.kvPut("token", text(etToken));
        db.kvPut("roots", etRoots.getText().toString().trim());
        db.kvPut("lang", "zh");

        dirty = false;
        hideKeyboard();
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        if (finishAfter) {
            finish();
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (!dirty) {
            super.onBackPressed();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("有改动还没保存")
                .setMessage("服务器地址、Token 或扫描目录还没保存，返回会丢掉这些改动。")
                .setPositiveButton("保存并返回", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) { save(true); }
                })
                .setNegativeButton("放弃修改", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int which) {
                        dirty = false;
                        finish();
                    }
                })
                .setNeutralButton("继续编辑", null)
                .show();
    }

    // ---------------------------------------------------------------- 输入控件

    private EditText input(String value, String hint, int inputType, int minLines) {
        EditText e = new EditText(this);
        e.setText(value == null ? "" : value);
        e.setHint(hint);
        e.setTextSize(15);
        e.setTextColor(Ui.ON_SURFACE);
        e.setHintTextColor(Ui.OUTLINE);
        e.setInputType(inputType);
        e.setSingleLine(minLines <= 1);
        if (minLines > 1) {
            e.setMinLines(minLines);
            e.setGravity(Gravity.TOP | Gravity.START);
        }
        int pad = dp(12);
        e.setPadding(pad, dp(10), pad, dp(10));
        e.setBackground(Ui.round(Ui.SURFACE_HIGH, 12, this));
        if (value != null) {
            e.setSelection(e.getText().length());
        }
        return e;
    }

    private LinearLayout.LayoutParams inputParams() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        return lp;
    }

    private TextView fieldLabel(String text) {
        TextView t = Ui.tv(this, text, 13, Ui.ON_SURFACE_VARIANT, false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(12);
        t.setLayoutParams(lp);
        return t;
    }

    private void watch(EditText e) {
        e.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { dirty = true; }
        });
    }

    private static String text(EditText e) {
        return e == null ? "" : e.getText().toString().trim();
    }

    private void hideKeyboard() {
        View focus = getCurrentFocus();
        if (focus == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
        }
    }

    // ---------------------------------------------------------------- 小工具

    private void sectionTitle(LinearLayout parent, String text) {
        TextView t = Ui.tv(this, text, 13, Ui.ON_SURFACE_VARIANT, true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(18);
        lp.bottomMargin = dp(2);
        parent.addView(t, lp);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = Ui.col(this);
        int pad = dp(16);
        c.setPadding(pad, pad, pad, pad);
        if (!Ui.glassCard(c, 20, 0x80)) {
            c.setBackground(Ui.card(this, Ui.SURFACE_CONTAINER, 20));
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(6);
        parent.addView(c, lp);
        return c;
    }

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dp(24);
    }

    private int drawableId(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private void postUi(Runnable r) {
        runOnUiThread(r);
    }

    private static String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        if (list != null) {
            for (String s : list) {
                if (s == null || s.trim().isEmpty()) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(s.trim());
            }
        }
        return sb.toString();
    }
}
