package dev.goutou.wingman.wechat

import dev.goutou.wingman.proxy.ProxyProtocol

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

data class TextNode(
    val text: String,
    val top: Int,
    val kind: Kind = Kind.OTHER,
    val size: Float = 0f,
    /** 控件是否真的可见。微信会把正文放在 INVISIBLE 的占位控件里，所以只当参考权重用。 */
    val visible: Boolean = true,
)

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
    /**
     * 这一行里那张「消息图」（没有图就是 null）。
     *
     * 只带尺寸和一个**按需取字节**的入口：抓图 + 重新编码是有成本的，不该每轮读屏都做一遍；
     * 而取字节要碰 Android API，所以做成回调 —— 快照这一层仍然是纯数据，单测照样能造。
     */
    val image: RowImage? = null,
)

/**
 * 一行里的那张图（图片消息用）。
 *
 * @param width/height 图在屏幕上的尺寸（诊断里用它说明「取的是哪一张」）
 * @param grab 按「最长边」取 JPEG 字节；null = 这张图不打算送（取不到位图 / 编码失败）
 */
class RowImage(
    val width: Int,
    val height: Int,
    private val grab: (Int) -> ByteArray?,
) {
    /** 压成 JPEG。默认最长边走 [ProxyProtocol.OCR_MAX_SIDE]。 */
    fun jpeg(maxSide: Int = ProxyProtocol.OCR_MAX_SIDE): ByteArray? =
        runCatching { grab(maxSide) }.getOrNull()
}

/** 交给模型的一条消息。 */
data class ChatMsg(
    val fromMe: Boolean,
    val text: String,
    val who: String = "",
    /** true = 占位文本，不是原文（图片/表情/语音） */
    val attachment: Boolean = false,
)

const val ATTACHMENT_TEXT = "[图片/表情/语音]"

/** 认出来的图片文字写进上下文时的前缀（模型靠它知道「这是从图里认出来的，可能有错字」）。 */
const val IMAGE_PREFIX = "[图片] "

/**
 * 图片那一行最终写进上下文的样子。
 *
 * 认出字 → `[图片] 认出来的文字`（告诉模型这是从图里认出来的，可能有错别字 ——
 * 但总比「内容未知，不要猜」强）；没认出来 / 没开识图 / 认字失败 → 退回老占位 [ATTACHMENT_TEXT]。
 *
 * 截断而不是丢弃：普通文字消息超过 [ChatParser] 的上限会被整条丢掉，
 * 但一张图的文字往往只是一部分有用 —— 留前面这些字比什么都不留强。
 */
fun imageMessageText(ocr: String?, max: Int = 400): String {
    val t = ocr?.trim().orEmpty()
    if (t.isEmpty()) return ATTACHMENT_TEXT
    val body = if (max > 0 && t.length > max) t.take(max) + "…" else t
    return IMAGE_PREFIX + body
}
