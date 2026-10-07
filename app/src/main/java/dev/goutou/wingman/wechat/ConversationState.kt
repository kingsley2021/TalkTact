package dev.goutou.wingman.wechat

import java.security.MessageDigest

/** Length-prefixed fields prevent ambiguous keys; digests keep content out of cache identifiers. */
internal fun conversationDigest(vararg fields: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fields.forEach { field ->
        val bytes = field.toByteArray(Charsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
        digest.update(':'.code.toByte())
        digest.update(bytes)
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

/**
 * 「这一屏」的稳定身份：会话名 + 屏幕指纹 + 这几条消息的内容。
 *
 * **刻意不含角色档案 / 设置 / 说话风格** —— 那些每轮都在长（[recordToRoles] 一直在往档案里补），
 * 混进来就会得到一个每轮都变的键。
 */
internal fun screenIdentity(name: String, screen: String, msgs: List<ChatMsg>): String =
    conversationDigest(
        name,
        screen,
        *msgs.map { conversationDigest(it.fromMe.toString(), it.who, it.text, it.attachment.toString()) }
            .toTypedArray(),
    )

/**
 * 结果缓存 / 请求去重 / 敏感放行 的键：只由「这一屏 + 设置」决定。
 *
 * ⚠️ 这里以前是 `conversationRequestKey(name, settings, roleContext, msgs)`，把角色档案也算了进去。
 * 档案每 900ms 就可能长一条，于是同一屏的键一直在变：
 * ① 缓存必 miss → 同一屏反复问模型（白烧 token）；
 * ② `pendingRequestKey` 对不上 → 在飞的请求被作废后立刻重发；
 * ③ 用户刚点过的「仍然分析这一条」下一轮就失效 → 一直弹回风险卡（用户实测就是这个）。
 * 「档案变了要不要重新问」由 ask() 那一刻的 roleContext 决定，**不该让缓存键跟着抖**。
 */
internal fun resultKey(screen: String, settings: String): String = conversationDigest(screen, settings)

internal fun roleObservationKey(name: String, fromMe: Boolean, text: String): String =
    conversationDigest(name, fromMe.toString(), text)
