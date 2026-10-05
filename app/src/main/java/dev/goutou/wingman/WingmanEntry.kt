package dev.goutou.wingman

import android.app.Activity
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.MODULE_PKG
import dev.goutou.wingman.config.NAMESPACE
import dev.goutou.wingman.wechat.PanelRegistry
import dev.goutou.wingman.wechat.TextCapture
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface

/**
 * 模块入口（libxposed Modern API 102）。
 *
 * 和 legacy 入口（IXposedHookLoadPackage.handleLoadPackage）的对应关系：
 *
 * | legacy                                   | 现在                                  |
 * |------------------------------------------|---------------------------------------|
 * | `handleLoadPackage(lpparam)`             | `onPackageReady(param)`               |
 * | `lpparam.packageName`                    | `param.packageName`                   |
 * | `lpparam.classLoader`                    | `param.classLoader`                   |
 * | `lpparam.processName`                    | `onModuleLoaded` 的 `param.processName` |
 * | `XC_MethodHook` 的 before/after           | 拦截器链 `intercept { chain -> ... }`  |
 *
 * 认聊天页的策略**没有变**：不靠类名猜 LauncherUI/ChattingUI（微信一改版本就失效），
 * 而是挂在每个 Activity 的 onResume/onPause 上，由面板自己用
 * 「有没有输入框 + 有没有消息列表」的结构特征判断。
 *
 * 类名被 `META-INF/xposed/java_init.list` 按字符串引用，改名要两边一起改。
 */
class WingmanEntry : XposedModule() {

    /**
     * onPackageReady 的 param 里没有进程名，只有包名，所以进程名得从 onModuleLoaded 存下来。
     * 微信有 :push 之类的子进程，模块只该在主进程里干活。
     */
    private var processName = ""

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        XposedApi.bind(this)
        processName = param.processName
        XposedApi.log("已加载：进程=$processName system_server=${param.isSystemServer}")
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        when (param.packageName) {
            // 自己的 App 进程：点掉「模块已激活」探针
            MODULE_PKG -> hookSelf(param.classLoader)

            "com.tencent.mm" -> if (processName == "com.tencent.mm") hookWeChat(param.classLoader)
        }
    }

    /**
     * 探针。
     *
     * App 首页据此显示「微信内已生效」——是真检测，而不是原版那种「请你自己点一下确认」。
     * 类名/包名按字符串引用（模块 App 自己的进程里，它的类由应用 ClassLoader 加载，
     * 所以要用 param.classLoader 去 load，不能直接用 ::class.java）。
     */
    private fun hookSelf(classLoader: ClassLoader) {
        try {
            val cls = classLoader.loadClass("$NAMESPACE.ModuleStatus")
            val method = cls.getDeclaredMethod("isActive")
            // 完全不调 chain.proceed()：等价于 legacy 的 XC_MethodReplacement.returnConstant(true)
            hook(method).setId(HOOK_SELF).intercept { true }
            XposedApi.log("已 hook 自身探针 $NAMESPACE.ModuleStatus.isActive")
        } catch (t: Throwable) {
            XposedApi.log("hook self failed: $t")
        }
    }

    private fun hookWeChat(classLoader: ClassLoader) {
        XposedApi.log("hooked com.tencent.mm")
        preHookLearned(classLoader)

        try {
            // onResume：先让微信跑完，再拉起面板（等价于 legacy 的 afterHookedMethod）
            val onResume = Activity::class.java.getDeclaredMethod("onResume")
            hook(onResume).setId(HOOK_RESUME).intercept { chain ->
                val result = chain.proceed()
                val activity = chain.thisObject as? Activity
                if (activity != null) {
                    try {
                        PanelRegistry.onResume(activity)
                    } catch (t: Throwable) {
                        XposedApi.log("onResume: $t")
                    }
                }
                result
            }

            // onPause：先收面板，再让微信继续（等价于 legacy 的 beforeHookedMethod）
            val onPause = Activity::class.java.getDeclaredMethod("onPause")
            hook(onPause).setId(HOOK_PAUSE).intercept { chain ->
                val activity = chain.thisObject as? Activity
                if (activity != null) {
                    try {
                        PanelRegistry.onPause(activity)
                    } catch (t: Throwable) {
                        XposedApi.log("onPause: $t")
                    }
                }
                chain.proceed()
            }
        } catch (t: Throwable) {
            XposedApi.log("hook WeChat failed: $t")
        }
    }

    /**
     * 上次运行学到过「自己画字」的控件类，这里在聊天页渲染之前先把钩子挂好，
     * 这样第二次打开聊天页不用等重新绑定就能读到正文。
     */
    private fun preHookLearned(classLoader: ClassLoader) {
        try {
            val prefs = XposedApi.prefs() ?: return
            val names = prefs.getStringSet(Keys.LEARNED, emptySet()).orEmpty()
            var hooked = 0
            for (name in names) {
                if (TextCapture.hookByName(classLoader, name, remember = false)) hooked++
            }
            if (hooked > 0) XposedApi.log("已预先挂好 $hooked 个控件类")
        } catch (t: Throwable) {
            XposedApi.log("preHook 失败: $t")
        }
    }

    private companion object {
        const val HOOK_SELF = "wingman.module.status"
        const val HOOK_RESUME = "wingman.activity.onResume"
        const val HOOK_PAUSE = "wingman.activity.onPause"
    }
}
