package dev.goutou.wingman

import dev.goutou.wingman.wechat.ChipTap
import dev.goutou.wingman.wechat.chipTap
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 聊天页那个折叠按钮点一下该干什么（判定本体见 chipTap）。
 *
 * 钉住的核心是这一条：**手上有缓存时不许重新调模型**。这一档以前不存在 ——
 * 「缓存里有结果、hasResult 却被那些闲置分支清掉」的时候按钮走的是 regenerate()，
 * 而它会先把缓存删掉、再去问一次模型：点一下白烧一次 token，卡片还要等模型回来才有内容，
 * 用户看到的就是「点开又自动关闭 / 点了没反应」。
 */
class ChipTapTest {

    @Test fun `collapses when already expanded`() {
        assertEquals(ChipTap.Collapse, chipTap(expanded = true, busy = false, hasResult = true, hasCache = true))
    }

    @Test fun `opens while the model is still thinking`() {
        assertEquals(ChipTap.Expand, chipTap(expanded = false, busy = true, hasResult = false, hasCache = false))
    }

    @Test fun `opens when a result is already in hand`() {
        assertEquals(ChipTap.Expand, chipTap(expanded = false, busy = false, hasResult = true, hasCache = false))
    }

    /** 这一条就是这次修的 bug：缓存还在，就不该再去问模型。 */
    @Test fun `restores the cached result instead of calling the model again`() {
        assertEquals(ChipTap.Restore, chipTap(expanded = false, busy = false, hasResult = false, hasCache = true))
    }

    @Test fun `regenerates only when there is nothing to show`() {
        assertEquals(ChipTap.Regenerate, chipTap(expanded = false, busy = false, hasResult = false, hasCache = false))
    }
}
