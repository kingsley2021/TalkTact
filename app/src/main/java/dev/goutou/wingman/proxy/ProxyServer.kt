package dev.goutou.wingman.proxy

import android.content.Context
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.llm.chatCompletionsUrl
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

/**
 * 本地代理：**API Key 只留在 App 进程里**。
 *
 * 数据流：
 * ```
 * 微信进程 ──(http://127.0.0.1:PORT/proxy/chat/completions + 随机 token)──▶ 本进程
 *          ──(真实接口 + 真实 Key)──▶ 服务商 ──原样把响应写回──▶ 微信进程
 * ```
 *
 * 它只做「转发」这一件事：不解析 body、不落盘、**不记正文**（只记「成功/失败 + 耗时 + 状态码」），
 * 免得把聊天内容又抄一份到别处。
 *
 * 安全上的三条硬约束：
 * 1. **只绑回环地址** —— 同网段的其他设备连不上；
 * 2. **必须带 token** —— 回环端口不等于「只有微信能连」，同机其它 App 也能连；没 token 就是白蹭额度；
 * 3. **路径写死** —— 不接受任意转发，否则本机任意 App 就能拿它当跳板。
 */
class ProxyServer(private val context: Context) {

    private var socket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val pool = Executors.newFixedThreadPool(4)

    @Volatile
    var running: Boolean = false
        private set

    /** 最近一次转发的结论（设置页显示用，只放在进程内存里）。 */
    @Volatile
    var lastResult: String? = null
        private set

    fun start(port: Int) {
        if (running) return
        val s = ServerSocket(port, 16, InetAddress.getLoopbackAddress())
        socket = s
        running = true
        acceptThread = Thread {
            while (running) {
                val client = try {
                    s.accept()
                } catch (t: Throwable) {
                    if (running) lastResult = "accept 失败：${t.message}"
                    break
                }
                runCatching { pool.execute { handle(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        }.also { it.isDaemon = true; it.name = "talktact-proxy"; it.start() }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        socket = null
        runCatching { acceptThread?.interrupt() }
        acceptThread = null
    }

    // ---------------- 单个连接 ----------------

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 70_000
            val input = BufferedInputStream(client.getInputStream())
            val output = BufferedOutputStream(client.getOutputStream())

            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(' ')
            if (parts.size < 2) return respond(output, 400, errorBody("请求行不合法"))

            val method = parts[0].uppercase()
            val path = parts[1].substringBefore('?')

            // 头部：读到空行为止
            val headers = LinkedHashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            val cfg = ConfigStore(context).load()

            // 健康检查：设置页那个「测试」按钮用的，不需要 token（它本来就只回一句 ok）
            if (path == ProxyProtocol.PATH_HEALTH) {
                return respond(output, 200, """{"ok":true,"port":${cfg.proxyPort}}""")
            }

            if (path != ProxyProtocol.PATH_CHAT) return respond(output, 404, errorBody("未知路径：$path"))
            if (method != "POST") return respond(output, 405, errorBody("只接受 POST"))
            if (!ProxyProtocol.isAuthorized(headers["authorization"], cfg.proxyToken)) {
                lastResult = "拒绝：token 不对（${System.currentTimeMillis() % 100000}）"
                return respond(output, 403, errorBody("token 不对"))
            }
            if (cfg.baseUrl.isBlank()) return respond(output, 502, errorBody("App 里还没填接口地址"))
            if (cfg.apiKey.isBlank()) return respond(output, 502, errorBody("App 里还没填 API Key"))

            val length = headers["content-length"]?.toIntOrNull() ?: 0
            val body = if (length > 0) ByteArray(length).also { readFully(input, it) } else ByteArray(0)

            val started = System.currentTimeMillis()
            val upstream = forward(cfg.baseUrl, cfg.apiKey, body, headers["content-type"])
            val cost = System.currentTimeMillis() - started
            lastResult = "转发 ${upstream.first} · ${cost}ms"
            respond(output, upstream.first, upstream.second)
        } catch (t: Throwable) {
            lastResult = "转发异常：${t.message}"
            runCatching { respond(BufferedOutputStream(client.getOutputStream()), 502, errorBody("代理异常：${t.message}")) }
        } finally {
            runCatching { client.close() }
        }
    }

    /** 真正的那一跳：带上真实 Key 打服务商，然后把响应原样还回去。 */
    private fun forward(baseUrl: String, apiKey: String, body: ByteArray, contentType: String?): Pair<Int, String> {
        val conn = URL(chatCompletionsUrl(baseUrl)).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 12_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType ?: "application/json; charset=utf-8")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.outputStream.use { it.write(body) }

            val code = conn.responseCode
            val stream: InputStream? = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            code to text
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // ---------------- 小工具 ----------------

    private fun respond(output: BufferedOutputStream, code: Int, body: String) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(if (code in 200..299) "OK" else "Error").append("\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        output.write(head.toByteArray(Charsets.UTF_8))
        output.write(bytes)
        output.flush()
    }

    /** 错误也包成 OpenAI 的形状，注入侧那套错误分类（401/404/429…）就能照常工作。 */
    private fun errorBody(message: String): String =
        """{"error":{"message":${quote(message)},"type":"proxy"}}"""

    private fun quote(s: String): String =
        '"' + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ") + '"'

    private fun readLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun readFully(input: BufferedInputStream, into: ByteArray) {
        var off = 0
        while (off < into.size) {
            val n = input.read(into, off, into.size - off)
            if (n < 0) break
            off += n
        }
    }
}
