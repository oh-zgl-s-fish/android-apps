package com.zglinus.recstt.net;

import com.zglinus.recstt.model.Seg;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * whisper v2 接口的极简客户端：直连只用 HttpURLConnection + org.json，不引第三方。
 *
 * <p>接口（已用真机同网络的 curl 实测过）：
 * <ul>
 *   <li>GET /v2/health → {"ok":true,...}</li>
 *   <li>POST /v2/jobs (multipart: file + language) → 202 {"id","state",...}</li>
 *   <li>GET /v2/jobs/{id} → {"state","progress","segments_count","eta","error"}</li>
 *   <li>GET /v2/jobs/{id}/result → {"text","segments":[{"start","end","text"}]}</li>
 *   <li>DELETE /v2/jobs/{id} → 任务已结束时返回 409（属正常，不算失败）</li>
 * </ul>
 *
 * <p>两条路：
 * <ul>
 *   <li><b>直连</b>（默认，{@link #withHttpProxy(String,int)} 没被调用）：HttpURLConnection 原样发请求，行为与以前完全一致；</li>
 *   <li><b>隧道</b>（内置 WG 的 wireproxy，127.0.0.1:25344）：wireproxy 的 HTTP 代理**只认 CONNECT**
 *       （README: "currently only CONNECT is supported"），HttpURLConnection 走 Proxy 时对 http:// 目标发的是
 *       绝对 URI 请求行，wireproxy 直接不认，健康检查都过不去。所以这里不用 Proxy，而是自己
 *       {@code CONNECT host:port} 打洞，再在裸 socket 上手写 HTTP/1.1（请求行用相对路径），
 *       响应按 Content-Length / chunked / 读到 EOF 三种情况解析。</li>
 * </ul>
 *
 * <p>设计要点：
 * <ul>
 *   <li>上传用 multipart 手写边界 + Content-Length，边写边回调，32KB 一块，不把整段音频读进内存；</li>
 *   <li>读超时默认 10 分钟（长音频转写时服务端可能迟迟不返回），轮询可以 {@link #withReadTimeout(int)} 单独调短；</li>
 *   <li>失败一律抛 IOException 子类：HTTP 错误抛 {@link SttException} 并带一句给用户看的人话（human），
 *       网络层异常（UnknownHost/ConnectException/超时）原样抛出，由调用方按类型归因，避免把堆栈丢给用户。</li>
 * </ul>
 */
public final class SttClient {

  /** 上传进度回调。sent/total 都是整个 multipart 请求体的字节数（含头部），所以最后一定是 total==total。 */
  public interface UploadProgress {
    void onBytes(long sent, long total);
  }

  /** GET /v2/jobs/{id} 的解析结果。字段名对齐接口文档，progress 是 0..1。 */
  public static final class JobStatus {
    public String state = "";
    public double progress;
    public int segments;
    public String error;
    public Long etaSec;

    public boolean isDone() {
      return "done".equals(state);
    }

    public boolean isFailed() {
      return "failed".equals(state) || "canceled".equals(state);
    }

    public boolean isTerminal() {
      return isDone() || isFailed();
    }

    @Override
    public String toString() {
      return "JobStatus{state=" + state + ", progress=" + progress + ", segments=" + segments
          + ", eta=" + etaSec + ", error=" + error + "}";
    }
  }

  /**
   * 客户端自己判定的失败：HTTP 状态码不对劲、返回体不是 JSON、缺字段。
   * human 一定是一句可以直接给人看的中文，message（detail）留技术细节给 logcat。
   * 隧道模式下「代理不让 CONNECT」也走这里（code=0，human/detail 里带代理的状态行）。
   */
  public static class SttException extends IOException {
    private static final long serialVersionUID = 1L;

    /** HTTP 状态码；0 表示错误跟 HTTP 状态无关（地址不对/返回体不是 JSON/隧道被拒之类）。 */
    public final int code;
    /** 给用户看的一句话。 */
    public final String human;

    public SttException(int code, String human, String detail) {
      super(detail == null || detail.isEmpty() ? human : detail);
      this.code = code;
      this.human = human;
    }

    public SttException(int code, String human) {
      this(code, human, human);
    }
  }

  /** 默认读超时：10 分钟，长音频转写够用。 */
  public static final int READ_TIMEOUT_LONG_MS = 10 * 60 * 1000;
  /** 建连超时：局域网 15 秒足够，太短会在弱 wifi 上误报。 */
  private static final int CONNECT_TIMEOUT_MS = 15 * 1000;
  /** 隧道模式连代理（127.0.0.1）的超时：本机回环，5 秒连不上就是 wireproxy 没起来。 */
  private static final int TUNNEL_CONNECT_TIMEOUT_MS = 5 * 1000;
  /** 上传分块 32KB，正好也是「一次回调」的粒度。 */
  private static final int CHUNK = 32 * 1024;
  /** 响应体最多读这么多，防止被奇怪的服务端拖死。 */
  private static final int MAX_BODY = 256 * 1024;
  /** 隧道模式下单行状态行/响应头的上限，防止对端灌垃圾。 */
  private static final int MAX_LINE = 8 * 1024;
  /** 隧道模式下最多跟几次 3xx，跟 HttpURLConnection 的默认口径对齐（但这里是显式的）。 */
  private static final int MAX_REDIRECTS = 5;
  private static final String UA = "recstt/0.1 (Android; HttpURLConnection)";

  private final String base;
  private final String token;
  private int readTimeoutMs = READ_TIMEOUT_LONG_MS;
  /**
   * 隧道模式：CONNECT 到本机 HTTP 代理（wireproxy）。port<=0 = 直连。
   * 直连时不用 java.net.Proxy —— 那条路对 http:// 目标发绝对 URI，wireproxy 不认。
   */
  private String tunnelHost;
  private int tunnelPort;

  public SttClient(String baseUrl, String token) {
    this.base = normalizeBase(baseUrl);
    this.token = token == null ? "" : token.trim();
  }

  /**
   * 让请求改走「本机 HTTP 代理 + CONNECT 隧道」（内置 WG 隧道）。port<=0 表示回到直连。
   * 签名不变，只是语义从「HttpURLConnection 的 Proxy」换成「自己打 CONNECT 隧道」。
   */
  public SttClient withHttpProxy(String host, int port) {
    if (port <= 0) {
      this.tunnelHost = null;
      this.tunnelPort = 0;
    } else {
      String h = host == null ? "" : host.trim();
      this.tunnelHost = h.isEmpty() ? "127.0.0.1" : h;
      this.tunnelPort = port;
    }
    return this;
  }

  /** 当前是否走隧道模式（端点探测/日志用）。 */
  public boolean tunnelMode() {
    return tunnelPort > 0;
  }

  /** 规范化后的基地址（已去掉尾部斜杠和误填的 /v2）。 */
  public String baseUrl() {
    return base;
  }

  /**
   * 单独调整读超时（轮询用短超时，上传/取结果用默认 10 分钟）。
   * 隧道模式下同时也是 CONNECT 之后那条 socket 的 SO_TIMEOUT。
   * 返回 this 方便链式调用。
   */
  public SttClient withReadTimeout(int ms) {
    if (ms > 0) {
      this.readTimeoutMs = ms;
    }
    return this;
  }

  // ---------------------------------------------------------------- 三个业务接口

  /** GET /v2/health。HTTP 不正常会抛 SttException（401 → 鉴权失败），传输层错误原样抛。 */
  public boolean health() throws IOException {
    if (tunnelMode()) {
      Response r = exec("GET", "/v2/health", null, null, null);
      return parseHealth(r.code, r.text());
    }
    HttpURLConnection c = open("/v2/health", "GET");
    try {
      int code = c.getResponseCode();
      return parseHealth(code, readText(c, code));
    } finally {
      c.disconnect();
    }
  }

  /**
   * POST /v2/jobs 上传音频，返回服务端任务 id。
   *
   * <p>进度回调会先报一次「写完头部」的字节数，然后每写 32KB 报一次，最后再报一次 100%。
   * 回调里抛 RuntimeException 可以直接中断上传（前台服务用它响应「取消」）。
   */
  public String submit(File f, String lang, UploadProgress p) throws IOException {
    if (f == null) {
      throw new SttException(0, "没有可上传的音频文件", "file=null");
    }
    if (!f.isFile()) {
      throw new SttException(0, "找不到音频文件：" + f.getName(), "not a file: " + f.getAbsolutePath());
    }
    if (f.length() <= 0) {
      throw new SttException(0, "音频文件是空的，没法转写", "empty file: " + f.getAbsolutePath());
    }

    final String language = (lang == null || lang.trim().isEmpty()) ? "zh" : lang.trim();
    final String boundary =
        "----recstt" + Long.toHexString(System.nanoTime()) + Long.toHexString(Double.doubleToLongBits(Math.random()));
    final String filename = safeFilename(f);
    final String type = contentTypeFor(filename);

    byte[] langHead = utf8("--" + boundary
        + "\r\nContent-Disposition: form-data; name=\"language\"\r\n\r\n");
    byte[] langVal = utf8(language + "\r\n");
    byte[] fileHead = utf8("--" + boundary
        + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
        + "Content-Type: " + type + "\r\n\r\n");
    byte[] tail = utf8("\r\n--" + boundary + "--\r\n");
    MultipartBody body = new MultipartBody(langHead, langVal, fileHead, f, tail);

    if (tunnelMode()) {
      Map<String, String> h = new LinkedHashMap<>();
      h.put("Content-Type", "multipart/form-data; boundary=" + boundary);
      Response r = exec("POST", "/v2/jobs", h, body, p);
      return parseJobId(r.code, r.text());
    }

    URL url = url("/v2/jobs");
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    try {
      c.setConnectTimeout(CONNECT_TIMEOUT_MS);
      c.setReadTimeout(readTimeoutMs);
      c.setRequestMethod("POST");
      c.setDoOutput(true);
      c.setUseCaches(false);
      c.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
      c.setRequestProperty("Accept", "application/json");
      c.setRequestProperty("User-Agent", UA);
      auth(c);
      long total = body.length();
      if (total <= Integer.MAX_VALUE) {
        c.setFixedLengthStreamingMode((int) total);
      } else {
        c.setFixedLengthStreamingMode(total);
      }

      OutputStream raw = null;
      try {
        raw = c.getOutputStream();
        BufferedOutputStream out = new BufferedOutputStream(raw, CHUNK);
        body.writeTo(out, p);
        out.close(); // close 才会把请求真正发完
        raw = null;
      } finally {
        closeQuietly(raw);
      }

      int code = c.getResponseCode();
      return parseJobId(code, readText(c, code));
    } finally {
      c.disconnect();
    }
  }

  /** GET /v2/jobs/{id}。 */
  public JobStatus status(String jobId) throws IOException {
    if (tunnelMode()) {
      Response r = exec("GET", "/v2/jobs/" + pathEncode(jobId), null, null, null);
      return parseStatus(r.code, r.text());
    }
    HttpURLConnection c = open("/v2/jobs/" + pathEncode(jobId), "GET");
    try {
      int code = c.getResponseCode();
      return parseStatus(code, readText(c, code));
    } finally {
      c.disconnect();
    }
  }

  /**
   * GET /v2/jobs/{id}/result，秒（浮点）转毫秒填 Seg。
   *
   * @param recId   调用方给的录音 id，直接写进每个 Seg
   * @param textOut 非空时写入整段文本（服务端 text 字段；缺失时用分段拼）
   */
  public List<Seg> result(String jobId, long recId, StringBuilder textOut) throws IOException {
    if (tunnelMode()) {
      Response r = exec("GET", "/v2/jobs/" + pathEncode(jobId) + "/result", null, null, null);
      return parseResult(r.code, r.text(), recId, textOut);
    }
    HttpURLConnection c = open("/v2/jobs/" + pathEncode(jobId) + "/result", "GET");
    try {
      int code = c.getResponseCode();
      return parseResult(code, readText(c, code), recId, textOut);
    } finally {
      c.disconnect();
    }
  }

  /**
   * DELETE /v2/jobs/{id}。
   *
   * @return true=服务端受理了取消；false=任务本来就已经结束（404/409，属正常，不当失败）
   */
  public boolean cancel(String jobId) throws IOException {
    if (jobId == null || jobId.trim().isEmpty()) {
      return false;
    }
    String path = "/v2/jobs/" + pathEncode(jobId.trim());
    if (tunnelMode()) {
      Response r = exec("DELETE", path, null, null, null);
      return parseCancel(r.code, r.text());
    }
    HttpURLConnection c = open(path, "DELETE");
    try {
      int code = c.getResponseCode();
      return parseCancel(code, readText(c, code));
    } finally {
      c.disconnect();
    }
  }

  // ---------------------------------------------------------------- 响应解析（两条路共用）

  private boolean parseHealth(int code, String body) throws SttException {
    if (code / 100 != 2) {
      throw httpError(code, body, "自检");
    }
    String s = body == null ? "" : body.trim();
    if (s.isEmpty()) {
      return true; // 有 2xx 就算通，不苛求返回体
    }
    if (s.charAt(0) != '{') {
      throw new SttException(0, "服务器返回的不是 JSON，地址可能指到了别的服务", clip(s, 200));
    }
    try {
      JSONObject o = new JSONObject(s);
      if (o.has("ok") && !o.isNull("ok")) {
        return o.optBoolean("ok", true);
      }
      return true;
    } catch (JSONException e) {
      throw new SttException(0, "健康检查的返回解析失败", clip(s, 200));
    }
  }

  private String parseJobId(int code, String body) throws SttException {
    if (code / 100 != 2) {
      throw httpError(code, body, "提交任务");
    }
    JSONObject o = json(body, "提交任务");
    String id = o.optString("id", "").trim();
    if (id.isEmpty()) {
      throw new SttException(0, "服务器没给任务号（返回里没有 id）", clip(body, 300));
    }
    return id;
  }

  private JobStatus parseStatus(int code, String body) throws SttException {
    if (code / 100 != 2) {
      if (code == 404) {
        throw new SttException(404, "任务不存在（服务器可能重启过，重启会丢内存任务）", clip(body, 300));
      }
      throw httpError(code, body, "查任务状态");
    }
    JSONObject o = json(body, "查任务状态");
    JobStatus s = new JobStatus();
    s.state = o.optString("state", "");
    s.progress = o.optDouble("progress", 0);
    s.segments = o.optInt("segments_count", o.optInt("segments", 0));
    s.error = str(o, "error");
    if (o.has("eta") && !o.isNull("eta")) {
      double eta = o.optDouble("eta", -1);
      if (!Double.isNaN(eta) && eta >= 0 && eta < 24 * 3600) {
        s.etaSec = Long.valueOf(Math.round(eta));
      }
    }
    return s;
  }

  private List<Seg> parseResult(int code, String body, long recId, StringBuilder textOut)
      throws SttException {
    if (code / 100 != 2) {
      if (code == 409) {
        String d = detail(body);
        throw new SttException(409, d == null ? "任务还没转完，取不到结果" : "取不到结果：" + d, clip(body, 300));
      }
      throw httpError(code, body, "取转写结果");
    }
    JSONObject o = json(body, "取转写结果");

    String text = o.optString("text", "");
    if (text == null) {
      text = "";
    }
    text = text.trim();

    List<Seg> list = new ArrayList<>();
    JSONArray arr = o.optJSONArray("segments");
    if (arr != null) {
      for (int i = 0; i < arr.length(); i++) {
        JSONObject so = arr.optJSONObject(i);
        if (so == null) {
          continue;
        }
        String t = so.optString("text", "");
        if (t == null) {
          t = "";
        }
        t = t.trim();
        if (t.isEmpty()) {
          continue;
        }
        double startSec = so.optDouble("start", 0);
        double endSec = so.optDouble("end", 0);
        Seg s = new Seg();
        s.recId = recId;
        s.idx = i;
        s.startMs = clampMs(startSec);
        s.endMs = clampMs(endSec);
        if (s.endMs < s.startMs) {
          s.endMs = s.startMs;
        }
        s.text = t;
        list.add(s);
      }
    }

    if (text.isEmpty()) {
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < list.size(); i++) {
        if (sb.length() > 0) {
          sb.append('\n');
        }
        sb.append(list.get(i).text);
      }
      text = sb.toString();
    }
    if (textOut != null) {
      textOut.setLength(0);
      textOut.append(text);
    }
    if (list.isEmpty() && text.isEmpty()) {
      throw new SttException(0, "服务器返回的结果是空的（没有文本也没有分段）", clip(body, 300));
    }
    return list;
  }

  private boolean parseCancel(int code, String body) throws SttException {
    if (code / 100 == 2) {
      return true;
    }
    if (code == 404 || code == 409) {
      return false;
    }
    throw httpError(code, body, "取消任务");
  }

  // ---------------------------------------------------------------- 隧道：CONNECT + 手写 HTTP/1.1

  /** 一条完整的 HTTP 响应。包内可见，tools/ 下的桌面测试直接拿它断言。 */
  static final class Response {
    final int code;
    final String statusLine;
    final Map<String, List<String>> headers;
    final byte[] bytes;

    Response(int code, String statusLine, Map<String, List<String>> headers, byte[] bytes) {
      this.code = code;
      this.statusLine = statusLine;
      this.headers = headers;
      this.bytes = bytes;
    }

    String text() {
      return new String(bytes, StandardCharsets.UTF_8);
    }

    String header(String name) {
      if (headers == null || name == null) {
        return null;
      }
      List<String> v = headers.get(name.toLowerCase(Locale.US));
      return v == null || v.isEmpty() ? null : v.get(0);
    }
  }

  /** 请求体：长度已知（要发 Content-Length），可重复写（3xx 跟随/重试时才会重写）。 */
  interface Body {
    long length();

    void writeTo(OutputStream out, UploadProgress cb) throws IOException;
  }

  static Body bytesBody(final byte[] data) {
    final byte[] b = data == null ? new byte[0] : data;
    return new Body() {
      @Override
      public long length() {
        return b.length;
      }

      @Override
      public void writeTo(OutputStream out, UploadProgress cb) throws IOException {
        out.write(b);
        if (cb != null) {
          cb.onBytes(b.length, b.length);
        }
      }
    };
  }

  /**
   * 隧道模式下的一次请求：先 CONNECT 到代理，再在裸 socket 上手写 HTTP/1.1。
   * 包内可见，桌面测试（tools/SttTunnelTest.java）用它打任意 path/method。
   *
   * @param extra 额外请求头（Content-Type 之类），Host/Accept/UA/Authorization/Content-Length/Connection 由这里统一加
   * @param body  请求体，null=没有
   * @param cb    上传进度（只有带 body 时才会回调）
   */
  Response exec(String method, String path, Map<String, String> extra, Body body, UploadProgress cb)
      throws IOException {
    if (!tunnelMode()) {
      throw new SttException(0, "内部错误：没开隧道模式却走了隧道请求", "tunnelMode()=false");
    }
    URL target = url(path);
    String proto = target.getProtocol();
    if (!"http".equalsIgnoreCase(proto)) {
      // wireproxy 只做 CONNECT 明文转发；https 要在隧道里再包 TLS，当前目标地址都是明文 http。
      throw new SttException(0, "隧道模式只支持明文 http 地址（当前是 " + proto + "）：" + base,
          "unsupported scheme in tunnel: " + proto);
    }

    for (int hop = 0; ; hop++) {
      Response r = execOnce(method, target, extra, body, cb);
      String loc = r.header("location");
      if (r.code != 301 && r.code != 302 && r.code != 303 && r.code != 307 && r.code != 308) {
        return r;
      }
      if (loc == null || loc.trim().isEmpty()) {
        return r; // 没 Location 的 3xx 原样交给上层，让 httpError 报出来
      }
      if (hop >= MAX_REDIRECTS) {
        throw new SttException(r.code, "服务器一直重定向（超过 " + MAX_REDIRECTS + " 次），检查服务器地址",
            r.statusLine + " location=" + loc);
      }
      URL next;
      try {
        next = new URL(target, loc.trim());
      } catch (MalformedURLException e) {
        throw new SttException(r.code, "服务器给的重定向地址看不懂：" + clip(loc, 120), e.toString());
      }
      if (!"http".equalsIgnoreCase(next.getProtocol())) {
        throw new SttException(r.code, "隧道模式不支持重定向到 " + next.getProtocol() + "：" + clip(loc, 120),
            next.toString());
      }
      // 303（以及 301/302 的惯例）改成 GET 丢 body；307/308 保持原样重发。
      if (r.code == 303 || r.code == 301 || r.code == 302) {
        method = "GET";
        body = null;
      }
      target = next;
    }
  }

  /** 隧道模式下的一次 GET / POST，包内可见（业务接口之外的自检/调试口）。 */
  Response get(String path, Map<String, String> extra) throws IOException {
    return exec("GET", path, extra, null, null);
  }

  /** 带字节 body 的 POST；Content-Type 由 extra 给。 */
  Response post(String path, String contentType, byte[] body) throws IOException {
    Map<String, String> h = new LinkedHashMap<>();
    if (contentType != null && !contentType.isEmpty()) {
      h.put("Content-Type", contentType);
    }
    return exec("POST", path, h, bytesBody(body), null);
  }

  /** 一次 CONNECT + 一次请求/响应，不处理 3xx。 */
  private Response execOnce(String method, URL target, Map<String, String> extra, Body body,
      UploadProgress cb) throws IOException {
    String host = target.getHost();
    int port = target.getPort() > 0 ? target.getPort() : 80;
    String authority = authority(host, port);
    String pathQ = target.getFile();
    if (pathQ == null || pathQ.isEmpty()) {
      pathQ = "/";
    }

    Socket s = new Socket();
    try {
      s.connect(new InetSocketAddress(tunnelHost, tunnelPort), TUNNEL_CONNECT_TIMEOUT_MS);
      s.setSoTimeout(readTimeoutMs);
      InputStream in = new BufferedInputStream(s.getInputStream(), CHUNK);
      OutputStream out = new BufferedOutputStream(s.getOutputStream(), CHUNK);

      // 1) CONNECT：wireproxy 只认这个
      String connect = "CONNECT " + authority + " HTTP/1.1\r\n"
          + "Host: " + authority + "\r\n"
          + "Proxy-Connection: keep-alive\r\n"
          + "\r\n";
      out.write(utf8(connect));
      out.flush();

      String statusLine = readLine(in);
      if (statusLine == null) {
        throw new SttException(0, "隧道代理没有回应（CONNECT 连上了但没数据）",
            "no CONNECT response from " + tunnelHost + ":" + tunnelPort);
      }
      int connectCode = statusCode(statusLine);
      readHeaders(in); // CONNECT 的响应头读完（不管 200 还是错误，都得把这一轮读完）
      if (connectCode != 200) {
        // 状态行必须原样带出去，别吞
        throw new SttException(0, "隧道代理拒绝了到 " + authority + " 的连接（" + statusLine + "）",
            "CONNECT " + authority + " via " + tunnelHost + ":" + tunnelPort + " → " + statusLine);
      }

      // 2) 请求行用相对路径（wireproxy 后面的服务器只认这个，不是绝对 URI）
      StringBuilder head = new StringBuilder(256);
      head.append(method).append(' ').append(pathQ).append(" HTTP/1.1\r\n");
      head.append("Host: ").append(authority).append("\r\n");
      head.append("Accept: application/json\r\n");
      head.append("User-Agent: ").append(UA).append("\r\n");
      if (!token.isEmpty()) {
        head.append("Authorization: Bearer ").append(token).append("\r\n");
      }
      if (extra != null) {
        for (Map.Entry<String, String> e : extra.entrySet()) {
          if (e.getKey() != null && e.getValue() != null) {
            head.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
          }
        }
      }
      if (body != null) {
        head.append("Content-Length: ").append(body.length()).append("\r\n");
      }
      head.append("Connection: close\r\n\r\n");
      out.write(utf8(head.toString()));
      if (body != null) {
        body.writeTo(out, cb);
      }
      out.flush();

      // 3) 读响应（跳过 1xx 中间响应）
      String line = readLine(in);
      int code;
      Map<String, List<String>> headers;
      while (true) {
        if (line == null) {
          throw new SttException(0, "隧道里的服务器没回响应就断开了",
              "EOF before status line (method=" + method + " target=" + authority + pathQ + ")");
        }
        code = statusCode(line);
        headers = readHeaders(in);
        if (code >= 100 && code < 200 && code != 101) {
          line = readLine(in);
          continue;
        }
        statusLine = line;
        break;
      }

      // 4) 读 body：chunked / Content-Length / 读到 EOF
      String te = header(headers, "transfer-encoding");
      String cl = header(headers, "content-length");
      byte[] data;
      boolean noBody = "HEAD".equalsIgnoreCase(method) || code == 204 || code == 304;
      if (noBody) {
        data = new byte[0];
      } else if (te != null && te.toLowerCase(Locale.US).contains("chunked")) {
        data = readChunked(in);
      } else if (cl != null) {
        long len;
        try {
          len = Long.parseLong(cl.trim());
        } catch (NumberFormatException e) {
          throw new SttException(0, "服务器给的 Content-Length 看不懂：" + clip(cl, 40), "bad content-length: " + cl);
        }
        data = readFixed(in, Math.max(0, len));
      } else {
        data = readToEof(in);
      }
      return new Response(code, statusLine, headers, data);
    } finally {
      closeQuietly(s);
    }
  }

  /** 一行（CRLF 或 LF 结尾），用 ISO-8859-1 保住原始字节；EOF 且没内容返回 null。 */
  private static String readLine(InputStream in) throws IOException {
    ByteArrayOutputStream b = new ByteArrayOutputStream(128);
    int c;
    boolean any = false;
    while ((c = in.read()) != -1) {
      any = true;
      if (c == '\n') {
        break;
      }
      if (b.size() >= MAX_LINE) {
        throw new SttException(0, "代理/服务器返回的响应行太长（超过 " + MAX_LINE + " 字节）",
            "line longer than " + MAX_LINE);
      }
      b.write(c);
    }
    byte[] a = b.toByteArray();
    int n = a.length;
    if (n > 0 && a[n - 1] == '\r') {
      n--;
    }
    if (!any && n == 0) {
      return null;
    }
    return new String(a, 0, n, StandardCharsets.ISO_8859_1);
  }

  /** 读响应头到空行为止；键统一小写。 */
  private static Map<String, List<String>> readHeaders(InputStream in) throws IOException {
    Map<String, List<String>> h = new LinkedHashMap<>();
    int guard = 0;
    while (true) {
      String line = readLine(in);
      if (line == null) {
        throw new SttException(0, "响应头没读完连接就断了", "truncated response headers");
      }
      if (line.isEmpty()) {
        return h;
      }
      if (++guard > 200) {
        throw new SttException(0, "响应头行数太多（超过 200 行）", "too many header lines");
      }
      int colon = line.indexOf(':');
      if (colon <= 0) {
        continue;
      }
      String k = line.substring(0, colon).trim().toLowerCase(Locale.US);
      String v = line.substring(colon + 1).trim();
      List<String> l = h.get(k);
      if (l == null) {
        l = new ArrayList<>();
        h.put(k, l);
      }
      l.add(v);
    }
  }

  private static String header(Map<String, List<String>> headers, String name) {
    List<String> v = headers.get(name.toLowerCase(Locale.US));
    return v == null || v.isEmpty() ? null : v.get(0);
  }

  /** "HTTP/1.1 200 Connection Established" → 200。 */
  private static int statusCode(String statusLine) throws SttException {
    if (statusLine == null) {
      throw new SttException(0, "没收到 HTTP 状态行", "null status line");
    }
    String s = statusLine.trim();
    int sp = s.indexOf(' ');
    if (sp <= 0) {
      throw new SttException(0, "看不懂的 HTTP 状态行：" + clip(s, 80), "bad status line: " + clip(s, 120));
    }
    int i = sp + 1;
    while (i < s.length() && s.charAt(i) == ' ') {
      i++;
    }
    int start = i;
    while (i < s.length() && Character.isDigit(s.charAt(i))) {
      i++;
    }
    if (i == start || i - start > 3) {
      throw new SttException(0, "看不懂的 HTTP 状态行：" + clip(s, 80), "bad status line: " + clip(s, 120));
    }
    return Integer.parseInt(s.substring(start, i));
  }

  /** 固定长度 body：最多读 MAX_BODY；短读（对端提前关）直接报错，别把半截 JSON 当好数据。 */
  private static byte[] readFixed(InputStream in, long len) throws IOException {
    int want = (int) Math.min(len, MAX_BODY);
    byte[] out = new byte[want];
    int off = 0;
    while (off < want) {
      int n = in.read(out, off, want - off);
      if (n <= 0) {
        throw new SttException(0, "响应体没读满就断了（Content-Length=" + len + "，只收到 " + off + "）",
            "short body: " + off + "/" + len);
      }
      off += n;
    }
    return out;
  }

  private static byte[] readToEof(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
    byte[] buf = new byte[4096];
    while (out.size() < MAX_BODY) {
      int n = in.read(buf, 0, Math.min(buf.length, MAX_BODY - out.size()));
      if (n <= 0) {
        break;
      }
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  /** chunked：块长十六进制（可带 ;ext）→ 数据 → CRLF，0 块后跟 trailer。 */
  private static byte[] readChunked(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(1024);
    while (true) {
      String line = readLine(in);
      if (line == null) {
        throw new SttException(0, "chunked 响应体没读完连接就断了", "truncated chunked body");
      }
      int semi = line.indexOf(';');
      String hex = (semi >= 0 ? line.substring(0, semi) : line).trim();
      long size;
      try {
        size = Long.parseLong(hex, 16);
      } catch (NumberFormatException e) {
        throw new SttException(0, "chunked 响应体的块长度看不懂：" + clip(line, 60), "bad chunk size: " + clip(line, 60));
      }
      if (size < 0) {
        throw new SttException(0, "chunked 响应体的块长度是负数", "negative chunk size: " + size);
      }
      if (size == 0) {
        readHeaders(in); // trailer
        break;
      }
      int room = MAX_BODY - out.size();
      if (room <= 0) {
        break; // 到上限就不读了（和 readText 的上限口径一致）
      }
      int want = (int) Math.min(size, room);
      byte[] buf = new byte[want];
      int off = 0;
      while (off < want) {
        int n = in.read(buf, off, want - off);
        if (n <= 0) {
          throw new SttException(0, "chunked 响应体的数据没读满就断了",
              "short chunk: " + off + "/" + size);
        }
        off += n;
      }
      out.write(buf, 0, want);
      long skip = size - want;
      while (skip > 0) {
        long k = in.skip(skip);
        if (k <= 0) {
          if (in.read() < 0) {
            throw new SttException(0, "chunked 响应体没读完连接就断了", "EOF while skipping chunk");
          }
          skip--;
        } else {
          skip -= k;
        }
      }
      readLine(in); // 块尾 CRLF
    }
    return out.toByteArray();
  }

  /** Host 头/authority：非 80 端口要带端口，IPv6 字面量包方括号。 */
  private static String authority(String host, int port) {
    String h = host == null ? "" : host;
    if (h.indexOf(':') >= 0 && !h.startsWith("[")) {
      h = "[" + h + "]";
    }
    return port == 80 ? h : h + ":" + port;
  }

  // ---------------------------------------------------------------- 直连：HttpURLConnection

  private static String normalizeBase(String raw) {
    String s = raw == null ? "" : raw.trim();
    if (s.isEmpty()) {
      return "";
    }
    if (!s.contains("://")) {
      s = "http://" + s;
    }
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    if (s.endsWith("/v2")) {
      s = s.substring(0, s.length() - 3);
    }
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }

  private URL url(String path) throws IOException {
    if (base == null || base.isEmpty()) {
      throw new SttException(0, "没配置服务器地址（去设置里填）", "empty baseUrl");
    }
    try {
      return new URL(base + path);
    } catch (MalformedURLException e) {
      throw new SttException(0, "服务器地址不对：" + base, e.toString());
    }
  }

  /** 直连：建连 + 公共头。 */
  private HttpURLConnection open(String path, String method) throws IOException {
    URL u = url(path);
    HttpURLConnection c;
    try {
      java.net.URLConnection raw = u.openConnection();
      if (!(raw instanceof HttpURLConnection)) {
        throw new SttException(0, "只支持 http/https 地址：" + base, "not http: " + u);
      }
      c = (HttpURLConnection) raw;
    } catch (IOException e) {
      throw e;
    } catch (RuntimeException e) {
      throw new SttException(0, "服务器地址打不开：" + base, e.toString());
    }
    c.setConnectTimeout(CONNECT_TIMEOUT_MS);
    c.setReadTimeout(readTimeoutMs);
    c.setRequestMethod(method);
    c.setUseCaches(false);
    c.setRequestProperty("Accept", "application/json");
    c.setRequestProperty("User-Agent", UA);
    auth(c);
    return c;
  }

  /** token 非空才带 Authorization，空 token 时别乱加头（有的反代见到坏头直接 400）。 */
  private void auth(HttpURLConnection c) {
    if (!token.isEmpty()) {
      c.setRequestProperty("Authorization", "Bearer " + token);
    }
  }

  /** 读响应体（4xx/5xx 读 errorStream），最多 MAX_BODY，UTF-8。 */
  private static String readText(HttpURLConnection c, int code) {
    InputStream in = null;
    try {
      in = code >= 400 ? c.getErrorStream() : c.getInputStream();
      if (in == null) {
        return "";
      }
      ByteArrayOutputStream bos = new ByteArrayOutputStream(1024);
      byte[] buf = new byte[4096];
      int total = 0;
      int n;
      while ((n = in.read(buf)) > 0) {
        if (total + n > MAX_BODY) {
          int keep = MAX_BODY - total;
          if (keep > 0) {
            bos.write(buf, 0, keep);
          }
          break;
        }
        bos.write(buf, 0, n);
        total += n;
      }
      return new String(bos.toByteArray(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      // 读 body 失败不能盖掉真正的错误（比如 401）
      return "";
    } finally {
      closeQuietly(in);
    }
  }

  // ---------------------------------------------------------------- 内部工具

  /** multipart 请求体：长度固定，可重复写，进度按「写进输出流的字节数」报。 */
  private static final class MultipartBody implements Body {
    private final byte[] langHead;
    private final byte[] langVal;
    private final byte[] fileHead;
    private final File file;
    private final byte[] tail;
    private final long length;

    MultipartBody(byte[] langHead, byte[] langVal, byte[] fileHead, File file, byte[] tail) {
      this.langHead = langHead;
      this.langVal = langVal;
      this.fileHead = fileHead;
      this.file = file;
      this.tail = tail;
      this.length = (long) langHead.length + langVal.length + fileHead.length + file.length()
          + tail.length;
    }

    @Override
    public long length() {
      return length;
    }

    @Override
    public void writeTo(OutputStream out, UploadProgress cb) throws IOException {
      long sent = 0;
      out.write(langHead);
      sent += langHead.length;
      out.write(langVal);
      sent += langVal.length;
      out.write(fileHead);
      sent += fileHead.length;
      if (cb != null) {
        cb.onBytes(sent, length);
      }

      FileInputStream in = new FileInputStream(file);
      try {
        byte[] buf = new byte[CHUNK];
        int n;
        while ((n = in.read(buf)) > 0) {
          out.write(buf, 0, n);
          sent += n;
          if (cb != null) {
            cb.onBytes(sent, length);
          }
        }
      } finally {
        closeQuietly(in);
      }

      out.write(tail);
      sent += tail.length;
      out.flush();
      if (cb != null) {
        cb.onBytes(length, length);
      }
    }
  }

  private static JSONObject json(String body, String what) throws SttException {
    String s = body == null ? "" : body.trim();
    if (s.isEmpty()) {
      throw new SttException(0, "服务器返回了空内容（" + what + "）", "empty body for " + what);
    }
    if (s.charAt(0) != '{') {
      throw new SttException(0, "服务器返回的不是 JSON（" + what + "），地址可能指到了别的服务", clip(s, 300));
    }
    try {
      return new JSONObject(s);
    } catch (JSONException e) {
      throw new SttException(0, "服务器返回的 JSON 解析失败（" + what + "）", clip(s, 300));
    }
  }

  /** 把 HTTP 错误码翻成人话；4xx 优先用服务端给的 detail（服务器自己写的中文更好懂）。 */
  private static SttException httpError(int code, String body, String what) {
    String d = detail(body);
    String human;
    if (code == 401 || code == 403) {
      human = "鉴权失败（请检查 token）";
    } else if (code == 404) {
      human = "服务器上没有这个接口(404)，检查服务器地址是不是少写了路径";
    } else if (code >= 500) {
      human = "服务出错(" + code + ")" + (d == null ? "" : "：" + d);
    } else if (d != null) {
      human = "服务器拒绝了请求：" + d;
    } else {
      human = "请求被拒绝(HTTP " + code + ")" + (what == null || what.isEmpty() ? "" : "（" + what + "）");
    }
    String detail = "HTTP " + code + (what == null ? "" : " [" + what + "]")
        + (body == null || body.isEmpty() ? "" : " body=" + clip(body, 300));
    return new SttException(code, human, detail);
  }

  /** 取 FastAPI 的 {"detail": "..."} 或 {"error": "..."}，非字符串（422 是数组）就 Rat 掉。 */
  static String detail(String body) {
    String s = body == null ? "" : body.trim();
    if (s.isEmpty() || s.charAt(0) != '{') {
      return null; // HTML 错误页别往用户脸上贴
    }
    try {
      JSONObject o = new JSONObject(s);
      Object d = o.opt("detail");
      if (d instanceof String) {
        String t = clip((String) d, 80);
        return t.isEmpty() ? null : t;
      }
      Object e = o.opt("error");
      if (e instanceof String) {
        String t = clip((String) e, 80);
        return t.isEmpty() ? null : t;
      }
    } catch (JSONException ignore) {
      // 落到下面返回 null
    }
    return null;
  }

  private static String str(JSONObject o, String k) {
    if (!o.has(k) || o.isNull(k)) {
      return null;
    }
    String s = o.optString(k, "");
    if (s == null) {
      return null;
    }
    s = s.trim();
    return s.isEmpty() ? null : s;
  }

  private static int clampMs(double sec) {
    if (Double.isNaN(sec) || sec <= 0) {
      return 0;
    }
    double ms = sec * 1000.0;
    if (ms > Integer.MAX_VALUE) {
      return Integer.MAX_VALUE;
    }
    return (int) Math.round(ms);
  }

  private static String clip(String s, int max) {
    if (s == null) {
      return "";
    }
    String t = s.replace('\r', ' ').replace('\n', ' ').trim();
    if (t.length() > max) {
      t = t.substring(0, max) + "…";
    }
    return t;
  }

  private static String pathEncode(String id) {
    if (id == null) {
      return "";
    }
    try {
      return URLEncoder.encode(id, "UTF-8").replace("+", "%20");
    } catch (IOException e) {
      return id;
    }
  }

  /** 文件名只放 ASCII：中文名在部分 multipart 解析器/服务器落盘时会被写坏，任务只认字节。 */
  private static String safeFilename(File f) {
    String n = f == null ? "" : f.getName();
    if (n == null || n.isEmpty()) {
      n = "audio";
    }
    boolean ascii = true;
    for (int i = 0; i < n.length(); i++) {
      char ch = n.charAt(i);
      if (ch < 0x20 || ch > 0x7e || ch == '"' || ch == '\\' || ch == ';') {
        ascii = false;
        break;
      }
    }
    if (ascii) {
      return n;
    }
    String ext = "";
    int dot = n.lastIndexOf('.');
    if (dot > 0 && n.length() - dot <= 6) {
      String candidate = n.substring(dot).toLowerCase(Locale.US);
      boolean ok = true;
      for (int i = 1; i < candidate.length(); i++) {
        char ch = candidate.charAt(i);
        if (!((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9'))) {
          ok = false;
          break;
        }
      }
      if (ok) {
        ext = candidate;
      }
    }
    return "audio_" + Long.toHexString(System.currentTimeMillis()) + ext;
  }

  private static String contentTypeFor(String name) {
    String n = name == null ? "" : name.toLowerCase(Locale.US);
    if (n.endsWith(".mp3")) {
      return "audio/mpeg";
    }
    if (n.endsWith(".m4a") || n.endsWith(".mp4")) {
      return "audio/mp4";
    }
    if (n.endsWith(".wav")) {
      return "audio/wav";
    }
    if (n.endsWith(".amr")) {
      return "audio/amr";
    }
    if (n.endsWith(".ogg") || n.endsWith(".opus")) {
      return "audio/ogg";
    }
    if (n.endsWith(".flac")) {
      return "audio/flac";
    }
    if (n.endsWith(".aac")) {
      return "audio/aac";
    }
    if (n.endsWith(".3gp")) {
      return "audio/3gpp";
    }
    return "application/octet-stream";
  }

  private static byte[] utf8(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private static void closeQuietly(java.io.Closeable c) {
    if (c == null) {
      return;
    }
    try {
      c.close();
    } catch (IOException ignore) {
      // 关不了就算了
    }
  }

  /**
   * 传输层错误（还没拿到 HTTP 状态码就断了）：地址解析不了、连不上、超时、连接被重置。
   * 这几类重试有意义；HTTP 4xx 重试没意义。
   */
  public static boolean isTransportError(Throwable t) {
    return t instanceof java.net.UnknownHostException
        || t instanceof ConnectException
        || t instanceof SocketTimeoutException
        || t instanceof java.net.NoRouteToHostException
        || t instanceof java.net.SocketException;
  }

  /**
   * 异常 → 一句能给用户看的中文。放在客户端里而不是服务里，是因为设置页的「自检」也要这一套口径，
   * 免得同一个「连不上」在两处说法不一样；也保证永远不会把异常类名 / 堆栈丢到界面上。
   */
  public static String human(Throwable t) {
    if (t == null) {
      return "转写失败（原因未知）";
    }
    if (t instanceof SttException) {
      SttException se = (SttException) t;
      if (se.human != null && !se.human.trim().isEmpty()) {
        return se.human.trim();
      }
      return "转写失败";
    }
    if (t instanceof java.net.UnknownHostException) {
      return "连不上服务器（地址解析不到，检查 wifi/内网）";
    }
    if (t instanceof ConnectException) {
      return "连不上服务器（连接被拒绝，服务可能没开）";
    }
    if (t instanceof SocketTimeoutException) {
      return "等待超时";
    }
    if (t instanceof javax.net.ssl.SSLException) {
      return "连不上服务器（HTTPS 证书有问题）";
    }
    if (t instanceof java.net.NoRouteToHostException) {
      return "连不上服务器（网络不可达）";
    }
    if (t instanceof java.io.FileNotFoundException) {
      return "读不到音频文件（权限不足或文件已删）";
    }
    if (t instanceof java.net.SocketException) {
      return "连不上服务器（网络中断）";
    }
    if (isTransportError(t)) {
      return "连不上服务器";
    }
    if (t instanceof IOException) {
      return "网络错误，检查服务器地址和网络后重试";
    }
    return "转写失败（内部错误，详见 logcat）";
  }
}
