package dev.goutou.wingman

/**
 * 这个类是「模块被 LSPosed 加载了」的探针：
 * 注入微信进程的代码会把它 hook 成返回 true，App 首页据此显示「模块已激活」。
 * 类名/包名被 Hook 代码按字符串引用，改名要两边一起改（Keys 里同理）。
 */
object ModuleStatus {
    @JvmStatic
    fun isActive(): Boolean = false
}
