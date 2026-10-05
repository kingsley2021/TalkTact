package dev.goutou.wingman

import dev.goutou.wingman.config.chatAllowed
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会话白名单的判定（纯函数）。 */
class WhitelistTest {

    @Test
    fun `没开白名单时一律放行`() {
        assertTrue(chatAllowed(whitelistEnabled = false, whitelist = emptySet(), name = "张三"))
        assertTrue(chatAllowed(whitelistEnabled = false, whitelist = emptySet(), name = ""))
    }

    @Test
    fun `开了白名单只认集合里的`() {
        val wl = setOf("张三", "老同学群")
        assertTrue(chatAllowed(true, wl, "张三"))
        assertTrue(chatAllowed(true, wl, "老同学群"))
        assertFalse(chatAllowed(true, wl, "李四"))
    }

    @Test
    fun `开着但集合为空时谁都不放行`() {
        // 这是有意的：用户把开关打开却还没加人，应该表现为「都没反应」，
        // 而不是「悄悄全都放行了」—— 后者会让人以为白名单没生效。
        assertFalse(chatAllowed(true, emptySet(), "张三"))
    }

    @Test
    fun `名字两边都归一化再比`() {
        val wl = setOf("张三")
        assertTrue(chatAllowed(true, wl, " 张三 "))
        // 微信标题常带未读数/条数，normalizeKey 会去掉尾部计数
        assertTrue(chatAllowed(true, wl, "张三(3)"))
        assertFalse(chatAllowed(true, wl, ""))
        assertFalse(chatAllowed(true, wl, "   "))
    }
}
