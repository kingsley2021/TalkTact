package dev.goutou.wingman.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.Backup
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.Role
import dev.goutou.wingman.llm.BUILT_IN_SKILLS
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.RemoteSkill
import dev.goutou.wingman.llm.Suggestion
import dev.goutou.wingman.wechat.ChatMsg
import dev.goutou.wingman.wechat.Sensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Level(val label: String) { OK("通过"), WARN("待确认"), BAD("有问题") }

private data class Check(val title: String, val desc: String, val level: Level, val onClick: (() -> Unit)? = null)

@Composable
private fun CheckRow(check: Check, glassAlpha: Float) {
    val palette = LocalPalette.current
    val color = when (check.level) {
        Level.OK -> palette.ok
        Level.WARN -> palette.warn
        Level.BAD -> palette.bad
    }
    // 状态行是顶层元素（直接躺在 LazyColumn 上），所以用玻璃面板：背后是真实的背景模糊
    GlassSurface(
        shape = RoundedCornerShape(16.dp),
        glassAlpha = glassAlpha,
        tintTop = 0.26f * glassAlpha,
        tintBottom = 0.18f * glassAlpha,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
        onClick = check.onClick,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(check.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text(check.desc, fontSize = 12.sp, color = palette.sub)
            }
            Text(check.level.label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

// ================= 运行状态 =================

@Composable
fun StatusScreen(store: ConfigStore, onTrial: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableStateOf(0) }
    var cfg by remember { mutableStateOf(store.load()) }
    val usage = remember(tick) { store.usage() }
    val beat = remember(tick) { store.heartbeatAt() }
    val diag = remember(tick) { store.diag() }
    val diagAt = remember(tick) { store.diagAt() }
    val lastCall = remember(tick) { store.lastCall() }
    val lastCallAt = remember(tick) { store.lastCallAt() }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    var diagAsked by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val glass = cfg.glassAlpha
    val active = moduleActive(store)
    val fresh = beat > 0 && System.currentTimeMillis() - beat < 6 * 3600_000L
    val confirmed = store.scopeConfirmed()

    val checks = listOf(
        Check(
            "模块激活",
            moduleActiveReason(store) ?: "未激活：在框架里启用本模块并勾选微信，然后强杀微信重开",
            if (active) Level.OK else Level.BAD,
        ),
        Check(
            "微信内已生效",
            when {
                fresh -> "微信进程最近报过心跳：${formatTime(beat)}"
                confirmed -> "你手动确认过（还没收到心跳）"
                else -> "没收到微信进程心跳：确认作用域勾了微信并强杀重开"
            },
            if (fresh) Level.OK else Level.WARN,
        ) {
            store.setScopeConfirmed(!confirmed)
            cfg = store.load()
        },
        Check(
            "API Key",
            if (cfg.apiKey.isBlank()) "未填写，到「设置」填" else "已填写（明文存本机，见设置页说明）",
            if (cfg.apiKey.isBlank()) Level.BAD else Level.OK,
        ),
        Check("接口与模型", "${cfg.model} · ${cfg.baseUrl}", if (cfg.baseUrl.startsWith("http")) Level.OK else Level.BAD),
        Check(
            "当前军师",
            "${skillName(cfg.skillId)}" +
                " · ${cfg.prompt.length} 字 · " + if (cfg.prompt.contains("replies")) "含 JSON 契约" else "缺少 JSON 契约",
            if (cfg.prompt.length >= 80 && cfg.prompt.contains("replies")) Level.OK else Level.WARN,
        ),
        Check(
            "调用节奏",
            "参考最近 ${cfg.ctx} 条 · 最短间隔 ${cfg.minIntervalSec}s · " +
                if (cfg.maxTokens == 0) "token 无限制" else "单次上限 ${cfg.maxTokens} token",
            Level.OK,
        ),
        Check(
            "敏感内容检查",
            if (cfg.allowSensitive) "已关闭：聊天内容会直接发给接口" else "开启：命中验证码/银行卡/转账等会先问一次",
            if (cfg.allowSensitive) Level.WARN else Level.OK,
        ),
    )
    val bad = checks.count { it.level == Level.BAD }
    val warn = checks.count { it.level == Level.WARN }
    val ok = checks.count { it.level == Level.OK }
    val overall = if (bad > 0) Level.BAD else if (warn > 0) Level.WARN else Level.OK
    val shown = when (filter) {
        1 -> checks.filter { it.level == Level.BAD }
        2 -> checks.filter { it.level == Level.WARN }
        3 -> checks.filter { it.level == Level.OK }
        else -> checks
    }

    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            ScreenHeader("运行状态", "WeChat · 聊天副驾 ${appVersion(context)}") {
                TextButton(onClick = { tick++ }) { Text("刷新") }
                Button(
                    onClick = onTrial,
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("试聊") }
            }
        }
        item {
            GlassCard(glass, border = when (overall) {
                Level.OK -> palette.ok.copy(alpha = 0.5f)
                Level.WARN -> palette.warn.copy(alpha = 0.5f)
                Level.BAD -> palette.bad.copy(alpha = 0.5f)
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape)
                            .background(
                                when (overall) {
                                    Level.OK -> palette.ok
                                    Level.WARN -> palette.warn
                                    Level.BAD -> palette.bad
                                },
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            when (overall) { Level.OK -> "✓"; Level.WARN -> "!"; Level.BAD -> "✕" },
                            fontSize = 24.sp,
                            color = androidx.compose.ui.graphics.Color.White,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(Modifier.weight(1f).padding(start = 14.dp)) {
                        Text(
                            when (overall) { Level.OK -> "全部就绪"; Level.WARN -> "基本可用"; Level.BAD -> "需要处理" },
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            color = palette.text,
                        )
                        Text(
                            "${checks.size} 项已检测 · 通过 $ok · 待确认 $warn · 有问题 $bad",
                            fontSize = 12.sp,
                            color = palette.sub,
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCell("${usage.first}", "调用次数", Modifier.weight(1f))
                    StatCell("${usage.second}", "累计 token", Modifier.weight(1f))
                    StatCell(formatTime(beat).takeIf { beat > 0 } ?: "—", "最近心跳", Modifier.weight(1f))
                }
            }
        }
        item {
            GlassCard(glass) {
                Text("接口自检", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text("真发一次最小请求，验证地址、Key、模型名（会消耗几个 token）。", fontSize = 12.sp, color = palette.sub)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            probing = true
                            probeResult = null
                            val conf = store.load()
                            scope.launch {
                                val text = try {
                                    val result = withContext(Dispatchers.IO) { LlmClient(conf).probe() }
                                    if (result.totalTokens > 0) store.addUsage(result.totalTokens)
                                    tick++
                                    "通 · ${result.millis}ms · 模型 ${result.model} · ${result.totalTokens} token"
                                } catch (t: Throwable) {
                                    "失败：${t.message}"
                                }
                                probeResult = text
                                probeOk = text.startsWith("通")
                                probing = false
                            }
                        },
                        enabled = !probing,
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text(if (probing) "测试中…" else "开始自检") }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        probeResult ?: "",
                        fontSize = 12.sp,
                        color = if (probeOk) palette.ok else palette.bad,
                    )
                }
            }
        }
        item {
            GlassCard(glass) {
                Text("抓取微信界面", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text(
                    "某些聊天页连卡片都不弹时用这个：先在微信里停在那个聊天页 → 切回这里点下面的按钮 → " +
                        "再切回微信（那个页面重新出现就会自动抓）→ 回来点「刷新」→ 复制下面的「诊断」发我。",
                    fontSize = 12.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { store.requestDiag(); diagAsked = true },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text("抓当前微信界面") }
                    if (diagAsked) {
                        Text("已排队，切回微信那一页即抓", fontSize = 12.sp, color = palette.ok)
                    }
                }
            }
        }
        if (diag.isNotBlank()) {
            item {
                GlassCard(glass, border = palette.warn.copy(alpha = 0.5f)) {
                    Text("诊断（来自微信进程）", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                    Text("生成于 ${formatTime(diagAt)} · 排查「读不到消息 / 全是图片」时把它复制给对方", fontSize = 12.sp, color = palette.sub)
                    Spacer(Modifier.height(8.dp))
                    Text(diag, fontSize = 10.sp, color = palette.text)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(diag)) },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("复制诊断") }
                }
            }
        }
        if (lastCall.isNotBlank()) {
            item {
                GlassCard(glass, border = palette.primary.copy(alpha = 0.45f)) {
                    Text("最近一次调用（注入侧真正发出去的）", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                    Text(
                        "发生于 ${formatTime(lastCallAt)} · 这里是微信进程实际拿去调接口的那一份，不是本 App 里的配置。" +
                            "核对「当前军师」有没有真的生效，看 system 长度那一行。",
                        fontSize = 12.sp,
                        color = palette.sub,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(lastCall, fontSize = 10.sp, color = palette.text)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(lastCall)) },
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("复制这一段") }
                }
            }
        }
        item {
            GlassCard(glass) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    GlassPill("全部 ${checks.size}", filter == 0, Modifier.weight(1f)) { filter = 0 }
                    GlassPill("有问题 $bad", filter == 1, Modifier.weight(1f)) { filter = 1 }
                    GlassPill("待确认 $warn", filter == 2, Modifier.weight(1f)) { filter = 2 }
                    GlassPill("通过 $ok", filter == 3, Modifier.weight(1f)) { filter = 3 }
                }
            }
        }
        items(shown) { CheckRow(it, glass) }
    }
}

// ================= 试一试 =================

@Composable
fun TrialScreen(store: ConfigStore, glassAlpha: Float) {
    val palette = LocalPalette.current
    var input by remember { mutableStateOf("对方: 在吗\n我: 在\n对方: 周末有空吗，想约你吃个饭") }
    var suggestion by remember { mutableStateOf<Suggestion?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val hits = remember(input) { Sensitive.hits(input) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("试一试", "粘贴聊天 · 预览回复 · 验证接口")
        GlassCard(glassAlpha) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp),
                label = { Text("每行一句，以「我:」或「对方:」开头") },
            )
            if (hits.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("⚠ 含敏感内容：${hits.joinToString("、")}（微信里会先问你一次）", fontSize = 12.sp, color = palette.warn)
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    loading = true
                    error = null
                    info = null
                    suggestion = null
                    val conf = store.load()
                    scope.launch {
                        try {
                            val msgs = input.lines().filter { it.isNotBlank() }.map {
                                val me = it.startsWith("我:") || it.startsWith("我：")
                                ChatMsg(me, it.substringAfter(':').substringAfter('：').trim().ifEmpty { it })
                            }
                            val result = withContext(Dispatchers.IO) { LlmClient(conf).analyze(msgs) }
                            suggestion = result.suggestion
                            if (result.totalTokens > 0) store.addUsage(result.totalTokens)
                            info = "${result.millis}ms · ${result.totalTokens} token · ${result.model}"
                        } catch (t: Throwable) {
                            error = t.message
                        }
                        loading = false
                    }
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                enabled = !loading,
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (loading) "思考中…" else "生成候选回复") }
        }
        error?.let { msg -> GlassCard(glassAlpha, border = palette.bad.copy(alpha = 0.5f)) { Text(msg, color = palette.bad, fontSize = 13.sp) } }
        info?.let { GlassCard(glassAlpha) { Text(it, fontSize = 12.sp, color = palette.sub) } }
        suggestion?.let { s ->
            GlassCard(glassAlpha) {
                Text("意图：${s.intent}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
                Text("风险：${s.risk}　${s.note}", fontSize = 13.sp, color = palette.sub)
            }
            s.replies.forEach { reply ->
                GlassCard(glassAlpha) {
                    Column(Modifier.fillMaxWidth().clickable { clipboard.setText(AnnotatedString(reply.text)) }) {
                        Text(reply.style, color = palette.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(reply.text, fontSize = 15.sp, color = palette.text)
                        Text("点击复制", fontSize = 11.sp, color = palette.sub)
                    }
                }
            }
        }
    }
}

// ================= 军师（提示词 / skill，两级） =================

@Composable
fun MentorScreen(store: ConfigStore, glassAlpha: Float, onSaved: () -> Unit) {
    val palette = LocalPalette.current
    var cfg by remember { mutableStateOf(store.load()) }
    // 「新手 / 进阶」以前是用 cfg.maxTokens == 0 推断出来的，会粘住：
    // 只要进过一次进阶（token 档位被改成无限制），以后每次进来都自动是进阶，切回新手也甩不掉。
    // 现在改成独立的界面偏好记下来。
    var advanced by remember { mutableStateOf(cfg.mentorAdvanced) }
    var tweak by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf(cfg.prompt) }
    var url by remember { mutableStateOf("") }
    var importing by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun persist(prompt: String, skillId: String, unlimited: Boolean) {
        store.save(store.load().copy(prompt = prompt, skillId = skillId, maxTokens = if (unlimited) 0 else store.load().maxTokens))
        cfg = store.load()
        text = cfg.prompt
        saved = true
        onSaved()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("军师", "提示词 · skill")
        GlassCard(glassAlpha) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassPill("新手设置", !advanced, Modifier.weight(1f)) {
                    advanced = false
                    tweak = false
                    store.save(store.load().copy(mentorAdvanced = false))
                    cfg = store.load()
                }
                GlassPill("进阶设置", advanced, Modifier.weight(1f)) {
                    advanced = true
                    tweak = false
                    val wasLimited = cfg.maxTokens != 0
                    store.save(store.load().copy(maxTokens = 0, mentorAdvanced = true))
                    cfg = store.load()
                    onSaved()
                    dialog = if (wasLimited) {
                        "已切到进阶设置。\n\n为避免 skill 输出被截断，Token 限制已改为：无限制。"
                    } else {
                        "已切到进阶设置。"
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                if (!advanced) "用内置的原版狗头军师，提示词可以直接编辑微调。"
                else "从内置 skill 里选一个，或者粘一个 GitHub 上开源 skill 的地址导入。",
                fontSize = 12.sp,
                color = palette.sub,
            )
        }

        if (!advanced) {
            GlassCard(glassAlpha) {
                Text(
                    if (text.contains("replies")) "✓ 含 JSON 输出契约 · ${text.length} 字" else "⚠ 缺少 JSON 契约，卡片会解析不了",
                    fontSize = 12.sp,
                    color = if (text.contains("replies")) palette.ok else palette.warn,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; saved = false },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = { text = ConfigData().prompt; saved = false },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("恢复默认") }
                    Button(
                        onClick = { persist(text, "classic", unlimited = false) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text(if (saved) "已保存" else "保存") }
                }
            }
        } else {
            GlassCard(glassAlpha) {
                Text("内置 skill", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text("点一下直接启用（会替换当前提示词）", fontSize = 12.sp, color = palette.sub)
                Spacer(Modifier.height(4.dp))
                BUILT_IN_SKILLS.forEach { skill ->
                    val selected = cfg.skillId == skill.id && cfg.prompt == skill.prompt
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 10.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(palette.glass.copy(alpha = if (selected) 0.30f else 0.14f))
                            .clickable {
                                store.save(store.load().copy(prompt = skill.prompt, skillId = skill.id, maxTokens = 0))
                                cfg = store.load()
                                text = cfg.prompt
                                tweak = false
                                saved = true
                                onSaved()
                                dialog = "已启用「${skill.name}」。\n\nToken 限制已改为：无限制。"
                            }
                            .padding(14.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(skill.name, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text, modifier = Modifier.weight(1f))
                            Text(
                                if (selected) "使用中" else "启用",
                                fontSize = 12.sp,
                                color = if (selected) palette.ok else palette.primary,
                            )
                        }
                        Text(skill.summary, fontSize = 12.sp, color = palette.sub)
                    }
                }
            }
            GlassCard(glassAlpha) {
                Text("从 GitHub 导入 skill", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text(
                    "粘 SKILL.md 的地址即可（网页地址也行，会自动换成 raw 直链）。导入时会自动补齐 JSON 输出契约。",
                    fontSize = 12.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("https://github.com/…/SKILL.md") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        importing = true
                        note = null
                        val target = url
                        scope.launch {
                            note = try {
                                val fetched = withContext(Dispatchers.IO) { RemoteSkill.fetch(target) }
                                text = fetched
                                persist(fetched, "custom", unlimited = true)
                                dialog = "已导入并启用这个 skill。\n\nToken 限制已改为：无限制。"
                                "导入成功 · ${fetched.length} 字"
                            } catch (t: Throwable) {
                                "导入失败：${t.message}"
                            }
                            importing = false
                        }
                    },
                    enabled = !importing && url.isNotBlank(),
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text(if (importing) "导入中…" else "导入并启用") }
                note?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = 12.sp, color = if (it.startsWith("导入成功")) palette.ok else palette.bad)
                }
            }
            // 这块以前是一整屏的提示词输入框，长得跟上面「新手设置」几乎一模一样 ——
            // 切到进阶后往下滑，会以为新手的内容没关掉。现在默认收起，要看再点开。
            GlassCard(glassAlpha) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("当前军师", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                        Text(
                            "${skillName(cfg.skillId)} · ${text.length} 字 · " +
                                if (text.contains("replies")) "含 JSON 契约" else "⚠ 缺少 JSON 契约",
                            fontSize = 12.sp,
                            color = if (text.contains("replies")) palette.sub else palette.warn,
                        )
                    }
                    TextButton(onClick = { tweak = !tweak }) { Text(if (tweak) "收起" else "微调提示词") }
                }
                if (tweak) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it; saved = false },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = {
                            // 内容跟哪个内置 skill 逐字一样，就还算那个 skill；动过了才算「自定义」
                            val known = BUILT_IN_SKILLS.firstOrNull { it.prompt == text }?.id
                            persist(text, known ?: "custom", unlimited = true)
                        },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text(if (saved) "已保存" else "保存") }
                }
            }
        }
    }

    dialog?.let { msg ->
        AlertDialog(
            onDismissRequest = { dialog = null },
            confirmButton = { TextButton(onClick = { dialog = null }) { Text("好") } },
            title = { Text("进阶设置") },
            text = { Text(msg) },
        )
    }
}

// ================= 设置 =================

@Composable
fun SettingsScreen(
    store: ConfigStore,
    ui: ConfigData,
    onUi: (ConfigData) -> Unit,
    onSaved: () -> Unit,
) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var d by remember { mutableStateOf(ui) }
    var saved by remember { mutableStateOf(false) }
    var includeKey by remember { mutableStateOf(true) }
    var backupNote by remember { mutableStateOf<String?>(null) }

    fun update(next: ConfigData) {
        d = next
        saved = false
        onUi(next)
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (t: Throwable) {
                // 有的来源不支持持久化授权，那就只在本次运行里有效
            }
            update(d.copy(bgUri = uri.toString()))
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            backupNote = try {
                context.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(Backup.export(store.load(), store.roles(), includeKey).toByteArray(Charsets.UTF_8))
                }
                "已导出到所选文件"
            } catch (t: Throwable) {
                "导出失败：${t.message}"
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            backupNote = try {
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                val result = Backup.import(text, store.load(), store.roles())
                if (result == null) {
                    "导入失败：不是有效的备份文件"
                } else {
                    store.save(result.config)
                    store.saveRoles(result.roles)
                    d = store.load()
                    onSaved()
                    "已导入：配置 + ${result.roleCount} 个角色"
                }
            } catch (t: Throwable) {
                "导入失败：${t.message}"
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("设置", "接口 · 外观 · 节奏")

        GlassCard(d.glassAlpha) {
            Text("外观", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text("玻璃不透明度：${(d.glassAlpha * 100).toInt()}%", fontSize = 12.sp, color = palette.sub)
            Slider(value = d.glassAlpha, onValueChange = { update(d.copy(glassAlpha = it)) }, valueRange = 0.3f..1f)
            Spacer(Modifier.height(4.dp))
            Text(
                "玻璃背景模糊：${d.glassBlur.toInt()}dp（面板背后是真·背景模糊，配了自定义背景图才看得出来）",
                fontSize = 12.sp,
                color = palette.sub,
            )
            Slider(value = d.glassBlur, onValueChange = { update(d.copy(glassBlur = it)) }, valueRange = 0f..40f)
            Spacer(Modifier.height(4.dp))
            Text("背景压暗：${(d.bgDim * 100).toInt()}%", fontSize = 12.sp, color = palette.sub)
            Slider(value = d.bgDim, onValueChange = { update(d.copy(bgDim = it)) }, valueRange = 0f..0.8f)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { pickImage.launch(arrayOf("image/*")) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("选择背景图") }
                OutlinedButton(
                    onClick = { update(d.copy(bgUri = "")) },
                    enabled = d.bgUri.isNotBlank(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("恢复默认背景") }
            }
            if (d.bgUri.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text("已设置自定义背景：玻璃面板背后会对它做真实的背景模糊（RenderEffect）。", fontSize = 11.sp, color = palette.sub)
            }
        }

        GlassCard(d.glassAlpha) {
            OutlinedTextField(
                value = d.baseUrl,
                onValueChange = { update(d.copy(baseUrl = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址（OpenAI 兼容，写到 /v1）") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.apiKey,
                onValueChange = { update(d.copy(apiKey = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.model,
                onValueChange = { update(d.copy(model = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                singleLine = true,
            )
            Spacer(Modifier.height(12.dp))
            Text("单次回复的 token 上限", fontSize = 12.sp, color = palette.sub)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlassPill("200", d.maxTokens == 200, Modifier.weight(1f)) { update(d.copy(maxTokens = 200)) }
                GlassPill("400", d.maxTokens == 400, Modifier.weight(1f)) { update(d.copy(maxTokens = 400)) }
                GlassPill("600", d.maxTokens == 600, Modifier.weight(1f)) { update(d.copy(maxTokens = 600)) }
                GlassPill("无限制", d.maxTokens == 0, Modifier.weight(1f)) { update(d.copy(maxTokens = 0)) }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (d.maxTokens == 0) "无限制：请求里不带 max_tokens，由服务端决定（进阶 skill 建议用这档）"
                else "越小越省额度，但 skill 提示词较长时可能被截断",
                fontSize = 11.sp,
                color = palette.sub,
            )
        }

        GlassCard(d.glassAlpha) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = d.ctx.toString(),
                    onValueChange = { update(d.copy(ctx = it.toIntOrNull() ?: d.ctx)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("参考条数 2-20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = d.minIntervalSec.toString(),
                    onValueChange = { update(d.copy(minIntervalSec = it.toIntOrNull() ?: d.minIntervalSec)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("最短间隔(s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = d.temperature.toString(),
                onValueChange = { update(d.copy(temperature = it.toDoubleOrNull() ?: d.temperature)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("temperature（0.2 稳 / 0.8 活）") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("微信内自动分析", fontSize = 15.sp, color = palette.text)
                    Text("关掉后只在手动点「识别」时出现卡片", fontSize = 11.sp, color = palette.sub)
                }
                Switch(checked = d.enabled, onCheckedChange = { update(d.copy(enabled = it)) })
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("敏感内容检查", fontSize = 15.sp, color = palette.text)
                    Text("命中验证码/银行卡/转账时先拦一次（推荐开）", fontSize = 11.sp, color = palette.sub)
                }
                Switch(checked = !d.allowSensitive, onCheckedChange = { update(d.copy(allowSensitive = !it)) })
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    store.save(d)
                    d = store.load()
                    saved = true
                    onSaved()
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (saved) "已保存（微信里下次识别即生效）" else "保存") }
        }

        GlassCard(d.glassAlpha) {
            Text("备份 / 迁移", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
            Text(
                "换包名、换手机的时候用：导出成一个 json 文件，装好新的再导入回来。\n" +
                    "导入是合并：角色只覆盖同名的，备份里没有的会保留。",
                fontSize = 12.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("导出时包含 API Key", fontSize = 13.sp, color = palette.text)
                    Text("开了的话导出文件里有明文 Key，别往公开地方放", fontSize = 11.sp, color = palette.sub)
                }
                Switch(checked = includeKey, onCheckedChange = { includeKey = it })
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { exportLauncher.launch(Backup.FILE_NAME) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("导出配置") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("导入配置") }
            }
            backupNote?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, fontSize = 12.sp, color = if (it.startsWith("已")) palette.ok else palette.bad)
            }
        }

        GlassCard(d.glassAlpha) {
            Text("关于", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text("版本：${appVersion(context)}", fontSize = 12.sp, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 开启后，聊天页最近几条消息会发送到你填写的接口地址，请自行确认该服务可信。\n" +
                    "· API Key 以明文存放在本应用私有目录（不能加密：注入微信进程的代码需要跨进程读取，Keystore 密钥按 UID 隔离读不到）。\n" +
                    "· 只读消息、只把候选回复填进输入框，不会自动发送；但仍属于修改微信客户端行为，有风控风险，建议先用小号。\n" +
                    "· 日志关键字：[Goutou]。",
                fontSize = 12.sp,
                color = palette.sub,
            )
        }
    }
}

// ================= 角色 =================

/** 「TA 是你什么人」的备选。用户也可以直接把自定义的写进「平时的关系」里。 */
private val RELATIONS = listOf("家人", "恋人", "暧昧", "朋友", "同学", "同事", "上级", "客户", "其他")

/**
 * 角色页。
 *
 * 列表里每个人 = 一个微信会话名。数据全自动来：模块在你打开某个聊天页时，把它读到的消息
 * 按会话名归档回来（1 小时内重复只留一条）。点进某人可以写「TA 是你什么人」和「平时的关系」，
 * 这两样 + 之前攒下的聊天记录会一起拼进提示词，直接影响当前 skill 生成出来的回复。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RolesScreen(store: ConfigStore, glassAlpha: Float, open: String?, onOpen: (String?) -> Unit) {
    val palette = LocalPalette.current
    var tick by remember { mutableStateOf(0) }
    val roles = remember(tick) { store.roles() }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    val current = open?.let { k -> roles.firstOrNull { it.key == k } }

    if (open != null && current != null) {
        RoleDetail(
            store = store,
            role = current,
            glassAlpha = glassAlpha,
            onBack = {
                onOpen(null)
                tick++
            },
            onRename = { newName ->
                store.renameRole(current.key, newName)
                tick++
            },
        )
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("角色", "每个人一份档案，直接影响生成") {
            TextButton(onClick = { tick++ }) { Text("刷新") }
        }

        GlassCard(glassAlpha) {
            Text("这是干什么的", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
            Text(
                "模块会在你打开某个聊天页时，把读到的消息按联系人归档到这里（1 小时内重复的内容只留一条）。\n" +
                    "点进某个人，写上「TA 是你什么人」和「平时的关系」—— 这些会连同之前攒下的聊天记录一起，\n" +
                    "拼进提示词，直接影响「军师」生成出来的回复。\n" +
                    "长按某一项可以直接删除它；点进去可以改名字。",
                fontSize = 12.sp,
                color = palette.sub,
            )
        }

        if (roles.isEmpty()) {
            GlassCard(glassAlpha) {
                Text("还没有记录", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text(
                    "去微信里打开几个聊天页，每个停两三秒，再回来点「刷新」。\n" +
                        "（只在聊天页可见时读得到，所以记录是「你在场时看到的那几条」慢慢攒起来的。）",
                    fontSize = 12.sp,
                    color = palette.sub,
                )
            }
        }

        roles.forEach { role ->
            GlassCard(glassAlpha, border = palette.primary.copy(alpha = if (role.relation.isBlank()) 0.15f else 0.5f)) {
                Column(
                    Modifier.fillMaxWidth().combinedClickable(
                        onClick = { onOpen(role.key) },
                        onLongClick = { pendingDelete = role.key },
                    ),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(role.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.text, modifier = Modifier.weight(1f, fill = false))
                        if (role.renamed) {
                            Text(
                                " ◦ 识别名 ${role.key.take(10)}",
                                fontSize = 10.sp,
                                color = palette.warn,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        Text("${role.msgs.size} 条", fontSize = 12.sp, color = palette.sub)
                    }
                    Text(
                        if (role.relation.isBlank()) "还没写 TA 是你什么人" else "${role.relation}${if (role.note.isBlank()) "" else " · ${role.note.take(18)}"}",
                        fontSize = 12.sp,
                        color = if (role.relation.isBlank()) palette.warn else palette.primary,
                    )
                    Text(
                        "最近一条：${if (role.lastAt > 0) formatTime(role.lastAt) else "—"}",
                        fontSize = 11.sp,
                        color = palette.sub,
                    )
                }
            }
        }
    }

    pendingDelete?.let { key ->
        val shown = roles.firstOrNull { it.key == key }?.name ?: key
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除角色「$shown」？") },
            text = { Text("档案和记录一起删掉，无法恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    store.removeRole(key)
                    pendingDelete = null
                    tick++
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}


@Composable
private fun RoleDetail(
    store: ConfigStore,
    role: Role,
    glassAlpha: Float,
    onBack: () -> Unit,
    onRename: (String) -> Unit,
) {
    val palette = LocalPalette.current
    var relation by remember(role.key) { mutableStateOf(role.relation) }
    var note by remember(role.key) { mutableStateOf(role.note) }
    var saved by remember(role.key) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<String?>(null) }
    var renameOpen by remember { mutableStateOf(false) }
    var draft by remember(role.key) { mutableStateOf(role.name) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader(role.name, "档案 · ${role.msgs.size} 条记录") {
            TextButton(onClick = { renameOpen = true }) { Text("改名字") }
            TextButton(onClick = onBack) { Text("返回") }
        }

        GlassCard(glassAlpha) {
            Text("TA 是你什么人", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
            if (role.renamed) {
                Text(
                    "（识别到的会话名是「${role.key}」，它负责匹配、不会被改动，所以改了名字以后消息还是记到这一条）",
                    fontSize = 11.sp,
                    color = palette.sub,
                )
            }
            RELATIONS.chunked(4).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { r ->
                        GlassPill(r, relation == r, Modifier.weight(1f)) {
                            relation = if (relation == r) "" else r
                            saved = false
                        }
                    }
                    repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = note,
                onValueChange = { note = it; saved = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                label = { Text("平时的关系（越具体越有用）") },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "例：同一个组的后端，说话直接，最近在催我 review；上次帮他带过饭。",
                fontSize = 11.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = {
                    store.setRoleProfile(role.key, relation, note)
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (saved) "已保存" else "保存") }
        }

        GlassCard(glassAlpha) {
            Text("记下来的聊天（${role.msgs.size} 条）", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
            Text(
                "时间是我「看到」它的时间，不是微信里那条消息的真实时间 —— 微信不给这条信息，" +
                    "而模块只在聊天页可见时读得到。生成回复时会带上最近 20 条（屏幕上已有的不再重复）。",
                fontSize = 11.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(6.dp))
            if (role.msgs.isEmpty()) {
                Text("（还没有）", fontSize = 12.sp, color = palette.sub)
            } else {
                var lastShown = 0L
                role.msgs.takeLast(60).forEach { m ->
                    if (m.at - lastShown > 10 * 60_000L) {
                        lastShown = m.at
                        Text(formatTime(m.at), fontSize = 10.sp, color = palette.sub, modifier = Modifier.padding(top = 6.dp))
                    }
                    Text(
                        "${if (m.fromMe) "我" else "对方"}：${m.text}",
                        fontSize = 13.sp,
                        color = if (m.fromMe) palette.sub else palette.text,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { confirm = "clear" },
                    enabled = role.msgs.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("清空记录") }
                OutlinedButton(
                    onClick = { confirm = "remove" },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(14.dp),
                ) { Text("删除角色") }
            }
        }
    }

    if (renameOpen) {
        AlertDialog(
            onDismissRequest = { renameOpen = false },
            title = { Text("给这个角色改个名字") },
            text = {
                Column {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("显示名字") },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "这里只改显示。识别到的会话名「${role.key}」不变 —— 所以改完名字，" +
                            "以后这个会话的消息还是记到同一条上，不会分家。",
                        fontSize = 11.sp,
                        color = palette.sub,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(draft)
                    renameOpen = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameOpen = false }) { Text("取消") } },
        )
    }

    confirm?.let { what ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(if (what == "clear") "清空聊天记录？" else "删除这个角色？") },
            text = {
                Text(
                    if (what == "clear") "只清掉记录，「TA 是你什么人 / 平时的关系」会保留。"
                    else "档案和记录一起删掉，无法恢复。",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (what == "clear") store.clearRoleMsgs(role.key) else store.removeRole(role.key)
                    confirm = null
                    onBack()
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("取消") } },
        )
    }
}
