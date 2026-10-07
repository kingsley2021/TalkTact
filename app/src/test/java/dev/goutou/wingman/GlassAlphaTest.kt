package dev.goutou.wingman

import dev.goutou.wingman.ui.edgeAlpha
import dev.goutou.wingman.ui.fillAlpha
import dev.goutou.wingman.ui.sweepAlpha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「玻璃强度」的三个换算公式（第 7 版玻璃重构）。
 *
 * 钉住的是这条设计约束：**只调「透」，不调「厚」**。
 * 用户实测的翻车方式就是「滑到最低档，整个界面变成一张纸」—— 以前填充 / 描边 / 顶边 / 底边 /
 * 阴影**全都乘** glassAlpha，厚度被一起调没了。现在只有填充与描边随它走，而且都留了下限。
 */
class GlassAlphaTest {

    @Test fun `fill stays visible at the lowest strength`() {
        // 0.62 的填充，强度拉到 0 也还有 0.50 —— 不会变成一张纸
        assertEquals(0.50f, fillAlpha(0.62f, 0f), 0.001f)
        assertEquals(0.62f, fillAlpha(0.62f, 0.5f), 0.001f)
        assertEquals(0.74f, fillAlpha(0.62f, 1f), 0.001f)
    }

    @Test fun `fill never exceeds one`() {
        assertTrue(fillAlpha(0.95f, 1f) <= 1f)
    }

    @Test fun `edge never fades below sixty percent`() {
        assertEquals(0.51f, edgeAlpha(0.85f, 0f), 0.001f)
        assertEquals(0.85f, edgeAlpha(0.85f, 1f), 0.001f)
    }

    @Test fun `sweep keeps a third of its brightness at the lowest strength`() {
        assertEquals(0.03f, sweepAlpha(0.10f, 0f), 0.001f)
        assertEquals(0.10f, sweepAlpha(0.10f, 1f), 0.001f)
    }
}
