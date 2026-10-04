package dev.goutou.wingman.wechat

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import de.robv.android.xposed.XSharedPreferences
import de.robv.android.xposed.XposedBridge
import dev.goutou.wingman.Heartbeat
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.MODULE_PKG
import dev.goutou.wingman.config.PREF_NAME
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.LlmException
import dev.goutou.wingman.llm.Suggestion

/** 每个微信 Activity 一个面板；面板自己判断「现在是不是聊天页」。 */
internal object PanelRegistry {
    private val panels = java.util.WeakHashMap<Activity, Panel>()

    fun onResume(activity: Activity) {
        val existing = panels[activity]
        if (existing != null) existing.onResume() else panels[activity] = Panel(activity).also { it.onResume() }
    }

    fun onPause(activity: Activity) {
        panels[activity]?.onPause()
    }
}

/**
 * 聊天页顶部那张候选回复卡片。
 *
 * 和原版的区别：
 * - 不在 Activity.onResume 时一次性建面板，而是绑定生命周期（onPause 停轮询、摘掉视图，省电、也不会残留在后台任务里）。
 * - 不再用 dp(96) 这种写死的顶部偏移：卡片贴在「消息列表顶部」的真实位置上，各机型/各版本都不用改代码。
 * - 深色模式有对应的配色（原版写死白色卡片，深色主题下非常刺眼）。
 * - 轮询间隔自适应：在聊天页 900ms，不在聊天页 2.6s；并且只有「最后一行变了」才会真正读列表 + 调接口。
 * - 结果按消息指纹缓存，来回切页面不会重复烧 token；两次调用之间有最短间隔限制。
 */
internal class Panel(private val a: Activity) {

    private val reader = ViewReader(a)
    private val parser = ChatParser(a.resources.displayMetrics.widthPixels)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = XSharedPreferences(MODULE_PKG, PREF_NAME)

    private val density = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()
    private val night = (a.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

    private val colorCard = if (night) 0xF21B1A22.toInt() else 0xF2FFFFFF.toInt()
    private val colorStroke = if (night) 0x557C3AED.toInt() else 0x447C3AED.toInt()
    private val colorMain = if (night) 0xFFEDEAF5.toInt() else 0xFF1C1B22.toInt()
    private val colorSub = if (night) 0xFF9A96A8.toInt() else 0xFF6E6A7C.toInt()
    private val colorReply = if (night) 0xFF2A2440.toInt() else 0xFFEDE7FA.toInt()
    private val colorAccent = 0xFF7C3AED.toInt()
    private val colorWarn = 0xFFE8A317.toInt()
    private val colorBad = 0xFFD64545.toInt()
    private val colorOk = 0xFF2FA566.toInt()

    private val card = LinearLayout(a)
    private val title = TextView(a)
    private val bodyBox = LinearLayout(a)
    private val chip = TextView(a)

    private var attached = false
    private var running = false
    private var onChat = false
    private var busy = false
    private var force = false
    private var generation = 0
    private var cardTop = -1
    private var lastCallAt = 0L
    private var lastFingerprint = ""
    private var dismissed = ""
    private var skipSensitiveFor = ""

    private var lastDiagAt = 0L
    private var noListTicks = 0
    private var config: ConfigData? = null
    private var prefsStamp = -1L
    private var inputRef: EditText? = null
    private var listRef: ViewGroup? = null
    private val cache = LinkedHashMap<String, Suggestion>()

    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            try {
                tick()
            } catch (t: Throwable) {
                XposedBridge.log("[Goutou] tick: $t")
            }
            handler.postDelayed(this, if (onChat) 900L else 2600L)
        }
    }

    fun onResume() {
        try {
            if (!attached) attach()
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] attach failed: $t")
            return
        }
        running = true
        handler.removeCallbacks(ticker)
        handler.postDelayed(ticker, 400L)
    }

    fun onPause() {
        running = false
        busy = false
        onChat = false
        generation++
        handler.removeCallbacks(ticker)
        card.visibility = View.GONE
        chip.visibility = View.GONE
    }

    // ---------------- 视图 ----------------

    private fun attach() {
        val decor = a.window?.decorView as? ViewGroup ?: return

        card.orientation = LinearLayout.VERTICAL
        card.background = roundRect(colorCard, 20, colorStroke)
        card.setPadding(dp(14), dp(10), dp(14), dp(10))
        card.elevation = dp(8).toFloat()
        card.visibility = View.GONE

        val head = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        title.textSize = 13f
        title.setTextColor(colorAccent)
        // 长按标题：把当前 View 树结构写成诊断（App 首页可复制，日志里也有一份）
        title.setOnLongClickListener {
            a.window?.decorView?.let { decor -> dumpDiagnosis(decor, listRef, inputRef, "手动诊断", manual = true) }
            toast("诊断已写入：App 首页「诊断」卡片可复制，或看 LSPosed 日志 [Goutou]")
            true
        }
        val refresh = label("↻ 重新识别", 12f, 0xFFFFFFFF.toInt()) {
            background = roundRect(colorAccent, 14)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        refresh.setOnClickListener { regenerate() }
        val close = label("✕", 14f, colorSub) { setPadding(dp(12), 0, 0, 0) }
        close.setOnClickListener {
            dismissed = lastFingerprint
            showCard(false)
        }
        head.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(refresh)
        head.addView(close)

        bodyBox.orientation = LinearLayout.VERTICAL
        card.addView(head)
        card.addView(bodyBox)

        chip.text = "↻ 识别"
        chip.textSize = 12f
        chip.setTextColor(0xFFFFFFFF.toInt())
        chip.setPadding(dp(12), dp(6), dp(12), dp(6))
        chip.background = roundRect(0xE67C3AED.toInt(), 16)
        chip.elevation = dp(6).toFloat()
        chip.visibility = View.GONE
        chip.setOnClickListener { regenerate() }

        decor.addView(card, matchTop(dp(10), dp(96), dp(10)))
        decor.addView(chip, wrapTopEnd(dp(10), dp(96)))
        attached = true
    }

    private fun label(text: String, size: Float, color: Int, style: TextView.() -> Unit = {}): TextView =
        TextView(a).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            style()
        }

    private fun roundRect(color: Int, radiusDp: Int, stroke: Int? = null) = GradientDrawable().apply {
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun matchTop(left: Int, top: Int, right: Int) =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP)
            .apply { setMargins(left, top, right, 0) }

    private fun wrapTopEnd(right: Int, top: Int) =
        FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
            .apply { setMargins(0, top, right, 0) }

    /** 卡片贴在消息列表顶部 —— 不再依赖 dp(96) 这种写死的偏移。 */
    private fun place(list: ViewGroup?) {
        val decor = a.window?.decorView ?: return
        val inset = statusBarHeight()
        val target = if (list != null && list.isShown && list.height > 0) {
            val listLoc = IntArray(2)
            list.getLocationOnScreen(listLoc)
            val decorLoc = IntArray(2)
            decor.getLocationOnScreen(decorLoc)
            maxOf(inset + dp(40), listLoc[1] - decorLoc[1] + dp(4))
        } else {
            inset + dp(80)
        }
        if (target == cardTop) return
        cardTop = target
        for (v in arrayOf<View>(card, chip)) {
            val lp = v.layoutParams as? FrameLayout.LayoutParams ?: continue
            lp.topMargin = target
            v.layoutParams = lp
        }
    }

    @Suppress("DEPRECATION")
    private fun statusBarHeight(): Int {
        val insets = a.window?.decorView?.rootWindowInsets
        if (insets != null) return insets.systemWindowInsets.top
        val id = a.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) a.resources.getDimensionPixelSize(id) else dp(24)
    }

    // ---------------- 主循环 ----------------

    private fun tick() {
        val decor = a.window?.decorView ?: return
        if (!decor.hasWindowFocus()) return

        val cachedInput = inputRef
        val input = if (cachedInput != null && cachedInput.isShown) {
            cachedInput
        } else {
            reader.findChatInput(decor)?.also { inputRef = it }
        }
        if (input == null) {
            hideAll()
            return
        }
        onChat = true
        if (!reloadConfig()) return
        val cfg = config ?: return
        if (!cfg.enabled) {
            hideAll()
            return
        }

        val cachedList = listRef
        val list = if (cachedList != null && cachedList.isShown) {
            cachedList
        } else {
            reader.findList(decor, input)?.also { listRef = it }
        }
        place(list)
        if (list == null) {
            // 布局刚切换时会短暂读不到，连续两次才算真的找不到
            noListTicks++
            if (noListTicks >= 2) {
                dumpDiagnosis(decor, null, input, "找到了输入框，但没找到消息列表")
                if (force || noListTicks == 2) {
                    force = false
                    showMessage(
                        "没找到消息列表（微信版本可能改了控件类型）。\n" +
                            "诊断已保存到 App 首页，长按标题可再次生成。",
                        isError = true,
                    )
                }
            }
            return
        }
        noListTicks = 0

        val fingerprint = reader.fingerprint(list)
        if (!force && fingerprint == lastFingerprint) return
        force = false
        lastFingerprint = fingerprint
        if (fingerprint == dismissed) {
            showCard(false)
            return
        }

        val msgs = parser.parse(reader.snapshot(list)).takeLast(cfg.ctx)
        if (msgs.isEmpty()) {
            showCard(false)
            return
        }
        // 刚学到新的「自己在画字」的控件类：回传 App 持久化，并强制重新绑定可见行。
        // （setText 钩子是「看到控件才挂」的，而这批文字在挂钩之前就设好了；
        //   重新绑定一次就能把原文喂进钩子）
        val fresh = TextCapture.takeLearned()
        if (fresh.isNotEmpty()) {
            fresh.forEach { Heartbeat.send(a, 0, learned = it) }
            nudgeRebind(list)
        }

        // 安全网：一条文字都没读到，说明「读的东西」本身就不对。
        // 这时候去调模型只会浪费 token 并给出荒谬建议，所以先停下、留诊断、明确告诉用户。
        if (msgs.size >= 2 && msgs.all { it.attachment }) {
            dumpDiagnosis(decor, list, input, "读到 ${msgs.size} 条消息，但全部是图片/表情占位，一条文字都没有")
            showMessage(
                "读到的全是图片占位、一条文字都没有 —— 多半是消息列表选错了。\n" +
                    "诊断已写入 App 首页「诊断」卡片，长按标题也能重新生成。",
                isError = true,
            )
            return
        }
        // 最后一条是我发的：没什么可回的，收起来不打扰
        if (msgs.last().fromMe) {
            showCard(false)
            return
        }
        if (busy) return

        cache[fingerprint]?.let {
            render(it, msgs, fromCache = true)
            return
        }

        val joined = msgs.joinToString("\n") { it.text }
        val hits = if (cfg.allowSensitive || fingerprint == skipSensitiveFor) emptyList() else Sensitive.hits(joined)
        if (hits.isNotEmpty()) {
            renderSensitive(hits)
            return
        }

        if (System.currentTimeMillis() - lastCallAt < cfg.minIntervalSec * 1000L) {
            showMessage("刚分析过，${cfg.minIntervalSec}s 内不重复调用（可到「设置」调小）")
            return
        }
        ask(cfg, msgs, fingerprint)
    }

    /** 让列表适配器重新绑定可见行（只在新学到控件类时调用一次）。 */
    private fun nudgeRebind(list: ViewGroup) {
        try {
            val adapter = list.javaClass.getMethod("getAdapter").invoke(list) ?: return
            adapter.javaClass.getMethod("notifyDataSetChanged").invoke(adapter)
            XposedBridge.log("[Goutou] 已请求列表重新绑定（好让 setText 钩子抓到原文）")
        } catch (t: Throwable) {
            // 不是 RecyclerView 就算了；下次滚动/新消息时会自然重新绑定
        }
    }

    private fun dumpDiagnosis(decor: View, list: ViewGroup?, input: View?, why: String, manual: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastDiagAt < 30_000L) return
        lastDiagAt = now
        try {
            val diag = "$why\n" + reader.diagnose(decor, list, input)
            XposedBridge.log("[Goutou] $diag")
            Heartbeat.send(a, 0, diag)
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] 生成诊断失败: $t")
        }
    }

    private fun reloadConfig(): Boolean {
        return try {
            if (!prefs.file.canRead()) {
                showMessage("读不到配置：确认模块已在 LSPosed 启用并勾选了微信，然后强杀微信重开")
                return false
            }
            val stamp = prefs.file.lastModified()
            if (stamp != prefsStamp) {
                prefs.reload()
                prefsStamp = stamp
                config = ConfigData.from(prefs)
            }
            true
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] prefs: $t")
            showMessage("配置读取异常：${t.message}")
            false
        }
    }

    private fun ask(cfg: ConfigData, msgs: List<ChatMsg>, fingerprint: String) {
        busy = true
        lastCallAt = System.currentTimeMillis()
        val gen = ++generation
        showMessage("狗头军师思考中…")
        Thread {
            var suggestion: Suggestion? = null
            var tokens = 0
            var error: Throwable? = null
            try {
                val result = LlmClient(cfg).analyze(msgs)
                suggestion = result.suggestion
                tokens = result.totalTokens
            } catch (t: Throwable) {
                error = t
            }
            if (tokens > 0) Heartbeat.send(a, tokens)
            val ok = suggestion
            val err = error
            handler.post {
                if (gen != generation) return@post
                busy = false
                when {
                    ok != null -> {
                        cache[fingerprint] = ok
                        while (cache.size > 12) cache.remove(cache.keys.first())
                        render(ok, msgs, fromCache = false)
                    }
                    err is LlmException -> showMessage(
                        "生成失败：${err.message}${err.hint?.let { "\n$it" } ?: ""}",
                        isError = true,
                    )
                    else -> showMessage("生成失败：${err?.message ?: "未知错误"}", isError = true)
                }
            }
        }.start()
    }

    private fun regenerate() {
        cache.remove(lastFingerprint)
        dismissed = ""
        lastCallAt = 0
        force = true
        handler.post {
            runCatching { tick() }.onFailure { XposedBridge.log("[Goutou] refresh: $it") }
        }
    }

    // ---------------- 渲染 ----------------

    private fun render(s: Suggestion, msgs: List<ChatMsg>, fromCache: Boolean) {
        title.text = "军师 · ${s.intent} · 风险${s.risk}" + if (fromCache) " · 缓存" else ""
        title.setTextColor(riskColor(s.risk))
        bodyBox.removeAllViews()
        bodyBox.addView(label(lastThree(msgs), 10f, colorSub) { setPadding(0, dp(2), 0, dp(2)) })
        if (s.note.isNotBlank()) {
            bodyBox.addView(label(s.note, 12f, colorSub) { setPadding(0, dp(4), 0, dp(2)) })
        }
        for (r in s.replies) {
            val view = label("${r.style}｜${r.text}", 14f, colorMain) {
                background = roundRect(colorReply, 14)
                setPadding(dp(12), dp(8), dp(12), dp(8))
            }
            view.setOnClickListener { fill(r.text) }
            view.setOnLongClickListener {
                copyToClipboard(r.text)
                true
            }
            bodyBox.addView(
                view,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = dp(6) },
            )
        }
        bodyBox.addView(label("点一下填入输入框 · 长按复制 · 本模块不会自动发送", 10f, colorSub) { setPadding(0, dp(6), 0, 0) })
        showCard(true)
    }

    private fun renderSensitive(hits: List<String>) {
        title.text = "⚠ 这条含敏感内容"
        title.setTextColor(colorWarn)
        bodyBox.removeAllViews()
        bodyBox.addView(
            label("命中：${hits.joinToString("、")}\n默认不发给模型。要发就点下面，或到「设置」里关掉这个检查。", 12f, colorSub),
        )
        val go = label("仍然分析这一条", 14f, colorMain) {
            background = roundRect(colorReply, 14)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        go.setOnClickListener {
            skipSensitiveFor = lastFingerprint
            force = true
            handler.post { runCatching { tick() } }
        }
        bodyBox.addView(go, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        showCard(true)
    }

    private fun showMessage(message: String, isError: Boolean = false) {
        title.text = if (isError) "生成失败" else "狗头军师"
        title.setTextColor(if (isError) colorBad else colorAccent)
        bodyBox.removeAllViews()
        bodyBox.addView(label(message, 13f, if (isError) colorBad else colorMain))
        showCard(true)
    }

    private fun showCard(visible: Boolean) {
        card.visibility = if (visible) View.VISIBLE else View.GONE
        chip.visibility = if (!visible && onChat) View.VISIBLE else View.GONE
    }

    private fun hideAll() {
        onChat = false
        noListTicks = 0
        card.visibility = View.GONE
        chip.visibility = View.GONE
    }

    private fun lastThree(msgs: List<ChatMsg>): String =
        "读取：" + msgs.takeLast(3).joinToString(" ‖ ") {
            val who = if (it.fromMe) "我" else if (it.who.isNotBlank()) "对方(${it.who})" else "对方"
            "$who:${it.text.take(16)}"
        }

    private fun riskColor(risk: String): Int = when (risk) {
        "低" -> colorOk
        "中" -> colorWarn
        "高" -> colorBad
        else -> colorAccent
    }

    // ---------------- 交互 ----------------

    private fun fill(text: String) {
        val decor = a.window?.decorView ?: return
        val input = reader.findChatInput(decor) ?: return
        try {
            input.setText(text)
            input.setSelection(text.length)
            input.requestFocus()
            title.text = "已填入 · 按发送即可"
            toast("已填入输入框")
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] fill: $t")
        }
    }

    private fun copyToClipboard(text: String) {
        try {
            val cm = a.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            cm.setPrimaryClip(ClipData.newPlainText("goutou", text))
            toast("已复制")
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] copy: $t")
        }
    }

    private fun toast(message: String) {
        try {
            Toast.makeText(a, message, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            // 忽略
        }
    }
}
