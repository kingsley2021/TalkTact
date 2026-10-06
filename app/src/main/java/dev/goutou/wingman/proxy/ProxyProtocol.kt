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

    /** 图片文字识别：注入侧把图 POST 上来，App 认完把**文字**放在响应体里还回去。 */
    const val PATH_OCR = "/proxy/ocr"

    /**
     * 送去识别的图：最长边（px）与最大字节数。
     *
     * 图是走回环从微信进程传到 App 的（两个进程不同 UID，只能这么传），所以要压：
     * 最长边 1280 + JPEG q82 之后，一张聊天截图通常 100~400KB —— 识别精度基本不受影响，
     * 但内存和传输成本差一个量级。超上限的图直接不认（宁可回落成 [图片] 占位，也不能卡住整轮分析）。
     */
    const val OCR_MAX_SIDE = 1280
    const val OCR_MAX_BYTES = 3_000_000

    /**
     * 这次该不该去认图。
     *
     * 两个条件缺一不可：**开关开着** + **走的是本地代理**。
     * 认字这件事在 App 进程做（ML Kit 只装在 App 里），没走代理就没有地方认 ——
     * 那时候去连回环只会白等一次超时，所以宁可提前跳过，回落成老占位。
     */
    fun ocrUsable(ocrEnabled: Boolean, route: String): Boolean =
        ocrEnabled && route == ROUTE_PROXY

    /**
     * 「这一跳用哪套接口」。
     *
     * 分级模式有两套接口（写回复一路 / 风险评估一路），它们的地址、Key、模型都可能完全不同。
     * 而代理那侧只拿到一个 token，**认不出「这一跳是谁」** —— 于是它会一律按第一套转发，
     * 症状就是「第二套明明填对了，却报 Key 不对，而且报的是另一把 Key」。
     * 所以由调用方在请求头里说明；没带 / 认不出 → 第一套（老行为）。
     */
    const val HEADER_ENDPOINT = "x-talktact-endpoint"
    const val ENDPOINT_FIRST = "1"
    const val ENDPOINT_SECOND = "2"

    /** 解析「用哪套接口」的头：除了明确的 "2"，一律当第一套。 */
    fun endpointOf(header: String?): String =
        if (header?.trim() == ENDPOINT_SECOND) ENDPOINT_SECOND else ENDPOINT_FIRST

    /** 这个头该写什么值（调用方用；[second] = 是不是「风险评估」那一路）。 */
    fun endpointHeader(second: Boolean): String = if (second) ENDPOINT_SECOND else ENDPOINT_FIRST

    /** 注入侧要访问的地址（只可能是本机回环）。 */
    fun urlFor(port: Int, path: String = PATH_CHAT): String = "http://127.0.0.1:$port$path"

    /** 图片识别那一跳的地址。 */
    fun ocrUrl(port: Int): String = urlFor(port, PATH_OCR)

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
