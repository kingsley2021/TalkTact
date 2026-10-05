package dev.goutou.wingman

import android.content.SharedPreferences
import android.util.Log
import dev.goutou.wingman.config.PREF_NAME
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Executable

/**
 * 现代 API 的收口点，也是 legacy API 的唯一替代路径。
 *
 * 为什么需要它：libxposed 的 hook / log / 读配置都是 [XposedModule] 的**实例方法**，
 * 而模块里大量代码是单例或普通对象（Overlay 的 Panel、TextCapture），拿不到入口实例。
 * 入口在 onModuleLoaded 里把实例交给这里，其余代码统一走这个出口，
 * 调用点因此不必都改成实例方法。
 *
 * 对应表：
 *
 * | legacy                                   | 现在                        |
 * |------------------------------------------|-----------------------------|
 * | `XposedBridge.log(String)`                | [log]                       |
 * | `XposedHelpers.findAndHookMethod(...)`    | [hook]                      |
 * | `XposedHelpers.findClass(name, cl)`       | `cl.loadClass(name)` 即可    |
 * | `XSharedPreferences(pkg, name)`           | [prefs]                     |
 */
internal object XposedApi {

    private const val TAG = "TalkTact"

    @Volatile
    private var module: XposedModule? = null

    /** 由入口在 onModuleLoaded 里调用；模块代码在此之前不应做任何初始化。 */
    fun bind(m: XposedModule) {
        module = m
    }

    fun log(msg: String) {
        val m = module
        if (m == null) {
            // 只可能发生在 onModuleLoaded 之前；真发生了也别炸，退到 logcat
            runCatching { Log.i(TAG, "[Goutou] $msg") }
            return
        }
        runCatching { m.log(Log.INFO, TAG, "[Goutou] $msg") }
    }

    /**
     * 拿 hook builder 自己配置（.setId / .intercept）。
     *
     * 用 setId：同一个 id 再次 hook 会**原子替换**旧的，而不是叠加第二个回调。
     * TextCapture 是「看到才挂钩子」的，重复挂同一批控件类很容易发生，有这个语义才不会叠钩子。
     */
    fun hook(executable: Executable): XposedInterface.HookBuilder? = try {
        module?.hook(executable)
    } catch (t: Throwable) {
        log("hook ${executable.name} 失败: $t")
        null
    }

    /**
     * 跨进程读配置 —— XSharedPreferences 的官方替代品。
     *
     * 和 XSharedPreferences 的三点差异，都会影响调用方写法：
     * 1. 这里是**只读**的。写入由 App 侧完成（走框架的 service 通道，见 RemoteSync）。
     * 2. 数据由框架推送到本进程，改了会实时反映过来 —— 所以**没有 reload()**，
     *    也不存在「文件 mtime」这种东西。
     * 3. 拿不到时抛异常而不是返回 null，所以这里统一收成可空值。
     */
    fun prefs(): SharedPreferences? = try {
        module?.getRemotePreferences(PREF_NAME)
    } catch (t: Throwable) {
        log("读远程配置失败: $t")
        null
    }
}
