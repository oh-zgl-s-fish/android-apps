package com.zglinus.installment.ui

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/** 最小可用的 chat/completions 调用（不引第三方库） */
object Llm {

    fun endpoint(base: String): String {
        val b = base.trim().trimEnd('/')
        if (b.isEmpty()) return ""
        return if (b.endsWith("/chat/completions")) b
        else if (b.endsWith("/v1")) "$b/chat/completions"
        else "$b/v1/chat/completions"
    }

    fun complete(
        base: String, key: String, model: String, system: String, user: String,
        imageB64: String? = null, imageMime: String? = null
    ): String {
        val url = URL(endpoint(base))
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
        }
        val body = JSONObject().apply {
            put("model", model.ifBlank { "deepseek-chat" })
            put("messages", JSONArray().apply {
                put(JSONObject().put("role", "system").put("content", system))
                val content = if (imageB64 == null) user
                else JSONArray().apply {
                    put(JSONObject().put("type", "text").put("text", user))
                    put(JSONObject().put("type", "image_url").put("image_url", JSONObject()
                        .put("url", "data:${imageMime ?: "image/jpeg"};base64,$imageB64")))
                }
                put(JSONObject().put("role", "user").put("content", content))
            })
            put("temperature", 0.3)
        }
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(stream)).readText()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(200)}")
        val json = JSONObject(text)
        return json.optJSONArray("choices")?.optJSONObject(0)
            ?.optJSONObject("message")?.optString("content")
            ?: throw IllegalStateException("返回里没有 choices")
    }
}
