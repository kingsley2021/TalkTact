package dev.goutou.wingman.wechat

import android.app.Activity
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XC_MethodReplacement
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import de.robv.android.xposed.XSharedPreferences
import dev.goutou.wingman.config.Keys
import dev.goutou.wingman.config.MODULE_PKG
import dev.goutou.wingman.config.PREF_NAME

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
            "com.tencent.mm" -> if (lpparam.processName == "com.tencent.mm") hookWeChat(lpparam)
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

    /**
     * 上次运行学到过「自己画字」的控件类，这里在聊天页渲染之前先把钩子挂好，
     * 这样第二次打开聊天页不用等重新绑定就能读到正文。
     */
    private fun preHookLearned(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val prefs = XSharedPreferences(MODULE_PKG, PREF_NAME)
            if (!prefs.file.canRead()) return
            prefs.reload()
            val names = prefs.getStringSet(Keys.LEARNED, emptySet()).orEmpty()
            var hooked = 0
            for (name in names) {
                if (TextCapture.hookByName(lpparam.classLoader, name, remember = false)) hooked++
            }
            if (hooked > 0) XposedBridge.log("[Goutou] 已预先挂好 $hooked 个控件类")
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] preHook 失败: $t")
        }
    }

    private fun hookWeChat(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("[Goutou] hooked com.tencent.mm (module 0.2.6)")
        preHookLearned(lpparam)
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
