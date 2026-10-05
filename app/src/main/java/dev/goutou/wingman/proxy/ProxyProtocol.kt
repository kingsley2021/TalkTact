package dev.goutou.wingman.proxy

/**
 * 本地代理的「协议」：路径、端口、鉴权格式，两侧（App 的代理服务、注入微信的那段代码）共用一份。
 *
 * 为什么单拎出来：这两侧的代码在两个进程里跑，改路径时最容易只改一边 —— 症状是「卡在识别中」
 * 这种最难查的毛病。所以约定集中在这儿，谁用谁 import。
 */
object ProxyProtocol {

    const val PORT_DEFAULT = 8799
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
