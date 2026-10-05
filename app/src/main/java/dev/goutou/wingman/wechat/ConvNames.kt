package dev.goutou.wingman.wechat

/**
 * 「从一屏里认出会话名」的两件纯函数 —— 配单测。
 *
 * 为什么单独抽出来：这里全是启发式规则，而真机上没法调试（改一次要发版、要你手动试）。
 * 抽成纯函数之后，照着「微信长什么样」构造几行就能一遍遍跑。
 *
 * 微信首页 / 通讯录里的一行大致是这样：
 *
 *     [头像]  名字（左上，字号最大）        时间（右上，小字）
 *             最后一条消息（左下，小字）
 *
 * 所以分两道闸：
 * 1. **形状闸**（[RowShape.looksLikeChat]）：这一行像不像一条会话（左边有没有方形头像 / 有没有时间角标）。
 * 2. **挑名字**（[pickRowName]）：在形状合法的行里按 **字号 > 靠上 > 靠左** 挑出名字。
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

/**
 * 一行「长什么样」—— 由 [ViewReader] 从视图里量出来，判断放在这里（纯函数、可单测）。
 *
 * 为什么要量形状：光有「一个容器像列表」是不够的。**个人资料页**（微信里点开头像进去的那一页）
 * 也是一行行的列表 —— 微信号 / 地区 / 个性签名…每一行都「像个列表行」。
 * 真机反馈就是被它坑到的：候选里只有「微信号：xxx」这种杂项，真正的名字（备注）反而读不到。
 *
 * 而会话行的形状其实非常固定：左边一个**方形头像**，右边一堆文字（名字 / 最后一条 / 时间角标）。
 * 资料页那几行两样都没有。所以让整行先过这道形状闸，过关的才交给 [pickRowName]。
 */
data class RowShape(
    /** 行里最左边那个方形头像的边长 px（0 = 没量到） */
    val avatarSize: Int = 0,
    /** 行里有「时间 / 未读数 / 群标签」这种角标小字 —— 会话行几乎每行都带 */
    val hasTimeMark: Boolean = false,
    /** 行里带文字的控件个数 */
    val textCount: Int = 0,
) {
    /** 够不够「像一条会话」。 */
    val looksLikeChat: Boolean get() = textCount >= 1 && (avatarSize > 0 || hasTimeMark)
}

/**
 * 一个容器至少要认出几条「像会话的行」，才肯承认它是会话列表。
 *
 * 取 2 而不是 1：资料页顶部正好有一个大头像（那一格也算「一行」），门槛放 1 会被它骗过去。
 */
const val MIN_CHAT_ROWS = 2

/** 界面上固定出现的那些词，不是会话名（底部 tab、搜索框提示、通讯录里的分组入口）。 */
private val CONV_NOISE = setOf(
    "微信", "通讯录", "发现", "我", "聊天", "搜索", "新的朋友", "群聊", "标签",
    "公众号", "服务号", "订阅号", "小程序", "收藏", "朋友圈", "看一看", "搜一搜",
    "视频号", "置顶", "已置顶", "折叠的群聊", "折叠置顶聊天", "已折叠",
)

/**
 * 「个人资料页」上的字段行：`微信号：wxid_xxx` / `地区：广东 深圳` / `个性签名：…`。
 * 它们和会话名长得一模一样（也是一行里字号最大的那个），只能按开头的标签头剔掉。
 */
private val PROFILE_LABEL_RE = Regex(
    "^(微信号|WeChat ?ID|地区|个性签名|来源|标签|备注|备注和标签|朋友权限|共同群聊|" +
        "更多信息|职业|公司|学校|电话)\\s*[：:]",
    RegexOption.IGNORE_CASE,
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

/** 时间戳 / 未读数 / 纯符号 / 「[图片]」这类占位 / 资料页字段 —— 都不是会话名。 */
fun isRowNoise(raw: String): Boolean {
    val t = raw.trim()
    if (t.isEmpty() || t.length > 32) return true
    if (t in CONV_NOISE) return true
    if (PROFILE_LABEL_RE.containsMatchIn(t)) return true // 「微信号：xxx」这类资料行
    if (Chrome.isChrome(t)) return true // 时间 / 纯数字（未读条数）/「群主」这类群标签
    if (t.none { it.isLetterOrDigit() }) return true // 纯表情、纯符号
    if (t.startsWith("[") && t.endsWith("]")) return true // [图片] / [草稿] / [语音]
    if (t.startsWith("正在输入") || t.startsWith("对方正在输入")) return true
    // 通讯录里的字母分组头（A / B / C…）—— 一个 ASCII 字符的都不是名字
    if (t.length == 1 && t[0].code < 128) return true
    return false
}

/**
 * 候选名单的搜索过滤（纯函数，单测在 ConvNamesTest）。
 *
 * 空查询 = 原样返回；否则按「包含」匹配、忽略大小写，并保持传进来的顺序。
 * 名字以中文为主，不做拼音 / 分词 —— 这里要的是「输一两个字把几十个名字筛到几个」，
 * 直接 contains 就够了，行为也更好预测。
 */
fun filterNames(names: List<String>, query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return names
    return names.filter { it.contains(q, ignoreCase = true) }
}
