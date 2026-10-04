package dev.goutou.wingman.wechat

/**
 * 「不是聊天内容」的文字：时间戳、未读数、群成员标签。
 * 读 View 的人和解析快照的人共用这一套规则 —— 原版是两边各写一份正则，改一处忘一处。
 */
object Chrome {
    val noiseRe = Regex("""^\d{1,3}\s*["'″′”“]?$""")
    val timeRe = Regex(
        """^(昨天|今天|前天|星期[一二三四五六日天]|周[一二三四五六日天])(\s*\d{1,2}[:：]\d{2}(:\d{2})?)?$""" +
            """|^\d{1,2}[:：]\d{2}(:\d{2})?$""" +
            """|^(\d{4}[年/])?\d{1,2}[月/]\d{1,2}日?.*$""" +
            """|^(昨天|今天|前天|星期[一二三四五六日天]|周[一二三四五六日天]).*\d{1,2}[:：]\d{2}.*$""",
    )
    val tags = setOf("成员", "群主", "管理员", "群管理员", "新人", "活跃", "潜水", "已退群")

    fun isTime(text: String): Boolean = text.length <= 24 && timeRe.matches(text)
    fun isNoise(text: String): Boolean = noiseRe.matches(text)
    fun isTag(text: String): Boolean = text in tags
    fun isChrome(text: String): Boolean = isTime(text) || isNoise(text) || isTag(text)
}
