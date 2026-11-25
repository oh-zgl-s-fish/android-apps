package com.zglinus.recstt.net;

import android.content.Context;

import com.zglinus.recstt.store.Db;
import com.zglinus.recstt.wg.Tunnel;

import java.util.ArrayList;
import java.util.List;

/**
 * 两条线路：局域网优先，不可用时退 WG。
 * 判断标准是「能不能真的连上 /v2/health」，不是猜 SSID —— 猜错会静默走错路。
 */
public final class Endpoints {
    public static final String DEFAULT_LAN = "http://192.0.2.10:54087";
    public static final String DEFAULT_WG = "http://198.51.100.10:54087";

    public static final class Probe {
        public String label;   // LAN / WG
        public String url;
        public boolean ok;
        public long ms;
        public String error;
        public String text() {
            return ok ? label + " " + ms + "ms" : label + " ✗ " + (error == null ? "不通" : error);
        }
    }

    private Endpoints() {}

    public static String lanUrl(Context c) {
        String v = Db.get(c).kv("server_url", null);
        return v == null || v.trim().isEmpty() ? DEFAULT_LAN : v.trim();
    }

    public static String wgUrl(Context c) {
        String v = Db.get(c).kv("server_url_wg", null);
        return v == null || v.trim().isEmpty() ? DEFAULT_WG : v.trim();
    }

    /** 依次探测（局域网优先）。超时 5 秒，免得界面卡死。 */
    /** 探针标签：probe() 注册和上层查找必须用同一份常量。 */
    public static final String LABEL_LAN = "局域网";
    public static final String LABEL_ALT = "备用";

    public static List<Probe> probe(Context c) {
        List<Probe> out = new ArrayList<>();
        // 局域网**永远直连**：它就在同一网段，没必要也不该绕隧道。
        //
        // 之前是"隧道在跑就让两条都走隧道"，结果隧道不通时局域网判定也跟着失败，
        // 两条线一起挂 —— 而抓包证明手机直连局域网地址完全正常
        // （三次握手成功、请求和响应都有）。
        out.add(one(c, LABEL_LAN, lanUrl(c), false));
        out.add(one(c, LABEL_ALT, wgUrl(c), Tunnel.running()));
        return out;
    }

    private static Probe one(Context c, String label, String url, boolean viaTunnel) {
        Probe p = new Probe();
        p.label = label;
        p.url = url;
        long t0 = System.currentTimeMillis();
        try {
            SttClient client = new SttClient(url, Db.get(c).kv("token", "")).withReadTimeout(5000);
            if (viaTunnel) client.withHttpProxy("127.0.0.1", Tunnel.LOCAL_PORT);
            p.ok = client.health();
            p.ms = System.currentTimeMillis() - t0;
            if (!p.ok) p.error = "服务没应答";
        } catch (Throwable t) {
            p.ms = System.currentTimeMillis() - t0;
            p.ok = false;
            p.error = SttClient.human(t);
        }
        return p;
    }

    /**
     * 唯一出口：按当前可达线路返回一个**已配好隧道**的客户端。
     * 自检、首页、转写都必须用它，别再各自判断 —— 三处各写一套必然漂移。
     */
    public static SttClient clientFor(Context c) {
        String token = Db.get(c).kv("token", "");
        SttClient client = new SttClient(pick(c), token);
        if (Tunnel.running()) {
            client.withHttpProxy("127.0.0.1", Tunnel.LOCAL_PORT);
        }
        return client;
    }

    /**
     * 选一条能用的：局域网通了就用局域网；不通且备用地址（公网/WG）通就用备用；
     * 都不通时仍返回局域网地址（让上层报错，而不是悄悄换个地址发出去）。
     */
    public static String pick(Context c) {
        String lan = lanUrl(c), wg = wgUrl(c);
        Probe a = one(c, "LAN", lan, false);
        if (a.ok) return lan;
        if (Tunnel.running()) {
            Probe b = one(c, "备用", wg, true);
            if (b.ok) return wg;
            // 备用地址可能落在"隧道另一侧并不存在"的网段上。
            // 那就用局域网地址走隧道：服务器上给隧道网段做 NAT 就能转进 LAN。
            Probe alt = one(c, "LAN", lan, true);
            if (alt.ok) return lan;
        }
        // 两条都不通：回到 LAN，让上层报出最可能的那条的错误，而不是悄悄换地址
        return lan;
    }
}
