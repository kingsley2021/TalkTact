package dev.goutou.wingman.wechat

/**
 * View 树 -> 纯数据的中间表示。
 *
 * 这一层之所以存在，是因为「读取聊天记录」原来是整个模块最容易坏、又最没法测的地方：
 * 判定逻辑和 View/Canvas 绞在一起，微信一更新就只能装到手机上肉眼试。
 * 现在 ViewReader（依赖 Android）只负责产出下面的快照，ChatParser（纯 Kotlin）负责判定，
 * 判定逻辑因此可以在 JVM 上跑单元测试。
 */

/** 消息方向。UNKNOWN = 判不了，宁可丢掉也不要猜。 */
enum class Side { ME, OTHER, UNKNOWN }

/** 行内文字块的类型（由 ViewReader 标注）。 */
enum class Kind { BUBBLE, NICKNAME, TIMESTAMP, TAG, OTHER }

data class TextNode(val text: String, val top: Int, val kind: Kind = Kind.OTHER, val size: Float = 0f)

data class AvatarNode(val centerX: Int, val top: Int, val size: Int)

/**
 * 一行的「主气泡」。
 * @param text null 表示这一行是图片/表情/语音之类没有文字的附件
 * @param colorSide 气泡底色给出的那一票（微信自己的绿色 = 我）
 * @param centerRatio 气泡中心 x / 屏宽，用来判左右
 */
data class Bubble(
    val text: String?,
    val colorSide: Side,
    val centerRatio: Double?,
    val top: Int,
)

data class RowSnapshot(
    val bubble: Bubble?,
    val texts: List<TextNode> = emptyList(),
    val avatars: List<AvatarNode> = emptyList(),
)

/** 交给模型的一条消息。 */
data class ChatMsg(
    val fromMe: Boolean,
    val text: String,
    val who: String = "",
    /** true = 占位文本，不是原文（图片/表情/语音） */
    val attachment: Boolean = false,
)

const val ATTACHMENT_TEXT = "[图片/表情/语音]"
