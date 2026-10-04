package dev.goutou.wingman.llm

import dev.goutou.wingman.wechat.ChatMsg

data class Reply(val style: String, val text: String)

data class Suggestion(
    val intent: String = "",
    val risk: String = "未知",
    val note: String = "",
    val replies: List<Reply> = emptyList(),
)

/** 带「给用户看的下一步建议」的异常，UI 直接显示 message + hint。 */
class LlmException(
    message: String,
    val hint: String? = null,
    val retryable: Boolean = false,
) : Exception(message)

object SuggestionParser {

    fun parse(raw: String): Suggestion {
        val body = extractJson(raw)
            ?: throw LlmException("模型没返回 JSON", "到「提示词」页确认「只输出 JSON」那条要求还在")
        val root = Json.parse(body)?.asObj()
            ?: throw LlmException("模型返回的 JSON 解析失败", "点「重新识别」重试，偶发残缺 JSON 是正常的", retryable = true)

        val replies = root["replies"].asArr().orEmpty()
            .mapNotNull { it.asObj() }
            .map {
                Reply(
                    style = it["style"].asStr()?.trim().orEmpty().ifEmpty { "回复" },
                    text = it["text"].asStr()?.trim().orEmpty(),
                )
            }
            .filter { it.text.isNotEmpty() }
        if (replies.isEmpty()) {
            throw LlmException("模型没给出可用回复", "点「重新识别」再试，或换个模型", retryable = true)
        }

        return Suggestion(
            intent = root["intent"].asStr()?.trim().orEmpty().ifEmpty { "未识别" },
            risk = normalizeRisk(root["risk"].asStr().orEmpty()),
            note = root["note"].asStr()?.trim().orEmpty(),
            replies = replies.take(4),
        )
    }

    fun normalizeRisk(raw: String): String {
        val t = raw.trim().lowercase()
        return when {
            t.isEmpty() -> "未知"
            t.startsWith("低") || t == "l" || t.contains("low") || t.contains("safe") -> "低"
            t.startsWith("中") || t == "m" || t.contains("mid") || t.contains("medium") -> "中"
            t.startsWith("高") || t == "h" || t.contains("high") || t.contains("risky") -> "高"
            else -> raw.trim()
        }
    }

    /**
     * 从模型输出里抠出 JSON 对象。
     * 比原版「第一个 { 到最后一个 }」稳：会跳过字符串内部的括号与转义，
     * 所以 {"note":"if (a} b)"} 这种也能正确截断，多段输出也能只抠出第一段。
     */
    fun extractJson(raw: String): String? {
        var s = raw.trim()
        Regex("```(?:json|JSON)?\\s*([\\s\\S]*?)```").find(s)?.let { s = it.groupValues[1].trim() }
        val start = s.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inStr = false
        var esc = false
        for (idx in start until s.length) {
            val c = s[idx]
            when {
                esc -> esc = false
                inStr && c == '\\' -> esc = true
                c == '"' -> inStr = !inStr
                inStr -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return s.substring(start, idx + 1)
                }
            }
        }
        return null
    }
}

/** 聊天记录 -> 提示词里的正文。 */
fun List<ChatMsg>.asTranscript(): String = joinToString("\n") { m ->
    val who = when {
        m.fromMe -> "我"
        m.who.isNotBlank() -> "对方(${m.who})"
        else -> "对方"
    }
    "$who: ${m.text}"
}
