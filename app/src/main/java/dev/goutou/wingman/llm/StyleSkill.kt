package dev.goutou.wingman.llm

import dev.goutou.wingman.config.RoleMsg

/**
 * 把「我自己发过的话」提炼成一份说话风格档案。
 *
 * 全是纯函数（只拼提示词、只清洗返回），不碰 Android，可以直接单测。
 */
object StyleSkill {

    /** 成品最多留多少字。太长会挤掉上下文，而风格这个量级几十条特征就够。 */
    const val MAX_CHARS = 600

    /** 少于这么多条样本就别提炼了 —— 提炼出来的只会是噪音。 */
    const val MIN_SAMPLES = 20

    /** 最多喂多少条进去。 */
    const val MAX_SAMPLES = 200

    const val SYSTEM = """你是「说话风格分析师」。用户会给你一批他本人在微信里发过的话，你要提炼出他的说话风格，供另一个 AI 模仿。

只描述**怎么说话**，不要复述具体内容，不要提任何具体的人名、群名、事件、时间。每行一条，写成「特点：说明」的短句；不要标题、不要编号、不要代码块、不要 JSON。

必须按下面几类覆盖（没有就跳过，不要编）：
- 语气与情绪基调（偏冷淡 / 爱开玩笑 / 常自嘲 …）
- 高频用词与口头禅（**原样列出**，这些是模仿的关键）
- 句子长短与断句习惯（几乎不打句号 / 爱连着发几条短句 …）
- 标点与表情习惯（常用「哈哈哈」「…」「~」、很少用表情 …）
- 称呼与客套方式（很少说"谢谢"、开口直接进正题 …）
- 习惯用的句式（爱反问、爱用"要不…"提议 …）

最后单独用一行 `倾向：` 概括他通常关心什么话题、对什么容易冷淡（同样不要涉及具体的人和事）。
最多 ${MAX_CHARS} 字。只输出这份风格档案本身。"""

    /** 拼给模型的用户消息。[samples] 是「我发出去的」消息。 */
    fun buildUserPrompt(samples: List<RoleMsg>): String {
        val picked = usable(samples).takeLast(MAX_SAMPLES)
        return buildString {
            append("这是我本人在微信里发过的话（时间顺序，共 ").append(picked.size).append(" 条）：\n")
            picked.forEach { append("- ").append(it.text.replace('\n', ' ').take(120)).append('\n') }
            append("\n请提炼我的说话风格。")
        }
    }

    private fun usable(samples: List<RoleMsg>): List<RoleMsg> =
        samples.filter { it.fromMe && it.text.isNotBlank() }.sortedBy { it.at }

    /** 样本够不够。 */
    fun enough(samples: List<RoleMsg>): Boolean = usable(samples).size >= MIN_SAMPLES

    fun count(samples: List<RoleMsg>): Int = usable(samples).size

    /**
     * 清洗模型返回。
     *
     * 必须清：这份文本会被原样拼进下一次的提示词，带上 ``` 围栏或 markdown 标题就是污染。
     * 顺带压到 [MAX_CHARS] 以内。
     */
    fun clean(raw: String): String {
        val lines = raw.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("```") }
            .map { it.removePrefix("####").removePrefix("###").removePrefix("##").removePrefix("#").trim() }
            .filter { it.isNotEmpty() }
        val text = lines.joinToString("\n")
        return if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "…"
    }
}
