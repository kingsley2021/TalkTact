package dev.goutou.wingman.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * 「从服务端拉模型列表」（`GET {baseUrl}/models`）。
 *
 * 纯锦上添花：填完接口地址和 Key 之后，省得凭记忆敲模型名。**手动输入永远保留** ——
 * 有的服务商直接把这个接口关了，而中转站常常会列出「一整份目录」（列出来 ≠ 你的 Key 能用、
 * 也不代表有余额），所以拉到的只是一份候选，不是真相。
 *
 * 解析与分类抽成纯函数（[modelsUrl] / [parseModels] / [looksNonChat]），因为真机上没法
 * 一家家服务商去试，只能靠单测把已知的几种形状钉住。
 */
internal object ModelList {

    /**
     * 把接口地址拼成 `/models` 的完整 URL。
     *
     * 和 [chatCompletionsUrl] 同一套规矩：末尾斜杠无所谓；已经写到 `/chat/completions` 就先摘掉。
     */
    fun modelsUrl(base: String): String {
        val u = base.trim().trimEnd('/').removeSuffix("/chat/completions").trimEnd('/')
        return "$u/models"
    }

    /**
     * 解析成模型名列表。见过的形状都认一下：
     * - OpenAI 与大多数中转：`{"data":[{"id":"gpt-4o",...},...]}`
     * - 少数服务商：`{"models":[...]}`、直接一个字符串数组 `["a","b"]`
     * - 数组元素也可能是对象：优先取 `id`，其次 `name` / `model`
     *
     * 认不出来就返回空列表（界面会提示「没解析出模型名」），**不抛** —— 拉列表失败不该是错误弹窗。
     */
    fun parseModels(body: String): List<String> {
        val root = Json.parse(body) ?: return emptyList()
        val candidates: List<JsonValue> =
            root.at("data").asArr()
                ?: root.at("models").asArr()
                ?: root.asArr()
                ?: return emptyList()
        val out = ArrayList<String>(candidates.size)
        for (c in candidates) {
            val id = c.asStr() ?: c.at("id").asStr() ?: c.at("name").asStr() ?: c.at("model").asStr()
            val t = id?.trim().orEmpty()
            if (t.isNotEmpty()) out.add(t)
        }
        return out.distinct()
    }

    /**
     * 「看着不像对话模型」——界面上把它们排到列表后面。
     *
     * 刻意**只分类、不过滤**：命名规则各家不同，谁也说不准某个怪名字是不是能对话，
     * 藏起来反而让人以为服务商没提供。
     */
    fun looksNonChat(id: String): Boolean {
        val s = id.lowercase()
        val marks = listOf(
            "embedding", "embed", "whisper", "tts", "audio", "speech", "dall-e", "dalle",
            "image", "moderation", "rerank", "bge", "clip", "stable-diffusion", "flux", "sora",
        )
        return marks.any { s.contains(it) }
    }

    /** 真去拉一次。失败抛 [LlmException]（文案和其他接口调用保持一致）。 */
    fun fetch(baseUrl: String, apiKey: String, timeoutMs: Int = 12_000): List<String> {
        if (baseUrl.isBlank()) throw LlmException("还没填接口地址", "到「设置 → 高级设置 → 接口地址」里填")
        if (apiKey.isBlank()) throw LlmException("还没填 API Key", "拉列表要带上 Key（多数服务商都校验）")
        val url = modelsUrl(baseUrl)
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            throw LlmException("接口地址不合法：${t.message}", "地址要带 http(s)://，一般写到 /v1")
        }
        try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim())

            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code in 200..299) return parseModels(body)

            val detail = Json.parse(body)?.at("error", "message").asStr() ?: body.take(160).replace('\n', ' ')
            throw when (code) {
                401, 403 -> LlmException("鉴权失败（HTTP $code）", "Key 不对，或这个 Key 没权限：$detail")
                404 -> LlmException("这个服务商没有 /models 接口（HTTP 404）", "手动填模型名即可：$detail")
                in 500..599 -> LlmException("服务端错误（HTTP $code）", detail)
                else -> LlmException("HTTP $code", detail)
            }
        } catch (e: LlmException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LlmException("拉取超时", "网络慢或接口没响应（当前 ${timeoutMs}ms）")
        } catch (e: IOException) {
            throw LlmException("网络错误：${e.message ?: e.javaClass.simpleName}", "检查手机网络")
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
