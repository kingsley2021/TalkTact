package dev.goutou.wingman.llm

/**
 * 最小 JSON 实现：只干「解析模型返回」和「拼请求体」两件事。
 *
 * 故意不引 kotlinx.serialization / Gson：核心逻辑因此零依赖，
 * 能在 JVM 上直接跑单测，也少一个和微信自带库撞车的可能。
 */
sealed interface JsonValue {
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

data object JsonNull : JsonValue

fun JsonValue?.asStr(): String? = (this as? JsonValue.Str)?.value

fun JsonValue?.asDouble(): Double? = when (this) {
    is JsonValue.Num -> value
    is JsonValue.Str -> value.toDoubleOrNull()
    else -> null
}

fun JsonValue?.asInt(): Int? = asDouble()?.toInt()

fun JsonValue?.asBool(): Boolean? = (this as? JsonValue.Bool)?.value

fun JsonValue?.asArr(): List<JsonValue>? = (this as? JsonValue.Arr)?.items

fun JsonValue?.asObj(): Map<String, JsonValue>? = (this as? JsonValue.Obj)?.fields

/** 按路径取值：node.at("choices", "0", "message", "content")，任意一段取不到就返回 null。 */
fun JsonValue?.at(vararg path: String): JsonValue? {
    var cur: JsonValue? = this
    for (segment in path) {
        val node = cur ?: return null
        cur = when (node) {
            is JsonValue.Obj -> node.fields[segment]
            is JsonValue.Arr -> node.items.getOrNull(segment.toIntOrNull() ?: -1)
            else -> null
        }
    }
    return cur
}

object Json {
    fun parse(text: String): JsonValue? = try {
        val p = Parser(text)
        val v = p.readValue()
        p.skipWs()
        v
    } catch (t: Throwable) {
        null
    }

    fun encode(value: JsonValue): String = StringBuilder().also { write(it, value) }.toString()

    private class Parser(private val s: String) {
        private var i = 0

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        fun readValue(): JsonValue {
            skipWs()
            if (i >= s.length) throw IllegalArgumentException("意外的结尾")
            return when (val c = s[i]) {
                '{' -> readObj()
                '[' -> readArr()
                '"' -> JsonValue.Str(readString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonNull }
                else -> if (c == '-' || c.isDigit()) readNum() else throw IllegalArgumentException("位置 $i 不支持字符 $c")
            }
        }

        private fun readObj(): JsonValue.Obj {
            i++
            val fields = LinkedHashMap<String, JsonValue>()
            skipWs()
            if (i < s.length && s[i] == '}') { i++; return JsonValue.Obj(fields) }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (i >= s.length || s[i] != ':') throw IllegalArgumentException("位置 $i 缺少 :")
                i++
                fields[key] = readValue()
                skipWs()
                when {
                    i >= s.length -> throw IllegalArgumentException("对象没有闭合")
                    s[i] == ',' -> i++
                    s[i] == '}' -> { i++; return JsonValue.Obj(fields) }
                    else -> throw IllegalArgumentException("位置 $i 期待 , 或 }")
                }
            }
        }

        private fun readArr(): JsonValue.Arr {
            i++
            val items = ArrayList<JsonValue>()
            skipWs()
            if (i < s.length && s[i] == ']') { i++; return JsonValue.Arr(items) }
            while (true) {
                items.add(readValue())
                skipWs()
                when {
                    i >= s.length -> throw IllegalArgumentException("数组没有闭合")
                    s[i] == ',' -> i++
                    s[i] == ']' -> { i++; return JsonValue.Arr(items) }
                    else -> throw IllegalArgumentException("位置 $i 期待 , 或 ]")
                }
            }
        }

        private fun readString(): String {
            skipWs()
            if (i >= s.length || s[i] != '"') throw IllegalArgumentException("位置 $i 不是字符串")
            i++
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i++]
                when {
                    c == '"' -> return sb.toString()
                    c == '\\' -> {
                        if (i >= s.length) break
                        when (val e = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = s.substring(i, minOf(i + 4, s.length))
                                i += hex.length
                                sb.append(hex.toInt(16).toChar())
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
            throw IllegalArgumentException("字符串没有闭合")
        }

        private fun readNum(): JsonValue.Num {
            val start = i
            if (s[i] == '-' || s[i] == '+') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val raw = s.substring(start, i)
            val d = raw.toDoubleOrNull() ?: throw IllegalArgumentException("数字解析失败：$raw")
            return JsonValue.Num(d)
        }

        private fun expect(word: String) {
            if (!s.startsWith(word, i)) throw IllegalArgumentException("位置 $i 期待 $word")
            i += word.length
        }
    }

    private fun write(sb: StringBuilder, v: JsonValue) {
        when (v) {
            is JsonValue.Str -> escape(sb, v.value)
            is JsonValue.Num -> sb.append(
                if (v.value.isFinite() && v.value == Math.floor(v.value) && Math.abs(v.value) < 1e15) {
                    v.value.toLong().toString()
                } else {
                    v.value.toString()
                },
            )
            is JsonValue.Bool -> sb.append(if (v.value) "true" else "false")
            is JsonValue.Arr -> {
                sb.append('[')
                v.items.forEachIndexed { idx, item ->
                    if (idx > 0) sb.append(',')
                    write(sb, item)
                }
                sb.append(']')
            }
            is JsonValue.Obj -> {
                sb.append('{')
                var first = true
                for ((k, item) in v.fields) {
                    if (!first) sb.append(',')
                    first = false
                    escape(sb, k)
                    sb.append(':')
                    write(sb, item)
                }
                sb.append('}')
            }
            JsonNull -> sb.append("null")
        }
    }

    private fun escape(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

// ---------- 拼请求体用的小工具 ----------
fun obj(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj = JsonValue.Obj(linkedMapOf(*pairs))
fun arr(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)
fun str(value: String): JsonValue.Str = JsonValue.Str(value)
fun num(value: Number): JsonValue.Num = JsonValue.Num(value.toDouble())
fun bool(value: Boolean): JsonValue.Bool = JsonValue.Bool(value)
