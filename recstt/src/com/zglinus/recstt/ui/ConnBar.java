package com.zglinus.recstt.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.zglinus.recstt.net.Endpoints;
import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.wg.Tunnel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 连通性条：局域网 / 备用两条线路的状态、自检、连隧道。
 *
 * <p>从 MainActivity 抽出来的第一个功能模块。界线：
 * <ul>
 *   <li>只负责这块卡片，不认识列表、扫描、菜单；</li>
 *   <li>所有网络/隧道操作都在后台线程，结果回主线程重画；</li>
 *   <li>对外只暴露 {@link #refreshConnRows()}、{@link #refreshTunnelState()}、{@link #onResumeSync()}。</li>
 * </ul>
 *
 * <p>抽出来之后，MainActivity 少 450 行左右，这块的 bug 也就不用在大文件里找了。
 */
public class ConnBar {

    private static final long PROBE_TTL_MS = 10_000L;

    private final Activity act;
    private final Handler handler;

    private LinearLayout card;
    private View dot;
    private TextView text1;
    private TextView text2;
    private TextView hint;
    private TextView btnProbe;
    private TextView btnWg;
    private TextView wgError;

    private volatile boolean probing;
    private volatile boolean tunnelStarting;
    private volatile boolean tunnelConf;
    private volatile boolean tunnelRunning;
    private volatile boolean tunnelChecking;
    private boolean probeAfterStart;

    private long lastProbeAt;
    private Endpoints.Probe lanProbe;
    private Endpoints.Probe altProbe;
    private String probedLanUrl;
    private String probedAltUrl;

    private final Runnable wgErrorHide = new Runnable() {
        @Override
        public void run() {
            if (wgError != null) {
                wgError.setText("");
                wgError.setVisibility(View.GONE);
            }
        }
    };

    public ConnBar(Activity act, Handler handler) {
        this.act = act;
        this.handler = handler;
    }

    /** 搭好卡片并挂到 parent 上（调用方负责给 parent 加边距）。 */
    public void attach(LinearLayout parent) {
        Db db = Db.get(act);
        card = Ui.row(act);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setPadding(px(14), px(12), px(10), px(12));
        card.setMinimumHeight(px(72));
        if (!Ui.glassCard(card, 16, 0x66)) {
            card.setBackground(cardBg(false));
        }
        card.setClickable(true);
        card.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runProbe(false);
            }
        });

        LinearLayout left = Ui.col(act);
        left.setLayoutParams(Ui.lpWeight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout line1 = Ui.row(act);
        dot = new View(act);
        LinearLayout.LayoutParams dotLp = Ui.lp(px(8), px(8));
        dotLp.rightMargin = px(7);
        dot.setBackground(Ui.round(Ui.OUTLINE, 4, act));
        line1.addView(dot, dotLp);

        text1 = Ui.tv(act, "局域网 未自检", 14.5f, Ui.ON_SURFACE, false);
        text1.setSingleLine(true);
        text1.setEllipsize(TextUtils.TruncateAt.END);
        line1.addView(text1, Ui.lpWeight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        left.addView(line1, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout line2 = Ui.row(act);
        text2 = Ui.tv(act, "备用 · 未配置备用地址", 11.5f, Ui.ON_SURFACE_VARIANT, false);
        text2.setSingleLine(true);
        text2.setEllipsize(TextUtils.TruncateAt.END);
        line2.addView(text2, Ui.lpWeight(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        btnWg = Ui.tv(act, "连 WG", 12, Ui.ON_PRIMARY_CONTAINER, true);
        btnWg.setGravity(Gravity.CENTER);
        btnWg.setPadding(px(10), 0, px(10), 0);
        btnWg.setBackground(Ui.round(Ui.PRIMARY_CONTAINER, 8, act));
        btnWg.setVisibility(View.GONE);
        btnWg.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                connectTunnel();
            }
        });
        LinearLayout.LayoutParams wgLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT, px(28));
        wgLp.leftMargin = px(8);
        line2.addView(btnWg, wgLp);

        LinearLayout.LayoutParams line2Lp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        line2Lp.topMargin = px(3);
        left.addView(line2, line2Lp);

        wgError = Ui.tv(act, "", 11.5f, Ui.ERROR, false);
        wgError.setVisibility(View.GONE);
        LinearLayout.LayoutParams wgErrLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wgErrLp.topMargin = px(2);
        left.addView(wgError, wgErrLp);

        hint = Ui.tv(act, "两条线路都不通：检查局域网，或在设置里填公网/备用地址",
                11.5f, Ui.ERROR, false);
        hint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hintLp = Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        hintLp.topMargin = px(2);
        left.addView(hint, hintLp);

        card.addView(left);

        // 自检按钮：实底圆角，状态用「底色 + 字色」成对表达
        btnProbe = Ui.tv(act, "自检", 13, Ui.ON_PRIMARY, true);
        btnProbe.setGravity(Gravity.CENTER);
        btnProbe.setPadding(px(14), px(8), px(14), px(8));
        btnProbe.setBackground(Ui.round(Ui.PRIMARY, 999, act));
        btnProbe.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runProbe(false);
            }
        });
        LinearLayout.LayoutParams btnLp = Ui.lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnLp.leftMargin = px(10);
        card.addView(btnProbe, btnLp);

        parent.addView(card);
        if (db != null) {
            refreshConnRows();
        }
    }

    /** onResume 时调：地址变了作废旧结果，隧道状态重读，必要时补一次静默探测。 */
    public void onResumeSync() {
        if (card == null) {
            return;
        }
        String lan = Endpoints.lanUrl(act);
        String alt = Endpoints.wgUrl(act);
        if ((probedLanUrl != null && !probedLanUrl.equals(lan))
                || (probedAltUrl != null && !probedAltUrl.equals(alt))) {
            lanProbe = null;
            altProbe = null;
            lastProbeAt = 0L;
        }
        refreshTunnelState();
        refreshConnRows();
        // 真探测只在界面上放超过 10 秒时才补一次，省电也省服务器
        if (System.currentTimeMillis() - lastProbeAt > PROBE_TTL_MS) {
            runProbe(true);
        }
    }

    // ------------------------------------------------------------------ 探测

    /** 探测两条线路。必须在后台跑（每条 5 秒超时，卡主线程会 ANR）。 */
    public void runProbe(final boolean silent) {
        if (card == null) {
            return;
        }
        if (probing) {
            if (!silent) {
                Toast.makeText(act, "正在自检，稍等一下", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        probing = true;
        if (!silent) {
            btnProbe.setText("自检中…");
            btnProbe.setTextColor(Ui.ON_SURFACE);
            btnProbe.setBackground(Ui.round(0xFFDAD3E2, 999, act));
            dot.setBackground(Ui.round(Ui.OUTLINE, 4, act));
            text1.setText("局域网 探测中…");
            text1.setTextColor(Ui.ON_SURFACE);
            text2.setText(tunnelRunning ? "备用 探测中…（经隧道）" : "备用 探测中…");
            text2.setTextColor(Ui.ON_SURFACE_VARIANT);
            hint.setVisibility(View.GONE);
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                final List<Endpoints.Probe> result;
                try {
                    result = Endpoints.probe(act);
                } catch (Throwable t) {
                    List<Endpoints.Probe> fallback = new ArrayList<Endpoints.Probe>();
                    Endpoints.Probe p = new Endpoints.Probe();
                    p.label = "LAN";
                    p.url = Endpoints.lanUrl(act);
                    p.ok = false;
                    p.error = human(t);
                    fallback.add(p);
                    postFinish(fallback, silent);
                    return;
                }
                postFinish(result, silent);
            }
        }, "recstt-probe").start();
    }

    private void postFinish(final List<Endpoints.Probe> result, final boolean silent) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                finishProbe(result, silent);
            }
        });
    }

    private void finishProbe(List<Endpoints.Probe> result, boolean silent) {
        probing = false;
        lastProbeAt = System.currentTimeMillis();
        // 标签必须和 Endpoints.probe() 里注册的完全一致。
        // 原来这里写 "LAN"，而 probe() 注册的是 "局域网"，pick 永远返回 null，
        // 结果"局域网"那行永远停在"未自检"——探测其实成功了也看不出来。
        lanProbe = pick(result, Endpoints.LABEL_LAN);
        altProbe = pick(result, Endpoints.LABEL_ALT);
        probedLanUrl = Endpoints.lanUrl(act);
        probedAltUrl = Endpoints.wgUrl(act);
        btnProbe.setText("自检");
        btnProbe.setTextColor(Ui.ON_PRIMARY);
        btnProbe.setBackground(Ui.round(Ui.PRIMARY, 999, act));
        if (act.isFinishing() || card == null) {
            return;
        }
        refreshConnRows();
        if (!silent) {
            boolean lanOk = lanProbe != null && lanProbe.ok;
            boolean altOk = altProbe != null && altProbe.ok;
            String msg;
            if (lanOk && altOk) {
                msg = "两条线路都通";
            } else if (lanOk) {
                msg = "局域网通，备用不通：" + shortWhy(altProbe == null ? null : altProbe.error);
            } else if (altOk) {
                msg = "局域网不通，走备用线路";
            } else {
                msg = "两条都不通：" + shortWhy(lanProbe == null ? null : lanProbe.error);
            }
            Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
        }
        // 隧道刚连上时若正有一次探测在跑，那次可能没走代理：等它收尾再补一次
        if (probeAfterStart && !act.isFinishing()) {
            probeAfterStart = false;
            runProbe(false);
        }
    }

    private static Endpoints.Probe pick(List<Endpoints.Probe> list, String label) {
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.size(); i++) {
            Endpoints.Probe p = list.get(i);
            if (p != null && label.equals(p.label)) {
                return p;
            }
        }
        return null;
    }

    /** 按当前探测结果重画两行（不联网，主线程调用）。 */
    public void refreshConnRows() {
        if (card == null || text1 == null) {
            return;
        }
        boolean hasWgConf = tunnelConf;
        boolean tunnelOn = tunnelRunning;
        boolean altSet = altConfigured() || hasWgConf;
        String lanHost = hostOf(Endpoints.lanUrl(act));
        String altHost = hostOf(Endpoints.wgUrl(act));

        boolean lanOk = lanProbe != null && lanProbe.ok;
        boolean lanFail = lanProbe != null && !lanProbe.ok;
        boolean altOk = altProbe != null && altProbe.ok;
        // 隧道在跑但探测还没成功（刚连上/超时）：这是「等一等」，不是「死了」
        boolean altPending = tunnelOn && (altProbe == null || looksTimeout(altProbe.error));

        dot.setBackground(Ui.round(lanOk ? Ui.SUCCESS : (lanFail ? Ui.ERROR : Ui.OUTLINE),
                4, act));
        if (lanProbe == null) {
            text1.setText("局域网 " + lanHost + " · 未自检");
            text1.setTextColor(Ui.ON_SURFACE);
        } else if (lanOk) {
            text1.setText("局域网 " + lanHost + " · " + lanProbe.ms + "ms");
            text1.setTextColor(Ui.ON_SURFACE);
        } else {
            text1.setText("局域网 " + lanHost + " ✗ " + shortWhy(lanProbe.error));
            text1.setTextColor(Ui.ERROR);
        }

        if (!altSet) {
            text2.setText("备用 · 未配置备用地址");
            text2.setTextColor(Ui.ON_SURFACE_VARIANT);
        } else if (tunnelStarting) {
            text2.setText("备用 " + altHost + " · 正在连隧道…");
            text2.setTextColor(Ui.ON_SURFACE_VARIANT);
        } else if (tunnelOn) {
            if (altOk) {
                text2.setText("备用 " + altHost + " · " + altProbe.ms + "ms（隧道）");
                text2.setTextColor(Ui.SUCCESS);
            } else if (altPending) {
                text2.setText("隧道已连，等待探测");
                text2.setTextColor(Ui.SUCCESS);
            } else {
                text2.setText("备用 " + altHost + " ✗ " + shortWhy(altProbe.error));
                text2.setTextColor(Ui.ERROR);
            }
        } else if (altProbe == null) {
            text2.setText("备用 " + altHost + " · 未自检");
            text2.setTextColor(Ui.ON_SURFACE_VARIANT);
        } else if (altOk) {
            text2.setText("备用 " + altHost + " · " + altProbe.ms + "ms");
            text2.setTextColor(Ui.SUCCESS);
        } else {
            text2.setText("备用 " + altHost + " ✗ " + shortWhy(altProbe.error));
            text2.setTextColor(Ui.ERROR);
        }

        if (btnWg != null) {
            btnWg.setVisibility(hasWgConf && !tunnelOn && !tunnelStarting
                    ? View.VISIBLE : View.GONE);
        }

        boolean bothBad = lanFail && !altOk && !altPending && !tunnelStarting;
        hint.setVisibility(bothBad ? View.VISIBLE : View.GONE);
        if (!Ui.glassCard(card, 16, bothBad ? 0x99 : 0x66)) {
            card.setBackground(cardBg(bothBad));
        }
    }

    /** 后台读隧道状态（有没有 wg.conf、进程活着没、25344 通不通），主线程只拿结果。 */
    public void refreshTunnelState() {
        if (tunnelChecking) {
            return;
        }
        tunnelChecking = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean has = Tunnel.hasConf(act);
                final boolean run = Tunnel.running();
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tunnelChecking = false;
                        boolean changed = tunnelConf != has || tunnelRunning != run;
                        tunnelConf = has;
                        tunnelRunning = run;
                        if (changed) {
                            // 隧道刚起/刚断：旧探测结果是从另一条路测的，不能挂在新的状态上
                            altProbe = null;
                            lastProbeAt = 0L;
                        }
                        if (act.isFinishing()) {
                            return;
                        }
                        refreshConnRows();
                        if (changed && run && !probing) {
                            runProbe(true);
                        }
                    }
                });
            }
        }, "recstt-wg-state").start();
    }

    /** 「连 WG」：后台起隧道，成功立刻自检；失败把原话摆出来（Toast + 红字 6 秒）。 */
    private void connectTunnel() {
        if (tunnelStarting) {
            return;
        }
        tunnelStarting = true;
        hideWgError();
        refreshConnRows();
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String why = Tunnel.start(act);
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        tunnelStarting = false;
                        if (act.isFinishing()) {
                            return;
                        }
                        if (why == null) {
                            tunnelConf = true;
                            tunnelRunning = true;
                            altProbe = null;
                            lastProbeAt = 0L;
                            refreshConnRows();
                            Toast.makeText(act, "隧道已连上", Toast.LENGTH_SHORT).show();
                            if (probing) {
                                probeAfterStart = true;
                            } else {
                                runProbe(false);
                            }
                        } else {
                            tunnelRunning = false;
                            refreshConnRows();
                            showWgError(why);
                        }
                    }
                });
            }
        }, "recstt-wg-start").start();
    }

    private void showWgError(String why) {
        String msg = (why == null || why.trim().isEmpty())
                ? "隧道没起来，也没留下原因" : why.trim();
        Toast.makeText(act, msg, Toast.LENGTH_LONG).show();
        if (wgError == null) {
            return;
        }
        wgError.setText(msg);
        wgError.setVisibility(View.VISIBLE);
        handler.removeCallbacks(wgErrorHide);
        handler.postDelayed(wgErrorHide, 6000L);
    }

    private void hideWgError() {
        handler.removeCallbacks(wgErrorHide);
        if (wgError != null) {
            wgError.setText("");
            wgError.setVisibility(View.GONE);
        }
    }

    /** 设置里到底填没填备用地址：没填就不拿默认值冒充「已配置」。 */
    private boolean altConfigured() {
        try {
            Db db = Db.get(act);
            String v = db == null ? null : db.kv("server_url_wg", "");
            return v != null && !v.trim().isEmpty();
        } catch (Throwable t) {
            return false;
        }
    }

    private GradientDrawable cardBg(boolean error) {
        int base = error ? Ui.ERROR_CONTAINER : Ui.SURFACE_CONTAINER;
        if (!Ui.hasBg(act)) {
            // 没背景图时底色和背景一样，只能靠描边区分
            return Ui.round(base, 16, Ui.OUTLINE_VARIANT, 1f, act);
        }
        // 有背景图时只给一层很淡的底 + 描边，别把底图盖死
        return Ui.round((base & 0x00FFFFFF) | 0x33000000, 16,
                error ? Ui.ERROR : Ui.OUTLINE_VARIANT, 1f, act);
    }

    // ------------------------------------------------------------------ 小工具

    private int px(float dp) {
        return Math.round(dp * act.getResources().getDisplayMetrics().density);
    }

    /** 探测失败是不是「等超时」：隧道刚起时这属于正常过渡，不能当死。 */
    private static boolean looksTimeout(String why) {
        String s = why == null ? "" : why.trim().toLowerCase(Locale.US);
        return s.isEmpty() || s.contains("超时") || s.contains("timeout")
                || s.contains("timed out");
    }

    private static String hostOf(String url) {
        String s = url == null ? "" : url.trim();
        int scheme = s.indexOf("://");
        if (scheme >= 0) {
            s = s.substring(scheme + 3);
        }
        int slash = s.indexOf('/');
        if (slash >= 0) {
            s = s.substring(0, slash);
        }
        int colon = s.indexOf(':');
        if (colon > 0) {
            s = s.substring(0, colon);
        }
        return s.isEmpty() ? "地址未填" : s;
    }

    /** 一句话原因（探针给的 error 可能很长，卡片一行放不下）。 */
    private static String shortWhy(String why) {
        String s = why == null ? "" : why.trim();
        if (s.isEmpty()) {
            return "不通（服务没应答）";
        }
        return s.length() > 28 ? s.substring(0, 28) + "…" : s;
    }

    /** 异常 → 给人看的一句话，别把堆栈甩给用户，也别吞成「失败」。 */
    private static String human(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String m = t.getMessage();
        if (m != null && !m.trim().isEmpty()) {
            return m.trim();
        }
        return t.getClass().getSimpleName();
    }
}
