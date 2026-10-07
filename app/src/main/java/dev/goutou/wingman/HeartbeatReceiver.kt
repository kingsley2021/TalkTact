package dev.goutou.wingman

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.MODULE_PKG
import dev.goutou.wingman.config.PREF_NAME

/**
 * 心跳。
 *
 * 注入进微信进程的代码写不了本 App 的私有文件（不同 UID），所以「我活着，而且刚用了 N 个 token」
 * 这件事得用广播传回来。首页的「微信内已生效」因此是真检测，而不是原版那种「请你自己点一下确认」。
 */
object Heartbeat {
    const val ACTION = "io.github.shibry88_netizen.talktact.HEARTBEAT"

    /**
     * 接收方必须持有该权限，发送方（微信进程）不需要任何权限 ——
     * 正好绕开「不能给微信加权限」这个限制。
     */
    const val PERMISSION = "io.github.shibry88_netizen.talktact.permission.HEARTBEAT"

    fun send(
        context: Context,
        tokens: Int,
        diag: String? = null,
        learned: String? = null,
        call: String? = null,
        roles: String? = null,
        /** 这次调用实际走的路线（proxy / direct），回传给 App 显示 */
        route: String? = null,
        /** 会话名候选（换行分隔）—— 白名单页的「拉取会话列表」用它 */
        chats: String? = null,
        /** 上面那次拉取的现场说明（扫到几个列表 / 几行），拉不到东西时靠它排查 */
        chatsInfo: String? = null,
        /** 图片文字识别：最近一次的结果（认到几个字 / 为什么没认），设置页显示它 */
        ocr: String? = null,
        /** 决策轨迹（ring buffer 渲染好的文本）：排查「读不到消息 / 卡片不弹」看它 */
        trace: String? = null,
        /** 实时探测的回执（App 用它判断「模块现在还在不在」，见 Keys.PROBE_REQ） */
        probe: String? = null,
    ) {
        try {
            val intent = Intent(ACTION).setPackage(MODULE_PKG).putExtra("tokens", tokens)
            if (diag != null) intent.putExtra("diag", diag)
            if (learned != null) intent.putExtra("learned", learned)
            if (call != null) intent.putExtra("call", call)
            if (roles != null) intent.putExtra("roles", roles)
            if (route != null) intent.putExtra("route", route)
            if (chats != null) intent.putExtra("chats", chats)
            if (chatsInfo != null) intent.putExtra("chatsInfo", chatsInfo)
            if (ocr != null) intent.putExtra("ocr", ocr)
            if (trace != null) intent.putExtra("trace", trace)
            if (probe != null) intent.putExtra("probe", probe)
            context.sendBroadcast(intent, PERMISSION)
        } catch (t: Throwable) {
            // 广播失败不影响主流程
        }
    }
}

class HeartbeatReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Heartbeat.ACTION) return
        val sp = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val tokens = intent.getIntExtra("tokens", 0)
        val editor = sp.edit().putLong(Keys.HEARTBEAT, System.currentTimeMillis())
        if (tokens > 0) {
            editor.putInt(Keys.CALLS, sp.getInt(Keys.CALLS, 0) + 1)
            editor.putInt(Keys.TOKENS, sp.getInt(Keys.TOKENS, 0) + tokens)
        }
        intent.getStringExtra("learned")?.let { name ->
            val old = sp.getStringSet(Keys.LEARNED, emptySet()) ?: emptySet()
            if (name !in old && old.size < 60) editor.putStringSet(Keys.LEARNED, old + name)
        }
        intent.getStringExtra("diag")?.let {
            editor.putString(Keys.DIAG, it)
            editor.putLong(Keys.DIAG_AT, System.currentTimeMillis())
        }
        intent.getStringExtra("call")?.let {
            editor.putString(Keys.LAST_CALL, it)
            editor.putLong(Keys.LAST_CALL_AT, System.currentTimeMillis())
        }
        // 这次实际走的路线：排查「明明开了本地代理，微信侧却还在直连」靠它
        intent.getStringExtra("route")?.let {
            editor.putString(Keys.ROUTE, it)
            editor.putLong(Keys.ROUTE_AT, System.currentTimeMillis())
        }
        // 图片文字识别：最近一次认了什么 / 为什么没认，设置页的 OCR 卡显示它
        intent.getStringExtra("ocr")?.let {
            editor.putString(Keys.OCR_INFO, it)
            editor.putLong(Keys.OCR_AT, System.currentTimeMillis())
        }
        // 决策轨迹：覆盖式保存（它本身就是「最近 N 条」，不需要合并历史）
        intent.getStringExtra("trace")?.let {
            editor.putString(Keys.TRACE, it)
            editor.putLong(Keys.TRACE_AT, System.currentTimeMillis())
        }
        // 实时探测的回执：记下「什么时候回的 + 当时在哪一屏」。
        // 心跳时间戳也会被上面那行刷新 —— 但界面判定「现在还在不在」只认这一条。
        intent.getStringExtra("probe")?.let {
            editor.putLong(Keys.PROBE_ACK, System.currentTimeMillis())
            editor.putString(Keys.PROBE_INFO, it)
        }
        // 会话名候选：给「白名单」页用。名字统一走 normalizeKey —— 微信标题常带未读数（张三(3)），
        // 而白名单里存的本来就是归一化过的 key，两边必须同一套，否则「拉回来却勾不上」。
        val chats = intent.getStringExtra("chats")
        val chatsInfo = intent.getStringExtra("chatsInfo")
        if (chats != null || chatsInfo != null) {
            if (!chats.isNullOrBlank()) {
                // 已经被「删掉」的候选不再收回来 —— 否则删完下一次拉取又原样长回来
                val ignored = sp.getStringSet(Keys.CHAT_IGNORED, emptySet()).orEmpty()
                val incoming = chats.split('\n')
                    .map { dev.goutou.wingman.config.Roles.normalizeKey(it) }
                    .filter { it.isNotBlank() && it.length <= 32 && it !in ignored }
                if (incoming.isNotEmpty()) {
                    val merged = LinkedHashSet(sp.getStringSet(Keys.CHAT_CANDIDATES, emptySet()).orEmpty())
                    merged.addAll(incoming)
                    editor.putStringSet(
                        Keys.CHAT_CANDIDATES,
                        if (merged.size <= 300) merged.toHashSet() else merged.toList().takeLast(300).toHashSet(),
                    )
                }
            }
            if (chatsInfo != null) editor.putString(Keys.CHAT_INFO, chatsInfo)
            // 只要收到了回应就刷时间戳 —— 界面靠它显示「上次拉取于 …」，拉空也算拉过
            editor.putLong(Keys.CHAT_AT, System.currentTimeMillis())
        }
        // 「角色」的聊天记录：注入侧每轮把新读到的消息回传，这里按 1 小时窗口查重后合并
        intent.getStringExtra("roles")?.let { payload ->
            val sp2 = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val incoming = dev.goutou.wingman.config.Roles.decodeIncoming(payload)
            if (incoming.isNotEmpty()) {
                val merged = dev.goutou.wingman.config.Roles.merge(
                    dev.goutou.wingman.config.Roles.decode(sp2.getString(Keys.ROLES, "").orEmpty()),
                    incoming,
                )
                sp2.edit().putString(Keys.ROLES, dev.goutou.wingman.config.Roles.encode(merged)).apply()
            }
        }
        editor.apply()
    }
}
