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

/**
 * 一条被记录下来的聊天消息。
 *
 * 时间是「模块看到它的时间」，不是微信里那条消息的真实时间 —— 微信不暴露每条消息的时间戳，
 * 而模块只能在聊天页可见时读到当前屏幕上的那几行。所以这里是「观测时间」，够用来排序了。
 */
data class RoleMsg(val fromMe: Boolean, val text: String, val at: Long)

/** 一个「角色」：某个微信联系人 + 你对他的定位 + 平时攒下来的聊天记录。 */
data class Role(
    val name: String,
    /** TA 是你什么人：家人 / 朋友 / 同事 …（自定义也行） */
    val relation: String = "",
    /** 平时的关系：自由文本，越具体对模型越有用 */
    val note: String = "",
    /** 按观测时间升序 */
    val msgs: List<RoleMsg> = emptyList(),
) {
    val lastAt: Long get() = msgs.lastOrNull()?.at ?: 0L
}

/** 查重窗口：1 小时内内容相同的重复消息只留一条。 */
const val ROLE_DEDUP_WINDOW_MS = 3_600_000L

/** 每个角色最多留多少条（提示词只用最近一小段，其余是给你自己看的）。 */
const val ROLE_MAX_MSGS = 120

/** 最多保留多少个角色（按最近活跃淘汰）。 */
const val ROLE_MAX_COUNT = 40

/**
 * 角色的序列化 / 反序列化 / 合并。
 *
 * 全是纯函数，不碰 Android，所以可以直接单测 —— 「1 小时内重复只留一条」这条规则
 * 靠肉眼在真机上是验不准的。
 */
object Roles {

    fun decode(raw: String): List<Role> {
        val root = Json.parse(raw)?.asArr() ?: return emptyList()
        return root.mapNotNull { el ->
            val o = el.asObj() ?: return@mapNotNull null
            val name = o["n"].asStr()?.trim().orEmpty()
            if (name.isEmpty()) return@mapNotNull null
            val msgs = o["m"].asArr().orEmpty().mapNotNull { mi ->
                val mo = mi.asObj() ?: return@mapNotNull null
                val text = mo["t"].asStr()?.trim().orEmpty()
                if (text.isEmpty()) return@mapNotNull null
                RoleMsg(mo["me"].asBool() ?: false, text, mo["a"].asDouble()?.toLong() ?: 0L)
            }
            Role(
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
                    "n" to str(r.name),
                    "r" to str(r.relation),
                    "d" to str(r.note),
                    "m" to arr(r.msgs.map { m -> obj("me" to bool(m.fromMe), "t" to str(m.text), "a" to num(m.at)) }),
                )
            },
        ),
    )

    /** 注入侧回传用的紧凑格式（不带关系字段，只有「谁、谁发的、内容、时间」）。 */
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
     * 合并新观测到的消息。
     *
     * 查重规则（「1 小时内多条重复只留一条」）：同一角色、同一方向、内容完全相同，
     * 且和已记录的某条时间差在 1 小时以内 → 判定重复，丢弃。
     * 之所以需要它：模块每 900ms 就会重新读到屏幕上那同样的几行。
     */
    fun merge(roles: List<Role>, incoming: List<Pair<String, RoleMsg>>): List<Role> {
        if (incoming.isEmpty()) return roles
        val byName = LinkedHashMap<String, Role>()
        roles.forEach { byName[it.name] = it }
        for ((name, m) in incoming) {
            if (name.isBlank() || m.text.isBlank()) continue
            val cur = byName[name] ?: Role(name)
            val dup = cur.msgs.any {
                it.fromMe == m.fromMe && it.text == m.text && abs(it.at - m.at) <= ROLE_DEDUP_WINDOW_MS
            }
            if (dup) continue
            val merged = (cur.msgs + m).sortedBy { it.at }
            byName[name] = cur.copy(
                msgs = if (merged.size > ROLE_MAX_MSGS) merged.takeLast(ROLE_MAX_MSGS) else merged,
            )
        }
        return byName.values.sortedByDescending { it.lastAt }.take(ROLE_MAX_COUNT)
    }

    /** 写「TA 是你什么人 / 平时的关系」。角色不存在就新建一个。 */
    fun setProfile(roles: List<Role>, name: String, relation: String, note: String): List<Role> {
        val target = name.trim()
        if (target.isEmpty()) return roles
        val found = roles.any { it.name == target }
        val next = if (found) {
            roles.map { if (it.name == target) it.copy(relation = relation, note = note) else it }
        } else {
            roles + Role(target, relation, note)
        }
        return next.sortedByDescending { it.lastAt }
    }
}
