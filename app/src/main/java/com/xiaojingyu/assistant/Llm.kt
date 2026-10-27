package com.xiaojingyu.assistant

import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** OpenAI 兼容接口的聊天客户端（DeepSeek / 硅基流动 / 阿里百炼 / OpenAI / 本地 Ollama 都可）。 */
class Llm(private val cfg: Cfg) {

    data class Msg(val role: String, val content: String)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun stream(
        messages: List<Msg>,
        onDelta: (String) -> Unit,
        onDone: (String) -> Unit,
        onError: (String) -> Unit
    ): Call? {
        if (cfg.apiKey.isBlank()) {
            onError("还没填 API Key，请在设置里填写")
            return null
        }
        val url = cfg.apiBase.trimEnd('/') + "/chat/completions"
        val body = buildString {
            append("{\"model\":").append(Json.quote(cfg.model))
            append(",\"stream\":true,\"temperature\":0.6,\"messages\":[")
            messages.forEachIndexed { i, m ->
                if (i > 0) append(',')
                append("{\"role\":").append(Json.quote(m.role))
                append(",\"content\":").append(Json.quote(m.content)).append('}')
            }
            append("]}")
        }

        val request = try {
            Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + cfg.apiKey)
                .header("Accept", "text/event-stream")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) {
            onError("API 地址不合法：" + cfg.apiBase)
            return null
        }

        val call = client.newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(c: Call, e: IOException) {
                onError("网络失败：" + (e.message ?: e.javaClass.simpleName))
            }

            override fun onResponse(c: Call, response: Response) {
                val sb = StringBuilder()
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val raw = try {
                            resp.body?.string()?.take(300) ?: ""
                        } catch (_: Throwable) {
                            ""
                        }
                        onError("模型返回 " + resp.code + "：" + raw)
                        return
                    }
                    val src = resp.body?.source()
                    if (src == null) {
                        onError("模型没有返回内容")
                        return
                    }
                    try {
                        while (!src.exhausted()) {
                            val line = src.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val payload = line.substring(5).trim()
                            if (payload.isEmpty() || payload == "[DONE]") continue
                            val delta = Json.field(payload, "content")
                            if (delta.isNotEmpty()) {
                                sb.append(delta)
                                onDelta(sb.toString())
                            }
                        }
                        onDone(sb.toString())
                    } catch (e: Throwable) {
                        if (sb.isNotEmpty()) onDone(sb.toString()) else onError("读取响应失败：" + (e.message ?: ""))
                    }
                }
            }
        })
        return call
    }
}
