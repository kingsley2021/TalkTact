package dev.goutou.wingman

import dev.goutou.wingman.config.Role
import dev.goutou.wingman.config.RoleMsg
import dev.goutou.wingman.config.ROLE_MAX_MSGS
import dev.goutou.wingman.config.Roles
import dev.goutou.wingman.config.SELF_ROLE_KEY
import dev.goutou.wingman.config.SELF_ROLE_NAME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        assertEquals("王五", next[0].key)
        assertEquals("王五", next[0].name)
        assertTrue(next[0].msgs.isEmpty())
    }

    @Test
    fun `群名结尾的成员数会被归一化掉`() {
        assertEquals("同事摸鱼群", Roles.normalizeKey("同事摸鱼群(9)"))
        assertEquals("同事摸鱼群", Roles.normalizeKey(" 同事摸鱼群 (12) "))
        assertEquals("例会", Roles.normalizeKey("例会"))
        // 括号在中间的不动（那是名字的一部分）
        assertEquals("群(9)公告", Roles.normalizeKey("群(9)公告"))
    }

    @Test
    fun `改名只动显示名，消息照样记到同一条`() {
        var roles = Roles.merge(emptyList(), listOf("张三" to RoleMsg(false, "你好", t0)))
        roles = Roles.rename(roles, "张三", "老张")
        assertEquals("老张", roles[0].name)
        assertEquals("张三", roles[0].key)
        assertTrue(roles[0].renamed)

        // 之后还是用识别名上报消息 → 必须落回同一条
        roles = Roles.merge(roles, listOf("张三" to RoleMsg(false, "在吗", t0 + min(30))))
        assertEquals(1, roles.size)
        assertEquals(2, roles[0].msgs.size)
        assertEquals("老张", roles[0].name)
    }

    @Test
    fun `旧数据没有 k 字段时用名字当 key`() {
        val legacy = """[{"n":"张三","r":"同事","m":[{"me":false,"t":"你好","a":$t0}]}]"""
        val roles = Roles.decode(legacy)
        assertEquals(1, roles.size)
        assertEquals("张三", roles[0].key)
        assertEquals("张三", roles[0].name)
        assertFalse(roles[0].renamed)
    }

    @Test
    fun `手改的名字不会被同名的识别名顶掉`() {
        var roles = Roles.merge(emptyList(), listOf("物业管家" to RoleMsg(false, "交费了", t0)))
        roles = Roles.rename(roles, "物业管家", "小区物业")
        roles = Roles.setProfile(roles, "物业管家", "服务方", "")
        assertEquals("小区物业", roles[0].name)
        assertEquals("服务方", roles[0].relation)
        assertNotEquals(2, roles.size)
    }

    @Test
    fun `手动合并：记录按时间升序排到一起，被并的那条消失`() {
        val zhang = Role(
            "张三",
            msgs = listOf(RoleMsg(false, "在吗", t0 + min(5)), RoleMsg(true, "在", t0 + min(6))),
        )
        val dup = Role("张三(2)", msgs = listOf(RoleMsg(false, "文件发你了", t0 + min(1))))

        val out = Roles.mergeTwo(listOf(dup, zhang), from = "张三(2)", to = "张三")
        val merged = out.first { it.key == "张三" }
        assertEquals(listOf("文件发你了", "在吗", "在"), merged.msgs.map { it.text })
        assertTrue(out.none { it.key == "张三(2)" })
    }

    @Test
    fun `手动合并：名字和档案以保留的那条为准，空着才用对方的补`() {
        val keep = Role("张三", relation = "同事", note = "写过后端")
        val gone = Role("老王", relation = "朋友", note = "打球的")
        val out = Roles.mergeTwo(listOf(keep, gone), from = "老王", to = "张三")
        val merged = out.first { it.key == "张三" }
        // 名字永远是保留那条自己的（不会因为对方改过名就被顶掉）
        assertEquals("张三", merged.name)
        assertEquals("同事", merged.relation)
        assertEquals("写过后端", merged.note)

        // 保留那条的档案空着时，用对方的补
        val empty = Role("小李")
        val rich = Role("小李(2)", relation = "同学", note = "高中的")
        val out2 = Roles.mergeTwo(listOf(empty, rich), from = "小李(2)", to = "小李")
        assertEquals("同学", out2.first { it.key == "小李" }.relation)
        assertEquals("高中的", out2.first { it.key == "小李" }.note)
    }

    @Test
    fun `手动合并：「本人」不参与，合并自己到自己也不动`() {
        val self = Role(SELF_ROLE_KEY, name = SELF_ROLE_NAME, msgs = listOf(RoleMsg(true, "我发的话", t0)))
        val other = Role("张三", msgs = listOf(RoleMsg(false, "你好", t0 + min(1))))
        val input = listOf(self, other)

        assertEquals(input, Roles.mergeTwo(input, from = "张三", to = SELF_ROLE_KEY))
        assertEquals(input, Roles.mergeTwo(input, from = SELF_ROLE_KEY, to = "张三"))
        assertEquals(input, Roles.mergeTwo(input, from = "张三", to = "张三"))
        // 不存在的 key 也不动
        assertEquals(input, Roles.mergeTwo(input, from = "查无此人", to = "张三"))
    }

    @Test
    fun `手动合并：超过上限时留最近的`() {
        val many = (0 until 200).map { RoleMsg(false, "旧$it", t0 + it * 1000L) }
        val a = Role("A", msgs = many)
        val b = Role("B")
        val merged = Roles.mergeTwo(listOf(a, b), from = "A", to = "B").first { it.key == "B" }
        assertEquals(ROLE_MAX_MSGS, merged.msgs.size)
        assertEquals("旧199", merged.msgs.last().text)
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
