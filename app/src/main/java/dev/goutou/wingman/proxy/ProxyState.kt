package dev.goutou.wingman.proxy

/**
 * 代理的运行状态。
 *
 * 为什么用静态对象而不是 SharedPreferences：服务与设置界面在**同一个进程**里，
 * 静态字段是即时的 —— 用户点完开关，界面立刻能读到「服务到底起没起来、为什么没起来」，
 * 不用等落盘再读回来。（原来的代码把启动异常全吞了，所以界面只能显示一句「连不上」，没法排查。）
 */
object ProxyState {
    @Volatile
    var running: Boolean = false

    @Volatile
    var port: Int = 0

    /** 启动 / 绑定失败的原因（给设置页显示）。 */
    @Volatile
    var lastError: String? = null

    /** 最近一次转发结论：HTTP 状态 + 耗时。 */
    @Volatile
    var lastResult: String? = null
}
