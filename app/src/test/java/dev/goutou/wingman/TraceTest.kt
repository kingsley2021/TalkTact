package dev.goutou.wingman

import dev.goutou.wingman.wechat.Trace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 「决策轨迹」环形缓冲的单测。
 *
 * 为什么值得测：它跑在微信主线程上、每 900ms 记一次，还决定了「自动回传会不会把广播淹了」。
 * 合并重复、容量上限、版本号水位这三条最容易写错，而写错了在真机上只表现为
 * 「App 里那股轨迹怎么看怎么不对」，很难反推是这里的问题。
 */
class TraceTest {

    private var now = 1_000_000L

    @Before
    fun setUp() {
        Trace.resetForTest()
        Trace.setClockForTest { now }
    }

    @After
    fun tearDown() {
        Trace.resetForTest()
    }

    @Test
    fun `记一条就多一条，首尾空白会被去掉`() {
        Trace.note("输入", "  有候选（EditText 300×80）  ")
        assertEquals(1, Trace.size())
        val d = Trace.dump()
        assertTrue(d.contains("输入 有候选（EditText 300×80）"))
        assertFalse(d.contains("  有候选"))
    }

    @Test
    fun `连着两条一模一样只留一条、次数累加`() {
        now = 1_000L
        Trace.note("跳过", "这一屏和上一轮一模一样")
        now = 2_000L
        Trace.note("跳过", "这一屏和上一轮一模一样")
        assertEquals(1, Trace.size())
        val d = Trace.dump()
        assertTrue(d.contains("（×2）"))
        // 时间跟着最后一次走：所以「最近一次是什么时候」看的是这一行
        assertTrue(d.contains("now"))
    }

    @Test
    fun `tag 或文本只要有一个不同就另起一条`() {
        Trace.note("列表", "没找到")
        Trace.note("列表", "找到了")
        Trace.note("输入", "没找到")
        assertEquals(3, Trace.size())
    }

    @Test
    fun `版本号只在新增时加一，合并重复不算`() {
        assertEquals(0, Trace.version())
        Trace.note("跳过", "指纹没变")
        Trace.note("跳过", "指纹没变")
        Trace.note("跳过", "指纹没变")
        assertEquals(1, Trace.version())
        Trace.note("跳过", "内容变了")
        assertEquals(2, Trace.version())
        // 清空也算一次变化：水位得动，否则清完之后自动回传不会把「空了」发出去
        Trace.clear()
        assertEquals(3, Trace.version())
        assertEquals(0, Trace.size())
    }

    @Test
    fun `超过容量丢最旧的，序号继续累加`() {
        repeat(Trace.CAP + 5) { Trace.note("n", "第 $it 条") }
        assertEquals(Trace.CAP, Trace.size())
        val d = Trace.dump()
        assertFalse(d.contains("第 0 条"))
        assertTrue(d.contains("第 5 条"))
        assertTrue(d.contains("第 ${Trace.CAP + 4} 条"))
        // 序号不回绕：靠它看「中间被挤掉了几轮」
        assertTrue(d.contains("#${Trace.CAP + 4} "))
    }

    @Test
    fun `单条超长会被截断`() {
        Trace.note("长", "字".repeat(Trace.MAX_CHARS + 50))
        val d = Trace.dump()
        assertTrue(d.contains("字".repeat(Trace.MAX_CHARS)))
        assertFalse(d.contains("字".repeat(Trace.MAX_CHARS + 1)))
    }

    @Test
    fun `空空的一条不记`() {
        Trace.note("", "")
        Trace.note("   ", "   ")
        assertEquals(0, Trace.size())
    }

    @Test
    fun `渲染是新在上，空的时候给引导语`() {
        assertTrue(Trace.dump().contains("还没有轨迹"))
        Trace.note("一", "第一条")
        Trace.note("二", "第二条")
        val d = Trace.dump()
        assertTrue(d.indexOf("#1 ") < d.indexOf("#0 "))
    }

    @Test
    fun `相对时间`() {
        now = 0L
        Trace.note("a", "x")
        assertTrue(Trace.dump(now = 400L).contains("now"))
        assertTrue(Trace.dump(now = 3_400L).contains("-3.4s"))
        assertTrue(Trace.dump(now = 72_000L).contains("-1m12s"))
    }
}
