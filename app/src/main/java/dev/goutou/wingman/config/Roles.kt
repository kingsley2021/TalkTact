package dev.goutou.wingman.config

import dev.goutou.wingman.llm.Json
import dev.goutou.wingman.llm.JsonValue
import dev.goutou.wingman.llm.arr
import dev.goutou.wingman.llm.asArr
import dev.goutou.wingman.llm.asBool
import dev.goutou.wingman.llm.asDouble
import dev.goutou.wingman.llm.asObj
import dev.goutou.wingman.llm.asStr
import dev.goutou.wingman.llm.bool
import dev.goutou.wingman.llm.num
import dev.goutou.wingman.llm.obj
import dev.goutou.wingman.llm.str
import kotlin.math.abs

/** 一条被记录下来的聊天消息（时间 = 模块看到它的时间）。 */
data class RoleMsg(val fromMe: Boolean, val text: String, val at: Long)

/**
 * 一个「角色」。
 *
 * [key] 是**识别到的会话名**，负责匹配，模块内部拿它找角色，用户改不了；
 * [name] 是**给人看的名字**，随便改。
 *
 * 为什么拆成两个：只用名字当标识的话，① 识别有偏差时同一个人会被记成好几条；
 * ② 用户一改名，后续消息又会对不上、另起一条。拆开之后改名只动显示，匹配不受影响。
 */
data class Role(
    val key: String,
    val name: String = key,
    val relation: String = "",
    val note: String = "",
    val msgs: List<RoleMsg> = emptyList(),
) {
    val lastAt: Long get() = msgs.lastOrNull()?.at ?: 0L

    /** 用户手动改过名字（列表上标一下，省得自己忘了哪条是改过的）。 */
    val renamed: Boolean get() = name != key
}

/** 查重窗口：1 小时内内容相同的重复消息只留一条。 */
const val ROLE_DEDUP_WINDOW_MS = 3_600_000L

/** 每个角色最多留多少条。 */
const val ROLE_MAX_MSGS = 120

/** 最多保留多少个角色（按最近活跃淘汰）。 */
const val ROLE_MAX_COUNT = 40

/**
 * 角色的序列化 / 反序列化 / 合并 / 改名。全是纯函数，不碰 Android，可以直接单测。
 */
object Roles {

    private val SPACES = Regex("\\s+")

    /** 群名结尾的成员数，如「XX群(9)」—— 有人进群退群就会变，得归一到同一个 key。 */
    private val TRAILING_COUNT = Regex("[（(]\\d{1,4}[)）]$")

    /**
     * 归一化识别到的会话名。
     *
     * ① 折叠空白；② 去掉结尾的成员数 —— 微信群标题常带「(9)」，
     * 它一变就会被记成另一个人，这是「名字有偏差」里最常见的一种。
     */
    fun normalizeKey(raw: String): String =
        raw.trim().replace(SPACES, " ").replace(TRAILING_COUNT, "").trim()

    fun decode(raw: String): List<Role> {
        val root = Json.parse(raw)?.asArr() ?: return emptyList()
        return root.mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val name = o["n"].asStr()?.trim().orEmpty()
            // 旧数据（v0.6.2 及以前）没有 k 字段：那时候名字就是 key
            val key = o["k"].asStr()?.trim().orEmpty().ifEmpty { name }
            if (name.isEmpty() || key.isEmpty()) return@mapNotNull null
            val msgs = o["m"].asArr().orEmpty().mapNotNull { mi ->
                val mo = mi.asObj() ?: return@mapNotNull null
                val text = mo["t"].asStr()?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                RoleMsg(mo["me"].asBool() ?: false, text, mo["a"].asDouble()?.toLong() ?: 0L)
            }
            Role(
                key = key,
                name = name,
                relation = o["r"].asStr().orEmpty(),
                note = o["d"].asStr().orEmpty(),
                msgs = msgs.sortedBy { it.at },
            )
        }
    }

    fun encode(roles: List<Role>): String = Json.encode(
        arr(
            roles.map { r ->
                obj(
                    "k" to str(r.key),
                    "n" to str(r.name),
                    "r" to str(r.relation),
                    "d" to str(r.note),
                    "m" to arr(r.msgs.map { m -> obj("me" to bool(m.fromMe), "t" to str(m.text), "a" to num(m.at)) }),
                )
            },
        ),
    )

    /** 注入侧回传用的紧凑格式（只有「谁、谁发的、内容、时间」）。 */
    fun encodeIncoming(items: List<Pair<String, RoleMsg>>): String = Json.encode(
        arr(
            items.map { (name, m) ->
                obj("n" to str(name), "me" to bool(m.fromMe), "t" to str(m.text), "a" to num(m.at))
            },
        ),
    )

    fun decodeIncoming(raw: String): List<Pair<String, RoleMsg>> {
        val root = Json.parse(raw)?.asArr() ?: return emptyList()
        return root.mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val name = o["n"].asStr()?.trim().orEmpty()
            val text = o["t"].asStr()?.trim().orEmpty()
            if (name.isEmpty() || text.isEmpty()) return@mapNotNull null
            name to RoleMsg(o["me"].asBool() ?: false, text, o["a"].asDouble()?.toLong() ?: 0L)
        }
    }

    /**
     * 合并新观测到的消息（key 取归一化后的会话名）。
     *
     * 查重：同角色、同方向、内容相同，且和已记录的某条时间差在 1 小时以内 → 丢弃。
     * 需要它是因为模块每 900ms 就会重读同一屏。
     */
    fun merge(roles: List<Role>, incoming: List<Pair<String, RoleMsg>>): List<Role> {
        if (incoming.isEmpty()) return roles
        val byKey = LinkedHashMap<String, Role>()
        roles.forEach { byKey[it.key] = it }
        for ((rawName, m) in incoming) {
            val key = normalizeKey(rawName)
            if (key.isBlank() || m.text.isBlank()) continue
            val cur = byKey[key] ?: Role(key)
            val dup = cur.msgs.any {
                it.fromMe == m.fromMe && it.text == m.text && abs(it.at - m.at) <= ROLE_DEDUP_WINDOW_MS
            }
            if (dup) continue
            val merged = (cur.msgs + m).sortedBy { it.at }
            byKey[key] = cur.copy(
                msgs = if (merged.size > ROLE_MAX_MSGS) merged.takeLast(ROLE_MAX_MSGS) else merged,
            )
        }
        return byKey.values.sortedByDescending { it.lastAt }.take(ROLE_MAX_COUNT)
    }

    /** 写「TA 是你什么人 / 平时的关系」。 */
    fun setProfile(roles: List<Role>, key: String, relation: String, note: String): List<Role> {
        val target = key.trim()
        if (target.isEmpty()) return roles
        val next = if (roles.any { it.key == target }) {
            roles.map { if (it.key == target) it.copy(relation = relation, note = note) else it }
        } else {
            roles + Role(target, relation = relation, note = note)
        }
        return next.sortedByDescending { it.lastAt }
    }

    /** 改显示名。key 不动，所以以后同一会话的消息还是记到这一条上。 */
    fun rename(roles: List<Role>, key: String, newName: String): List<Role> {
        val target = newName.trim()
        return roles.map {
            if (it.key == key) it.copy(name = target.ifEmpty { it.key }) else it
        }
    }

    /** 把 [from] 的记录并到 [to] 上（识别成两个名字的同一个人，用这个手动合并）。 */
    fun mergeTwo(roles: List<Role>, from: String, to: String): List<Role> {
        val a = roles.firstOrNull { it.key == from } ?: return roles
        val b = roles.firstOrNull { it.key == to } ?: return roles
        if (a.key == b.key) return roles
        val mergedMsgs = (b.msgs + a.msgs).sortedBy { it.at }
            .let { if (it.size > ROLE_MAX_MSGS) it.takeLast(ROLE_MAX_MSGS) else it }
        val merged = b.copy(
            name = if (b.renamed) b.name else a.name,
            relation = b.relation.ifBlank { a.relation },
            note = b.note.ifBlank { a.note },
            msgs = mergedMsgs,
        )
        return roles.filterNot { it.key == from }.map { if (it.key == to) merged else it }
    }
}
