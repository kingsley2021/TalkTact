package dev.goutou.wingman

import dev.goutou.wingman.llm.sanitizeOneLine
import org.junit.Assert.assertEquals
import org.junit.Test

/** 「单条改写」返回值的净化：模型很爱加引号、加前缀、多解释两行。 */
class RewriteSanitizeTest {

    @Test
    fun `去掉成对的引号`() {
        assertEquals("好呀", sanitizeOneLine("\"好呀\""))
        assertEquals("好呀", sanitizeOneLine("“好呀”"))
        assertEquals("好呀", sanitizeOneLine("「好呀」"))
    }

    @Test
    fun `去掉改写后这类前缀`() {
        assertEquals("那就周六吧", sanitizeOneLine("改写后：那就周六吧"))
        assertEquals("在的", sanitizeOneLine("改写: 在的"))
        assertEquals("收到啦", sanitizeOneLine("结果：收到啦"))
    }

    @Test
    fun `多行只留第一行并剥掉围栏`() {
        assertEquals("好呀", sanitizeOneLine("```\n好呀\n```"))
        assertEquals("行啊", sanitizeOneLine("行啊\n（说明：我把它改短了）"))
    }

    @Test
    fun `前后空白与换行都清掉`() {
        assertEquals("嗯嗯", sanitizeOneLine("   \n 嗯嗯 \n  "))
        assertEquals("", sanitizeOneLine("   "))
    }

    @Test
    fun `正常一句话原样返回`() {
        assertEquals("周六或周日下午都行，你定", sanitizeOneLine("周六或周日下午都行，你定"))
    }
}
