package dev.goutou.wingman.llm

import dev.goutou.wingman.wechat.ChatMsg

/** 分级模式的结果：合并后的卡片内容 + 两路各自的情况。 */
data class GradedOutcome(
    /** null = 两路都挂了（调用方按整体失败处理） */
    val suggestion: Suggestion?,
    val riskError: String?,
    val replyError: String?,
    val totalTokens: Int,
    val millis: Long,
)

/**
 * 把两路的结果合成「一张卡片要的东西」。
 *
 * 铁律：**任一路挂了都不能让整次调用失败** —— 悬浮窗必须永远有东西可渲染，
 * 不能出现「模型明明返回了、界面却是空的」。所以这里做成纯函数，四个组合都有单测。
 *
 * 合并规则：
 * - 意图 / 风险 / 提醒 只可能来自风险那一路（回复那一路的契约里没有这三样）；
 * - 候选回复 / best / why 只可能来自回复那一路；
 * - 挂掉的那一路写进 [Suggestion.warnings]，界面照常渲染另一路。
 */
fun mergeGraded(
    risk: Suggestion?,
    reply: Suggestion?,
    riskError: String?,
    replyError: String?,
): Suggestion? {
    if (risk == null && reply == null) return null
    val warnings = buildList {
        if (risk == null) add("⚠ 风险评估那一路没跑通：${riskError ?: "未知错误"}")
        if (reply == null) add("⚠ 写回复那一路没跑通：${replyError ?: "未知错误"} —— 点「刷新」重试")
    }
    return Suggestion(
        intent = risk?.intent?.takeIf { it.isNotBlank() } ?: "未识别",
        risk = risk?.risk ?: "未评估",
        note = risk?.note.orEmpty(),
        replies = reply?.replies.orEmpty(),
        best = reply?.best,
        why = reply?.why.orEmpty(),
        partial = (risk?.partial == true) || (reply?.partial == true),
        warnings = warnings,
    )
}

/**
 * 跑一次分级模式：风险一路 + 写回复一路，**两路各自成败**，最后合并。
 *
 * 两路**并行**跑：串行会把等待时间翻倍，而一次调用本来就要 5~15 秒。
 * 任一路抛异常都只体现在那一侧的 warning 里，绝不让整次调用失败。
 */
/**
 * 两路「对齐」要等多久（毫秒）。
 *
 * 自检测到的往返是**最小请求**（一句 ping、只回 1 个 token），真正生成要久得多，
 * 所以基准给足余地：没测过 → 60 秒；测过 → **慢的那一路 × 4 + 10 秒**，夹在 60~180 秒。
 *
 * 它只是「别让一路卡住把界面吊死」的兜底上限 —— 两路本来就是并行跑、**一起出结果**，
 * 快的那一路不会先把半张卡片显出来（这才是「延迟低的等延迟高的」真正的意思）。
 */
fun gradedWaitMs(probeReplyMs: Long, probeRiskMs: Long): Long {
    val slow = maxOf(probeReplyMs, probeRiskMs)
    if (slow <= 0L) return 60_000L
    return (slow * 4 + 10_000L).coerceIn(60_000L, 180_000L)
}

object Graded {
    fun run(
        replyClient: LlmClient,
        riskClient: LlmClient,
        skillPrompt: String,
        msgs: List<ChatMsg>,
        roleContext: String?,
        /** 等慢的那一路多久（见 [gradedWaitMs]） */
        waitMs: Long = 60_000L,
    ): GradedOutcome {
        val start = System.currentTimeMillis()
        var riskSug: Suggestion? = null
        var riskErr: String? = null
        var riskTokens = 0
        var replySug: Suggestion? = null
        var replyErr: String? = null
        var replyTokens = 0

        val riskThread = Thread {
            try {
                val r = riskClient.analyzeWith(gradedRiskPrompt(skillPrompt), msgs, roleContext)
                riskSug = r.suggestion
                riskTokens = r.totalTokens
            } catch (t: Throwable) {
                riskErr = t.message ?: t.javaClass.simpleName
            }
        }
        riskThread.start()

        try {
            val r = replyClient.analyzeWith(gradedReplyPrompt(skillPrompt), msgs, roleContext)
            replySug = r.suggestion
            replyTokens = r.totalTokens
        } catch (t: Throwable) {
            replyErr = t.message ?: t.javaClass.simpleName
        }

        // 等风险那一路（它可能用的是另一套接口，慢一点很正常）：按自检测出的延迟给上限，超时按失败算。
        // 注意这里是「一起出结果」而不是「谁先回来先显」—— 快的那一路等慢的，卡片不会闪两次。
        runCatching { riskThread.join(waitMs) }
        if (riskThread.isAlive && riskErr == null) riskErr = "风险那一路等太久了（超过 ${waitMs / 1000} 秒）"

        return GradedOutcome(
            suggestion = mergeGraded(riskSug, replySug, riskErr, replyErr),
            riskError = riskErr,
            replyError = replyErr,
            totalTokens = riskTokens + replyTokens,
            millis = System.currentTimeMillis() - start,
        )
    }
}
