package dev.goutou.wingman

import android.app.Application
import dev.goutou.wingman.config.RemoteSync

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
    }
}
