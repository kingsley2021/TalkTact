package dev.goutou.wingman.proxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import dev.goutou.wingman.MainActivity
import dev.goutou.wingman.R
import dev.goutou.wingman.config.ConfigStore

/**
 * 本地代理的宿主服务。
 *
 * 为什么必须是**前台服务**：代理得在微信随时来请求时都活着，而 targetSdk 34 起常驻服务必须是前台服务
 * （带常驻通知，类型要在 manifest 里声明）。
 *
 * 类型选 `specialUse` 而不是 `dataSync`：dataSync 在 Android 15+ 有「每天最多 6 小时」的时长上限，
 * 而代理是要一直开着的，会被系统掐掉。specialUse 本来用于「不属于其它任何类型」的用途，
 * 我们不上应用商店，但它是这里最贴切的类型（manifest 里还写了 subtype 说明）。
 */
class ProxyService : Service() {

    companion object {
        private const val CHANNEL = "talktact-proxy"
        private const val NOTIF_ID = 1001
        const val ACTION_START = "dev.goutou.wingman.PROXY_START"
        const val ACTION_STOP = "dev.goutou.wingman.PROXY_STOP"

        /** 开代理。只能在界面里调 —— 后台启动前台服务从 Android 12 起会被系统拦掉。 */
        fun start(context: Context) {
            val i = Intent(context, ProxyService::class.java).setAction(ACTION_START)
            runCatching { context.startForegroundService(i) }
        }

        fun stop(context: Context) {
            val i = Intent(context, ProxyService::class.java).setAction(ACTION_STOP)
            runCatching { context.startService(i) }
        }

        /**
         * App 一打开就补一次。
         * 重启手机后代理不会自己回来（我们不做开机自启广播），用户开一次 App 就够了。
         */
        fun startIfEnabled(context: Context) {
            if (ConfigStore(context.applicationContext).load().proxyEnabled) start(context)
        }
    }

    private var server: ProxyServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            stopSelf()
            return START_NOT_STICKY
        }
        val cfg = ConfigStore(this).load()

        // 前台服务只是「别被系统回收」，**代理本身不依赖它** ——
        // 所以这里的失败只记下来给用户看，绝不 return：起不来通知也要把代理跑起来。
        runCatching { startForeground(NOTIF_ID, buildNotification(cfg.proxyPort)) }
            .onFailure { e ->
                ProxyState.lastError = "前台服务没起来（${e.javaClass.simpleName}：${e.message}）；代理仍在跑，但系统可能随时回收它"
            }

        if (server == null) {
            val s = ProxyServer(this)
            runCatching { s.start(cfg.proxyPort) }
                .onSuccess { server = s }
                .onFailure { e ->
                    server = null
                    ProxyState.running = false
                    ProxyState.lastError = when {
                        e.message?.contains("in use", ignoreCase = true) == true ->
                            "端口 ${cfg.proxyPort} 被占用了，换一个再试"
                        else -> "端口 ${cfg.proxyPort} 起不来：${e.message}"
                    }
                }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        ProxyState.running = false
        super.onDestroy()
    }

    private fun buildNotification(port: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "本地代理", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "API Key 留在 App 进程里的本地代理"
                    setShowBadge(false)
                },
            )
        }
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("TalkTact 本地代理运行中")
            .setContentText("127.0.0.1:$port · API Key 没有离开本应用")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }
}
