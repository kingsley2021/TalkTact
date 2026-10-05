package dev.goutou.wingman.wechat

import android.view.View
import dev.goutou.wingman.XposedApi
import java.util.WeakHashMap

/**
 * 「从源头拿正文」：给会自己画字的控件挂 setText 钩子。
 *
 * 微信 8.0.78 的正文控件是 `MMNeat7extView`（303x118，和气泡一样大）——
 * 它不继承 TextView，文字由它自己绘制，所以：
 *   · getText() 拿不到（不是 TextView）
 *   · 试过 getText/getTextContent/getTextString/getMessage 反射，也拿不到
 * 唯一稳的做法是钩住它设文字的那个方法。
 *
 * 做法：
 * 1. 读取时遇到「有尺寸、又不是 TextView/ImageView」的控件，就把它的
 *    set*(CharSequence) 全挂上（不依赖包名/类名，微信改版本也不怕）；
 * 2. 之后每次微信给它设文字，都按控件实例记下来（WeakHashMap，回收即消失）；
 * 3. 学到的类名通过广播回传 App 持久化，模块下次启动就先挂好钩子 ——
 *    这样第二次打开聊天页时不用等重新绑定就有文字。
 */
internal object TextCapture {

    /** 不是正文的 setter，见 hookClass 里的说明。 */
    private val SKIP_SETTERS = setOf(
        "setHint",
        "setError",
        "setContentDescription",
        "setTooltipText",
        "setTransitionName",
        "setStateDescription",
    )

    private val captured = WeakHashMap<View, CharSequence>()
    private val hookedClasses = HashSet<String>()
    private val pending = LinkedHashSet<String>()

    @Synchronized
    fun textOf(v: View): CharSequence? = captured[v]

    /** 取出「刚学到的类名」并清空，交给调用方回传 App 持久化。 */
    @Synchronized
    fun takeLearned(): List<String> {
        if (pending.isEmpty()) return emptyList()
        val out = pending.toList()
        pending.clear()
        return out
    }

    /** 模块启动时先把上次学到的类挂好（这样第一次打开聊天页就有文字）。 */
    @Synchronized
    fun hookByName(cl: ClassLoader, name: String, remember: Boolean = false): Boolean {
        if (name in hookedClasses) return false
        val cls = try {
            Class.forName(name, false, cl)
        } catch (t: Throwable) {
            return false
        }
        return hookClass(cls, remember)
    }

    @Synchronized
    fun hookClass(cls: Class<*>, remember: Boolean = true): Boolean {
        val name = cls.name
        if (name in hookedClasses) return false
        hookedClasses.add(name)
        var hookedAny = false
        val methods = try {
            cls.methods
        } catch (t: Throwable) {
            emptyArray()
        }
        for (m in methods) {
            if (!m.name.startsWith("set") || m.parameterTypes.isEmpty()) continue
            if (!CharSequence::class.java.isAssignableFrom(m.parameterTypes[0])) continue
            // 只认正文。setHint / setError / setContentDescription 存下来的不是聊天内容，
            // 一旦被当成正文，就会串进候选回复里（而且它们常常在 setText 之后被调用，会覆盖掉真正文）。
            if (m.name in SKIP_SETTERS) continue
            val builder = XposedApi.hook(m) ?: continue
            try {
                builder
                    // 同 id 重复 hook 是原子替换，不会叠出第二个回调
                    .setId("wingman.setText:" + m.toGenericString())
                    .intercept { chain ->
                        // 先让微信把文字真正设下去（等价于 legacy 的 afterHookedMethod）
                        val result = chain.proceed()
                        val self = chain.thisObject as? View
                        val text = chain.getArg(0) as? CharSequence
                        if (self != null && text != null && text.isNotEmpty()) store(self, text)
                        result
                    }
                hookedAny = true
            } catch (t: Throwable) {
                // 已经挂过 / 抽象方法 之类，忽略
            }
        }
        if (hookedAny) {
            if (remember) pending.add(name)
            XposedApi.log("已给 $name 挂上 setText 钩子")
        }
        return hookedAny
    }

    @Synchronized
    private fun store(v: View, text: CharSequence) {
        captured[v] = text
    }
}
