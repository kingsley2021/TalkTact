package dev.goutou.wingman

import dev.goutou.wingman.llm.Json
import dev.goutou.wingman.llm.asBool
import dev.goutou.wingman.llm.asDouble
import dev.goutou.wingman.llm.asStr
import dev.goutou.wingman.llm.at
import dev.goutou.wingman.llm.num
import dev.goutou.wingman.llm.obj
import dev.goutou.wingman.llm.str
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {

    @Test
    fun `解析嵌套结构`() {
        val v = Json.parse("""{"a":[1,2,{"b":"x\ny"}],"c":true,"d":null}""")
        assertEquals(2.0, v.at("a", "1").asDouble()!!, 0.0)
        assertEquals("x\ny", v.at("a", "2", "b").asStr())
        assertEquals(true, v.at("c").asBool())
        assertNull(v.at("a", "9"))
        assertNull(v.at("nope", "x"))
    }

    @Test
    fun `坏 JSON 返回 null 而不是抛异常`() {
        assertNull(Json.parse("{不是 json"))
        assertNull(Json.parse(""))
        assertNull(Json.parse("[1,2"))
    }

    @Test
    fun `编码会正确转义并能读回`() {
        val encoded = Json.encode(obj("k" to str("a\"b\\c\nd"), "n" to num(0.8), "i" to num(500), "b" to dev.goutou.wingman.llm.bool(true)))
        assertEquals("""{"k":"a\"b\\c\nd","n":0.8,"i":500,"b":true}""", encoded)
        assertEquals("a\"b\\c\nd", Json.parse(encoded).at("k").asStr())
    }
}
