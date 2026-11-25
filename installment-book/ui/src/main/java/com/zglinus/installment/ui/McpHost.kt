package com.zglinus.installment.ui

import com.zglinus.installment.core.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

/**
 * 本机对外暴露的 MCP 服务（只读）。
 * 支持 JSON-RPC 2.0：initialize / tools/list / tools/call。
 * 数据全部现算，不写库。
 */
object McpHost {

    @Volatile var running = false
        private set
    @Volatile var port = 8765
        private set
    @Volatile private var token = ""
    private var server: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile var lastError: String? = null
        private set

    @Volatile var localOnly = false
        private set

    fun start(port: Int, token: String, localOnly: Boolean = false, onError: (String) -> Unit = {}) {
        if (running && this.port == port && this.localOnly == localOnly) return
        stop()
        this.localOnly = localOnly
        this.port = port
        this.token = token
        try {
            val ss = java.net.ServerSocket()
            val addr = if (localOnly) java.net.InetAddress.getByName("127.0.0.1")
                       else java.net.InetAddress.getByName("0.0.0.0")
            ss.reuseAddress = true
            ss.bind(java.net.InetSocketAddress(addr, port), 32)
            server = ss
            running = true
            thread = Thread {
                while (running) {
                    try {
                        val client = ss.accept()
                        Thread { handleClient(client) }.start()
                    } catch (e: Exception) {
                        if (running) {
                            lastError = "accept: ${e.message}"
                            android.util.Log.e("McpHost", lastError!!)
                            onError(lastError!!)
                        }
                        break
                    }
                }
            }.also { it.isDaemon = true; it.start() }
        } catch (e: Exception) {
            running = false
            lastError = e.message ?: e.javaClass.simpleName
            android.util.Log.e("McpHost", "start($port) 失败: $lastError")
            onError(lastError!!)
        }
    }

    /** 每次回前台校验：没监听就重绑（端口改动、被系统回收都能自愈） */
    fun ensureRunning(port: Int, token: String, localOnly: Boolean = false) {
        if (running && this.port == port && this.localOnly == localOnly) return
        android.util.Log.w("McpHost", "ensureRunning: 未监听或端口变了（was=$running/$this.port → $port），重绑")
        start(port, token, localOnly)
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        thread = null
    }

    // ---------------- HTTP ----------------

    private fun handleClient(socket: Socket) {
        socket.use { s ->
            try {
                val input = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = input.readLine() ?: return
                var auth = ""
                var acceptHeader = ""
                var contentLength = 0
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                    val lower = line.lowercase()
                    if (lower.startsWith("authorization:")) auth = line.substringAfter(":").trim()
                    if (lower.startsWith("accept:")) acceptHeader = line.substringAfter(":").trim()
                    if (lower.startsWith("content-length:")) {
                        contentLength = line.substringAfter(":").trim().toIntOrNull() ?: 0
                    }
                }
                val method = requestLine.split(" ").firstOrNull() ?: "GET"
                android.util.Log.d("McpReq", "→ $requestLine accept=$acceptHeader auth=${if (auth.isBlank()) "-" else "有"}")
                val body = if (contentLength > 0) {
                    val buf = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = input.read(buf, read, contentLength - read)
                        if (r < 0) break
                        read += r
                    }
                    String(buf, 0, read)
                } else ""

                val out = s.getOutputStream()
                fun respond(
                    code: Int, json: String,
                    contentType: String = "application/json; charset=utf-8",
                    extra: Map<String, String> = emptyMap()
                ) {
                    val bytes = json.toByteArray()
                    val sb = StringBuilder()
                    sb.append("HTTP/1.1 $code ${if (code == 200) "OK" else "ERR"}\r\n")
                    sb.append("Content-Type: $contentType\r\n")
                    sb.append("Content-Length: ${bytes.size}\r\n")
                    sb.append("Connection: close\r\n")
                    extra.forEach { (k, v) -> sb.append("$k: $v\r\n") }
                    sb.append("\r\n")
                    out.write(sb.toString().toByteArray()); out.write(bytes); out.flush()
                }

                if (token.isNotBlank() && auth != "Bearer $token") {
                    android.util.Log.w("McpReq", "← 401 unauthorized")
                    respond(401, JSONObject().put("error", "unauthorized").toString())
                    return
                }

                val wantSse = (acceptHeader.contains("text/event-stream"))
                if (method == "GET") {
                    if (wantSse) {
                        val frame = "event: message\ndata: " + info().toString() + "\n\n"
                        respond(200, frame, "text/event-stream; charset=utf-8",
                            mapOf("Cache-Control" to "no-cache", "Mcp-Session-Id" to sessionId()))
                    } else respond(200, info().toString())
                    return
                }

                val rpc = runCatching { JSONObject(body) }.getOrNull()
                if (rpc == null) {
                    respond(400, JSONObject().put("error", "invalid json").toString()); return
                }
                val id = rpc.opt("id")
                val name = rpc.optString("method")
                if (name.startsWith("notifications/")) { respond(200, ""); return }

                android.util.Log.d("McpReq", "  rpc method=$name id=$id")
                val result = when (name) {
                    "initialize" -> JSONObject().apply {
                        put("protocolVersion", "2024-11-05")
                        put("capabilities", JSONObject().put("tools", JSONObject()))
                        put("serverInfo", JSONObject().put("name", "installment").put("version", "0.19"))
                    }
                    "tools/list" -> JSONObject().put("tools", JSONArray(tools()))
                    "tools/call" -> callTool(
                        rpc.optJSONObject("params")?.optString("name") ?: "",
                        rpc.optJSONObject("params")?.optJSONObject("arguments") ?: JSONObject()
                    )
                    else -> null
                }

                if (result == null) {
                    respond(200, JSONObject().apply {
                        put("jsonrpc", "2.0"); put("id", id)
                        put("error", JSONObject().put("code", -32601).put("message", "unknown method $name"))
                    }.toString())
                } else {
                    val payload = JSONObject().apply {
                        put("jsonrpc", "2.0"); put("id", id); put("result", result)
                    }.toString()
                    val sid = if (name == "initialize") sessionId() else currentSession
                    if (wantSse) {
                        val frame = "event: message\ndata: $payload\n\n"
                        respond(200, frame, "text/event-stream; charset=utf-8",
                            buildMap {
                                put("Cache-Control", "no-cache")
                                if (sid != null) put("Mcp-Session-Id", sid)
                            })
                    } else {
                        respond(200, payload, extra = if (sid != null) mapOf("Mcp-Session-Id" to sid) else emptyMap())
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("McpReq", "处理异常: ${e.message}")
                runCatching { s.getOutputStream().write("HTTP/1.1 500 ERR\r\nContent-Length: 0\r\n\r\n".toByteArray()) }
            }
        }
    }

    @Volatile private var currentSession: String? = null

    private fun sessionId(): String {
        val s = currentSession ?: java.util.UUID.randomUUID().toString().also { currentSession = it }
        return s
    }

    private fun info() = JSONObject().apply {
        put("name", "installment-mcp")
        put("readOnly", true)
        put("tools", JSONArray(tools().map { it.getString("name") }))
    }

    private fun tools(): List<JSONObject> = listOf(
        tool("summary", "当前预算与负债总览：收入/还款/固定支出/可支配/待还/已付利息", JSONObject()
            .put("type", "object").put("properties", JSONObject())),
        tool("plans", "在还分期计划列表：银行、剩余本金、每期、期数、年化", JSONObject()
            .put("type", "object").put("properties", JSONObject())),
        tool("upcoming", "接下来 N 次还款（默认 5）", JSONObject().put("type", "object")
            .put("properties", JSONObject().put("n", JSONObject().put("type", "integer"))))
    )

    private fun tool(name: String, desc: String, schema: JSONObject) = JSONObject()
        .put("name", name).put("description", desc)
        .put("inputSchema", schema)

    private fun callTool(name: String, args: JSONObject): JSONObject {
        val text = when (name) {
            "summary" -> JSONObject().apply {
                put("income", Repo.incomeTotal)
                put("repay", Repo.repay)
                put("fixedExpense", Repo.fixedExpense)
                put("disposable", Repo.disposable)
                put("remainingTotal", Repo.remainingTotal)
                put("paidInterest", Repo.paidInterestTotal)
                put("interestAllTime", Repo.interestAllTime)
            }.toString(2)
            "plans" -> JSONArray().apply {
                Repo.active.forEach { p ->
                    put(JSONObject().apply {
                        put("title", p.title.ifBlank { "${p.bank} ${p.tail}" })
                        put("bank", p.bank); put("tail", p.tail)
                        put("remainingPrincipal", p.perPrincipal * p.remainPeriods)
                        put("perPay", p.perPay)
                        put("periods", p.periods); put("paidPeriods", p.paidPeriods)
                        put("apr", p.apr)
                        put("nextDue", Calc.dueDates(
                            Calc.firstDue(p.startDate, p.statementDay, p.dueDay), p.periods
                        ).getOrNull(p.paidPeriods)?.toString() ?: "")
                    })
                }
            }.toString(2)
            "upcoming" -> JSONArray().apply {
                Repo.upcoming(args.optInt("n", 5)).forEach { t ->
                    put(JSONObject().put("date", t.first).put("bank", t.second).put("amount", t.third))
                }
            }.toString(2)
            else -> return JSONObject().apply {
                put("isError", true)
                put("content", JSONArray().put(JSONObject().put("type", "text").put("text", "未知工具 $name")))
            }
        }
        return JSONObject().apply {
            put("content", JSONArray().put(JSONObject().apply {
                put("type", "text"); put("text", text)
            }))
        }
    }
}
