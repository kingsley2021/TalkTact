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
object Graded {
    fun run(
        replyClient: LlmClient,
        riskClient: LlmClient,
        skillPrompt: String,
        msgs: List<ChatMsg>,
        roleContext: String?,
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

        // 等风险那一路（它可能用的是另一套接口，慢一点正常）：给 30 秒上限，超时按失败算
        runCatching { riskThread.join(30_000) }
        if (riskThread.isAlive && riskErr == null) riskErr = "风险那一路等太久了（超过 30 秒）"

        return GradedOutcome(
            suggestion = mergeGraded(riskSug, replySug, riskErr, replyErr),
            riskError = riskErr,
            replyError = replyErr,
            totalTokens = riskTokens + replyTokens,
            millis = System.currentTimeMillis() - start,
        )
    }
}
