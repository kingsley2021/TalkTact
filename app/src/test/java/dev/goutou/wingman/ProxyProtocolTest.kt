package dev.goutou.wingman

import dev.goutou.wingman.proxy.ProxyProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 本地代理的「协议」：路径、URL、token 校验（纯函数，两侧共用一份）。 */
class ProxyProtocolTest {

    @Test
    fun `注入侧访问的地址只可能是回环`() {
        assertEquals("http://127.0.0.1:8799/proxy/chat/completions", ProxyProtocol.urlFor(8799))
        assertTrue(ProxyProtocol.urlFor(1).startsWith("http://127.0.0.1:"))
    }

    @Test
    fun `解析 Bearer token`() {
        assertEquals("abc123", ProxyProtocol.bearerToken("Bearer abc123"))
        assertEquals("abc123", ProxyProtocol.bearerToken("bearer abc123"))
        assertEquals("abc123", ProxyProtocol.bearerToken("  Bearer  abc123  "))
        assertNull(ProxyProtocol.bearerToken(null))
        assertNull(ProxyProtocol.bearerToken(""))
        assertNull(ProxyProtocol.bearerToken("abc123"))
        assertNull(ProxyProtocol.bearerToken("Bearer "))
    }

    @Test
    fun `识图那一跳也在回环上`() {
        assertEquals("/proxy/ocr", ProxyProtocol.PATH_OCR)
        assertEquals("http://127.0.0.1:8799/proxy/ocr", ProxyProtocol.ocrUrl(8799))
    }

    @Test
    fun `没走本地代理就不去认图`() {
        // 认字是在 App 进程里做的：没走代理就没有地方认，这时候去连回环只会白等一次超时
        assertTrue(ProxyProtocol.ocrUsable(true, ProxyProtocol.ROUTE_PROXY))
        assertFalse(ProxyProtocol.ocrUsable(false, ProxyProtocol.ROUTE_PROXY))
        assertFalse(ProxyProtocol.ocrUsable(true, ProxyProtocol.ROUTE_DIRECT))
    }

    @Test
    fun `第二套接口靠请求头区分，认不出就是第一套`() {
        // 代理只看得到 token、看不到「这一跳是谁」，所以由调用方在头里说明用哪套接口
        assertEquals(ProxyProtocol.ENDPOINT_SECOND, ProxyProtocol.endpointHeader(second = true))
        assertEquals(ProxyProtocol.ENDPOINT_FIRST, ProxyProtocol.endpointHeader(second = false))

        assertEquals(ProxyProtocol.ENDPOINT_SECOND, ProxyProtocol.endpointOf("2"))
        assertEquals(ProxyProtocol.ENDPOINT_SECOND, ProxyProtocol.endpointOf(" 2 "))
        // 没带 / 写了别的 / 大小写不对 —— 一律当第一套（老行为），绝不能猜成第二套
        assertEquals(ProxyProtocol.ENDPOINT_FIRST, ProxyProtocol.endpointOf(null))
        assertEquals(ProxyProtocol.ENDPOINT_FIRST, ProxyProtocol.endpointOf(""))
        assertEquals(ProxyProtocol.ENDPOINT_FIRST, ProxyProtocol.endpointOf("1"))
        assertEquals(ProxyProtocol.ENDPOINT_FIRST, ProxyProtocol.endpointOf("3"))
        assertEquals(ProxyProtocol.ENDPOINT_FIRST, ProxyProtocol.endpointOf("second"))
    }

    @Test
    fun `没配 token 时一律拒绝`() {
        // 空 token 是「还没生成」的状态，这时候绝不能放行（否则任何 App 都能白蹭额度）
        assertFalse(ProxyProtocol.isAuthorized("Bearer ", ""))
        assertFalse(ProxyProtocol.isAuthorized("Bearer x", ""))
        assertTrue(ProxyProtocol.isAuthorized("Bearer x", "x"))
        assertFalse(ProxyProtocol.isAuthorized("Bearer y", "x"))
        assertFalse(ProxyProtocol.isAuthorized(null, "x"))
    }
}
