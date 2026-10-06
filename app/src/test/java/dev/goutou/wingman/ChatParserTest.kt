package dev.goutou.wingman

import dev.goutou.wingman.wechat.ATTACHMENT_TEXT
import dev.goutou.wingman.wechat.AvatarNode
import dev.goutou.wingman.wechat.Bubble
import dev.goutou.wingman.wechat.ChatParser
import dev.goutou.wingman.wechat.Kind
import dev.goutou.wingman.wechat.RowImage
import dev.goutou.wingman.wechat.RowSnapshot
import dev.goutou.wingman.wechat.Side
import dev.goutou.wingman.wechat.TextNode
import dev.goutou.wingman.wechat.isImageLikeAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatParserTest {
    private val w = 1080
    private val parser = ChatParser(w)

    private fun row(
        text: String?,
        top: Int,
        color: Side = Side.UNKNOWN,
        center: Double? = null,
        avatarOnRight: Boolean? = null,
        nickname: String? = null,
        image: RowImage? = null,
    ): RowSnapshot {
        val avatars = when (avatarOnRight) {
            null -> emptyList()
            true -> listOf(AvatarNode(w - 60, top, 96))
            false -> listOf(AvatarNode(60, top, 96))
        }
        val texts = ArrayList<TextNode>()
        nickname?.let { texts.add(TextNode(it, top - 40, Kind.NICKNAME, 28f)) }
        text?.let { texts.add(TextNode(it, top, Kind.BUBBLE, 36f)) }
        return RowSnapshot(Bubble(text, color, center, top), texts, avatars, image)
    }

    /** 造一张「行里的图」：抓字节返回空数组就行 —— 单测里不会真的编码，更不会联网。 */
    private fun img(): RowImage = RowImage(300, 400) { ByteArray(0) }

    @Test
    fun `单聊方向正确`() {
        val msgs = parser.parse(
            listOf(
                row("在吗", 300, Side.OTHER, 0.35, false),
                row("在", 400, Side.ME, 0.65, true),
                row("周末有空吗", 500, Side.OTHER, 0.35, false),
            ),
        )
        assertEquals(3, msgs.size)
        assertFalse(msgs[0].fromMe)
        assertTrue(msgs[1].fromMe)
        assertFalse(msgs[2].fromMe)
        assertEquals("周末有空吗", msgs[2].text)
    }

    @Test
    fun `深色模式把气泡判成对方时靠头像和左右纠正回来`() {
        // 深色气泡底色接近页面底色 -> colorSide 只能是 OTHER；但头像在右、气泡靠右
        val msgs = parser.parse(listOf(row("我发的", 300, Side.OTHER, 0.7, true)))
        assertEquals(1, msgs.size)
        assertTrue(msgs[0].fromMe)
    }

    @Test
    fun `颜色和头像冲突时以头像为准`() {
        val msgs = parser.parse(listOf(row("hi", 300, Side.ME, 0.35, false)))
        assertEquals(1, msgs.size)
        assertFalse(msgs[0].fromMe)
    }

    @Test
    fun `群聊带出昵称`() {
        val msgs = parser.parse(listOf(row("周六谁去", 300, Side.OTHER, 0.35, false, nickname = "张三")))
        assertEquals("张三", msgs[0].who)
    }

    @Test
    fun `时间戳 未读数 服务提示 超长文本都被过滤`() {
        val msgs = parser.parse(
            listOf(
                row("19:03", 100, Side.UNKNOWN, 0.5),
                row("星期三 19:03", 150, Side.UNKNOWN, 0.5),
                row("3", 200, Side.UNKNOWN, 0.5),
                row("群主", 250, Side.UNKNOWN, 0.5),
                row("啊".repeat(500), 300, Side.OTHER, 0.35, false),
                row("真的在吗", 400, Side.OTHER, 0.35, false),
            ),
        )
        assertEquals(1, msgs.size)
        assertEquals("真的在吗", msgs[0].text)
    }

    @Test
    fun `没有文字但有头像的行算图片`() {
        val msgs = parser.parse(listOf(row(null, 300, Side.UNKNOWN, null, false)))
        assertEquals(1, msgs.size)
        assertEquals(ATTACHMENT_TEXT, msgs[0].text)
        assertTrue(msgs[0].attachment)
        assertFalse(msgs[0].fromMe)
    }

    @Test
    fun `三个信号全缺失时宁可不显示也不猜`() {
        assertTrue(parser.parse(listOf(row("???", 300, Side.UNKNOWN, null, null))).isEmpty())
    }

    @Test
    fun `相邻重复只留一条`() {
        val rows = listOf(row("好的", 300, Side.OTHER, 0.35, false), row("好的", 320, Side.OTHER, 0.35, false))
        assertEquals(1, parser.parse(rows).size)
    }

    @Test
    fun `首尾空白和零宽字符被清掉`() {
        val msgs = parser.parse(listOf(row("  你好\u200b  ", 300, Side.OTHER, 0.35, false)))
        assertEquals("你好", msgs[0].text)
    }

    @Test
    fun `图片消息认出字就当成真消息`() {
        // 「[图片] 」这个前缀是给模型看的：这些字是从图里认出来的，可能有错别字
        val msgs = parser.parse(listOf(row(null, 300, Side.OTHER, 0.35, false, image = img()))) { "今晚八点老地方" }
        assertEquals(1, msgs.size)
        assertEquals("[图片] 今晚八点老地方", msgs[0].text)
        assertFalse(msgs[0].attachment)
    }

    @Test
    fun `图片没认出字仍是老占位（行为不变）`() {
        val msgs = parser.parse(listOf(row(null, 300, Side.OTHER, 0.35, false, image = img())))
        assertEquals(ATTACHMENT_TEXT, msgs[0].text)
        assertTrue(msgs[0].attachment)
    }

    @Test
    fun `认得是空串也当没认出来`() {
        val msgs = parser.parse(listOf(row(null, 300, Side.OTHER, 0.35, false, image = img()))) { "   " }
        assertEquals(ATTACHMENT_TEXT, msgs[0].text)
        assertTrue(msgs[0].attachment)
    }

    @Test
    fun `认字炸了也不能影响这一行`() {
        // 识图失败只是「这次没有文字可用」——绝不能把整轮分析带崩
        val msgs = parser.parse(listOf(row(null, 300, Side.OTHER, 0.35, false, image = img()))) {
            error("连不上代理")
        }
        assertEquals(ATTACHMENT_TEXT, msgs[0].text)
        assertTrue(msgs[0].attachment)
    }

    @Test
    fun `纯文字的消息不会去问识图`() {
        var calls = 0
        parser.parse(listOf(row("在吗", 300, Side.OTHER, 0.35, false))) { calls++; "x" }
        assertEquals(0, calls)
    }

    @Test
    fun `认出来的字太长是截断而不是丢掉`() {
        // 普通文字消息超长会被整条丢掉；图里的字只知道一部分也比什么都没有强
        val long = "字".repeat(600)
        val msgs = parser.parse(listOf(row(null, 300, Side.OTHER, 0.35, false, image = img()))) { long }
        assertEquals(1, msgs.size)
        assertTrue(msgs[0].text.endsWith("…"))
        assertEquals("[图片] ".length + 401, msgs[0].text.length)
        assertFalse(msgs[0].attachment)
    }

    @Test
    fun `通用占位也会拿图里的字把它换掉`() {
        // 微信（无障碍描述里）给的 [图片] 也是「没文字」，认出字就该换掉 —— 模型才知道图里写了什么
        val msgs = parser.parse(
            listOf(row("[图片]", 300, Side.OTHER, 0.35, false, image = img())),
        ) { "今晚八点老地方" }
        assertEquals(1, msgs.size)
        assertEquals("[图片] 今晚八点老地方", msgs[0].text)
        assertFalse(msgs[0].attachment)
    }

    @Test
    fun `占位行没认出字就照旧用原文`() {
        // 不能把 [图片] 换成更笼统的 [图片/表情/语音] —— 那是信息倒退
        val msgs = parser.parse(listOf(row("[图片]", 300, Side.OTHER, 0.35, false, image = img())))
        assertEquals("[图片]", msgs[0].text)
        assertFalse(msgs[0].attachment)
    }

    @Test
    fun `具体表情名不会被当成图片消息`() {
        // [微笑] 这种本来就是有内容的文字消息，不该被重写
        val msgs = parser.parse(
            listOf(row("[微笑]", 300, Side.OTHER, 0.35, false, image = img())),
        ) { "不该被用上" }
        assertEquals("[微笑]", msgs[0].text)
    }

    @Test
    fun `语音视频文件位置行不值得去找图`() {
        // 这些行里没有能认的文字 —— 以前对着它们也要把控件画下来送去 OCR，
        // 结果永远是「认了：这张图里没字」，第 21 版起直接跳过。
        assertFalse(isImageLikeAttachment("[语音]"))
        assertFalse(isImageLikeAttachment("[视频]"))
        assertFalse(isImageLikeAttachment("[文件]"))
        assertFalse(isImageLikeAttachment("[链接]"))
        assertFalse(isImageLikeAttachment("[位置]"))
    }

    @Test
    fun `表情行不值得去找图`() {
        assertFalse(isImageLikeAttachment("[表情]"))
        assertFalse(isImageLikeAttachment("[动画表情]"))
        // 具体表情名本来就不算附件（它是「有内容的文字消息」）
        assertFalse(isImageLikeAttachment("[微笑]"))
    }

    @Test
    fun `图片与照片行才去找图`() {
        assertTrue(isImageLikeAttachment("[图片]"))
        assertTrue(isImageLikeAttachment(" [照片] "))   // 带空白也认
        assertTrue(isImageLikeAttachment(ATTACHMENT_TEXT))
    }

    @Test
    fun `普通文字与空串都不去找图`() {
        assertFalse(isImageLikeAttachment("在吗"))
        assertFalse(isImageLikeAttachment(""))
    }
}
