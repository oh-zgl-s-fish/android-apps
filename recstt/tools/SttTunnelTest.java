package com.zglinus.recstt.net;

import com.zglinus.recstt.model.Seg;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 桌面验收：拿**真实的 SttClient** 走「CONNECT 隧道」跑一遍，不打桩、不模拟网络。
 *
 * <p>拓扑（全部本机）：
 * <pre>
 *   SttClient ──CONNECT──> ConnectProxy.py(127.0.0.1:25399) ──> OriginServer.py(127.0.0.1:18081)
 * </pre>
 *
 * <p>跑法（jdk17）：
 * <pre>
 *   python3 tools/ConnectProxy.py --port 25399 --deny-port 18082 &amp;
 *   python3 tools/OriginServer.py &amp;
 *   javac -encoding UTF-8 -d /tmp/sttt \
 *       src/com/zglinus/recstt/model/Seg.java \
 *       src/com/zglinus/recstt/net/SttClient.java \
 *       tools/stub/org/json/*.java tools/SttTunnelTest.java
 *   java -cp /tmp/sttt com.zglinus.recstt.net.SttTunnelTest
 * </pre>
 *
 * <p>放在 com.zglinus.recstt.net 包里是故意的：这样能直接打到包内可见的
 * {@code exec(...)} / {@code Response}，测的就是产品路径本身，不是复刻品。
 *
 * <p>退出码：0=全过，1=有失败。
 */
public final class SttTunnelTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) throws Exception {
        String proxyHost = args.length > 0 ? args[0] : "127.0.0.1";
        int proxyPort = args.length > 1 ? Integer.parseInt(args[1]) : 25399;
        String origin = args.length > 2 ? args[2] : "http://127.0.0.1:18081";

        System.out.println("=== SttClient CONNECT 隧道桌面验收 ===");
        System.out.println("代理    : " + proxyHost + ":" + proxyPort + "（只支持 CONNECT）");
        System.out.println("源站    : " + origin);
        System.out.println("JDK     : " + System.getProperty("java.version") + " / " + System.getProperty("os.name"));
        System.out.println("SttClient: " + SttClient.class.getProtectionDomain().getCodeSource().getLocation());

        testHealth(proxyHost, proxyPort, origin);
        testGetAndChunked(proxyHost, proxyPort, origin);
        testRedirect(proxyHost, proxyPort, origin);
        testSmallPost(proxyHost, proxyPort, origin);
        testSubmitMultipart(proxyHost, proxyPort, origin);
        testStatusAndResult(proxyHost, proxyPort, origin);
        testCancel(proxyHost, proxyPort, origin);
        testRejectedByProxy(proxyHost, proxyPort, origin);
        testUpstreamDown(proxyHost, proxyPort);
        testDirectModeUnaffected(origin);
        testProxyRejectsAbsoluteUri(proxyHost, proxyPort, origin);

        System.out.println();
        System.out.println("=== 结果：" + passed + " 通过 / " + failed + " 失败 ===");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ---------------------------------------------------------------- 用例

    /** 1) health()：CONNECT 打洞 + 相对路径请求行 + JSON 解析。 */
    private static void testHealth(String h, int p, String origin) {
        section("health() 经隧道");
        try {
            SttClient c = new SttClient(origin, "").withHttpProxy(h, p).withReadTimeout(5000);
            long t0 = System.nanoTime();
            boolean ok = c.health();
            long ms = (System.nanoTime() - t0) / 1_000_000;
            check("health() 返回 true", ok, "实际=" + ok);
            check("tunnelMode() 为真", c.tunnelMode(), "tunnelMode=" + c.tunnelMode());
            check("耗时 < 3000ms（本机回环）", ms < 3000, ms + "ms");
        } catch (Throwable t) {
            fail("health() 抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 2) 裸 GET：Content-Length 与 chunked 两种响应都要读对。 */
    private static void testGetAndChunked(String h, int p, String origin) {
        section("GET /somefile（Content-Length） 与 /chunked（Transfer-Encoding: chunked）");
        try {
            SttClient c = new SttClient(origin, "").withHttpProxy(h, p).withReadTimeout(5000);

            SttClient.Response r = c.exec("GET", "/somefile", null, null, null);
            check("状态码 200", r.code == 200, "code=" + r.code + " line=" + r.statusLine);
            check("状态行形如 HTTP/1.x 200",
                    r.statusLine.startsWith("HTTP/1.") && r.statusLine.contains("200"), r.statusLine);
            check("body 内容逐字正确", "hello from origin\n".equals(r.text()),
                    "实际=[" + r.text().replace("\n", "\\n") + "]");
            check("Content-Length 头存在", r.header("content-length") != null,
                    "content-length=" + r.header("content-length"));
            check("读到的字节数 == Content-Length",
                    r.header("content-length") != null
                            && Long.parseLong(r.header("content-length")) == r.bytes.length,
                    "len=" + r.bytes.length + " header=" + r.header("content-length"));

            SttClient.Response ch = c.exec("GET", "/chunked", null, null, null);
            check("chunked 状态码 200", ch.code == 200, "code=" + ch.code);
            check("响应头是 chunked",
                    ch.header("transfer-encoding") != null
                            && ch.header("transfer-encoding").toLowerCase().contains("chunked"),
                    "transfer-encoding=" + ch.header("transfer-encoding"));
            check("chunked body 拼接正确（含跨块中文）",
                    ch.text().contains("\"chunked\": true")
                            && ch.text().contains("分块来的中文")
                            && ch.text().contains("\"n\": 3"),
                    "body=" + ch.text());
            System.out.println("  · chunked body = " + ch.text().trim());
        } catch (Throwable t) {
            fail("GET 抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 3) 3xx 跟随（显式实现，不靠 HttpURLConnection 的隐式行为）。 */
    private static void testRedirect(String h, int p, String origin) {
        section("302 跟随");
        try {
            SttClient c = new SttClient(origin, "").withHttpProxy(h, p).withReadTimeout(5000);
            SttClient.Response r = c.exec("GET", "/redirect", null, null, null);
            check("跟随 302 后拿到 200", r.code == 200, "code=" + r.code + " line=" + r.statusLine);
            check("跟随后的 body 是 /somefile 的内容", "hello from origin\n".equals(r.text()), "body=" + r.text());
        } catch (Throwable t) {
            fail("302 跟随抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 4) 报文体 POST：小 body + 回读，校验服务端收到的字节数。 */
    private static void testSmallPost(String h, int p, String origin) {
        section("POST /echo（小 body）");
        try {
            SttClient c = new SttClient(origin, "").withHttpProxy(h, p).withReadTimeout(5000);
            byte[] payload = "{\"hello\":\"隧道\"}".getBytes(StandardCharsets.UTF_8);
            Map<String, String> hd = new LinkedHashMap<>();
            hd.put("Content-Type", "application/json");
            SttClient.Response r = c.exec("POST", "/echo", hd, SttClient.bytesBody(payload), null);
            check("POST 状态码 200", r.code == 200, "code=" + r.code);
            String want = "bytes=" + payload.length;
            check("服务端收到的字节数正确（" + payload.length + "B，UTF-8 中文 3 字节/字）",
                    r.text().contains(want), "返回=" + r.text().trim() + " 期望含 " + want);
            System.out.println("  · 回执: " + r.text().trim());
        } catch (Throwable t) {
            fail("POST 抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 5) submit()：真实 multipart + 进度回调。 */
    private static void testSubmitMultipart(String h, int p, String origin) {
        section("submit() multipart 上传 + 进度回调");
        File tmp = null;
        try {
            tmp = File.createTempFile("recstt-tunnel-", ".mp3");
            byte[] audio = new byte[100 * 1024];
            for (int i = 0; i < audio.length; i++) {
                audio[i] = (byte) (i * 31 + 7);
            }
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(audio);
            }

            SttClient c = new SttClient(origin, "").withHttpProxy(h, p).withReadTimeout(10_000);
            final int[] calls = {0};
            final long[] lastSent = {-1};
            final long[] lastTotal = {-1};
            final boolean[] monotonic = {true};
            String id = c.submit(tmp, "zh", new SttClient.UploadProgress() {
                @Override
                public void onBytes(long sent, long total) {
                    calls[0]++;
                    if (sent < lastSent[0]) {
                        monotonic[0] = false;
                    }
                    lastSent[0] = sent;
                    lastTotal[0] = total;
                }
            });
            check("拿到任务 id（202 的 id 字段）", "job-1".equals(id), "id=" + id);
            check("进度回调被调用超过 2 次", calls[0] > 2, "calls=" + calls[0]);
            check("进度单调不减", monotonic[0], "monotonic=" + monotonic[0]);
            check("最后一次 sent == total 且 total > 音频本身",
                    lastSent[0] == lastTotal[0] && lastTotal[0] > audio.length,
                    "sent=" + lastSent[0] + " total=" + lastTotal[0] + " audio=" + audio.length);

            SttClient.Response info = c.exec("GET", "/multipart-info", null, null, null);
            String body = info.text();
            check("源站确实收到了 multipart 请求体", info.code == 200 && body.contains("\"bytes\""), body);
            check("含 file 字段", body.contains("\"has_file_field\": true"), body);
            check("含 language 字段", body.contains("\"has_language_field\": true"), body);
            check("结尾 --boundary-- 完整（没被截断）", body.contains("\"has_end_boundary\": true"), body);
            System.out.println("  · 源站回执: " + body.trim());
            System.out.println("  · 进度回调 " + calls[0] + " 次，最终 " + lastSent[0] + "/" + lastTotal[0] + " 字节");
        } catch (Throwable t) {
            fail("submit() 抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        } finally {
            if (tmp != null) {
                tmp.delete();
            }
        }
    }

    /** 6) status()/result()：真实业务解析路径。 */
    private static void testStatusAndResult(String h, int p, String origin) {
        section("status() / result() 解析");
        try {
            SttClient c = new SttClient(origin, "").withHttpProxy(h, p).withReadTimeout(5000);
            SttClient.JobStatus st = c.status("job-1");
            check("state=done", "done".equals(st.state), st.toString());
            check("progress=1.0", Math.abs(st.progress - 1.0) < 1e-9, st.toString());
            check("segments=2", st.segments == 2, st.toString());
            check("isDone()", st.isDone(), st.toString());

            StringBuilder text = new StringBuilder();
            List<Seg> segs = c.result("job-1", 42L, text);
            check("result 文本正确", "你好，世界".equals(text.toString()), "text=" + text);
            check("result 分段数=2", segs.size() == 2, "size=" + segs.size());
            if (segs.size() == 2) {
                Seg s0 = segs.get(0);
                check("seg0 startMs=500", s0.startMs == 500, "startMs=" + s0.startMs);
                check("seg0 endMs=1250", s0.endMs == 1250, "endMs=" + s0.endMs);
                check("seg0 recId 回填=42", s0.recId == 42L, "recId=" + s0.recId);
                check("seg1 text=世界", "世界".equals(segs.get(1).text), "text=" + segs.get(1).text);
            }

            try {
                c.status("missing");
                fail("status(missing) 应该抛 404 SttException，但没抛");
            } catch (SttClient.SttException e) {
                check("404 翻成人话", e.code == 404 && e.human.contains("任务不存在"),
                        "code=" + e.code + " human=" + e.human);
            }
        } catch (Throwable t) {
            fail("status/result 抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 7) cancel()：200→true，409→false。 */
    private static void testCancel(String h, int p, String origin) {
        section("cancel()");
        try {
            SttClient c = new SttClient(origin, "").withHttpProxy(h, p).withReadTimeout(5000);
            check("cancel(job-1) = true（200）", c.cancel("job-1"), "false");
            check("cancel(ended) = false（409 属正常）", !c.cancel("ended"), "true");
            check("cancel(blank) = false", !c.cancel("  "), "true");
        } catch (Throwable t) {
            fail("cancel() 抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 8) 代理拒绝 CONNECT（403）：错误必须是**人话**并带代理状态行。 */
    private static void testRejectedByProxy(String h, int p, String origin) {
        section("代理拒绝 CONNECT（--deny-port 18082 → 403）");
        try {
            SttClient c = new SttClient("http://127.0.0.1:18082", "")
                    .withHttpProxy(h, p).withReadTimeout(5000);
            c.health();
            fail("应该抛 SttException，但 health() 成功了");
        } catch (SttClient.SttException e) {
            check("抛的是 SttException", true, "");
            check("人话里带 403 Forbidden",
                    e.human != null && e.human.contains("403") && e.human.contains("Forbidden"),
                    "human=" + e.human);
            check("人话里带目标 authority", e.human.contains("127.0.0.1:18082"), "human=" + e.human);
            check("detail 里带完整状态行（没被吞）",
                    e.getMessage() != null && e.getMessage().contains("403 Forbidden"),
                    "detail=" + e.getMessage());
            System.out.println("  · human  : " + e.human);
            System.out.println("  · detail : " + e.getMessage());
            System.out.println("  · SttClient.human(e) → " + SttClient.human(e));
            check("SttClient.human() 给的是同一句人话",
                    SttClient.human(e).equals(e.human.trim()), SttClient.human(e));
        } catch (Throwable t) {
            fail("抛的不是 SttException 而是 " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 9) 后端不可达（代理回 502）：同样要是人话。 */
    private static void testUpstreamDown(String h, int p) {
        section("后端不可达 → 代理 502");
        try {
            SttClient c = new SttClient("http://127.0.0.1:18083", "")
                    .withHttpProxy(h, p).withReadTimeout(5000);
            c.health();
            fail("应该抛 SttException，但 health() 成功了");
        } catch (SttClient.SttException e) {
            check("人话里带 502 Bad Gateway", e.human != null && e.human.contains("502"), "human=" + e.human);
            System.out.println("  · human  : " + e.human);
            System.out.println("  · detail : " + e.getMessage());
        } catch (Throwable t) {
            fail("抛的不是 SttException 而是 " + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /** 10) 直连路径不变：不调 withHttpProxy 时仍然走 HttpURLConnection。 */
    private static void testDirectModeUnaffected(String origin) {
        section("直连路径（未设置 proxy）行为不变");
        try {
            SttClient c = new SttClient(origin, "").withReadTimeout(5000);
            check("tunnelMode() 为假", !c.tunnelMode(), "tunnelMode=" + c.tunnelMode());
            check("直连 health() 仍然 true", c.health(), "false");
            check("直连 status() 仍然可用", "done".equals(c.status("job-1").state), "state!=done");
            StringBuilder directText = new StringBuilder();
            c.result("job-1", 1L, directText);
            check("直连 result() 仍然可用", "你好，世界".equals(directText.toString()),
                    "text=" + directText);
        } catch (Throwable t) {
            fail("直连抛异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    /**
     * 11) 反向验证：让 HttpURLConnection 走 java.net.Proxy（就是改动前的写法），
     * 复现「绝对 URI 请求行 → 代理 400」的现场，证明隧道模式修的确实是这个问题。
     */
    private static void testProxyRejectsAbsoluteUri(String h, int p, String origin) {
        section("对照：HttpURLConnection + java.net.Proxy（改动前的写法）→ 代理 400");
        try {
            java.net.URL u = new java.net.URL(origin + "/v2/health");
            java.net.Proxy proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    java.net.InetSocketAddress.createUnresolved(h, p));
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) u.openConnection(proxy);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setRequestMethod("GET");
            int code = c.getResponseCode();
            c.disconnect();
            check("被代理拒绝（400），复现真机现象", code == 400, "code=" + code);
        } catch (Throwable t) {
            fail("对照组异常：" + t.getClass().getName() + ": " + t.getMessage());
        }
    }

    // ---------------------------------------------------------------- 断言脚手架

    private static void section(String title) {
        System.out.println();
        System.out.println("── " + title + " ──");
    }

    private static void check(String what, boolean ok, String detail) {
        if (ok) {
            passed++;
            System.out.println("  [PASS] " + what);
        } else {
            failed++;
            System.out.println("  [FAIL] " + what + "  → " + detail);
        }
    }

    private static void fail(String msg) {
        failed++;
        System.out.println("  [FAIL] " + msg);
    }
}
