package dev.goutou.wingman.llm

import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.wechat.ChatMsg
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

data class LlmResult(val suggestion: Suggestion, val totalTokens: Int, val millis: Long, val model: String)

/**
 * 「接口自检」的结果：**只报连通性和延迟**，不含任何模型回复内容。
 *
 * 三段耗时分开量，好判断慢在哪一段：DNS 解析 / 裸 TCP 握手 / 一次完整往返。
 */
data class ProbeResult(
    val host: String?,
    val targetIp: String?,
    val dnsMs: Long,
    /** 裸 TCP 握手；null = 没测到（前置代理拦裸 TCP 很正常，不代表接口不通） */
    val connectMs: Long?,
    val totalMs: Long,
    val model: String,
    val totalTokens: Int,
)

/**
 * OpenAI 兼容接口客户端。
 *
 * 刻意用 HttpURLConnection 而不是 OkHttp：这段代码会被注入进微信进程，
 * 而微信自己也打包了 okhttp/okio，parent-first 的类加载会先拿到它那份，
 * 版本不一致就是 NoSuchMethodError。系统 API 没有撞车问题，也不用多带依赖。
 */
class LlmClient(
    private val cfg: ConfigData,
    /** 把「这次实际发出去的东西」交出去存档，null = 不记 */
    private val onTrace: ((String) -> Unit)? = null,
) {

    /**
     * @param roleContext 「角色」页攒下来的背景（TA 是你什么人 / 平时的关系 / 更早的聊天记录）。
     *   刻意拼进 user 消息而不是 system：skill 提示词要保持原样（App 里显示的字长、
     *   「最近一次调用」里核对的那份都是它），角色背景属于「这一次的素材」。
     */
    fun analyze(msgs: List<ChatMsg>, roleContext: String? = null): LlmResult {
        if (cfg.apiKey.isBlank()) throw LlmException("还没填 API Key", "到「设置」里填地址和 Key")
        if (msgs.isEmpty()) throw LlmException("没读到聊天内容", null)

        val start = System.currentTimeMillis()
        // 先落成两个局部变量：trace 要能原样看到「发出去的是什么」，不能只看 cfg
        val systemText = cfg.prompt
        // 顺序刻意是「跨请求不变的东西在前、每次都变的东西在后」：
        // system（skill）→ 角色档案 + 说话风格 → 聊天记录。
        // 这样同一个联系人的前两次之后，前缀就一直一样，能吃到服务端的**前缀缓存**
        // （OpenAI / DeepSeek 是按前缀自动命中的，命中价差一个数量级）。
        val userText = buildString {
            if (!roleContext.isNullOrBlank()) append(roleContext).append("\n\n")
            append("聊天记录（时间顺序，最后一条是对方刚发的）：\n").append(msgs.asTranscript())
            append("\n\n只输出系统要求的那个 JSON 对象，不要任何解释文字。")
        }
        val fields = LinkedHashMap<String, JsonValue>()
        fields["model"] = str(cfg.model)
        fields["temperature"] = num(cfg.temperature)
        // 0 = 无限制：干脆不传这个参数，交给服务端默认
        if (cfg.maxTokens > 0) fields["max_tokens"] = num(cfg.maxTokens)
        fields["messages"] = arr(
            listOf(
                obj("role" to str("system"), "content" to str(systemText)),
                obj("role" to str("user"), "content" to str(userText)),
            ),
        )
        val payload = Json.encode(JsonValue.Obj(fields))
        try {
            val root = parseResponse(post(endpoint(cfg.baseUrl), payload))
            val content = root.at("choices", "0", "message", "content").asStr()
                ?: throw LlmException("返回里没有 choices[0].message.content", "确认模型名是否可用、该接口是否兼容 OpenAI 格式")
            trace(systemText, userText, content)
            val tokens = root.at("usage", "total_tokens").asInt() ?: 0
            return LlmResult(
                suggestion = SuggestionParser.parse(content),
                totalTokens = tokens,
                millis = System.currentTimeMillis() - start,
                model = root.at("model").asStr() ?: cfg.model,
            )
        } catch (t: Throwable) {
            trace(systemText, userText, "（请求失败）${t.message}")
            throw t
        }
    }

    /**
     * 通用的一次文本调用（目前只有「把我的说话风格提炼成 skill」用它）。
     *
     * 和 [analyze] 的区别：不要求模型返回 JSON，直接把 content 原文给回去。
     * 复用同一套 endpoint / post / 错误处理，省得两份请求代码各自漂移。
     */
    fun complete(systemText: String, userText: String): Pair<String, Int> {
        if (cfg.apiKey.isBlank()) throw LlmException("还没填 API Key", "到「设置」里填地址和 Key")
        val fields = LinkedHashMap<String, JsonValue>()
        fields["model"] = str(cfg.model)
        // 提炼风格不需要发散：温度压低，免得每次结果跳来跳去
        fields["temperature"] = num(0.3)
        if (cfg.maxTokens > 0) fields["max_tokens"] = num(cfg.maxTokens)
        fields["messages"] = arr(
            listOf(
                obj("role" to str("system"), "content" to str(systemText)),
                obj("role" to str("user"), "content" to str(userText)),
            ),
        )
        val root = parseResponse(post(endpoint(cfg.baseUrl), Json.encode(JsonValue.Obj(fields))))
        val content = root.at("choices", "0", "message", "content").asStr()
            ?: throw LlmException("返回里没有 choices[0].message.content", "确认模型名是否可用、该接口是否兼容 OpenAI 格式")
        return content to (root.at("usage", "total_tokens").asInt() ?: 0)
    }

    /** 把这次实际发出去的 system 提示词 / user 消息 / 模型原始返回交给调用方存档。 */
    private fun trace(systemText: String, userText: String, response: String) {
        val cb = onTrace ?: return
        val body = buildString {
            append("system 长度 = ${systemText.length} 字\n")
            append("-------- system 开头 300 字 --------\n")
            append(systemText.take(300))
            if (systemText.length > 300) append("\n…（后面省略 ${systemText.length - 300} 字）")
            append("\n\n-------- user 消息 --------\n").append(userText)
            append("\n\n-------- 模型原始返回 --------\n").append(response.take(1200))
        }
        runCatching { cb(body) }
    }


    /**
     * 「接口自检」用的一次最小请求 —— **只验连通性和延迟**。
     *
     * 和 [analyze] 的三点区别，都是刻意的：
     *
     * ① 请求最小：**只有一句 `user: "ping"`**，既不拼当前 skill 也不拼角色档案 ——
     *    自检不该受它们影响（它跟「你配了哪套提示词」一点关系都没有）；
     * ② `max_tokens = 1`：只让服务端吐一个 token，尽量不花钱；
     * ③ **不看模型回了什么**：HTTP 2xx 且返回是 JSON 就算通。
     *
     * 第 ③ 条是这次修的重点。以前这里拿模型回复去跑 `SuggestionParser.parse()`，
     * 于是「模型话多了 / 被 max_tokens 截断 / 没按 JSON 契约回」都会被报成**接口不通** ——
     * 那是模型听不听话的问题，不是连接的问题。想验模型是否按契约回复，去「试一试」页。
     */
    fun probe(): ProbeResult {
        if (cfg.apiKey.isBlank()) throw LlmException("还没填 API Key", "到「设置 → 高级设置」里填地址和 Key")
        val url = endpoint(cfg.baseUrl)
        val host = NetInfo.hostOf(url)

        // ① DNS 解析耗时（顺便拿到目标 IP）
        val dnsStart = System.currentTimeMillis()
        val targetIp = NetInfo.resolve(host)
        val dnsMs = System.currentTimeMillis() - dnsStart

        // ② 裸 TCP 握手：这才是「连接延迟」
        val connectMs = NetInfo.tcpConnectMs(host, NetInfo.portOf(url))

        // ③ 一次最小往返
        val payload = Json.encode(
            obj(
                "model" to str(cfg.model),
                "max_tokens" to num(1),
                "messages" to arr(
                    listOf(obj("role" to str("user"), "content" to str("ping"))),
                ),
            ),
        )
        val start = System.currentTimeMillis()
        val root = parseResponse(post(url, payload))
        return ProbeResult(
            host = host,
            targetIp = targetIp,
            dnsMs = dnsMs,
            connectMs = connectMs,
            totalMs = System.currentTimeMillis() - start,
            model = root.at("model").asStr() ?: cfg.model,
            totalTokens = root.at("usage", "total_tokens").asInt() ?: 0,
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
