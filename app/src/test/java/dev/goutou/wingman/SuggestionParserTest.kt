package dev.goutou.wingman

import dev.goutou.wingman.llm.LlmException
import dev.goutou.wingman.llm.SuggestionParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    // ---------------- 0.8.8：最推荐 + 截断抢救 ----------------

    @Test
    fun `best 与 why 正常解析`() {
        val s = SuggestionParser.parse(
            """{"intent":"约饭","risk":"低","note":"","best":1,"why":"留了余地又推进了一步","replies":[{"style":"稳妥","text":"好呀"},{"style":"幽默","text":"我请客？"},{"style":"推进","text":"周六？"}]}""",
        )
        assertEquals(1, s.best)
        assertEquals("留了余地又推进了一步", s.why)
        assertEquals(false, s.partial)
    }

    @Test
    fun `best 允许写 style 名 越界或缺失都当没给`() {
        val three = """{"replies":[{"style":"稳妥","text":"a"},{"style":"幽默","text":"b"},{"style":"推进","text":"c"}]"""
        assertEquals(
            2,
            SuggestionParser.parse("""$three,"best":"推进"}""").best,
        )
        assertEquals(1, SuggestionParser.parse("""$three,"best":"1"}""").best)
        assertNull(SuggestionParser.parse("""$three,"best":9}""").best)
        assertNull(SuggestionParser.parse("""$three}""").best)
    }

    @Test
    fun `被截断的输出能抢救出已经说完的那几条`() {
        val raw = """{"intent":"约饭","risk":"低","note":"","best":0,"why":"先接住","replies":[{"style":"稳妥","text":"好呀"},{"style":"幽默","text":"我请"""
        val s = SuggestionParser.parse(raw)
        assertEquals(1, s.replies.size)
        assertEquals("好呀", s.replies[0].text)
        assertEquals("约饭", s.intent)
        // 「why」在残缺部分之前，也应该被保住
        assertEquals("先接住", s.why)
        assertTrue(s.partial)
    }

    @Test
    fun `截断到一条完整回复都不剩时提示是截断`() {
        try {
            SuggestionParser.parse("""{"intent":"x","replies":[{"style":"稳妥","text":"周""")
            fail("应该抛异常")
        } catch (e: LlmException) {
            assertTrue(e.message!!.contains("截断"))
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `JSON 里有转义引号时抢救不会砍错位置`() {
        val raw = """{"intent":"x","risk":"低","note":"他说\"在的\"","replies":[{"style":"稳妥","text":"好的"},{"style":"幽默","text":"那"""
        val s = SuggestionParser.parse(raw)
        assertEquals(1, s.replies.size)
        assertEquals("好的", s.replies[0].text)
        assertTrue(s.partial)
    }
}
