#!/usr/bin/env bash
# 一条命令跑完隧道模式的本地验收：起「只支持 CONNECT」的代理 + 源站 → 跑真实 SttClient → 收摊。
#
#   ./tools/run-tunnel-test.sh
#
# 依赖：python3、JDK17（SttClient 的目标字节码版本）。不需要 Android SDK，不需要联网。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME:-/usr/lib64/jvm/java-17-openjdk}"
JAVAC="$JDK/bin/javac"
JAVA="$JDK/bin/java"
OUT="${TMPDIR:-/tmp}/recstt-tunnel-test"
PROXY_PORT="${PROXY_PORT:-25399}"
ORIGIN_PORT="${ORIGIN_PORT:-18081}"
DENY_PORT="${DENY_PORT:-18082}"     # 代理回 403 的端口（验「代理拒绝」）
DEAD_PORT="${DEAD_PORT:-18083}"     # 没人监听（验「后端不可达 → 502」）

if [ ! -x "$JAVAC" ]; then
  echo "找不到 JDK17 的 javac：$JAVAC（用 JAVA_HOME=... 指定）" >&2
  exit 2
fi

rm -rf "$OUT"
mkdir -p "$OUT"

cleanup() {
  [ -n "${PROXY_PID:-}" ] && kill "$PROXY_PID" 2>/dev/null || true
  [ -n "${ORIGIN_PID:-}" ] && kill "$ORIGIN_PID" 2>/dev/null || true
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

echo "== 编译（JDK17，包含真实 SttClient + org.json 测试桩）=="
"$JAVAC" -encoding UTF-8 -Xlint:all -d "$OUT" \
  "$ROOT/src/com/zglinus/recstt/model/Seg.java" \
  "$ROOT/src/com/zglinus/recstt/net/SttClient.java" \
  "$ROOT"/tools/stub/org/json/*.java \
  "$ROOT/tools/SttTunnelTest.java"

echo "== 起源站 127.0.0.1:$ORIGIN_PORT =="
python3 "$ROOT/tools/OriginServer.py" >"$OUT/origin.log" 2>&1 &
ORIGIN_PID=$!

echo "== 起「只支持 CONNECT」的代理 127.0.0.1:$PROXY_PORT（deny=$DENY_PORT）=="
python3 "$ROOT/tools/ConnectProxy.py" --port "$PROXY_PORT" --deny-port "$DENY_PORT" \
  --log "$OUT/proxy.log" >"$OUT/proxy.stdout" 2>&1 &
PROXY_PID=$!

for _ in $(seq 1 50); do
  if python3 - "$PROXY_PORT" "$ORIGIN_PORT" <<'PY' 2>/dev/null
import socket, sys
for port in (int(sys.argv[1]), int(sys.argv[2])):
    s = socket.create_connection(("127.0.0.1", port), timeout=0.3)
    s.close()
PY
  then break; fi
  sleep 0.1
done

echo
"$JAVA" -cp "$OUT" com.zglinus.recstt.net.SttTunnelTest \
  "127.0.0.1" "$PROXY_PORT" "http://127.0.0.1:$ORIGIN_PORT"
RC=$?

echo
echo "== 代理侧的 CONNECT 记账（前 6 条）=="
grep -E "CONNECT |NON-CONNECT" "$OUT/proxy.log" 2>/dev/null | head -6 || true
echo "（完整日志：$OUT/proxy.log，源站日志：$OUT/origin.log）"

exit $RC
