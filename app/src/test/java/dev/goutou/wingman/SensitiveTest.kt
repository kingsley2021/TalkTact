package dev.goutou.wingman

import dev.goutou.wingman.wechat.Sensitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensitiveTest {

    @Test
    fun `命中高危内容`() {
        assertTrue(Sensitive.hits("把验证码发我").contains("验证码/动态码"))
        assertTrue(Sensitive.hits("卡号 6222021234567890123").contains("银行卡号"))
        assertTrue(Sensitive.hits("帮我转账 500 好吗").contains("转账/红包/借钱"))
        assertTrue(Sensitive.hits("身份证 11010119900307123X").contains("身份证号"))
    }

    @Test
    fun `长数字串不会误判成手机号`() {
        assertFalse(Sensitive.hits("订单号 6222021234567890123").contains("手机号"))
    }

    @Test
    fun `正常聊天不误报`() {
        assertEquals(emptyList<String>(), Sensitive.hits("周末一起去爬山吗"))
    }

    @Test
    fun `redact 只打码命中规则的片段`() {
        val red = Sensitive.redact("验证码是 123456")
        assertFalse(red.contains("验证码"))
        assertTrue(red.contains("«已屏蔽»"))
    }
}
