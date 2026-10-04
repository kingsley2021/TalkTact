package dev.goutou.wingman.wechat

import android.app.Activity
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.goutou.wingman.config.MODULE_PKG

/**
 * 入口。
 *
 * 和原版的区别：不再靠「类名里有没有 LauncherUI/ChattingUI」认聊天页。
 * 微信版本一变、或者聊天页变成 Fragment，那种写死的判断就会失效；
 * 现在挂在每个 Activity 的 onResume/onPause 上，由面板自己用「有没有输入框 + 有没有消息列表」结构特征判断。
 */
class WeChatHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            MODULE_PKG -> hookSelf(lpparam)
            "com.tencent.mm" -> if (lpparam.processName == "com.tencent.mm") hookWeChat()
        }
    }

    private fun hookSelf(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                "$MODULE_PKG.ModuleStatus",
                lpparam.classLoader,
                "isActive",
                XC_MethodReplacement.returnConstant(true),
            )
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] hook self failed: $t")
        }
    }

    private fun hookWeChat() {
        XposedBridge.log("[Goutou] hooked com.tencent.mm (module 0.2.0)")
        try {
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onResume",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        try {
                            PanelRegistry.onResume(activity)
                        } catch (t: Throwable) {
                            XposedBridge.log("[Goutou] onResume: $t")
                        }
                    }
                },
            )
            XposedHelpers.findAndHookMethod(
                Activity::class.java,
                "onPause",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val activity = param.thisObject as? Activity ?: return
                        try {
                            PanelRegistry.onPause(activity)
                        } catch (t: Throwable) {
                            XposedBridge.log("[Goutou] onPause: $t")
                        }
                    }
                },
            )
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] hook WeChat failed: $t")
        }
    }
}
