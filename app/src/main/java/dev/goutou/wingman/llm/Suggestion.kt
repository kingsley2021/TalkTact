package dev.goutou.wingman.llm

import dev.goutou.wingman.wechat.ChatMsg

data class Reply(val style: String, val text: String)

data class Suggestion(
    val intent: String = "",
    val risk: String = "未知",
    val note: String = "",
    val replies: List<Reply> = emptyList(),
    /** 最推荐的那条在 [replies] 里的下标；null = 模型没给，或给的下标不合法 */
    val best: Int? = null,
    /** 为什么推荐它（一句话） */
    val why: String = "",
    /** true = 模型输出被截断过，这份是从残缺 JSON 里抢救出来的（提醒用户重试） */
    val partial: Boolean = false,
    /**
     * 分级模式下「有一路没跑通」这类提示，直接显示在卡片上。
     * 空 = 两路都正常（直通模式也永远是空）。
     */
    val warnings: List<String> = emptyList(),
)

/** 带「给用户看的下一步建议」的异常，UI 直接显示 message + hint。 */
class LlmException(
    message: String,
    val hint: String? = null,
    val retryable: Boolean = false,
) : Exception(message)

object SuggestionParser {

    /**
     * @param requireReplies 要不要强制要求候选回复。
     *   默认 true（直通模式那一路必须有候选）。
     *   **分级模式的「风险评估」那一路契约里根本没有 replies** —— 再按「必须有候选」判，
     *   就会把一次完全正确的调用报成「模型没给出可用回复」，整条风险路都被判失败（真踩过）。
     */
    fun parse(raw: String, requireReplies: Boolean = true): Suggestion {
        // 先按正常路径抠 JSON；抠不出来再试「截断抢救」——被 max_tokens 截断时 JSON 是残缺的，
        // 但里面已经说完的那几条回复还有救，总比整条报错强。
        val strictRoot = extractJson(raw)?.let { Json.parse(it)?.asObj() }
        val repairedRoot = if (strictRoot == null) {
            repairTruncatedJson(raw)?.let { Json.parse(it)?.asObj() }
        } else {
            null
        }
        val root = strictRoot ?: repairedRoot
        if (root == null) {
            val looksTruncated = raw.contains("\"replies\"")
            throw LlmException(
                if (looksTruncated) "模型输出被截断了（JSON 没闭合）" else "模型没返回 JSON",
                if (looksTruncated) {
                    "把 token 上限调大一档、或换个话更少的模型；也可以直接点「重新识别」"
                } else {
                    "到「提示词」页确认「只输出 JSON」那条要求还在"
                },
                retryable = looksTruncated,
            )
        }

        val replies = root["replies"].asArr().orEmpty()
            .mapNotNull { it.asObj() }
            .map {
                Reply(
                    style = it["style"].asStr()?.trim().orEmpty().ifEmpty { "回复" },
                    text = it["text"].asStr()?.trim().orEmpty(),
                )
            }
            .filter { it.text.isNotEmpty() }
        if (replies.isEmpty() && requireReplies) {
            val cut = strictRoot == null
            throw LlmException(
                if (cut) "模型输出被截断了，没剩一条完整回复" else "模型没给出可用回复",
                if (cut) {
                    "把 token 上限调大一档、或换个话更少的模型；也可以直接点「重新识别」"
                } else {
                    "点「重新识别」再试，或换个模型"
                },
                retryable = true,
            )
        }

        val kept = replies.take(4)
        return Suggestion(
            intent = root["intent"].asStr()?.trim().orEmpty().ifEmpty { "未识别" },
            risk = normalizeRisk(root["risk"].asStr().orEmpty()),
            note = root["note"].asStr()?.trim().orEmpty(),
            replies = kept,
            best = parseBest(root["best"], kept),
            why = root["why"].asStr()?.trim().orEmpty(),
            partial = strictRoot == null,
        )
    }

    /**
     * best 三种写法都认：数字下标、字符串数字、以及直接写 style 名（模型经常自作主张）。
     * 越界 / 认不出来一律当没给 —— 界面上只是不标「最推荐」，不会出错。
     */
    fun parseBest(raw: JsonValue?, replies: List<Reply>): Int? {
        if (replies.isEmpty()) return null
        raw?.asInt()?.let { i -> return i.takeIf { it in replies.indices } }
        val s = raw?.asStr()?.trim().orEmpty()
        if (s.isEmpty()) return null
        s.toIntOrNull()?.let { i -> return i.takeIf { it in replies.indices } }
        val idx = replies.indexOfFirst { it.style.equals(s, ignoreCase = true) }
        return if (idx >= 0) idx else null
    }

    /**
     * 截断抢救：把「已经说完的部分」保住 —— 砍掉半截的字符串/键，再补齐没关上的括号。
     * 救不出来（连 `{` 都没有、或结构整段坏掉）就返回 null，交给调用方按原样报错。
     */
    private fun repairTruncatedJson(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val out = StringBuilder()
        var inStr = false
        var esc = false
        val open = ArrayDeque<Char>()
        for (i in start until raw.length) {
            val c = raw[i]
            if (esc) {
                out.append(c); esc = false; continue
            }
            if (inStr) {
                out.append(c)
                when (c) {
                    '\\' -> esc = true
                    '"' -> inStr = false
                }
                continue
            }
            when (c) {
                '"' -> { out.append(c); inStr = true }
                '{', '[' -> { out.append(c); open.addLast(c) }
                '}', ']' -> { out.append(c); if (open.isNotEmpty()) open.removeLast() }
                else -> out.append(c)
            }
        }
        if (inStr) {
            // 最后那个引号就是没写完的字符串的开引号，从这儿砍掉
            val cut = out.lastIndexOf("\"")
            if (cut < 0) return null
            out.setLength(cut)
        }
        // 结尾可能剩下「"why":」这种只有键没值的残片，以及多余的逗号
        var s = Regex("""["A-Za-z0-9_]*"\s*:\s*$""").replace(out.toString().trimEnd(), "")
        s = s.trimEnd().trimEnd(',').trimEnd()
        if (s.length <= start) return null
        while (open.isNotEmpty()) s += if (open.removeLast() == '{') "}" else "]"
        return s
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


/** 一条候选多长算「偏长」：提示词里要求 40~45 字，超过这个数就值得提醒一句（只提示，不拦）。 */
const val REPLY_TOO_LONG_CHARS = 60

/** 是否偏长（纯函数，配单测）。 */
fun isReplyTooLong(text: String): Boolean = text.trim().length > REPLY_TOO_LONG_CHARS
