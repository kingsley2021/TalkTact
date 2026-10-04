package dev.goutou.wingman

import dev.goutou.wingman.llm.LlmException
import dev.goutou.wingman.llm.SuggestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SuggestionParserTest {

    @Test
    fun `干净的 JSON`() {
        val s = SuggestionParser.parse(
            """{"intent":"约饭","risk":"低","note":"注意时间","replies":[{"style":"稳妥","text":"好呀"},{"style":"幽默","text":"我请客？"},{"style":"推进","text":"周六？"}]}""",
        )
        assertEquals("约饭", s.intent)
        assertEquals("低", s.risk)
        assertEquals("注意时间", s.note)
        assertEquals(3, s.replies.size)
        assertEquals("好呀", s.replies[0].text)
    }

    @Test
    fun `带 markdown 围栏和前后解释文字`() {
        val raw = """
            好的，这是结果：
            ```json
            {"intent":"试探","risk":"中","note":"","replies":[{"style":"稳妥","text":"在的"}]}
            ```
            还需要改吗？
        """.trimIndent()
        val s = SuggestionParser.parse(raw)
        assertEquals("试探", s.intent)
        assertEquals("中", s.risk)
        assertEquals(1, s.replies.size)
    }

    @Test
    fun `字符串里的花括号不会把 JSON 截断`() {
        val s = SuggestionParser.parse(
            """{"intent":"x","risk":"低","note":"if (a} b) 就别答应","replies":[{"style":"稳妥","text":"收到"}]}""",
        )
        assertEquals("if (a} b) 就别答应", s.note)
        assertEquals("收到", s.replies[0].text)
    }

    @Test
    fun `risk 归一化`() {
        assertEquals("低", SuggestionParser.normalizeRisk("low"))
        assertEquals("高", SuggestionParser.normalizeRisk("HIGH"))
        assertEquals("中", SuggestionParser.normalizeRisk("中等"))
        assertEquals("未知", SuggestionParser.normalizeRisk(""))
        assertEquals("低", SuggestionParser.parse("""{"risk":"低","replies":[{"text":"a"}]}""").risk)
    }

    @Test
    fun `回复为空或没有 JSON 时给出可读错误`() {
        try {
            SuggestionParser.parse("""{"intent":"x","replies":[]}""")
            fail("应该抛异常")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("可用回复"))
        }
        try {
            SuggestionParser.parse("抱歉，我不能帮你做这个。")
            fail("应该抛异常")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("没返回 JSON"))
        }
    }

    @Test
    fun `style 缺失时给默认值`() {
        val s = SuggestionParser.parse("""{"replies":[{"text":"嗯"}]}""")
        assertEquals("回复", s.replies[0].style)
    }
}
