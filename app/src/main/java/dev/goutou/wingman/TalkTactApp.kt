package dev.goutou.wingman

import android.app.Application
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.RemoteSync
import dev.goutou.wingman.style.SelfStyle

/**
 * 存在的唯一目的：把配置镜像挂上。
 *
 * 为什么不能写在 MainActivity 里：框架把 service binder 递过来的时机是
 * 「模块 App 进程启动、Application.onCreate 之前」，而这个进程不一定是用户点图标起来的
 * （框架自己会拉）。挂在 Application 上才算把所有入口都覆盖住。
 */
class TalkTactApp : Application() {
    override fun onCreate() {
        super.onCreate()
        RemoteSync.install(this)

        // 「说话风格 skill」开着的话，确保「每天那个点」的任务排着。
        // WorkManager 自己会持久化，这里只是兜底：重装、清数据、被系统回收之后重新排上。
        val store = ConfigStore(this)
        if (store.selfStyleEnabled()) SelfStyle.schedule(this, store.selfStyleHour())
    }
}
