package dev.goutou.wingman

import dev.goutou.wingman.llm.NetInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 归属地解析是「喂字符串 → 出结构」的纯函数，而且各家字段名不一样、还有「不支持指定查询」
 * 这种陷阱，所以必须单测 —— 真机上看不出来它到底解析对了没有。
 */
class NetInfoTest {

    @Test
    fun `主机名与端口从地址里取出来`() {
        assertEquals("api.openai.com", NetInfo.hostOf("https://api.openai.com/v1"))
        assertEquals("api.openai.com", NetInfo.hostOf("api.openai.com/v1"))
        assertEquals("127.0.0.1", NetInfo.hostOf("http://127.0.0.1:8080/v1"))

        assertEquals(443, NetInfo.portOf("https://api.openai.com/v1"))
        assertEquals(80, NetInfo.portOf("http://127.0.0.1/v1"))
        assertEquals(8080, NetInfo.portOf("http://127.0.0.1:8080/v1"))
    }

    @Test
    fun `空地址或乱地址不炸`() {
        assertNull(NetInfo.hostOf(""))
        assertNull(NetInfo.hostOf("   "))
    }

    @Test
    fun `归属地：常见字段名`() {
        val g = NetInfo.parseGeo(
            """{"ip":"1.2.3.4","country":"中国","province":"广东","city":"深圳","isp":"电信"}""",
        )
        assertEquals("1.2.3.4", g?.ip)
        assertEquals("广东", g?.province)
        assertEquals("广东 · 电信", g?.label())
    }

    @Test
    fun `归属地：换个接口的字段名与嵌套结构也认`() {
        val g = NetInfo.parseGeo(
            """{"code":0,"data":{"query":"8.8.8.8","regionName":"California","city":"Mountain View","isp":"Google","country":"United States"}}""",
            "8.8.8.8",
        )
        assertEquals("California", g?.province)
        assertEquals("8.8.8.8", g?.ip)
        assertEquals("California · Google", g?.label())
    }

    @Test
    fun `要查指定 IP、接口却回另一个 IP → 宁可不显示`() {
        // 这家不支持 ?ip=，回的是本机的归属地；显示成目标的就骗人了
        assertNull(NetInfo.parseGeo("""{"ip":"9.9.9.9","province":"北京"}""", "1.2.3.4"))
    }

    @Test
    fun `查不到时的占位值当成没有`() {
        assertNull(NetInfo.parseGeo("""{"ip":"1.2.3.4","province":"0","city":"-","isp":"未知"}"""))
        assertNull(NetInfo.parseGeo("不是 JSON"))
        assertNull(NetInfo.parseGeo(""))
    }

    @Test
    fun `什么都没有的 label 是 null 而不是空串`() {
        assertNull(NetInfo.parseGeo("""{"ip":"1.2.3.4"}"""))
    }
}
