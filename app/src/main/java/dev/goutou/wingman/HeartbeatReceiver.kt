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
    const val ACTION = "dev.goutou.wingman.HEARTBEAT"

    /**
     * 接收方必须持有该权限，发送方（微信进程）不需要任何权限 ——
     * 正好绕开「不能给微信加权限」这个限制。
     */
    const val PERMISSION = "dev.goutou.wingman.permission.HEARTBEAT"

    fun send(context: Context, tokens: Int) {
        try {
            context.sendBroadcast(
                Intent(ACTION).setPackage(MODULE_PKG).putExtra("tokens", tokens),
                PERMISSION,
            )
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
        editor.apply()
    }
}
