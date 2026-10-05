package dev.goutou.wingman.wechat

/**
 * 「会话列表里怎么从一行挑出会话名」—— 纯函数，配单测。
 *
 * 为什么单独抽出来：这里全是启发式规则，而真机上没法调试（改一次要发版、要你手动试）。
 * 抽成纯函数之后，照着「微信长什么样」构造几行就能一遍遍跑。
 *
 * 微信首页 / 通讯录里的一行大致是这样：
 *
 *     [头像]  名字（左上，字号最大）        时间（右上，小字）
 *             最后一条消息（左下，小字）
 *
 * 所以判据按强度排：**字号 > 靠上 > 靠左**。时间 / 未读数 / 纯符号这些先剔掉。
 */
data class NameCandidate(
    /** 控件里的文字（已 trim） */
    val text: String,
    /** 字号（px）。自绘控件不是 TextView，取不到就是 0 */
    val textSize: Float = 0f,
    /** 屏幕坐标：y 越小越靠上，x 越小越靠左 */
    val y: Int = 0,
    val x: Int = 0,
)

/** 界面上固定出现的那些词，不是会话名（底部 tab、搜索框提示、通讯录里的分组入口）。 */
private val CONV_NOISE = setOf(
    "微信", "通讯录", "发现", "我", "聊天", "搜索", "新的朋友", "群聊", "标签",
    "公众号", "服务号", "订阅号", "小程序", "收藏", "朋友圈", "看一看", "搜一搜",
    "视频号", "置顶", "已置顶", "折叠的群聊", "折叠置顶聊天", "已折叠",
)

/**
 * 一行里挑出会话名；挑不出来返回 null（调用方跳过这一行）。
 *
 * 排序 = 字号降序 → 纵坐标升序 → 横坐标升序。名字是行内字号最大的那个；
 * 字号一样大（或者都取不到 —— 自绘控件没有 textSize）时，名字比「最后一条消息」靠上、
 * 比右上角的时间靠左。
 */
fun pickRowName(cands: List<NameCandidate>): String? =
    cands.filter { !isRowNoise(it.text) }
        .minWithOrNull(compareByDescending<NameCandidate> { it.textSize }.thenBy { it.y }.thenBy { it.x })
        ?.text?.trim()

/** 时间戳 / 未读数 / 纯符号 / 「[图片]」这类占位 —— 都不是会话名。 */
fun isRowNoise(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t.length > 32) return true
    if (t in CONV_NOISE) return true
    if (Chrome.isChrome(t)) return true // 时间 / 纯数字（未读条数）/「群主」这类群标签
    if (t.none { it.isLetterOrDigit() }) return true // 纯表情、纯符号
    if (t.startsWith("[") && t.endsWith("]")) return true // [图片] / [草稿] / [语音]
    if (t.startsWith("正在输入") || t.startsWith("对方正在输入")) return true
    // 通讯录里的字母分组头（A / B / C…）—— 一个 ASCII 字符的都不是名字
    if (t.length == 1 && t[0].code < 128) return true
    return false
}
