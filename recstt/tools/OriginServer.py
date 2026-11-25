#!/usr/bin/env python3
"""测试用「源站」：既当普通静态站，也当一个小号 whisper v2 服务。

只跑在 127.0.0.1:18081，供 tools/SttTunnelTest.java 隔着 ConnectProxy 访问。
不参与 APK 构建。

路由：
  GET    /somefile                → 200 text/plain，固定内容（带 Content-Length）
  GET    /chunked                 → 200 application/json，**Transfer-Encoding: chunked**
  GET    /redirect                → 302 → /somefile
  POST   /echo                    → 200 text/plain "bytes=N sha256=..."（校验请求体原样到达）
  GET    /v2/health               → {"ok": true, ...}
  POST   /v2/jobs                 → 202 {"id":"job-1",...}，记下 multipart 的体积和 sha256
  GET    /v2/jobs/job-1           → {"state":"done",...}
  GET    /v2/jobs/job-1/result    → {"text":...,"segments":[...]}
  DELETE /v2/jobs/job-1           → 200 {"ok":true}
  DELETE /v2/jobs/ended           → 409 {"detail":"已经结束"}
  GET    /v2/jobs/missing         → 404
  GET    /multipart-info          → 上一次 POST /v2/jobs 的体积/sha256/字段（测试断言用）
"""

import hashlib
import json
import sys
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT = "127.0.0.1", 18081
SOMEFILE = "hello from origin\n"
STATE = {}
LOCK = threading.Lock()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "OriginTest/1.0"

    # ---- 工具 ----

    def log_message(self, fmt, *args):
        sys.stderr.write("origin %s - %s\n" % (self.address_string(), fmt % args))

    def send_plain(self, code, text, ctype="text/plain; charset=utf-8"):
        body = text.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(body)
        self.close_connection = True

    def send_json(self, code, obj):
        self.send_plain(code, json.dumps(obj, ensure_ascii=False), "application/json; charset=utf-8")

    def send_chunked_json(self, code, obj):
        body = json.dumps(obj, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Transfer-Encoding", "chunked")
        self.send_header("Connection", "close")
        self.end_headers()
        # 拆成 3 块，故意让边界落在多字节 UTF-8 字符中间
        cuts = [0, 7, len(body) - 5, len(body)]
        for a, b in zip(cuts, cuts[1:]):
            piece = body[a:b]
            if not piece:
                continue
            self.wfile.write(b"%x\r\n" % len(piece) + piece + b"\r\n")
        self.wfile.write(b"0\r\nX-Trailer: test\r\n\r\n")
        self.close_connection = True

    def read_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        data = b""
        while len(data) < n:
            chunk = self.rfile.read(n - len(data))
            if not chunk:
                break
            data += chunk
        return data

    # ---- 路由 ----

    def do_GET(self):
        path = self.path.split("?", 1)[0]
        if path == "/somefile":
            self.send_plain(200, SOMEFILE)
        elif path == "/chunked":
            self.send_chunked_json(200, {"chunked": True, "text": "分块来的中文", "n": 3})
        elif path == "/redirect":
            body = b"moved\n"
            self.send_response(302)
            self.send_header("Location", "/somefile")
            self.send_header("Content-Type", "text/plain")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True
        elif path == "/v2/health":
            self.send_json(200, {"ok": True, "backend": "origin-test", "version": "v2"})
        elif path == "/v2/jobs/job-1":
            self.send_json(200, {"state": "done", "progress": 1.0, "segments_count": 2,
                                 "eta": None, "error": None})
        elif path == "/v2/jobs/job-1/result":
            self.send_json(200, {
                "text": "你好，世界",
                "segments": [
                    {"start": 0.5, "end": 1.25, "text": "你好"},
                    {"start": 1.25, "end": 2.0, "text": "世界"},
                ],
            })
        elif path == "/v2/jobs/missing":
            self.send_json(404, {"detail": "没有这个任务"})
        elif path == "/multipart-info":
            with LOCK:
                self.send_json(200, dict(STATE))
        else:
            self.send_plain(404, "no such path: %s\n" % path)

    def do_POST(self):
        path = self.path.split("?", 1)[0]
        data = self.read_body()
        if path == "/echo":
            self.send_plain(200, "bytes=%d sha256=%s\n" % (len(data), hashlib.sha256(data).hexdigest()))
        elif path == "/v2/jobs":
            ctype = self.headers.get("Content-Type") or ""
            boundary = ""
            if "boundary=" in ctype:
                boundary = ctype.split("boundary=", 1)[1].split(";")[0].strip().strip('"')
            with LOCK:
                STATE.clear()
                STATE.update({
                    "bytes": len(data),
                    "sha256": hashlib.sha256(data).hexdigest(),
                    "content_type": ctype,
                    "boundary": boundary,
                    "has_file_field": b'name="file"' in data,
                    "has_language_field": b'name="language"' in data,
                    "has_end_boundary": ("--" + boundary + "--").encode() in data,
                    "tail": data[-12:].decode("latin-1"),
                })
            self.send_json(202, {"id": "job-1", "state": "queued"})
        else:
            self.send_plain(404, "no such path: %s\n" % path)

    def do_DELETE(self):
        path = self.path.split("?", 1)[0]
        if path == "/v2/jobs/job-1":
            self.send_json(200, {"ok": True, "canceled": "job-1"})
        elif path == "/v2/jobs/ended":
            self.send_json(409, {"detail": "任务已经结束，无法取消"})
        else:
            self.send_json(404, {"detail": "没有这个任务"})


def main():
    srv = ThreadingHTTPServer((HOST, PORT), Handler)
    srv.daemon_threads = True
    sys.stderr.write("origin listening on %s:%d\n" % (HOST, PORT))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
