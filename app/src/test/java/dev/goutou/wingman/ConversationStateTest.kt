package dev.goutou.wingman

import dev.goutou.wingman.wechat.*
import org.junit.Assert.*
import org.junit.Test

class ConversationStateTest {
    private val messages = listOf(ChatMsg(false, "好的"))

    @Test fun `same message in different conversations has separate replies and records`() {
        assertNotEquals(
            resultKey(screenIdentity("张三", "屏", messages), "cfg"),
            resultKey(screenIdentity("项目群", "屏", messages), "cfg"),
        )
        assertNotEquals(roleObservationKey("张三", false, "好的"), roleObservationKey("李四", false, "好的"))
        assertEquals(roleObservationKey("张三", false, "好的"), roleObservationKey("张三", false, "好的"))
    }

    /**
     * **回归测试**：角色档案长起来不许让键失效。
     *
     * 以前这把键里混了 `roleContext`（角色档案每 900ms 就可能长一条），于是：
     * 同一屏反复 miss、反复问模型（白烧 token）；用户刚点过的「仍然分析这一条」下一轮就失效
     * （一直弹回风险卡）—— 用户实测报的就是这个。现在键只看「这一屏 + 设置」。
     */
    @Test fun `growing archives must not invalidate the key settings still should`() {
        val screen = screenIdentity("张三", "这一屏", messages)
        assertEquals(resultKey(screen, "cfg"), resultKey(screen, "cfg"))
        assertNotEquals(resultKey(screen, "cfg"), resultKey(screen, "model2"))
    }

    @Test fun `entire transcript including author direction and OCR participates in the screen identity`() {
        val original = screenIdentity("群", "屏", messages)
        for (changed in listOf(
            listOf(ChatMsg(true, "好的")),
            listOf(ChatMsg(false, "好的", "小明")),
            listOf(ChatMsg(false, "好的", attachment = true)),
            listOf(ChatMsg(false, "前文")) + messages,
            listOf(ChatMsg(false, "[图片] 新文字")),
        )) assertNotEquals(original, screenIdentity("群", "屏", changed))
    }

    @Test fun `length prefixed keys distinguish delimiter text and scopes`() {
        assertNotEquals(conversationDigest("a|b", "c"), conversationDigest("a", "b|c"))
        assertNotEquals(conversationDigest("ab", "c"), conversationDigest("a", "bc"))
        assertNotEquals(roleObservationKey("张三", true, "好的"), roleObservationKey("张三", false, "好的"))
        assertFalse(screenIdentity("张三", "屏", messages).contains("张三"))
    }
}
