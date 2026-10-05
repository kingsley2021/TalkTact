package dev.goutou.wingman.llm

import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/**
 * 一次 IP 归属地查询的结果。**任何一项取不到就是 null** —— 不编一个看起来像真的值出来。
 */
data class Geo(
    val ip: String? = null,
    val province: String? = null,
    val city: String? = null,
    val isp: String? = null,
    val country: String? = null,
) {
    /** 「广东 · 电信」这种一行摘要；什么都没有就 null。 */
    fun label(): String? = joinToNull(province ?: country, isp)
}

/** 把几段拼成「a · b」；全空返回 null。 */
internal fun joinToNull(vararg parts: String?): String? =
    parts.filter { !it.isNullOrBlank() }.joinToString(" · ").ifBlank { null }

/**
 * 「当前 IP / 目标 IP / 归属地」这些网络信息。
 *
 * 分成两类，别混在一起看：
 *
 * - **本地就能拿到**：内网 IP、目标域名解析出的 IP、裸 TCP 握手耗时 —— 不依赖任何第三方；
 * - **得问第三方**：公网出口 IP、归属地 —— 尽力而为，拿不到返回 null，**绝不阻塞自检**。
 *
 * ⚠️ 归属地是整个模块**唯一**一处会让 IP 离开「你的设备 ↔ 你填的接口」这条线的地方
 * （[GEO_ENDPOINT] 那个第三方）。所以它永远只做「锦上添花」：失败就当没有。
 *
 * 为什么本机省份也要走网络：内网 IP（192.168.x.x / 10.x.x.x）里根本没有归属地信息，
 * SIM 卡也只到运营商（"中国移动"）到不了省。只能拿公网出口 IP 去反查 ——
 * 而蜂窝网络的出口 IP 池经常跨省，所以这个「本机省份」本来就只准个大概。
 */
internal object NetInfo {

    /** 默认的归属地接口：HTTPS、国内可达、支持 `?ip=` 指定查询（不传就是查自己）。换服务只改这一行。 */
    const val GEO_ENDPOINT = "https://ip.useragentinfo.com/json"

    // ---------------- 纯本地 ----------------

    private fun urlOf(baseUrl: String): URL? = try {
        val u = baseUrl.trim()
        if (u.isEmpty()) null else URL(if (u.contains("://")) u else "https://$u")
    } catch (t: Throwable) {
        null
    }

    /** 从接口地址里取主机名；地址不合法/为空返回 null。 */
    fun hostOf(baseUrl: String): String? = urlOf(baseUrl)?.host?.takeIf { it.isNotBlank() }

    /** 端口：地址里写了就用它，否则按协议 443 / 80。 */
    fun portOf(baseUrl: String): Int {
        val u = urlOf(baseUrl) ?: return 443
        return if (u.port > 0) u.port else if (u.protocol.equals("http", true)) 80 else 443
    }

    /**
     * 本机 IPv4。优先内网地址（192.168. / 10. / 172.16-31.），没有就取第一个非回环地址；
     * 一个都没有（比如飞行模式）返回 null。
     */
    fun localIpv4(): String? = try {
        val list = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .asSequence()
            .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
            .flatMap { it.inetAddresses.asSequence() }
            .filterIsInstance<Inet4Address>()
            .mapNotNull { it.hostAddress }
            .filter { !it.startsWith("127.") && !it.startsWith("169.254.") }
            .toList()
        list.firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
            ?: list.firstOrNull()
    } catch (t: Throwable) {
        null
    }

    /** 域名/IP → IP；解析不到返回 null（不抛）。 */
    fun resolve(host: String?): String? {
        if (host.isNullOrBlank()) return null
        return try {
            InetAddress.getByName(host).hostAddress
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * 裸 TCP 握手耗时（ms）—— 这才是「连接延迟」，跟 API 语义无关。
     *
     * 连不上返回 null 但**不算接口不通**：有的前置代理/CDN 会拦裸 TCP，真正的请求照样能过。
     */
    fun tcpConnectMs(host: String?, port: Int, timeoutMs: Int = 8000): Long? {
        if (host.isNullOrBlank()) return null
        return try {
            val t0 = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
            System.currentTimeMillis() - t0
        } catch (t: Throwable) {
            null
        }
    }

    // ---------------- 归属地（要问第三方） ----------------

    private val cache = HashMap<String, Pair<Long, Geo>>()

    /** 缓存时长：10 分钟内不重复问第三方（自检会先清缓存，所以点自检一定是新查的）。 */
    private const val CACHE_TTL_MS = 10 * 60_000L

    fun clearGeoCache() = cache.clear()

    /**
     * 查归属地。`ip = null` 表示查「本机公网出口」。
     * 失败（网络不通、接口抽风、返回看不懂）一律返回 null，不抛。
     */
    fun geo(endpoint: String = GEO_ENDPOINT, ip: String? = null, force: Boolean = false): Geo? {
        val key = "$endpoint|${ip ?: ""}"
        if (!force) {
            cache[key]?.let { (at, g) -> if (System.currentTimeMillis() - at < CACHE_TTL_MS) return g }
        }
        val got = runCatching { queryGeo(endpoint, ip) }.getOrNull() ?: return null
        cache[key] = System.currentTimeMillis() to got
        return got
    }

    private fun queryGeo(endpoint: String, ip: String?): Geo? {
        val base = endpoint.trim()
        if (base.isEmpty()) return null
        val url = if (ip.isNullOrBlank()) {
            base
        } else {
            base + (if (base.contains('?')) "&" else "?") + "ip=" + URLEncoder.encode(ip, "UTF-8")
        }
        val body = httpGet(url, 6000) ?: return null
        return parseGeo(body, ip)
    }

    private fun httpGet(url: String, timeoutMs: Int): String? {
        // 注意：这里**不能**带 Authorization —— 那是给你自己填的接口用的，别顺手发给第三方
        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return null
        }
        return try {
            conn.requestMethod = "GET"
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.setRequestProperty("Accept", "application/json")
            // 有些服务不带 UA 直接 403
            conn.setRequestProperty("User-Agent", "TalkTact")
            if (conn.responseCode in 200..299) {
                conn.inputStream?.bufferedReader()?.use { it.readText() }
            } else {
                null
            }
        } catch (t: Throwable) {
            null
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    // 各家字段名不一样，所以按「候选键名」递归找第一个命中的值 —— 换个接口通常也能认
    private val IP_KEYS = listOf("ip", "query", "ipAddress", "ip_str")
    private val PROVINCE_KEYS = listOf("province", "pro", "regionName", "region", "region_name", "proName", "info1")
    private val CITY_KEYS = listOf("city", "cityName", "city_name", "info2")
    private val ISP_KEYS = listOf("isp", "ispName", "isp_name", "net", "org", "as")
    private val COUNTRY_KEYS = listOf("country", "countryName", "country_name", "short_name")

    /**
     * 从归属地接口的返回里抠字段（纯函数，单测直接喂字符串）。
     *
     * `requestedIp` 是「我们明确要查的那个 IP」：如果返回里的 ip 字段跟它不一样，
     * 说明这家不支持指定查询，宁可不显示 —— 显示成别人（其实是本机）的省份比 null 更糟。
     */
    fun parseGeo(body: String, requestedIp: String? = null): Geo? {
        val root = Json.parse(body) ?: return null
        val got = clean(firstString(root, IP_KEYS))
        if (!requestedIp.isNullOrBlank() && got != null && got != requestedIp) return null
        val geo = Geo(
            ip = got ?: requestedIp,
            province = clean(firstString(root, PROVINCE_KEYS)),
            city = clean(firstString(root, CITY_KEYS)),
            isp = clean(firstString(root, ISP_KEYS)),
            country = clean(firstString(root, COUNTRY_KEYS)),
        )
        // 什么都认不出来就跟没查一样
        return geo.takeIf { it.province != null || it.country != null || it.isp != null || it.city != null }
    }

    /** 有的接口查不到时会回 "0" / "-" / "未知"，这些一律当没有。 */
    private fun clean(value: String?): String? {
        val t = value?.trim().orEmpty()
        return t.takeIf { it.isNotEmpty() && it != "0" && it != "-" && it != "未知" && it != "null" }
    }

    private fun firstString(node: JsonValue?, keys: List<String>): String? {
        when (node) {
            is JsonValue.Obj -> {
                for (k in keys) node.fields[k]?.asStr()?.takeIf { it.isNotBlank() }?.let { return it }
                for (v in node.fields.values) firstString(v, keys)?.let { return it }
            }
            is JsonValue.Arr -> for (v in node.items) firstString(v, keys)?.let { return it }
            else -> Unit
        }
        return null
    }
}
