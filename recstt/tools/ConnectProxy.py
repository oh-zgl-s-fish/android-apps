#!/usr/bin/env python3
"""最小 HTTP 代理：**只支持 CONNECT**，用于本地验证 SttClient 的隧道模式。

行为对齐 wireproxy（其 README: "currently only CONNECT is supported"）：

* 收到 `CONNECT host:port HTTP/1.1` → 连上游，成功回
  `HTTP/1.1 200 Connection Established`，然后双向原样转发字节；
* 收到任何**非 CONNECT** 请求（例如 HttpURLConnection 走 Proxy 时发的
  `GET http://host/path HTTP/1.1` 绝对 URI 请求行）→ 回 400 并记一笔，
  这就是现状「健康检查都过不去」的根因，本地也能复现；
* 上游连不上 → 回 502，状态行会原样出现在客户端的报错里；
* `--deny-port` 指定的端口一律 403（用来验「代理拒绝」这条分支）。

用法：
    python3 tools/ConnectProxy.py --port 25399 --log /tmp/proxy.log
    python3 tools/ConnectProxy.py --port 25399 --deny-port 1337

注意：这是测试用的脚手架，不是产品代码，不参与 APK 构建。
"""

import argparse
import socket
import sys
import threading
import time

BUFSIZE = 65536


class Log:
    def __init__(self, path=None):
        self.path = path
        self.lock = threading.Lock()
        self.lines = []
        if path:
            open(path, "w", encoding="utf-8").close()

    def __call__(self, msg):
        line = "%s %s" % (time.strftime("%H:%M:%S"), msg)
        with self.lock:
            self.lines.append(line)
            print(line, flush=True)
            if self.path:
                with open(self.path, "a", encoding="utf-8") as f:
                    f.write(line + "\n")


def read_head(sock, limit=65536):
    """读到 \\r\\n\\r\\n 为止，返回 (head_bytes, rest_bytes)。"""
    buf = b""
    while b"\r\n\r\n" not in buf and b"\n\n" not in buf:
        chunk = sock.recv(BUFSIZE)
        if not chunk:
            return buf, b""
        buf += chunk
        if len(buf) > limit:
            raise ValueError("request head too long")
    idx = buf.find(b"\r\n\r\n")
    sep = 4
    if idx < 0:
        idx = buf.find(b"\n\n")
        sep = 2
    return buf[:idx], buf[idx + sep:]


def pipe(src, dst, tag, log):
    """单向转发直到 EOF，然后半关闭对端。"""
    total = 0
    try:
        while True:
            data = src.recv(BUFSIZE)
            if not data:
                break
            dst.sendall(data)
            total += len(data)
    except OSError as e:
        log("%s pipe error: %s" % (tag, e))
    finally:
        try:
            dst.shutdown(socket.SHUT_WR)
        except OSError:
            pass
        log("%s closed after %d bytes" % (tag, total))


def handle(conn, addr, deny_ports, log, cid):
    conn.settimeout(30)
    try:
        head, rest = read_head(conn)
    except Exception as e:
        log("[%d] bad request head: %s" % (cid, e))
        conn.close()
        return

    text = head.decode("latin-1", "replace")
    lines = text.split("\r\n")
    request_line = lines[0] if lines else ""
    log("[%d] >>> %s" % (cid, request_line))

    parts = request_line.split()
    if len(parts) < 3:
        reply(conn, "400 Bad Request", "malformed request line")
        return

    method, target, version = parts[0].upper(), parts[1], parts[2]

    if method != "CONNECT":
        # 这就是 wireproxy 不认的那种请求（绝对 URI 请求行）
        log("[%d] NON-CONNECT %s target=%r → 400 (proxy only supports CONNECT)" % (cid, method, target))
        reply(conn, "400 Bad Request",
              "this proxy only supports CONNECT (got %s %s)" % (method, target))
        return

    host, _, port_s = target.rpartition(":")
    if not host or not port_s.isdigit():
        log("[%d] bad CONNECT target %r" % (cid, target))
        reply(conn, "400 Bad Request", "bad CONNECT target: %s" % target)
        return
    port = int(port_s)
    host = host.strip("[]")

    if port in deny_ports:
        log("[%d] CONNECT %s:%d → 403 (denied by --deny-port)" % (cid, host, port))
        reply(conn, "403 Forbidden", "target port %d is denied by this proxy" % port)
        return

    try:
        up = socket.create_connection((host, port), timeout=5)
    except OSError as e:
        log("[%d] CONNECT %s:%d upstream failed: %s → 502" % (cid, host, port, e))
        reply(conn, "502 Bad Gateway", "cannot reach %s:%d (%s)" % (host, port, e))
        return

    log("[%d] CONNECT %s:%d → 200 Connection Established" % (cid, host, port))
    conn.sendall(b"HTTP/1.1 200 Connection Established\r\n"
                 b"Proxy-Agent: ConnectProxy-test/1.0\r\n\r\n")
    if rest:
        up.sendall(rest)
    conn.settimeout(None)
    up.settimeout(None)

    t = threading.Thread(target=pipe, args=(conn, up, "[%d] c→s" % cid, log), daemon=True)
    t.start()
    pipe(up, conn, "[%d] s→c" % cid, log)
    t.join(timeout=5)
    try:
        up.close()
    except OSError:
        pass


def reply(conn, status, detail):
    body = (detail + "\n").encode("utf-8")
    conn.sendall(("HTTP/1.1 %s\r\n"
                  "Content-Type: text/plain; charset=utf-8\r\n"
                  "Content-Length: %d\r\n"
                  "Connection: close\r\n\r\n" % (status, len(body))).encode("latin-1") + body)
    try:
        conn.shutdown(socket.SHUT_WR)
    except OSError:
        pass
    conn.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25399)
    ap.add_argument("--deny-port", type=int, action="append", default=[])
    ap.add_argument("--log", default=None)
    args = ap.parse_args()

    log = Log(args.log)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(64)
    log("CONNECT-only proxy listening on %s:%d (deny=%s)" % (args.host, args.port, args.deny_port))

    cid = 0
    try:
        while True:
            conn, addr = srv.accept()
            cid += 1
            log("accept #%d from %s:%d" % (cid, addr[0], addr[1]))
            threading.Thread(target=handle,
                             args=(conn, addr, set(args.deny_port), log, cid),
                             daemon=True).start()
    except KeyboardInterrupt:
        log("bye")


if __name__ == "__main__":
    sys.exit(main())
