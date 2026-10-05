package dev.goutou.wingman

import dev.goutou.wingman.config.Backup
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.Roles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupTest {

    private val t0 = 1_700_000_000_000L

    private fun sampleCfg() = ConfigData(
        baseUrl = "https://api.example.com/v1",
        apiKey = "sk-secret",
        model = "gpt-4o-mini",
        prompt = "你是军师",
        ctx = 12,
        temperature = 0.5,
        maxTokens = 600,
        minIntervalSec = 30,
        allowSensitive = true,
        glassAlpha = 0.8f,
        glassBlur = 30f,
        bgDim = 0.5f,
        skillId = "full",
        mentorAdvanced = true,
    )

    private fun sampleRoles() = Roles.setProfile(
        Roles.merge(emptyList(), listOf("张三" to dev.goutou.wingman.config.RoleMsg(false, "在吗", t0))),
        "张三", "同事", "同一个组的后端",
    )

    @Test
    fun `导出再导入能把配置和角色搬回来`() {
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = true)
        val r = Backup.import(text, ConfigData(), emptyList())!!
        assertEquals(sampleCfg(), r.config)
        assertEquals(sampleRoles(), r.roles)
        assertEquals(1, r.roleCount)
    }

    @Test
    fun `不带 API Key 导出时不会冲掉已填的 Key`() {
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = false)
        assertTrue(!text.contains("sk-secret"))
        val r = Backup.import(text, ConfigData(apiKey = "sk-已经在用了"), emptyList())!!
        assertEquals("sk-已经在用了", r.config.apiKey)
    }

    @Test
    fun `导入是合并角色不是替换`() {
        val existing = listOf(Role(key = "李四", name = "李四", relation = "朋友"))
        val text = Backup.export(sampleCfg(), sampleRoles(), includeApiKey = true)
        val r = Backup.import(text, ConfigData(), existing)!!
        assertEquals(2, r.roles.size)
        assertTrue(r.roles.any { it.name == "李四" })
        assertTrue(r.roles.any { it.name == "张三" && it.relation == "同事" })
    }

    @Test
    fun `坏数据不覆盖现有内容`() {
        assertNull(Backup.import("", ConfigData(), emptyList()))
        assertNull(Backup.import("不是 json", ConfigData(), emptyList()))
        assertNull(Backup.import("""{"app":"TalkTact"}""", ConfigData(), emptyList()))
    }
}
