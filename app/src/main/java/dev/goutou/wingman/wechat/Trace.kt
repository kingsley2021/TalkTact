package dev.goutou.wingman.wechat

/**
 * 「决策轨迹」：把注入侧每一轮**读到什么、判成什么、为什么停**记成一条条事件，
 * 存在一个固定容量的环形缓冲里。
 *
 * 为什么需要它：卡片不弹有好几个分支都能造成（读不到输入框 / 没找到列表 / 一条都没解析出来 /
 * 最后一条是我发的 / 被白名单拦 / 还在最短间隔内 / 指纹没变…）。原来只有部分分支会写一份
 * 界面结构诊断，而且 30 秒限流一次 —— 中间那些轮次到底走到了哪一步，基本靠猜。
 * 轨迹是**每个分支都记一条**：便宜、连续、不丢现场，App 里直接看时间线。
 *
 * 和「界面结构诊断」的分工（刻意分开，别合并）：
 * - 轨迹 = **判定与时间线**（读到几条、停在哪一步、为什么），**不含消息正文**；
 * - 诊断 = **快照与细节**（View 树、控件尺寸、正文前 10 字），限流、体量大。
 * 轨迹会随心跳常驻 App 本地，所以它不该比诊断包更容易泄露聊天内容。
 *
 * 四条设计约束（都是被注入侧的环境逼出来的）：
 * 1. **只写内存，不落盘**：注入进微信进程的代码写不了本 App 的私有文件，跨进程只能走广播。
 *    所以这里是「攒着」的，等 App 来要（点「抓取微信界面」）或自动回传时一起走。
 * 2. **不能把广播淹了**：tick 900ms 一次，如实全记就是每小时几千条。所以
 *    **连续相同的记录只留一条、累加次数**（见 [note]）—— 正常聊天时它几乎不增长，
 *    判定一变就留下痕迹。这也是它能覆盖「最近几分钟」而不是「最近 70 秒」的原因。
 * 3. **够小**：[CAP] 条 × [MAX_CHARS] 字封顶（约 16KB），随广播发出去不会撑到 Binder 上限。
 * 4. **纯 Kotlin**（只用标准库），所以能在 JVM 单测里直接验 —— 见 `app/src/test` 的 TraceTest。
 */
object Trace {

    /** 最多留多少条（超出丢掉最旧的）。 */
    const val CAP = 80

    /** 单条文本最长多少字（超出截断）：挡住把整段提示词 / 整屏文本塞进来。 */
    const val MAX_CHARS = 200

    /**
     * 自动回传的最小间隔。轨迹本身很长（80 条），而 tick 900ms 一轮 ——
     * 不设间隔的话广播通道会被淹；设成 20 秒，用户在微信里复现完切回来就正好能看到。
     * 手动点「抓当前微信界面」不受这个限制（那是用户明确在要）。
     */
    const val SEND_MIN_MS = 20_000L

    /**
     * 一条轨迹。公开只为了能渲染，**不要**在别处 new 它 —— 一律走 [note]。
     */
    class Entry internal constructor(
        /** 全序号（从 0 起，跨越被挤掉的旧条目一直累加）：看「中间断了几轮」靠它 */
        val seq: Int,
        /** 阶段/类别，短词（输入 / 列表 / 跳过 / 白名单 …），方便按行扫 */
        val tag: String,
        val text: String,
        /** 第一次出现的时间 */
        val firstAt: Long,
    ) {
        /** 连续重复的次数（紧挨着的同 tag+text 会被合并到这一条上） */
        var repeat: Int = 1
            internal set

        /** 最近一次出现的时间 */
        var lastAt: Long = firstAt
            internal set
    }

    private val buf = ArrayList<Entry>(CAP)

    /** 全序号计数器：跨越被挤掉的旧条目一直累加。 */
    private var seq = 0

    /**
     * 内容版本号：**新增**一条就 +1（合并重复不算、清空算）。
     *
     * 为什么需要它：自动回传要判「轨迹有没有变」，而每 900ms 拼一遍 80 条文本去比对太浪费
     * （这里跑在微信主线程上）。比一个整数就够了。
     * 为什么合并重复不 +1：合并只改「这条出现了几次」，`×40` 和 `×41` 对排查没有区别 ——
     * 要是也 +1，那一行每轮都在长的「指纹没变」会让水位一直往上顶，变成每 20 秒必发一次。
     */
    private var ver = 0

    private var clock: () -> Long = { System.currentTimeMillis() }

    /**
     * 记一条。当 **上一条** 的 tag 和 text 都相同时，只累加次数、不新增 ——
     * 这是「900ms 一次 tick、却不把缓冲刷满」的关键：
     * 一行「×40」比 40 行一模一样的「指纹没变」信息量大得多，也读得下去。
     */
    fun note(tag: String, text: String) {
        val now = clock()
        val t = text.trim().take(MAX_CHARS)
        if (tag.isBlank() && t.isEmpty()) return
        synchronized(buf) {
            val last = buf.lastOrNull()
            if (last != null && last.tag == tag && last.text == t) {
                last.repeat++
                last.lastAt = now
                return
            }
            buf.add(Entry(seq++, tag, t, now))
            ver++
            while (buf.size > CAP) buf.removeAt(0)
        }
    }

    /** 当前条数（已合并重复）。 */
    fun size(): Int = synchronized(buf) { buf.size }

    /** 内容版本号：只有**新增**一条才会变。自动回传拿它当水位，见 [SEND_MIN_MS]。 */
    fun version(): Int = synchronized(buf) { ver }

    fun clear() {
        synchronized(buf) { buf.clear(); ver++ }
    }

    /**
     * 渲染成可直接读 / 直接复制的文本。**新的在上面** ——
     * 排查「为什么没弹卡片」要的是最后一刻的现场，倒序一眼就能看到。
     */
    fun dump(now: Long = clock()): String = synchronized(buf) {
        if (buf.isEmpty()) return "（还没有轨迹：微信里打开一个聊天页，再回来抓一次）"
        val sb = StringBuilder()
        sb.append("—— 决策轨迹（最近 ").append(buf.size).append(" 条，新→旧）——\n")
        for (i in buf.indices.reversed()) {
            val e = buf[i]
            sb.append('#').append(e.seq)
                .append(' ').append(ago(now - e.lastAt)).append(' ')
                .append(e.tag).append(' ').append(e.text)
            if (e.repeat > 1) sb.append("（×").append(e.repeat).append('）')
            sb.append('\n')
        }
        return sb.toString()
    }

    /** 相对时间：`now` / `-3.4s` / `-1m12s`。看时间线比看绝对时间戳快。 */
    private fun ago(ms: Long): String {
        if (ms < 1000L) return "now"
        val s = ms / 1000L
        if (s < 60L) return "-" + s + "." + ((ms % 1000L) / 100L) + "s"
        return "-" + (s / 60L) + "m" + (s % 60L) + "s"
    }

    /** 只给单测用：把时钟换成可控的。 */
    fun setClockForTest(c: () -> Long) {
        clock = c
    }

    /** 只给单测用：清空并还原时钟。 */
    fun resetForTest() {
        synchronized(buf) { buf.clear(); ver = 0 }
        clock = { System.currentTimeMillis() }
    }
}
