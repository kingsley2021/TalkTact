package dev.goutou.wingman.llm

import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.wechat.ChatMsg
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class LlmResult(val suggestion: Suggestion, val totalTokens: Int, val millis: Long, val model: String)

/**
 * OpenAI 兼容接口客户端。
 *
 * 刻意用 HttpURLConnection 而不是 OkHttp：这段代码会被注入进微信进程，
 * 而微信自己也打包了 okhttp/okio，parent-first 的类加载会先拿到它那份，
 * 版本不一致就是 NoSuchMethodError。系统 API 没有撞车问题，也不用多带依赖。
 */
class LlmClient(private val cfg: ConfigData) {

    fun analyze(msgs: List<ChatMsg>): LlmResult {
        if (cfg.apiKey.isBlank()) throw LlmException("还没填 API Key", "到「设置」里填地址和 Key")
        if (msgs.isEmpty()) throw LlmException("没读到聊天内容", null)

        val start = System.currentTimeMillis()
        val fields = LinkedHashMap<String, JsonValue>()
        fields["model"] = str(cfg.model)
        fields["temperature"] = num(cfg.temperature)
        // 0 = 无限制：干脆不传这个参数，交给服务端默认
        if (cfg.maxTokens > 0) fields["max_tokens"] = num(cfg.maxTokens)
        fields["messages"] = arr(
            listOf(
                obj("role" to str("system"), "content" to str(cfg.prompt)),
                obj(
                    "role" to str("user"),
                    "content" to str(
                        "聊天记录（时间顺序，最后一条是对方刚发的）：\n${msgs.asTranscript()}\n\n" +
                            "只输出系统要求的那个 JSON 对象，不要任何解释文字。",
                    ),
                ),
            ),
        )
        val payload = Json.encode(JsonValue.Obj(fields))
        val root = parseResponse(post(endpoint(cfg.baseUrl), payload))
        val content = root.at("choices", "0", "message", "content").asStr()
            ?: throw LlmException("返回里没有 choices[0].message.content", "确认模型名是否可用、该接口是否兼容 OpenAI 格式")
        val tokens = root.at("usage", "total_tokens").asInt() ?: 0
        return LlmResult(
            suggestion = SuggestionParser.parse(content),
            totalTokens = tokens,
            millis = System.currentTimeMillis() - start,
            model = root.at("model").asStr() ?: cfg.model,
        )
    }

    /** 首页「接口自检」调它：真发一次最小请求，把延迟和用量报回来。 */
    fun probe(): LlmResult {
        if (cfg.apiKey.isBlank()) throw LlmException("还没填 API Key", "到「设置」里填地址和 Key")
        val start = System.currentTimeMillis()
        val payload = Json.encode(
            obj(
                "model" to str(cfg.model),
                "max_tokens" to num(48),
                "messages" to arr(
                    listOf(
                        obj("role" to str("system"), "content" to str("你是联通性测试。收到任何输入都只回复这一个 JSON：{\"intent\":\"ok\",\"risk\":\"低\",\"note\":\"自检\",\"replies\":[{\"style\":\"稳妥\",\"text\":\"联通正常\"}]}")),
                        obj("role" to str("user"), "content" to str("ping")),
                    ),
                ),
            ),
        )
        val root = parseResponse(post(endpoint(cfg.baseUrl), payload))
        val content = root.at("choices", "0", "message", "content").asStr()
            ?: throw LlmException("接口通了，但返回结构不是 OpenAI 格式", "中转站可能改了返回体")
        return LlmResult(
            suggestion = SuggestionParser.parse(content),
            totalTokens = root.at("usage", "total_tokens").asInt() ?: 0,
            millis = System.currentTimeMillis() - start,
            model = root.at("model").asStr() ?: cfg.model,
        )
    }

    private fun parseResponse(body: String) = run {
        val root = Json.parse(body)
            ?: throw LlmException("接口返回的不是 JSON（HTTP 200）", "可能是中转站返回了 HTML 错误页")
        root.at("error", "message").asStr()?.let { throw LlmException("接口报错：$it", null) }
        root
    }

    private fun endpoint(base: String): String {
        val u = base.trim().trimEnd('/')
        if (u.isEmpty()) throw LlmException("接口地址是空的", "示例：https://api.openai.com/v1")
        return if (u.endsWith("/chat/completions")) u else "$u/chat/completions"
    }

    /** 失败重试一次（只对超时/5xx/429 这种「可能只是碰巧」的错误）。 */
    private fun post(url: String, payload: String): String {
        var last: LlmException? = null
        for (attempt in 0..1) {
            try {
                return once(url, payload)
            } catch (e: LlmException) {
                last = e
                if (!e.retryable || attempt == 1) break
                try {
                    Thread.sleep(500L * (attempt + 1))
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        throw last ?: LlmException("请求失败", null)
    }

    private fun once(url: String, payload: String): String {
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            throw LlmException("接口地址不合法：${t.message}", "地址要带 http(s)://，且写到 /v1")
        }
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return body

            val detail = Json.parse(body)?.at("error", "message").asStr() ?: body.take(160).replace('\n', ' ')
            throw when (code) {
                401, 403 -> LlmException("鉴权失败（HTTP $code）", "Key 不对，或这个 Key 没有该模型的权限：$detail")
                404 -> LlmException("接口地址 404", "地址一般要写到 /v1，例如 https://api.openai.com/v1")
                429 -> LlmException("被限流了（HTTP 429）", "等几秒再点「重新识别」，或把「最短调用间隔」调大", retryable = true)
                in 500..599 -> LlmException("服务端错误（HTTP $code）", detail, retryable = true)
                else -> LlmException("HTTP $code", detail)
            }
        } catch (e: SocketTimeoutException) {
            throw LlmException("请求超时", "网络慢或接口没响应（当前 12s 连接 / 60s 读取）", retryable = true)
        } catch (e: IOException) {
            throw LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", "检查手机网络；需要代理的接口在微信进程里可能连不上", retryable = true)
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
