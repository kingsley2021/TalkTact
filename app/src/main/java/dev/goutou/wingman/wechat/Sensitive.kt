package dev.goutou.wingman.wechat

/**
 * 发给模型之前的本地隐私自检。
 * 原版是「把最近 8 条消息直接发到你填的接口地址」+ 一句免责说明；
 * 现在至少能在命中高危内容时先弹一次确认，用户可以选择不发。
 */
object Sensitive {
    private val rules: List<Pair<String, Regex>> = listOf(
        "验证码/动态码" to Regex("(?i)(验证码|校验码|动态码|短信码|verification code|\\botp\\b)"),
        "密码/口令" to Regex("(?i)(密码|口令|password|passwd|\\bpwd\\b)"),
        "身份证号" to Regex("""(?<!\d)\d{17}[\dXx](?!\d)"""),
        "银行卡号" to Regex("""(?<!\d)\d{16,19}(?!\d)"""),
        "手机号" to Regex("""(?<!\d)1[3-9]\d{9}(?!\d)"""),
        "转账/红包/借钱" to Regex("(转账|打款|汇款|红包|借钱|借点|扫码付|付款码|收款码)"),
        "链接" to Regex("""https?://\S+|www\.\S+\.\S+"""),
    )

    /** 命中的规则名（去重），空 = 干净。 */
    fun hits(text: String): List<String> =
        rules.filter { it.second.containsMatchIn(text) }.map { it.first }

    /** 只用于本地预览/日志，别拿它替换真正要发的正文。 */
    fun redact(text: String): String =
        rules.fold(text) { acc, (_, re) -> re.replace(acc, "«已屏蔽»") }
}
