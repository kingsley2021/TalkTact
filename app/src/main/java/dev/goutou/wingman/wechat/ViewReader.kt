package dev.goutou.wingman.wechat

import android.app.Activity
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.NinePatchDrawable
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import android.widget.AbsListView
import android.widget.EditText
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import java.util.WeakHashMap
import kotlin.math.abs

/**
 * View 树 -> RowSnapshot。整个模块唯一依赖 Android API 的读取逻辑。
 *
 * 判据都是被真机数据打过脸的，改动前先看线上诊断（长按卡片标题）：
 *
 * 1. 选列表：不认类名顺序，以输入框为锚点（见 findList）。
 * 2. 微信的输入框「浮在」消息列表上层，列表高度会伸到输入框下面，不能要求列表整个在输入框上方。
 * 3. 正文不一定在 TextView 里：微信 8.0.78 实测 `jh` 行里只有时间戳是 TextView，
 *    正文要么在 INVISIBLE 的占位控件里、要么是自绘的 —— 所以这里：
 *    · 读文字时不跳过 INVISIBLE（只跳 GONE）
 *    · 兜底时用 contentDescription（读屏用的那种描述）
 * 4. 方向判定三票制（头像位置 / 气泡左右 / 气泡颜色），颜色只算一票（深色模式会失效）。
 * 5. 别每轮重画：气泡底色只采样一次并缓存，重画共用同一块 Bitmap。
 */
internal class ViewReader(private val a: Activity) {

    private val metrics = a.resources.displayMetrics
    private val width = metrics.widthPixels
    private val height = metrics.heightPixels
    private val density = metrics.density
    private val night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private fun dp(v: Int) = (v * density).toInt()

    private val scratch: Bitmap by lazy { Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888) }
    private val canvas = Canvas()
    private val bubbleCache = WeakHashMap<View, BubbleHit>()

    /** 类 -> 它的 getText() 方法（null 表示这个类没有）。只查一次。 */
    private val textMethods = HashMap<Class<*>, java.lang.reflect.Method?>()
    private val pageBg: Int by lazy { detectPageBackground() }

    /** 上一次 findList 的候选情况，只用于诊断输出。 */
    var lastCandidates: String = "（还没找过）"
        private set

    class BubbleHit(val side: Side, val centerRatio: Double, val widthRatio: Double)

    // ---------------- 找锚点 ----------------

    /** 聊天输入框：必须在屏幕偏下的位置，这样顶部的搜索框不会被当成输入框。 */
    fun findChatInput(root: View): EditText? {
        var best: EditText? = null
        walk(root) { v ->
            if (best == null && v is EditText && v.isShown && v.width > dp(50) && topOf(v) > height * 0.3) {
                best = v
            }
        }
        return best
    }

    /**
     * 选「消息列表」。规则：
     * 起于输入框上方 / 在屏幕上 / 内部不含输入框（排除页面级容器）/ 够高 / 子视图≥2。
     * 多个候选取面积最大者，面积相同取子视图多的那个。
     * 真机数据（微信 8.0.78，1156x2306，输入框 y=2228）：
     *   选中 ScrollControlRecyclerView 1156x2450@0,0 子=9；MMChattingListView 子=3 为同一块。
     */
    fun findList(root: View, input: View?): ViewGroup? {
        val inputTop = input?.let { topOf(it) } ?: height
        val strict = ArrayList<ViewGroup>()
        val relaxedNotes = ArrayList<String>()
        val rejected = ArrayList<String>()

        walk(root) { v ->
            if (v !is ViewGroup || !v.isShown) return@walk
            val w = v.width
            val h = v.height
            if (w <= 0 || h <= 0) return@walk
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val top = loc[1]
            val bottom = top + h

            val looksList = v is AbsListView || v is ScrollView ||
                v.javaClass.name.contains("RecyclerView") ||
                v.javaClass.name.contains("ListView") ||
                v.javaClass.name.contains("ScrollView") ||
                v.canScrollVertically(1) || v.canScrollVertically(-1)
            if (!looksList) return@walk

            val onScreen = loc[0] < width && loc[0] + w > 0 && bottom > 0 && top < height
            val startsAboveInput = top < inputTop
            val holdsInput = input != null && isAncestor(v, input)
            val tallEnough = h >= height * 0.22
            val multiChild = v.childCount >= 2

            val fatal = buildString {
                if (!onScreen) append("不在屏幕上 ")
                if (!startsAboveInput) append("起点在输入框下方 ")
                if (holdsInput) append("内部含输入框(页面容器) ")
            }.trim()
            val soft = buildString {
                if (!tallEnough) append("太矮 ")
                if (!multiChild) append("子视图<2 ")
            }.trim()

            when {
                fatal.isNotEmpty() -> rejected.add(describe(v) + " [$fatal]")
                soft.isEmpty() -> strict.add(v)
                else -> relaxedNotes.add(describe(v) + " [$soft]")
            }
        }

        val chosen = strict.maxWithOrNull(compareBy({ it.width.toLong() * it.height }, { it.childCount }))
        lastCandidates = if (chosen == null) {
            "入选=0；位置不合格=${rejected.take(6).joinToString(" / ").ifEmpty { "无" }}；" +
                "仅差高度/子视图=${relaxedNotes.take(3).joinToString(" / ").ifEmpty { "无" }}"
        } else {
            "入选=${strict.size}，选中=${describe(chosen)}；" +
                "仅差高度/子视图=${relaxedNotes.take(3).joinToString(" / ").ifEmpty { "无" }}；" +
                "位置不合格=${rejected.take(4).joinToString(" / ").ifEmpty { "无" }}"
        }
        return chosen
    }

    // ---------------- 读内容 ----------------

    /**
     * 变化指纹：只读最后一行。
     * 「有没有新消息」看最后一行就够，比每轮把整棵树走一遍便宜得多。
     */
    fun fingerprint(list: ViewGroup): String {
        val n = list.childCount
        if (n == 0) return "0"
        val sb = StringBuilder(n.toString())
        walk(list.getChildAt(n - 1), includeInvisible = true) { v ->
            if (v is TextView && v !is EditText) {
                val t = v.text?.toString()?.trim().orEmpty()
                if (t.isNotEmpty()) sb.append('|').append(t.take(48))
            }
        }
        return sb.toString()
    }

    fun snapshot(list: ViewGroup): List<RowSnapshot> {
        val rows = ArrayList<RowSnapshot>(list.childCount)
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            if (!row.isShown) continue
            rows.add(runCatching { readRow(row) }.getOrElse { RowSnapshot(null) })
        }
        return rows
    }

    private fun readRow(row: View): RowSnapshot {
        val nodes = ArrayList<Pair<TextNode, BubbleHit?>>(6)
        val avatars = ArrayList<AvatarNode>(2)
        val spoken = ArrayList<TextNode>(3)

        // includeInvisible = true：微信可能把正文放在 INVISIBLE 的占位控件里，
        // 只认 isShown() 会把正文整条漏掉。这里只跳过 GONE。
        walk(row, includeInvisible = true) { v ->
            when {
                v is ImageView -> {
                    val w = if (v.width > 0) v.width else v.measuredWidth
                    val h = if (v.height > 0) v.height else v.measuredHeight
                    if (w in dp(24)..dp(84) && h > 0 && abs(w - h) <= dp(4)) {
                        val cx = centerX(v)
                        if (cx < width * 0.25 || cx > width * 0.75) avatars.add(AvatarNode(cx, topOf(v), w))
                    }
                    // 头像上的 contentDescription 通常是联系人名，不当正文
                }

                v is TextView && v !is EditText -> {
                    val text = v.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        val kind = when {
                            Chrome.isTime(text) -> Kind.TIMESTAMP
                            Chrome.isTag(text) -> Kind.TAG
                            else -> Kind.OTHER
                        }
                        nodes.add(TextNode(text, topOf(v), kind, v.textSize, v.isShown) to bubbleOf(v, row))
                    } else {
                        collectDesc(v, spoken)
                    }
                }

                else -> {
                    val reflected = reflectText(v)
                    if (reflected.length >= 2 && reflected.length <= 500 && reflected !in UI_WORDS) {
                        // 自绘控件的正文，自己拿到手了
                        nodes.add(
                            TextNode(reflected, topOf(v), Kind.OTHER, 0f, v.isShown) to bubbleOf(v, row),
                        )
                    } else {
                        collectDesc(v, spoken)
                    }
                }
            }
        }

        // 一行里的「正文」= 位置最低的气泡文字块（上面那块通常是「引用」的旧消息）
        val bubbleNodes = nodes.filter { it.second != null }
        val visibleBubble = bubbleNodes.filter { it.first.visible }
        val lower = (if (visibleBubble.isNotEmpty()) visibleBubble else bubbleNodes).maxByOrNull { it.first.top }
        val bubbleSize = lower?.first?.size ?: 0f

        val bubble = if (lower != null) {
            val hit = lower.second!!
            Bubble(lower.first.text, hit.side, hit.centerRatio, lower.first.top)
        } else {
            fallbackBubble(nodes, spoken, avatars)
        }

        val texts = nodes.map { (node, hit) ->
            val b = bubble
            when {
                node.kind != Kind.OTHER -> node
                hit != null && hit.side != Side.UNKNOWN -> node.copy(kind = Kind.BUBBLE)
                b != null && b.text != null && node.top <= b.top - dp(2) &&
                    (bubbleSize <= 0f || node.size < bubbleSize) -> node.copy(kind = Kind.NICKNAME)
                else -> node
            }
        }

        return RowSnapshot(bubble, texts, avatars)
    }

    /**
     * 反射兜底取正文。
     *
     * 微信的自绘正文控件不继承 TextView，但很多这类控件仍然保留一个 `getText()` 方法
     * （它们自己也要序列化/复制文本）。每个类只查一次方法，查不到就记住 null。
     */
    private fun reflectText(v: View): String {
        val cls = v.javaClass
        val method = if (textMethods.containsKey(cls)) {
            textMethods[cls]
        } else {
            val found = try {
                cls.getMethod("getText").takeIf {
                    CharSequence::class.java.isAssignableFrom(it.returnType)
                }
            } catch (t: Throwable) {
                null
            }
            textMethods[cls] = found
            found
        } ?: return ""
        return try {
            (method.invoke(v) as? CharSequence)?.toString()?.trim().orEmpty()
        } catch (t: Throwable) {
            ""
        }
    }

    /**
     * 无障碍描述兜底。
     * 正文如果由自绘控件画出来，getText() 是空的；但微信一般会给控件挂一个
     * contentDescription（否则读屏软件读不了消息），这里把它当最后一道兜底。
     */
    private fun collectDesc(v: View, out: MutableList<TextNode>) {
        val desc = v.contentDescription?.toString()?.trim().orEmpty()
        if (desc.length < 2 || desc.length > 200) return
        if (desc in UI_WORDS) return
        out.add(TextNode(desc, topOf(v), Kind.OTHER, 0f, v.isShown))
    }

    /**
     * 认不出气泡容器时的兜底：
     * 先取最长的非噪音文字，再退到无障碍描述，最后才是「有头像的行 = 图片/表情」。
     */
    private fun fallbackBubble(
        nodes: List<Pair<TextNode, BubbleHit?>>,
        spoken: List<TextNode>,
        avatars: List<AvatarNode>,
    ): Bubble? {
        val texts = nodes.map { it.first }
        val visibleTexts = texts.filter { it.visible }
        (if (visibleTexts.isNotEmpty()) visibleTexts else texts)
            .filter { it.kind == Kind.OTHER }
            .maxByOrNull { it.text.length }
            ?.let { return Bubble(it.text, Side.UNKNOWN, null, it.top) }

        val visibleSpoken = spoken.filter { it.visible }
        (if (visibleSpoken.isNotEmpty()) visibleSpoken else spoken)
            .maxByOrNull { it.text.length }
            ?.let { return Bubble(it.text, Side.UNKNOWN, null, it.top) }

        val avatar = avatars.minByOrNull { it.top } ?: return null
        return Bubble(null, Side.UNKNOWN, null, avatar.top)
    }

    // ---------------- 诊断 ----------------

    /**
     * 结构快照：真机上「为什么读不到 / 为什么全是图片」靠它定位，不用猜。
     * 会写进 LSPosed 日志，同时广播回 App 首页，可以直接复制发出来。
     */
    fun diagnose(root: View, list: ViewGroup?, input: View?, rowCount: Int = 3): String {
        val sb = StringBuilder()
        sb.append("DIAG 屏=").append(width).append('x').append(height)
            .append(" dp=").append(density).append(" 夜间=").append(night).append(NL)
        sb.append("输入框: ").append(input?.let { describe(it) } ?: "未找到").append(NL)
        sb.append("消息列表: ").append(list?.let { describe(it) } ?: "未找到").append(NL)
        sb.append("候选列表: ").append(lastCandidates).append(NL)
        if (list != null) {
            for (r in 0 until minOf(rowCount, list.childCount)) {
                if (sb.length > 3400) break
                val row = list.getChildAt(r)
                sb.append("行").append(r).append(' ').append(describe(row)).append(NL)
                dumpRow(sb, row, 14)
            }
        }
        return sb.toString().take(3800)
    }

    /**
     * 把一行控件全部摊开打印 —— 定位「正文到底藏在哪个控件里」。
     * 每条形如 `#2 MMTextView 700x120@180,320 v0S t="在吗"`
     * v 后面是 visibility（0=VISIBLE / 4=INVISIBLE / 8=GONE），S=isShown、s=不是。
     */
    private fun dumpRow(sb: StringBuilder, row: View, maxEntries: Int) {
        var count = 0
        fun dump(v: View, depth: Int) {
            if (count >= maxEntries || sb.length > 3400) return
            count++
            sb.append("  ".repeat(depth))
                .append('#').append(count - 1).append(' ')
                .append(v.javaClass.simpleName).append(' ')
                .append(v.width).append('x').append(v.height)
                .append('@').append(leftOf(v)).append(',').append(topOf(v))
                .append(" v").append(v.visibility).append(if (v.isShown) 'S' else 's')
            val text = (v as? TextView)?.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) sb.append(" t=\"").append(text.take(18)).append('"')
            val desc = v.contentDescription?.toString()?.trim().orEmpty()
            if (desc.isNotEmpty()) sb.append(" d=\"").append(desc.take(18)).append('"')
            if (text.isEmpty() && desc.isEmpty() && v !is ViewGroup) {
                val reflected = reflectText(v)
                if (reflected.isNotEmpty()) sb.append(" r=\"").append(reflected.take(18)).append('"')
            }
            if (text.isEmpty() && desc.isEmpty() && v !is ViewGroup && count <= 10) {
                val a11y = try {
                    v.createAccessibilityNodeInfo()?.text?.toString()?.trim().orEmpty()
                } catch (t: Throwable) {
                    ""
                }
                if (a11y.isNotEmpty()) sb.append(" a=\"").append(a11y.take(18)).append('"')
            }
            sb.append(NL)
            if (v is ViewGroup && depth < 4) {
                for (i in 0 until minOf(v.childCount, 12)) dump(v.getChildAt(i), depth + 1)
            }
        }
        dump(row, 1)
    }

    private fun describe(v: View): String {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val kids = if (v is ViewGroup) v.childCount else 0
        return "${v.javaClass.simpleName} ${v.width}x${v.height}@${loc[0]},${loc[1]} 子=$kids"
    }

    private fun leftOf(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[0]
    }

    // ---------------- 气泡与取色 ----------------

    /**
     * 从文字块往上找气泡容器：第一个「不满屏宽、且看起来像气泡」的背景。
     * 满屏宽度的背景是消息行自己的底色，不算气泡 —— 这条判断避免整行被误判。
     */
    private fun bubbleOf(v: View, row: View): BubbleHit? {
        bubbleCache[v]?.let { return it }
        var c: View? = v
        var hit: BubbleHit? = null
        while (c != null) {
            val bg = c.background
            if (bg != null) {
                val w = if (c.width > 0) c.width else c.measuredWidth
                val ratio = if (width > 0) w.toDouble() / width else 1.0
                if (ratio < 0.92) {
                    val px = sample(bg)
                    if (((px ushr 24) and 0xFF) >= 24) {
                        val looksLikeBubble = bg is NinePatchDrawable || bg is BitmapDrawable ||
                            bg.javaClass.name.contains("Bubble", ignoreCase = true) ||
                            colorDistance(px, pageBg) > 20
                        if (looksLikeBubble) {
                            hit = BubbleHit(classify(px), centerX(c).toDouble() / width.coerceAtLeast(1), ratio)
                            break
                        }
                    }
                }
            }
            if (c === row) break
            c = c.parent as? View
        }
        if (hit != null) bubbleCache[v] = hit
        return hit
    }

    private fun sample(drawable: Drawable): Int = try {
        val d = drawable.constantState?.newDrawable()?.mutate() ?: drawable
        canvas.setBitmap(scratch)
        scratch.eraseColor(0)
        d.setBounds(0, 0, 64, 64)
        d.draw(canvas)
        canvas.setBitmap(null)
        scratch.getPixel(32, 32)
    } catch (t: Throwable) {
        0
    }

    private fun classify(px: Int): Side {
        if (((px ushr 24) and 0xFF) < 24) return Side.UNKNOWN
        val r = (px shr 16) and 0xFF
        val g = (px shr 8) and 0xFF
        val b = px and 0xFF
        return if (g - r > 16 && g - b > 10) Side.ME else Side.OTHER
    }

    private fun colorDistance(a: Int, b: Int): Int {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr + dg + db
    }

    private fun detectPageBackground(): Int {
        val decor = a.window?.decorView
        val fallback = if (night) 0xFF121212.toInt() else 0xFFFFFFFF.toInt()
        if (decor == null) return fallback
        var found = 0
        walk(decor) { v ->
            if (found == 0) {
                val bg = v.background ?: return@walk
                val px = sample(bg)
                if (((px ushr 24) and 0xFF) == 0xFF) found = px
            }
        }
        return if (found != 0) found else fallback
    }

    // ---------------- 遍历与小工具 ----------------

    /**
     * 迭代式深度优先遍历（Kotlin 不允许递归的 inline 函数，用显式栈）。
     *
     * @param includeInvisible true 时只跳过 GONE。
     *   读正文必须为 true：微信会把正文放在 INVISIBLE 的占位控件里。
     *   找输入框/列表时保持 false（只认真正显示的控件）。
     */
    private fun walk(root: View, includeInvisible: Boolean = false, action: (View) -> Unit) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 5000) {
            val v = stack.removeLast()
            visited++
            if (v !== root) {
                val blocked = if (includeInvisible) v.visibility == View.GONE else !v.isShown
                if (blocked) continue
            }
            action(v)
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
    }

    /** 判断 parent 是否是 child 的祖先（往上走，比往下遍历便宜）。 */
    private fun isAncestor(parent: View, child: View): Boolean {
        var p: ViewParent? = child.parent
        var guard = 0
        while (p is View && guard++ < 60) {
            if (p === parent) return true
            p = p.parent
        }
        return false
    }

    private fun topOf(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[1]
    }

    private fun centerX(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val w = if (v.width > 0) v.width else v.measuredWidth
        return loc[0] + w / 2
    }

    private companion object {
        /** 避免在源码里写转义序列。 */
        val NL: String = System.lineSeparator()

        /** 纯 UI 文案的无障碍描述，不当消息正文。 */
        val UI_WORDS = setOf(
            "头像", "表情", "更多功能", "更多", "返回", "发送", "语音输入", "加号",
            "图片", "视频", "按住 说话", "切换键盘", "菜单", "关闭", "搜索", "聊天信息",
        )
    }
}
