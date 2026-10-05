package dev.goutou.wingman

import dev.goutou.wingman.llm.DEFAULT_PROMPT
import dev.goutou.wingman.llm.Reply
import dev.goutou.wingman.llm.Suggestion
import dev.goutou.wingman.llm.gradedReplyPrompt
import dev.goutou.wingman.llm.gradedWaitMs
import dev.goutou.wingman.llm.gradedRiskPrompt
import dev.goutou.wingman.llm.mergeGraded
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分级模式最要紧的一件事：**任一路挂了，悬浮窗也得有东西渲染**。
 * 所以合并逻辑做成纯函数，四个组合逐个钉住。
 */
class GradedTest {

    private val risk = Suggestion(intent = "约饭", risk = "低", note = "只问了时间")
    private val reply = Suggestion(
        replies = listOf(Reply("稳妥", "好呀"), Reply("幽默", "我请客？"), Reply("推进", "周六？")),
        best = 2,
        why = "给两个具体日期",
    )

    @Test
    fun `两路都成功就合成一张卡`() {
        val s = mergeGraded(risk, reply, null, null)!!
        assertEquals("约饭", s.intent)
        assertEquals("低", s.risk)
        assertEquals("只问了时间", s.note)
        assertEquals(3, s.replies.size)
        assertEquals(2, s.best)
        assertEquals("给两个具体日期", s.why)
        assertTrue(s.warnings.isEmpty())
        assertFalse(s.partial)
    }

    @Test
    fun `只有风险那一路成功也不能是空白`() {
        val s = mergeGraded(risk, null, null, "请求超时")!!
        assertEquals("低", s.risk)
        assertEquals("约饭", s.intent)
        assertTrue(s.replies.isEmpty())
        assertEquals(1, s.warnings.size)
        assertTrue(s.warnings[0].contains("写回复"))
    }

    @Test
    fun `只有回复那一路成功时风险显示未评估`() {
        val s = mergeGraded(null, reply, "HTTP 401", null)!!
        assertEquals("未评估", s.risk)
        assertEquals(3, s.replies.size)
        assertEquals("未识别", s.intent)
        assertTrue(s.warnings[0].contains("风险评估"))
    }

    @Test
    fun `两路都挂才是整体失败`() {
        assertNull(mergeGraded(null, null, "超时", "401"))
    }

    @Test
    fun `任一路被截断都标记 partial`() {
        assertTrue(mergeGraded(risk.copy(partial = true), reply, null, null)!!.partial)
        assertTrue(mergeGraded(risk, reply.copy(partial = true), null, null)!!.partial)
    }

    // ---------------- 两路提示词 ----------------

    @Test
    fun `摘掉输出契约时连段落一起摘`() {
        val riskPrompt = gradedRiskPrompt(DEFAULT_PROMPT)
        // 旧契约（带 replies / best 的那整段）必须消失 —— 用它的原文来钉，别用「只输出…」这种
        // 两路契约里都有的措辞（RISK_CONTRACT 自己也写着「只输出下面这个 JSON」）
        assertFalse(riskPrompt.contains("\"replies\""))
        assertFalse(riskPrompt.contains("\"intent\":\"一句话\""))
        assertTrue(riskPrompt.endsWith("\"note\":\"一句话提醒\"}"))
    }

    @Test
    fun `两路各带自己的契约`() {
        val riskPrompt = gradedRiskPrompt(DEFAULT_PROMPT)
        val replyPrompt = gradedReplyPrompt(DEFAULT_PROMPT)
        assertTrue(riskPrompt.contains("\"risk\""))
        assertTrue(replyPrompt.contains("\"replies\""))
        assertTrue(replyPrompt.contains("\"best\""))
        // 风险那路不该再被要求写回复
        assertFalse(riskPrompt.contains("\"text\""))
        // 两路都还带着 skill 正文（不能把用户选的人格弄丢）
        assertTrue(riskPrompt.contains("狗头军师"))
        assertTrue(replyPrompt.contains("狗头军师"))
    }

    @Test
    fun `没有契约的自定义提示词也能用`() {
        val custom = "你是我的助理，随便聊。"
        assertEquals(custom + "\n\n" + dev.goutou.wingman.llm.RISK_CONTRACT, gradedRiskPrompt(custom))
    }

    // ---------------- 两路对齐等待 ----------------

    @Test
    fun `没测过就给 60 秒兜底`() {
        assertEquals(60_000L, gradedWaitMs(0L, 0L))
    }

    @Test
    fun `按慢的那一路放大 并夹在上下限之间`() {
        // 慢的是 900ms → 900*4+10000 = 13600，低于下限 → 60 秒
        assertEquals(60_000L, gradedWaitMs(500L, 900L))
        // 慢的是 20s → 20*4+10 = 90 秒
        assertEquals(90_000L, gradedWaitMs(3_000L, 20_000L))
        // 慢的是 60s → 250 秒，超过上限 → 180 秒
        assertEquals(180_000L, gradedWaitMs(60_000L, 1_000L))
    }
}
