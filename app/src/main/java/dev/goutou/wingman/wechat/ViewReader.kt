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
 * 三条设计原则，都是原版踩过的坑：
 * 1. 不认类名：只认结构特征（屏幕下半部分有输入框 = 聊天页，AbsListView/RecyclerView = 消息列表）。
 * 2. 尽力少猜：方向由 头像位置 / 气泡左右 / 气泡颜色 三票决定，颜色只算一票（深色模式会失效）。
 * 3. 别每轮重画：气泡底色只采样一次并缓存；重画共用同一块 Bitmap
 *    （原版每轮、每条消息都 Bitmap.createBitmap(96,96)，1.5 秒一次，GC 压力全在这里）。
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
    private val pageBg: Int by lazy { detectPageBackground() }

    class BubbleHit(val side: Side, val centerRatio: Double, val widthRatio: Double)

    // ---------------- 对外 ----------------

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
     * 选「消息列表」。这是最容易出错的一步，所以规则写得啰嗦一点。
     *
     * v0.2.0（以及 v0.1）取的是深度优先遇到的**第一个** AbsListView/RecyclerView，
     * 完全不看它在屏幕的什么位置 —— 于是很容易选中输入框下方那个表情/更多功能的宫格：
     * 它也是 AbsListView，每一格都是 ImageView，加起来就是「读到的全是图片」。
     *
     * 现在以输入框为锚点，只接受同时满足这几条的容器：
     * 在屏幕上 / 完全位于输入框上方 / 高度不小于屏幕 22% / 至少 2 个子视图（避免选到只包一层的壳）。
     * 多个候选取面积最大的。
     */
    fun findList(root: View, input: View?): ViewGroup? {
        val inputTop = input?.let { topOf(it) } ?: height
        val candidates = ArrayList<ViewGroup>()
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
            val aboveInput = bottom <= inputTop + dp(8)
            val tallEnough = h >= height * 0.22
            val multiChild = v.childCount >= 2

            if (onScreen && aboveInput && tallEnough && multiChild) {
                candidates.add(v)
            } else {
                rejected.add(
                    describe(v) + " [" +
                        (if (!onScreen) "不在屏幕上 " else "") +
                        (if (!aboveInput) "伸到输入框下方 " else "") +
                        (if (!tallEnough) "太矮 " else "") +
                        (if (!multiChild) "子视图<2 " else "") + "]",
                )
            }
        }

        lastCandidates = "入选=${candidates.size}；排除=${rejected.take(6).joinToString(" / ").ifEmpty { "无" }}"
        return candidates.maxWithOrNull(compareBy({ it.width.toLong() * it.height }, { it.childCount }))
    }

    /** 上一次 findList 的候选情况，只用于诊断输出。 */
    var lastCandidates: String = "（还没找过）"
        private set

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
            for (i in 0 until minOf(rowCount, list.childCount)) {
                val row = list.getChildAt(i)
                sb.append("  行").append(i).append(' ').append(describe(row)).append(NL)
                val parts = ArrayList<String>(6)
                walk(row) { v ->
                    when {
                        v is ImageView && v.width >= dp(20) ->
                            parts.add("IMG ${v.width}x${v.height}@x${leftOf(v)}")
                        v is TextView && v !is EditText -> {
                            val t = v.text?.toString()?.trim().orEmpty()
                            if (t.isNotEmpty()) {
                                val hit = bubbleOf(v, row)
                                val side = hit?.side?.name ?: "无"
                                parts.add("TXT「${t.take(16)}」 ${v.width}x${v.height}@x${leftOf(v)} 气泡=$side")
                            }
                        }
                    }
                }
                if (parts.isNotEmpty()) sb.append("      ").append(parts.take(6).joinToString(" | ")).append(NL)
            }
        }
        return sb.toString().take(1800)
    }

    private fun describe(v: View): String {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        // childCount 在 ViewGroup 上，View 没有，别直接取
        val kids = if (v is ViewGroup) v.childCount else 0
        return "${v.javaClass.simpleName} ${v.width}x${v.height}@${loc[0]},${loc[1]} 子=$kids"
    }

    private fun leftOf(v: View): Int {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return loc[0]
    }

    private companion object {
        /** 避免在源码里写转义序列。 */
        val NL: String = System.lineSeparator()
    }

    /**
     * 变化指纹：只读最后一行。
     * 「有没有新消息」看最后一行就够，比每轮把整棵树走一遍便宜得多。
     */
    fun fingerprint(list: ViewGroup): String {
        val n = list.childCount
        if (n == 0) return "0"
        val sb = StringBuilder(n.toString())
        walk(list.getChildAt(n - 1)) { v ->
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

    // ---------------- 单行 ----------------

    private fun readRow(row: View): RowSnapshot {
        val nodes = ArrayList<Pair<TextNode, BubbleHit?>>(6)
        val avatars = ArrayList<AvatarNode>(2)

        walk(row) { v ->
            when {
                v is ImageView -> {
                    val w = if (v.width > 0) v.width else v.measuredWidth
                    val h = if (v.height > 0) v.height else v.measuredHeight
                    if (w in dp(24)..dp(84) && h > 0 && abs(w - h) <= dp(4)) {
                        val cx = centerX(v)
                        if (cx < width * 0.25 || cx > width * 0.75) avatars.add(AvatarNode(cx, topOf(v), w))
                    }
                }

                v is TextView && v !is EditText -> {
                    val text = v.text?.toString()?.trim().orEmpty()
                    if (text.isNotEmpty()) {
                        val kind = when {
                            Chrome.isTime(text) -> Kind.TIMESTAMP
                            Chrome.isTag(text) -> Kind.TAG
                            else -> Kind.OTHER
                        }
                        nodes.add(TextNode(text, topOf(v), kind, v.textSize) to bubbleOf(v, row))
                    }
                }
            }
        }

        // 一行里的「正文」= 位置最低的那个气泡文字块（上面那块通常是「引用」的旧消息）
        val lower = nodes.filter { it.second != null }.maxByOrNull { it.first.top }
        val bubbleSize = lower?.first?.size ?: 0f

        val bubble = when {
            lower != null -> {
                val hit = lower.second!!
                Bubble(lower.first.text, hit.side, hit.centerRatio, lower.first.top)
            }
            else -> fallbackBubble(nodes, avatars)
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
     * 认不出气泡容器时的兜底（比如主题把气泡换成了自绘背景）：
     * 拿最长的非噪音文字当正文，方向交给头像；没有文字但有头像的行算附件（图片/表情/语音）。
     */
    private fun fallbackBubble(nodes: List<Pair<TextNode, BubbleHit?>>, avatars: List<AvatarNode>): Bubble? {
        val best = nodes.map { it.first }
            .filter { it.kind == Kind.OTHER }
            .maxByOrNull { it.text.length }
        if (best != null) return Bubble(best.text, Side.UNKNOWN, null, best.top)
        val avatar = avatars.minByOrNull { it.top } ?: return null
        return Bubble(null, Side.UNKNOWN, null, avatar.top)
    }

    /**
     * 从文字块往上找气泡容器：第一个「不满屏宽、且看起来像气泡」的背景。
     * 满屏宽度的背景是消息行自己的底色，不算气泡 —— 这条判断避免了整行被误判。
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

    // ---------------- 取色 ----------------

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

    // ---------------- 小工具 ----------------

    /**
     * 迭代式深度优先遍历。
     * 两个原因不用递归：View 树可能几十层深；而且 Kotlin 不允许「递归的 inline 函数」，
     * 非 inline 又会让每层都多一次真实调用。用显式栈还能顺手把隐藏子树整枝剪掉。
     */
    private fun walk(root: View, action: (View) -> Unit) {
        val stack = ArrayDeque<View>()
        stack.addLast(root)
        var visited = 0
        while (stack.isNotEmpty() && visited < 5000) {
            val v = stack.removeLast()
            visited++
            if (v !== root && !v.isShown) continue
            action(v)
            if (v is ViewGroup) {
                for (i in v.childCount - 1 downTo 0) stack.addLast(v.getChildAt(i))
            }
        }
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
}
