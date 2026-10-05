package dev.goutou.wingman

import dev.goutou.wingman.wechat.MIN_CHAT_ROWS
import dev.goutou.wingman.wechat.NameCandidate
import dev.goutou.wingman.wechat.RowShape
import dev.goutou.wingman.wechat.filterNames
import dev.goutou.wingman.wechat.isRowNoise
import dev.goutou.wingman.wechat.pickRowName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「会话列表里哪一行是名字」怎么挑。
 *
 * 这段在真机上没法调（改一次要发版、要你手动试），所以规则必须在这里钉住：
 * 照着微信首页一行的样子构造候选，把「字号 / 靠上 / 靠左」三条判据挨个验一遍。
 */
class ConvNamesTest {

    /** 微信首页一行：名字（字号最大、靠左上）、最后一条消息（小字、在下面）、时间（小字、右上）。 */
    private fun row(
        name: String,
        preview: String = "在吗",
        time: String = "12:30",
        unread: String? = null,
        nameSize: Float = 42f,
    ): List<NameCandidate> = buildList {
        add(NameCandidate(name, nameSize, 100, 220))
        add(NameCandidate(preview, 30f, 160, 220))
        add(NameCandidate(time, 24f, 100, 980))
        if (unread != null) add(NameCandidate(unread, 24f, 108, 1050))
    }

    @Test
    fun `一行里挑字号最大的那个当名字`() {
        assertEquals("张三", pickRowName(row("张三")))
        assertEquals("老同学群", pickRowName(row("老同学群")))
    }

    @Test
    fun `时间与未读数不算名字`() {
        // 故意把时间和未读数排成「字号最大」—— 也得先被剔掉，不能靠字号赢
        val cands = listOf(
            NameCandidate("张三", 30f, 100, 220),
            NameCandidate("昨天 22:31", 60f, 100, 980),
            NameCandidate("3", 60f, 108, 1050),
        )
        assertEquals("张三", pickRowName(cands))
    }

    @Test
    fun `取不到字号时按靠上再靠左`() {
        // 自绘控件不是 TextView，textSize 一律取到 0 —— 这时靠上下和左右分：
        // 名字在「最后一条消息」上面（y 小），也在右上角那行时间的左边（x 小）
        val cands = row("李四").map { it.copy(textSize = 0f) }
        assertEquals("李四", pickRowName(cands))
    }

    @Test
    fun `tab 栏与搜索框提示不算名字`() {
        assertTrue(isRowNoise("微信"))
        assertTrue(isRowNoise("通讯录"))
        assertTrue(isRowNoise("搜索"))
        assertTrue(isRowNoise("新的朋友"))
        assertNull(pickRowName(listOf(NameCandidate("通讯录", 40f, 2000, 100))))
    }

    @Test
    fun `时间与占位消息不算名字`() {
        assertTrue(isRowNoise("12:30"))
        assertTrue(isRowNoise("昨天"))
        assertTrue(isRowNoise("星期一"))
        assertTrue(isRowNoise("[图片]"))
        assertTrue(isRowNoise("[草稿]"))
        assertTrue(isRowNoise("群主"))
        assertTrue(isRowNoise("7"))
        assertTrue(isRowNoise(""))
    }

    @Test
    fun `真名字要原样留下`() {
        // 带未读数的群名照原样返回 —— 归一化成白名单 key 是 App 那边的事（Roles.normalizeKey）
        assertEquals("家人群(5)", pickRowName(listOf(NameCandidate("家人群(5)", 40f, 100, 200))))
        assertEquals("妈", pickRowName(listOf(NameCandidate("妈", 40f, 100, 200))))
        assertEquals("阿明 A", pickRowName(listOf(NameCandidate("阿明 A", 40f, 100, 200))))
    }
    // ---------------- 形状闸：个人资料页不是会话列表 ----------------

    @Test
    fun `有左侧头像或时间角标的才像会话行`() {
        assertTrue(RowShape(avatarSize = 120, hasTimeMark = true, textCount = 3).looksLikeChat)
        // 头像没被认出来（自绘头像）时，有右上角时间也认
        assertTrue(RowShape(avatarSize = 0, hasTimeMark = true, textCount = 2).looksLikeChat)
        assertTrue(RowShape(avatarSize = 120, hasTimeMark = false, textCount = 3).looksLikeChat)
    }

    @Test
    fun `个人资料页的字段行不算会话行`() {
        // 微信号 / 地区 / 个性签名那种行：只有文字，没头像、没时间角标
        assertFalse(RowShape(avatarSize = 0, hasTimeMark = false, textCount = 1).looksLikeChat)
        assertFalse(RowShape(avatarSize = 0, hasTimeMark = false, textCount = 2).looksLikeChat)
        // 一个字都没有的行（纯图片）
        assertFalse(RowShape(avatarSize = 120, hasTimeMark = false, textCount = 0).looksLikeChat)
    }

    @Test
    fun `一个容器至少要两条会话行才算会话列表`() {
        assertEquals(2, MIN_CHAT_ROWS)
    }

    @Test
    fun `资料页的字段文字不是会话名`() {
        assertTrue(isRowNoise("微信号：wxid_abc123"))
        assertTrue(isRowNoise("微信号:abc"))
        assertTrue(isRowNoise("WeChat ID：abc"))
        assertTrue(isRowNoise("地区：广东 深圳"))
        assertTrue(isRowNoise("个性签名：随便写写"))
        assertTrue(isRowNoise("来源：通过手机号添加"))
        assertTrue(isRowNoise("朋友权限：聊天、朋友圈"))
        assertNull(pickRowName(listOf(NameCandidate("微信号：wxid_abc", 42f, 100, 200))))
    }

    @Test
    fun `只是名字里带资料词的不误伤`() {
        // 必须「标签 + 冒号」才是字段行；单独出现的人名/群名照旧算名字
        assertEquals("备注", pickRowName(listOf(NameCandidate("备注", 42f, 100, 200))))
        assertEquals("来源不明的群", pickRowName(listOf(NameCandidate("来源不明的群", 42f, 100, 200))))
    }

    @Test
    fun `搜索过滤：空查询原样返回`() {
        val all = listOf("妈妈", "老张", "项目群")
        assertEquals(all, filterNames(all, ""))
        assertEquals(all, filterNames(all, "   "))
    }

    @Test
    fun `搜索过滤：包含匹配 忽略大小写 保持原顺序`() {
        val all = listOf("Alice", "bob", "ALIEN", "老张")
        assertEquals(listOf("Alice", "ALIEN"), filterNames(all, "al"))
        assertEquals(listOf("bob"), filterNames(all, "BOB"))
        // 前后空格不算内容
        assertEquals(listOf("老张"), filterNames(all, "  老张 "))
    }

    @Test
    fun `搜索过滤：没命中就是空`() {
        assertTrue(filterNames(listOf("妈妈", "老张"), "群").isEmpty())
    }
}
