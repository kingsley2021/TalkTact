package dev.goutou.wingman

import dev.goutou.wingman.config.chatAllowed
import dev.goutou.wingman.config.chatBlocked
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

    // ---------------- 注入侧真正用的那个判定（比 chatAllowed 多一道 fail-closed）----------------

    @Test
    fun `没开白名单时谁都不拦`() {
        assertFalse(chatBlocked(whitelistEnabled = false, whitelist = emptySet(), name = "张三"))
        assertFalse(chatBlocked(whitelistEnabled = false, whitelist = setOf("张三"), name = ""))
    }

    @Test
    fun `开着白名单却没加人时谁都拦`() {
        assertTrue(chatBlocked(whitelistEnabled = true, whitelist = emptySet(), name = "张三"))
        assertTrue(chatBlocked(whitelistEnabled = true, whitelist = emptySet(), name = "老同学群"))
    }

    @Test
    fun `认不出会话名时按拦下处理`() {
        // 有意为之（原来是放行）：白名单是隐私开关，标题认不出来时宁可这一屏什么都不做，
        // 也不能把内容读出来发到接口。界面上会写明原因，不会「完全没反应」。
        assertTrue(chatBlocked(whitelistEnabled = true, whitelist = setOf("张三"), name = ""))
        assertTrue(chatBlocked(whitelistEnabled = true, whitelist = setOf("张三"), name = "   "))
    }

    @Test
    fun `认得出又在名单里才放行`() {
        assertFalse(chatBlocked(whitelistEnabled = true, whitelist = setOf("张三"), name = "张三(3)"))
        assertTrue(chatBlocked(whitelistEnabled = true, whitelist = setOf("张三"), name = "李四"))
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
