package dev.goutou.wingman.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 把本地配置镜像到框架的 remote preferences。
 *
 * 为什么必须有这一层：迁移到现代 API 后，注入进程里再没有 XSharedPreferences 可用
 * （它依赖「两边共享一份世界可读的 XML」，正是被标记为废弃的那套机制）。
 * 现代 API 给的读取口是 `getRemotePreferences(group)`，而那份数据**只存在框架自己的数据库里**，
 * 框架并不会去读模块的 shared_prefs —— 也就是说，必须由 App 这边主动写进去。
 *
 * 所以策略是：App 自己**仍然以本地 SharedPreferences 为准**（界面、导出/导入完全不受影响），
 * 但任何一次改动都顺手镜像一份到框架；注入侧读到的就是这一份，而且是框架实时推过去的。
 *
 * 挂点只有一个：SharedPreferences 的变更监听。这样就不存在「某个字段忘了同步」的问题 ——
 * 心跳回传的 learned/roles/diag、设置页的每一项，都走同一条路。
 */
object RemoteSync {

    private const val TAG = "TalkTact"

    private var appContext: Context? = null

    @Volatile
    private var remote: SharedPreferences? = null

    /** 框架是否已经把 service 交到本进程手上。 */
    @Volatile
    var bound: Boolean = false
        private set

    /** 最近一次失败原因（排查用）。 */
    @Volatile
    var lastError: String? = null
        private set

    /**
     * 注意：SharedPreferences 内部对监听器是**弱引用**，写成临时对象会被回收掉，
     * 所以这里必须留一个强引用（object 的字段正好满足）。
     */
    private val changeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key != null) push(key)
    }

    private val serviceListener = object : XposedServiceHelper.OnServiceListener {
        override fun onServiceBind(service: XposedService) {
            try {
                remote = service.getRemotePreferences(PREF_NAME)
                bound = true
                lastError = null
                Log.i(TAG, "[Goutou] 已连上 Xposed 服务，开始镜像配置")
                syncAll()
            } catch (t: Throwable) {
                bound = false
                remote = null
                lastError = "getRemotePreferences 失败：$t"
                Log.w(TAG, "[Goutou] $lastError")
            }
        }

        override fun onServiceDied(service: XposedService) {
            bound = false
            remote = null
            Log.w(TAG, "[Goutou] Xposed 服务已断开（配置改动会先记在本地，等服务回来再补）")
        }
    }

    /**
     * 装上镜像。
     *
     * 放在 Application.onCreate 里调，而不是 Activity：框架递 binder 的时机是
     * 「模块 App 进程启动、Application.onCreate 之前」，而这个进程**不一定**是用户点图标拉起来的
     * （框架自己也会把它拉起来），用 Activity 会漏掉那种情况。
     */
    fun install(context: Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx

        try {
            local(ctx).registerOnSharedPreferenceChangeListener(changeListener)
        } catch (t: Throwable) {
            lastError = "监听本地配置失败：$t"
            Log.w(TAG, "[Goutou] $lastError")
        }

        try {
            // 框架可能已经把 binder 先塞进来了（XposedServiceHelper 会缓存），
            // 也可能还没起来；两种情况都由这个 listener 兜住。
            XposedServiceHelper.registerListener(serviceListener)
        } catch (t: Throwable) {
            lastError = "注册 Xposed 服务监听失败：$t"
            Log.w(TAG, "[Goutou] $lastError")
        }

        // 服务已经连上时不会再有 onServiceBind 回调，所以这里补一次全量
        syncAll()
    }

    /** 全量镜像：框架刚连上、或服务断开期间攒下的改动，都靠它补齐。 */
    fun syncAll() {
        val ctx = appContext ?: return
        if (remote == null) return
        for (key in local(ctx).all.keys) push(key)
    }

    private fun local(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun push(key: String) {
        val p = remote ?: return
        val ctx = appContext ?: return
        try {
            val value = local(ctx).all[key] ?: return
            val editor = p.edit()
            when (value) {
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Float -> editor.putFloat(key, value)
                is Boolean -> editor.putBoolean(key, value)
                // 框架那边是按 Serializable 存的，HashSet 稳过；
                // SharedPreferences 给出来的往往是不可变包装类，所以重新收一遍
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toHashSet())
                else -> return
            }
            editor.apply()
        } catch (t: Throwable) {
            lastError = "镜像 $key 失败：$t"
            Log.w(TAG, "[Goutou] $lastError")
        }
    }
}
