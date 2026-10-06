package dev.goutou.wingman

import dev.goutou.wingman.llm.ModelList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「从服务端拉模型列表」的纯函数部分。
 *
 * 真机上没法一家家服务商去试，所以把见过的形状都钉在单测里：
 * URL 怎么拼、返回怎么解析、哪些名字看着不像对话模型（只排序、不过滤）。
 */
class ModelListTest {

    @Test
    fun `模型 URL 拼接`() {
        assertEquals("https://api.openai.com/v1/models", ModelList.modelsUrl("https://api.openai.com/v1"))
        // 末尾斜杠 / 空格 / 已经写到 chat/completions 都要能收拾
        assertEquals("https://api.openai.com/v1/models", ModelList.modelsUrl("https://api.openai.com/v1/"))
        assertEquals("https://a.com/v1/models", ModelList.modelsUrl("  https://a.com/v1  "))
        assertEquals("https://a.com/v1/models", ModelList.modelsUrl("https://a.com/v1/chat/completions"))
    }

    @Test
    fun `解析 OpenAI 形状的返回`() {
        val body = """{"object":"list","data":[{"id":"gpt-4o","object":"model"},{"id":"gpt-4o-mini"}]}"""
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), ModelList.parseModels(body))
    }

    @Test
    fun `解析别的形状也不至于空手而归`() {
        // 有的服务商字段叫 models
        assertEquals(listOf("a", "b"), ModelList.parseModels("""{"models":[{"id":"a"},{"id":"b"}]}"""))
        // 有的直接给字符串数组
        assertEquals(listOf("x", "y"), ModelList.parseModels("""["x","y"]"""))
        // 数组元素用 name / model 当名字
        assertEquals(listOf("n1", "m1"), ModelList.parseModels("""{"data":[{"name":"n1"},{"model":"m1"}]}"""))
    }

    @Test
    fun `重复和空白会被收拾掉`() {
        assertEquals(listOf("a", "b"), ModelList.parseModels("""{"data":[{"id":"a"},{"id":" a "},{"id":"b"},{"id":""}]}"""))
    }

    @Test
    fun `看不懂的返回就是空列表`() {
        assertEquals(emptyList<String>(), ModelList.parseModels(""))
        assertEquals(emptyList<String>(), ModelList.parseModels("not json at all"))
        assertEquals(emptyList<String>(), ModelList.parseModels("""{"error":{"message":"bad key"}}"""))
        assertEquals(emptyList<String>(), ModelList.parseModels("[]"))
    }

    @Test
    fun `非对话模型只分类不过滤`() {
        assertTrue(ModelList.looksNonChat("text-embedding-3-small"))
        assertTrue(ModelList.looksNonChat("whisper-1"))
        assertTrue(ModelList.looksNonChat("tts-1"))
        assertTrue(ModelList.looksNonChat("dall-e-3"))
        assertTrue(ModelList.looksNonChat("bge-reranker-v2"))
        // 常见对话模型不能被误伤
        assertFalse(ModelList.looksNonChat("gpt-4o"))
        assertFalse(ModelList.looksNonChat("deepseek-chat"))
        assertFalse(ModelList.looksNonChat("qwen2.5-72b-instruct"))
        assertFalse(ModelList.looksNonChat("claude-sonnet-4"))
    }
}
