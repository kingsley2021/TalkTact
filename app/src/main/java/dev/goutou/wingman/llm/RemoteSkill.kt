package dev.goutou.wingman.llm

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 从 GitHub 拉一段 SKILL.md 当提示词。
 *
 * 只做两件事：取回纯文本 + 补齐 JSON 契约。
 * 不解析 front-matter：各家 skill 写法不一，硬解析比不解析更容易坏。
 */
object RemoteSkill {

    private const val MAX_CHARS = 12000

    /** 用户可能直接粘的是网页地址，顺手换成 raw 直链。 */
    fun toRaw(url: String): String {
        val u = url.trim()
        return if (u.contains("github.com") && u.contains("/blob/")) {
            u.replace("github.com", "raw.githubusercontent.com").replace("/blob/", "/")
        } else {
            u
        }
    }

    fun fetch(url: String): String {
        val target = toRaw(url)
        if (!target.startsWith("https://")) {
            throw LlmException("只接受 https 链接", "粘 GitHub 上 SKILL.md 的文件地址就行")
        }
        val conn = try {
            URL(target).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            throw LlmException("链接不合法：${t.message}")
        }
        try {
            conn.connectTimeout = 12_000
            conn.readTimeout = 25_000
            conn.setRequestProperty("Accept", "text/plain, */*")
            conn.setRequestProperty("User-Agent", "GoutouWingman")
            val code = conn.responseCode
            if (code !in 200..299) {
                throw LlmException("HTTP $code", "确认是文件直链（raw.githubusercontent.com/…）而不是仓库首页")
            }
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (text.isBlank()) throw LlmException("拉回来是空的", "确认链接指向具体文件")
            return ensureJsonContract(text.take(MAX_CHARS))
        } catch (e: IOException) {
            throw LlmException("网络错误：${e.message}", "需要能直连 raw.githubusercontent.com")
        } finally {
            runCatching { conn.disconnect() }
        }
    }
}
