package dev.goutou.wingman

import dev.goutou.wingman.config.DiagExport
import dev.goutou.wingman.llm.isReplyTooLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

class DiagExportTest {

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                out[e.name] = z.readBytes().toString(Charsets.UTF_8)
                z.closeEntry()
                e = z.nextEntry
            }
        }
        return out
    }

    @Test
    fun `打出来的 zip 能原样读回来`() {
        val files = linkedMapOf("00-说明.txt" to "hi", "02-配置.txt" to "接口地址：http://x")
        val back = unzip(DiagExport.zip(files))
        assertEquals(2, back.size)
        assertEquals("hi", back["00-说明.txt"])
        assertEquals("接口地址：http://x", back["02-配置.txt"])
    }

    @Test
    fun `同样的输入出同样的字节`() {
        val files = linkedMapOf("a.txt" to "1")
        assertTrue(DiagExport.zip(files).contentEquals(DiagExport.zip(files)))
    }

    @Test
    fun `说明文件必须提醒里面有聊天内容且不含 Key`() {
        val readme = DiagExport.readme("v0.8.8(35)")
        assertTrue(readme.contains("聊天内容"))
        assertTrue(readme.contains("不含 API Key"))
        assertTrue(readme.contains("v0.8.8(35)"))
        // 决策轨迹也得列进去：它是排查「卡片不弹」时用户最先要看的一份，
        // 说明里不提，对方根本不知道包里还有它。
        assertTrue(readme.contains("07-决策轨迹"))
        assertTrue(readme.contains("不含聊天内容"))
    }

    @Test
    fun `偏长复核的边界`() {
        assertFalse(isReplyTooLong("短"))
        assertFalse(isReplyTooLong("字".repeat(60)))
        assertTrue(isReplyTooLong("字".repeat(61)))
        // 前后空白不算数
        assertFalse(isReplyTooLong("   " + "字".repeat(60) + "  "))
    }
}
