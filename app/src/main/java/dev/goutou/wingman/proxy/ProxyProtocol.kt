package dev.goutou.wingman.proxy

/**
 * 本地代理的「协议」：路径、端口、鉴权格式，两侧（App 的代理服务、注入微信的那段代码）共用一份。
 *
 * 为什么单拎出来：这两侧的代码在两个进程里跑，改路径时最容易只改一边 —— 症状是「卡在识别中」
 * 这种最难查的毛病。所以约定集中在这儿，谁用谁 import。
 */
object ProxyProtocol {

    const val PORT_DEFAULT = 8799
    const val ROUTE_PROXY = "proxy"
    const val ROUTE_DIRECT = "direct"

    /**
     * 这次调用实际走哪条路。
     *
     * 两侧共用这一份判断：注入侧用它决定怎么发，回传给 App 显示的也是它 ——
     * 不然「界面说走了代理、其实走了直连」这种问题永远查不出来。
     */
    fun routeOf(proxyEnabled: Boolean, proxyToken: String): String =
        if (proxyEnabled && proxyToken.isNotBlank()) ROUTE_PROXY else ROUTE_DIRECT
    const val PATH_CHAT = "/proxy/chat/completions"
    const val PATH_HEALTH = "/proxy/health"

    /** 注入侧要访问的地址（只可能是本机回环）。 */
    fun urlFor(port: Int, path: String = PATH_CHAT): String = "http://127.0.0.1:$port$path"

    /** 解析 `Authorization: Bearer xxx`；格式不对/空白一律 null。 */
    fun bearerToken(header: String?): String? {
        val v = header?.trim().orEmpty()
        if (v.length <= 7 || !v.startsWith("Bearer ", ignoreCase = true)) return null
        return v.substring(7).trim().takeIf { it.isNotEmpty() }
    }

    /**
     * 这次请求能不能放行。
     *
     * token 是 App 随机生成的、只写在本机配置里：没有它的话，手机里任何 App 都能拿这个回环端口
     * 白蹭你的额度（回环端口不是「只有微信能连」）。
     */
    fun isAuthorized(header: String?, expected: String): Boolean {
        if (expected.isEmpty()) return false
        return bearerToken(header) == expected
    }
}
