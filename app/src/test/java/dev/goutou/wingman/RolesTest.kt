package dev.goutou.wingman

import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.RoleMsg
import dev.goutou.wingman.config.Roles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「1 小时内重复只留一条」这条规则靠肉眼在真机上是验不准的，必须单测。 */
class RolesTest {

    private val t0 = 1_700_000_000_000L
    private fun min(n: Long) = n * 60_000L

    @Test
    fun `同一小时内的重复内容只留一条`() {
        var roles = emptyList<Role>()
        // 模拟模块每 900ms 重读屏幕：同样的两行反复上报
        for (i in 0 until 20) {
            roles = Roles.merge(
                roles,
                listOf(
                    "张三" to RoleMsg(false, "在吗", t0 + i * 900L),
                    "张三" to RoleMsg(true, "在", t0 + i * 900L),
                ),
            )
        }
        assertEquals(1, roles.size)
        assertEquals(2, roles[0].msgs.size)
    }

    @Test
    fun `超过一小时后再出现同样的内容算新的一条`() {
        var roles = Roles.merge(emptyList(), listOf("张三" to RoleMsg(false, "在吗", t0)))
        roles = Roles.merge(roles, listOf("张三" to RoleMsg(false, "在吗", t0 + min(61))))
        assertEquals(2, roles[0].msgs.size)
    }

    @Test
    fun `方向不同不算重复`() {
        val roles = Roles.merge(
            emptyList(),
            listOf(
                "张三" to RoleMsg(false, "好的", t0),
                "张三" to RoleMsg(true, "好的", t0 + 1000L),
            ),
        )
        assertEquals(2, roles[0].msgs.size)
    }

    @Test
    fun `合并后按时间升序`() {
        val roles = Roles.merge(
            emptyList(),
            listOf(
                "张三" to RoleMsg(false, "第三句", t0 + 3000L),
                "张三" to RoleMsg(false, "第一句", t0),
                "张三" to RoleMsg(false, "第二句", t0 + 1000L),
            ),
        )
        assertEquals(listOf("第一句", "第二句", "第三句"), roles[0].msgs.map { it.text })
    }

    @Test
    fun `不同角色的消息互不干扰`() {
        val roles = Roles.merge(
            emptyList(),
            listOf(
                "张三" to RoleMsg(false, "你好", t0),
                "李四" to RoleMsg(false, "你好", t0),
            ),
        )
        assertEquals(2, roles.size)
    }

    @Test
    fun `超过上限时保留最近的`() {
        val incoming = (0 until 130).map { "张三" to RoleMsg(false, "第 $it 句", t0 + it * min(90)) }
        val roles = Roles.merge(emptyList(), incoming)
        assertEquals(120, roles[0].msgs.size)
        assertEquals("第 129 句", roles[0].msgs.last().text)
    }

    @Test
    fun `写档案不会丢掉已有记录`() {
        val roles = Roles.merge(emptyList(), listOf("张三" to RoleMsg(false, "你好", t0)))
        val next = Roles.setProfile(roles, "张三", "同事", "同一个组的后端")
        assertEquals(1, next.size)
        assertEquals("同事", next[0].relation)
        assertEquals("同一个组的后端", next[0].note)
        assertEquals(1, next[0].msgs.size)
    }

    @Test
    fun `写档案时角色不存在就新建`() {
        val next = Roles.setProfile(emptyList(), "王五", "家人", "")
        assertEquals(1, next.size)
        assertEquals("王五", next[0].name)
        assertTrue(next[0].msgs.isEmpty())
    }

    @Test
    fun `序列化可以往返`() {
        val roles = Roles.merge(
            emptyList(),
            listOf("张三" to RoleMsg(false, "你好", t0), "张三" to RoleMsg(true, "嗨", t0 + 1000L)),
        ).let { Roles.setProfile(it, "张三", "朋友", "老同学") }
        val back = Roles.decode(Roles.encode(roles))
        assertEquals(roles, back)
    }

    @Test
    fun `坏数据不会把配置读挂`() {
        assertEquals(emptyList<Role>(), Roles.decode(""))
        assertEquals(emptyList<Role>(), Roles.decode("不是 json"))
        assertEquals(emptyList<Role>(), Roles.decode("""{"n":"不是数组"}"""))
    }
}
