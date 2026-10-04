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
import dev.goutou.wingman.config.Keys
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
    private var lastDiagReq = 0L
    private var emptyNotified = false
    private var lastAttachTry = 0L
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
        (decor as? ViewGroup)?.let { ensureAttached(it) }

        // 配置文件变了就地重读。提到最前面：下面几个早退分支都依赖它，尤其是「手动抓取」。
        refreshPrefs()

        // App 里点了「抓当前微信界面」→ 把当前页面结构 dump 回去。
        // 刻意排在**所有**判定之前（包括 hasWindowFocus）：
        // 出问题的那几个聊天页可能是在下面任意一个分支提前 return 的，而诊断入口原本是长按卡片标题 ——
        // 卡片都不弹，那个入口根本够不着；连「窗口没焦点」这种原因也要能抓到证据。
        val req = prefs.getLong(Keys.DIAG_REQ, 0L)
        if (req > lastDiagReq) {
            lastDiagReq = req
            val in0 = reader.findChatInput(decor)
            val ls0 = in0?.let { reader.findList(decor, it) }
            dumpDiagnosis(
                decor, ls0, in0,
                "手动抓取（App 触发）｜hasWindowFocus=${decor.hasWindowFocus()}｜输入框=${if (in0 != null) "有" else "无"}",
                manual = true,
                extra = stateReport(ls0),
            )
            return
        }

        if (!decor.hasWindowFocus()) return

        val cachedInput = inputRef
        val input = if (cachedInput != null && cachedInput.isShown) {
            cachedInput
        } else {
            reader.findChatInput(decor)?.also { inputRef = it }
        }
        if (input == null) {
            // 以前这里直接 hideAll() 就结束，于是「这几个聊天页为什么连卡片都不弹」永远查不出来。
            // 现在只要屏幕上还有「像输入框」的控件，就把结构 dump 回去（30s 限流）。
            reader.findInputCandidate(decor)?.let { cand ->
                dumpDiagnosis(
                    decor, null, cand,
                    "像聊天页但找不到可用输入框：候选=${cand.javaClass.name} " +
                        "${cand.width}x${cand.height} isShown=${cand.isShown}" +
                        "（判定要求 宽>${dp(50)}、位于屏幕下 70%、isShown）",
                )
            }
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

        // 自愈必须排在「一条都没读到」的判断**之前**。
        // 正文控件是「看到才挂钩子」的，而文字早在挂钩之前就设好了 —— 只有让微信重绑一次，
        // 才能把原文喂进钩子。原来这段写在下面 msgs.isEmpty() 的 return 之后，
        // 等于最需要它的时候恰好不执行：读不到 → 立刻 return → 永远读不到。
        val fresh = TextCapture.takeLearned()
        if (fresh.isNotEmpty()) {
            fresh.forEach { Heartbeat.send(a, 0, learned = it) }
            nudgeRebind(list)
        }

        if (msgs.isEmpty()) {
            // 原来这里只有一句 showCard(false)：卡片和小气泡一起消失，用户什么都看不到、也没有提示。
            // 现在第一次把话说清楚，并留下诊断。
            if (!emptyNotified) {
                emptyNotified = true
                dumpDiagnosis(decor, list, input, "选到了消息列表，但一行文字都没解析出来（指纹=${fingerprint.take(60)}）")
                showMessage(
                    "这个聊天读不到文字（正文可能是自绘控件）。\n" +
                        "已请求微信重绑一次，等一两秒看看；还不行就把 App 首页的「诊断」发我。",
                    isError = true,
                )
            } else {
                showCard(false)
            }
            return
        }
        emptyNotified = false

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

    private fun dumpDiagnosis(
        decor: View,
        list: ViewGroup?,
        input: View?,
        why: String,
        manual: Boolean = false,
        extra: String? = null,
    ) {
        val now = System.currentTimeMillis()
        if (!manual && now - lastDiagAt < 30_000L) return
        lastDiagAt = now
        try {
            val diag = buildString {
                append(why).append('\n')
                if (!extra.isNullOrBlank()) append(extra).append('\n')
                append(reader.diagnose(decor, list, input))
            }
            XposedBridge.log("[Goutou] $diag")
            Heartbeat.send(a, 0, diag)
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] 生成诊断失败: $t")
        }
    }

    /**
     * 面板自己的状态快照。
     *
     * 为什么需要：「卡片不弹」有好几个分支都能造成（一条都没解析出来 / 最后一条是我发的 /
     * 没找到输入框 / 没找到列表 / 被关掉 / 还在最短间隔内 / 卡片挂在了旧的 decor 上），
     * 光看 View 树区分不出来，必须把面板内部的判定变量一起报回来。
     */
    private fun stateReport(list: ViewGroup?): String = try {
        val cfg = config
        val decor = a.window?.decorView
        val msgs = list?.let {
            runCatching { parser.parse(reader.snapshot(it)).takeLast(cfg?.ctx ?: 8) }.getOrNull()
        }
        buildString {
            append("—— 面板状态 ——\n")
            append("running=$running attached=$attached onChat=$onChat busy=$busy force=$force\n")
            append("card=${vis(card)} isShown=${card.isShown} 挂在当前decor=${card.parent === decor}\n")
            append("chip=${vis(chip)} isShown=${chip.isShown} 挂在当前decor=${chip.parent === decor}\n")
            append("inputRef=${inputRef?.let { "${it.javaClass.simpleName} ${it.width}x${it.height} isShown=${it.isShown}" } ?: "null"}\n")
            append("listRef=${listRef?.let { "${it.javaClass.simpleName} ${it.width}x${it.height} 子=${it.childCount}" } ?: "null"}\n")
            append("noListTicks=$noListTicks emptyNotified=$emptyNotified\n")
            append("距上次调用=${System.currentTimeMillis() - lastCallAt}ms（最短间隔 ${cfg?.minIntervalSec}s）\n")
            append("cfg: enabled=${cfg?.enabled} skill=${cfg?.skillId} prompt=${cfg?.prompt?.length}字 ctx=${cfg?.ctx}\n")
            if (msgs == null) {
                append("解析：列表为空，没跑\n")
            } else {
                append("解析出 ${msgs.size} 条；最后一条" +
                    if (msgs.lastOrNull()?.fromMe == true) "是【我】发的 → 按设计收起卡片（小气泡应仍可见）" else "是对方发的" + "\n")
                append("最后 3 条：" + msgs.takeLast(3).joinToString(" ‖ ") {
                    val who = if (it.fromMe) "我" else if (it.who.isNotBlank()) "对方(${it.who})" else "对方"
                    "$who:${it.text.take(16)}"
                } + "\n")
            }
        }
    } catch (t: Throwable) {
        "—— 面板状态 ——\n(生成失败 $t)\n"
    }

    private fun vis(v: View) = if (v.visibility == View.VISIBLE) "显示" else "隐藏"

    /**
     * 兜底重挂。
     *
     * 有些页面会把 decorView 换掉（Activity 复用、窗口重建），那时卡片还挂在**上一个** decor 上，
     * 于是「读取一切正常，但屏幕上什么都看不见」。挂错地方时重新挂一次即可。
     */
    private fun ensureAttached(decor: ViewGroup) {
        if (attached && card.parent === decor && chip.parent === decor) return
        val now = System.currentTimeMillis()
        if (now - lastAttachTry < 3_000L) return
        lastAttachTry = now
        XposedBridge.log("[Goutou] decor 变了，重新挂卡片（card.parent=${card.parent}）")
        runCatching { (card.parent as? ViewGroup)?.removeView(card) }
        runCatching { (chip.parent as? ViewGroup)?.removeView(chip) }
        runCatching { card.removeAllViews() }
        attached = false
        attach()
    }

    /**
     * 配置文件变了就地重读 —— App 侧的任何改动（含「手动抓取」请求）都靠它生效。
     * 只比对文件 mtime，没变就不动，所以每个 tick 调都没代价。
     */
    private fun refreshPrefs() {
        try {
            if (!prefs.file.canRead()) return
            val stamp = prefs.file.lastModified()
            if (stamp != prefsStamp) {
                prefs.reload()
                prefsStamp = stamp
                config = ConfigData.from(prefs)
            }
        } catch (t: Throwable) {
            XposedBridge.log("[Goutou] prefs: $t")
        }
    }

    private fun reloadConfig(): Boolean {
        if (!prefs.file.canRead()) {
            showMessage("读不到配置：确认模块已在 LSPosed 启用并勾选了微信，然后强杀微信重开")
            return false
        }
        return try {
            refreshPrefs()
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
            var trace = ""
            try {
                val result = LlmClient(cfg, onTrace = { trace = it }).analyze(msgs)
                suggestion = result.suggestion
                tokens = result.totalTokens
            } catch (t: Throwable) {
                error = t
            }
            // 顺带回传「这次实际发出去的那一份」，App 首页可以对着核对 skill 有没有真的生效
            Heartbeat.send(a, tokens, call = trace.takeIf { it.isNotBlank() })
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
