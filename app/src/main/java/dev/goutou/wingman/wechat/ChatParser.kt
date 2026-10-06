package dev.goutou.wingman.wechat

/**
 * 快照 -> 聊天记录。纯函数，不碰 Android API，可直接单测。
 *
 * 方向判定三票制：头像位置(2) / 气泡左右(2) / 气泡颜色(1)。
 * 原版只看气泡颜色 —— 深色模式、换主题、微信改配色都会瞎；而头像左右是微信最稳定的约束。
 */
class ChatParser(
    private val screenWidth: Int,
    private val maxTextLen: Int = 400,
) {

    /**
     * @param imageText 这一行的图认出来什么字（图片消息才问）。默认为「认不出来」——
     *   注入侧传的是「走本地代理去 App 认字」，单测传的是现成的字符串。
     *   **它抛异常也不会影响解析**：这一行走兜底（回落成 [ATTACHMENT_TEXT]）。
     */
    fun parse(rows: List<RowSnapshot>, imageText: (RowImage) -> String? = { null }): List<ChatMsg> {
        val out = ArrayList<ChatMsg>(rows.size)
        for (row in rows) {
            val bubble = row.bubble ?: continue
            val side = resolveSide(row, bubble)
            if (side == Side.UNKNOWN) continue
            val fromMe = side == Side.ME
            val who = if (fromMe) "" else nickname(row, bubble)

            val raw = bubble.text
            // 附件行 = 「一个字都没有」或者微信给的通用占位（[图片] 这种，无障碍描述里也会有）。
            // 问一句「这张图认出来什么字」——认字本身在 App 进程里做，这里可能什么都拿不到。
            if (raw == null || isGenericAttachment(raw)) {
                val ocr = row.image?.let { runCatching { imageText(it) }.getOrNull() }
                // 认出字了就换成它；没认出来时，原文本来就是 [图片] 这种占位的就照旧用原文
                // （不能把 [图片] 换成我们更笼统的占位 —— 那是信息倒退）
                if (raw == null || !ocr.isNullOrBlank()) {
                    val text = imageMessageText(ocr, maxTextLen)
                    // 认到字的当**真消息**（进上下文、也进角色记录）；没认到的仍是「内容未知」的占位
                    add(out, ChatMsg(fromMe, text, who, attachment = text == ATTACHMENT_TEXT))
                    continue
                }
            }
            if (raw == null) continue
            val text = clean(raw)
            if (text.isEmpty() || text.length > maxTextLen) continue
            if (Chrome.isChrome(text)) continue
            add(out, ChatMsg(fromMe, text, who))
        }
        return out
    }

    /** 相邻重复（合并转发、刷屏的相同回复）只留一条，省 token。 */
    private fun add(out: MutableList<ChatMsg>, msg: ChatMsg) {
        val last = out.lastOrNull()
        if (last != null && last.fromMe == msg.fromMe && last.text == msg.text && last.who == msg.who) return
        out.add(msg)
    }

    private fun resolveSide(row: RowSnapshot, bubble: Bubble): Side {
        var me = 0
        var other = 0

        // 票 1：头像在左还是右 —— 微信里对方的头像永远在左、我的永远在右
        val nearest = row.avatars.minByOrNull { minOf(it.centerX, screenWidth - it.centerX) }
        if (nearest != null) {
            if (nearest.centerX >= screenWidth * 0.5) me += 2 else other += 2
        }

        // 票 2：气泡靠左还是靠右
        bubble.centerRatio?.let { if (it >= 0.5) me += 2 else other += 2 }

        // 票 3：气泡颜色（微信自己的绿色）
        when (bubble.colorSide) {
            Side.ME -> me += 1
            Side.OTHER -> other += 1
            Side.UNKNOWN -> Unit
        }

        return when {
            me > other -> Side.ME
            other > me -> Side.OTHER
            else -> Side.UNKNOWN
        }
    }

    /** 群聊发言人昵称：同一行、在气泡上方、字号更小的那块文字。 */
    private fun nickname(row: RowSnapshot, bubble: Bubble): String =
        row.texts
            .filter { it.kind == Kind.NICKNAME && it.top <= bubble.top }
            .maxByOrNull { it.top }
            ?.text
            ?.trim()
            .orEmpty()
            .take(24)

    private fun clean(raw: String): String =
        raw.replace("\u200b", "")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
}
