package com.zglinus.recstt.wg;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * 内置 WireGuard —— 但**不申请 VPN 权限**。
 *
 * 做法：把 wireproxy（userspace WG + HTTP/SOCKS 代理，Go 静态二进制）作为子进程跑起来，
 * 隧道只在 App 进程内用 HTTP 代理（127.0.0.1:25344）走，不建 tun、不碰系统路由，
 * 所以不需要系统 VPN 授权，也不会接管别的 App 的流量。
 *
 * 代价：多了约 10MB 的原生二进制；进程跟着 App 死，App 被杀隧道就断。
 */
public final class Tunnel {
    private static final String TAG = "RecSttTunnel";
    public static final int LOCAL_PORT = 25344;
    public static final String CONF_NAME = "wg.conf";

    private static Process proc;
    /** 尾部滚动窗口（判断"最后发生了什么"）。 */
    private static final ArrayDeque<String> TAIL = new ArrayDeque<>();
    /** 头部固定几行（fatal error / panic 的报头永远在最前面，只看尾巴会丢掉)。 */
    private static final ArrayDeque<String> HEAD = new ArrayDeque<>();
    private static final int LOG_MAX = 40;
    private static final int HEAD_KEEP = 6;
    private static File logFile;

    private Tunnel() {}

    public static File confFile(Context c) {
        return new File(c.getFilesDir(), CONF_NAME);
    }

    public static boolean hasConf(Context c) {
        File f = confFile(c);
        return f.isFile() && f.length() > 32;
    }

    public static String readConf(Context c) {
        File f = confFile(c);
        if (!f.isFile()) return "";
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) Math.min(f.length(), 64 * 1024)];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    public static void writeConf(Context c, String text) throws Exception {
        File tmp = new File(c.getFilesDir(), CONF_NAME + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        File dst = confFile(c);
        if (dst.exists() && !dst.delete()) throw new Exception("旧配置删不掉");
        if (!tmp.renameTo(dst)) throw new Exception("配置落盘失败");
    }

    public static void clearConf(Context c) {
        stop();
        File f = confFile(c);
        if (f.exists()) f.delete();
    }

    public static synchronized boolean running() {
        if (proc == null) return false;
        if (!proc.isAlive()) return false;
        return portOpen();
    }

    private static boolean portOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", LOCAL_PORT), 300);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** 成功返回 null；失败返回一句人话（带 wireproxy 的日志尾巴）。 */
    public static synchronized String start(Context c) {
        if (running()) return null;
        if (!hasConf(c)) return "还没导入 WireGuard 配置";

        String base = readConf(c);
        if (!base.contains("[Interface]") || !base.contains("[Peer]")) {
            return "配置不像 WireGuard：缺 [Interface] 或 [Peer]";
        }
        // 在原始配置后面追加一个只监听本机的 HTTP 代理段
        // 段名必须是小写 [http]：wireproxy 用 ini 的 GetSection("http") 取，
        // 写成 [HTTP] 它直接找不到 → 隧道起来了但代理端口根本没开（真机踩过）。
        String merged = base + "\n\n[http]\nBindAddress = 127.0.0.1:" + LOCAL_PORT + "\n";
        File conf = new File(c.getFilesDir(), "wireproxy.conf");
        try (FileOutputStream out = new FileOutputStream(conf)) {
            out.write(merged.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "写临时配置失败：" + e.getMessage();
        }

        File exe = new File(c.getApplicationInfo().nativeLibraryDir, "libwireproxy.so");
        if (!exe.isFile()) return "缺少 wireproxy 二进制（这个 CPU 架构没打进去）";

        synchronized (TAIL) { TAIL.clear(); HEAD.clear(); }
        logFile = new File(c.getFilesDir(), "wireproxy.log");
        try {
            // 别给 -i：那是 info/status 监听地址，必须是 host:port；
            // 之前传 127.0.0.1 会让 lockNetwork 里的 extractPort panic（missing port in address）。
            ProcessBuilder pb = new ProcessBuilder(exe.getAbsolutePath(), "-c", conf.getAbsolutePath());
            pb.redirectErrorStream(true);
            proc = pb.start();
            pump(proc);
        } catch (Exception e) {
            Log.w(TAG, "起 wireproxy 失败", e);
            return "起隧道失败：" + e.getMessage();
        }

        // 等端口起来（最多 10 秒），失败就把日志尾巴带回去，不静默
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            if (portOpen()) return null;
            if (proc != null && !proc.isAlive()) {
                return "隧道进程退出了（exit=" + exitCode() + "）：" + tailLog();
            }
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
        }
        stop();
        return "隧道进程在跑，但 127.0.0.1:" + LOCAL_PORT + " 没开监听（代理段没被识别）：" + tailLog();
    }

    private static void pump(final Process p) {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (TAIL) {
                            TAIL.addLast(line);
                            if (TAIL.size() > LOG_MAX) TAIL.removeFirst();
                            if (HEAD.size() < HEAD_KEEP) HEAD.addLast(line);
                        }
                        Log.i(TAG, line);
                        try (java.io.FileOutputStream fo = new java.io.FileOutputStream(logFile, true)) {
                            fo.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                        } catch (Exception ignored) {
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        }, "wireproxy-log");
        t.setDaemon(true);
        t.start();
    }

    public static synchronized void stop() {
        if (proc != null) {
            try { proc.destroy(); } catch (Exception ignored) {}
            proc = null;
        }
    }

    /** 报头 + 尾巴：崩溃时最关键的是第一行（fatal error / panic），光看尾部只会看到寄存器转储。 */
    public static synchronized String tailLog() {
        synchronized (TAIL) {
            if (TAIL.isEmpty()) return "（没有输出）";
            StringBuilder sb = new StringBuilder();
            for (String s : HEAD) sb.append(s).append(" | ");
            if (TAIL.size() > HEAD.size()) {
                sb.append("… | ");
                int skip = 0, keep = Math.min(6, TAIL.size());
                for (String s : TAIL) {
                    if (skip++ < TAIL.size() - keep) continue;
                    sb.append(s).append(" | ");
                }
            }
            String out = sb.toString();
            return out.length() > 700 ? out.substring(0, 700) + " …" : out;
        }
    }

    private static int exitCode() {
        try { return proc == null ? -1 : proc.exitValue(); } catch (Exception e) { return -1; }
    }

    public static java.io.File logFile() { return logFile; }

    public static String status() {
        if (running()) return "隧道已连接";
        return "隧道未连接";
    }
}
