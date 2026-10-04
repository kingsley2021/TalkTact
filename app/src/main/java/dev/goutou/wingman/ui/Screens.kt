package dev.goutou.wingman.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.ModuleStatus
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.Suggestion
import dev.goutou.wingman.llm.asTranscript
import dev.goutou.wingman.wechat.ChatMsg
import dev.goutou.wingman.wechat.Sensitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ================= 首页：真自检 =================

@Composable
fun HomeScreen(store: ConfigStore, onTrial: () -> Unit) {
    val palette = LocalPalette.current
    var tick by remember { mutableStateOf(0) }
    var cfg by remember { mutableStateOf(store.load()) }
    var usage by remember(tick) { mutableStateOf(store.usage()) }
    var beat by remember(tick) { mutableStateOf(store.heartbeatAt()) }
    val diag = remember(tick) { store.diag() }
    val diagAt = remember(tick) { store.diagAt() }
    var probeResult by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf(false) }
    var probing by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val active = ModuleStatus.isActive()
    val heartbeatFresh = beat > 0 && System.currentTimeMillis() - beat < 6 * 3600_000L
    val confirmed = store.scopeConfirmed()

    val checks = listOf(
        Check(
            "模块激活",
            if (active) "LSPosed 已加载本模块" else "未激活：在 LSPosed 里启用模块后重启本应用",
            if (active) Level.OK else Level.BAD,
        ),
        Check(
            "微信内已生效",
            when {
                heartbeatFresh -> "微信进程最近报过心跳：${formatTime(beat)}"
                confirmed -> "你手动确认过（还没收到心跳）"
                else -> "没收到微信进程的心跳：确认作用域勾了微信并强杀重开"
            },
            if (heartbeatFresh) Level.OK else Level.WARN,
        ) {
            store.setScopeConfirmed(!confirmed)
            cfg = store.load()
        },
        Check(
            "API Key",
            if (cfg.apiKey.isBlank()) "未填写，到「设置」填" else "已填写（明文存本机，见设置页说明）",
            if (cfg.apiKey.isBlank()) Level.BAD else Level.OK,
        ),
        Check(
            "接口与模型",
            "${cfg.model} · ${cfg.baseUrl}",
            if (cfg.baseUrl.startsWith("http")) Level.OK else Level.BAD,
        ),
        Check(
            "军师提示词",
            "${cfg.prompt.length} 字 · " + if (cfg.prompt.contains("JSON")) "含「只输出 JSON」要求" else "缺少 JSON 格式要求，卡片会解析不了",
            if (cfg.prompt.length >= 80 && cfg.prompt.contains("JSON")) Level.OK else Level.WARN,
        ),
        Check(
            "调用节奏",
            "参考最近 ${cfg.ctx} 条 · 最短间隔 ${cfg.minIntervalSec}s · 单次上限 ${cfg.maxTokens} token",
            Level.OK,
        ),
        Check(
            "敏感内容检查",
            if (cfg.allowSensitive) "已关闭：聊天内容会直接发给接口" else "开启：命中验证码/银行卡/转账等会先问你一次",
            if (cfg.allowSensitive) Level.WARN else Level.OK,
        ),
    )

    val shown = when (filter) {
        1 -> checks.filter { it.level == Level.BAD }
        2 -> checks.filter { it.level == Level.WARN }
        3 -> checks.filter { it.level == Level.OK }
        else -> checks
    }
    val bad = checks.count { it.level == Level.BAD }
    val warn = checks.count { it.level == Level.WARN }
    val ok = checks.count { it.level == Level.OK }

    LazyColumn(contentPadding = PaddingValues(bottom = 120.dp)) {
        item {
            ScreenHeader("狗头军师", "WeChat · 聊天副驾 v0.2.1") {
                TextButton(onClick = { tick++ }) { Text("刷新") }
                Button(
                    onClick = onTrial,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text("试聊") }
            }
        }
        item {
            SectionCard {
                Text(
                    "${checks.size} 项检测 · 通过 $ok · 待确认 $warn · 有问题 $bad",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = palette.text,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        bad > 0 -> "有必需项没通过，模块暂时不会工作。"
                        warn > 0 -> "基本可用，剩下的按提示确认一下。"
                        else -> "配置完整，进微信聊天页会自动出候选回复。"
                    },
                    fontSize = 13.sp,
                    color = palette.sub,
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Stat("${usage.first}", "调用次数", Modifier.weight(1f))
                    Stat("${usage.second}", "累计 token", Modifier.weight(1f))
                    Stat(formatTime(beat).takeIf { beat > 0 } ?: "—", "最近心跳", Modifier.weight(1f))
                }
            }
        }
        item {
            SectionCard {
                Text("接口自检", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text("真发一次最小请求，验证地址、Key、模型名是否可用（会消耗几个 token）。", fontSize = 12.sp, color = palette.sub)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            probing = true
                            probeResult = null
                            probeOk = false
                            val conf = store.load()
                            scope.launch {
                                val text = try {
                                    val r = withContext(Dispatchers.IO) { LlmClient(conf).probe() }
                                    if (r.totalTokens > 0) store.addUsage(r.totalTokens)
                                    usage = store.usage()
                                    "通 · ${r.millis}ms · 模型 ${r.model} · ${r.totalTokens} token"
                                } catch (t: Throwable) {
                                    "失败：${t.message}"
                                }
                                probeResult = text
                                probeOk = text.startsWith("通")
                                probing = false
                            }
                        },
                        enabled = !probing,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                    ) { Text(if (probing) "测试中…" else "开始自检") }
                    Spacer(Modifier.width(12.dp))
                    Text(probeResult ?: "", fontSize = 12.sp, color = if (probeOk) palette.ok else palette.bad)
                }
            }
        }
        if (diag.isNotBlank()) {
            item {
                SectionCard(border = palette.warn) {
                    Text("诊断（来自微信进程）", fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                    Text(
                        "生成于 ${formatTime(diagAt)} · 排查「读不到消息 / 全是图片」时把它复制给对方",
                        fontSize = 12.sp,
                        color = palette.sub,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(diag, fontSize = 10.sp, color = palette.text)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { clipboard.setText(AnnotatedString(diag)) },
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    ) { Text("复制诊断") }
                }
            }
        }
        item {
            SectionCard {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Pill("全部 ${checks.size}", filter == 0) { filter = 0 }
                    Pill("有问题 $bad", filter == 1) { filter = 1 }
                    Pill("待确认 $warn", filter == 2) { filter = 2 }
                    Pill("通过 $ok", filter == 3) { filter = 3 }
                }
            }
        }
        item {
            Text(
                "检测项目",
                Modifier.padding(start = 20.dp, top = 8.dp, bottom = 2.dp),
                fontSize = 15.sp,
                color = palette.primary,
                fontWeight = FontWeight.Medium,
            )
        }
        items(shown) { CheckRow(it) }
    }
}

// ================= 试聊 =================

@Composable
fun TrialScreen(store: ConfigStore) {
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
        SectionCard {
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
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (loading) "思考中…" else "生成候选回复") }
        }

        error?.let { msg ->
            SectionCard(border = palette.bad) { Text(msg, color = palette.bad, fontSize = 13.sp) }
        }
        info?.let {
            SectionCard { Text(it, fontSize = 12.sp, color = palette.sub) }
        }
        suggestion?.let { s ->
            SectionCard {
                Text("意图：${s.intent}", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = palette.text)
                Text("风险：${s.risk}　${s.note}", fontSize = 13.sp, color = palette.sub)
            }
            s.replies.forEach { reply ->
                SectionCard {
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

// ================= 提示词 =================

@Composable
fun PromptScreen(store: ConfigStore) {
    val palette = LocalPalette.current
    var cfg by remember { mutableStateOf(store.load()) }
    var text by remember { mutableStateOf(cfg.prompt) }
    var saved by remember { mutableStateOf(false) }
    val hasJsonRule = text.contains("JSON")

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("提示词", "狗头军师 · 系统提示")
        SectionCard {
            Text(
                "可以粘上游仓库 SKILL.md 的精简版。必须保留「只输出 JSON」和 JSON 结构示例，否则卡片无法解析。",
                fontSize = 12.sp,
                color = palette.sub,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (hasJsonRule) "✓ 检测到 JSON 格式要求 · ${text.length} 字" else "⚠ 没检测到 JSON 格式要求",
                fontSize = 12.sp,
                color = if (hasJsonRule) palette.ok else palette.warn,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; saved = false },
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = { text = ConfigData().prompt; saved = false },
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                ) { Text("恢复默认") }
                Button(
                    onClick = {
                        store.save(store.load().copy(prompt = text))
                        cfg = store.load()
                        saved = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
                ) { Text(if (saved) "已保存" else "保存") }
            }
        }
    }
}

// ================= 设置 =================

@Composable
fun SettingsScreen(store: ConfigStore) {
    val palette = LocalPalette.current
    var cfg by remember { mutableStateOf(store.load()) }
    var saved by remember { mutableStateOf(false) }
    fun update(next: ConfigData) {
        cfg = next
        saved = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 120.dp)) {
        ScreenHeader("设置", "接口 · 节奏 · 开关")
        SectionCard {
            OutlinedTextField(
                value = cfg.baseUrl,
                onValueChange = { update(cfg.copy(baseUrl = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("接口地址（OpenAI 兼容，写到 /v1）") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cfg.apiKey,
                onValueChange = { update(cfg.copy(apiKey = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API Key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = cfg.model,
                onValueChange = { update(cfg.copy(model = it)) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("模型") },
                singleLine = true,
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = cfg.ctx.toString(),
                    onValueChange = { update(cfg.copy(ctx = it.toIntOrNull() ?: cfg.ctx)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("参考条数 2-20") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = cfg.minIntervalSec.toString(),
                    onValueChange = { update(cfg.copy(minIntervalSec = it.toIntOrNull() ?: cfg.minIntervalSec)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("最短间隔(s)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = cfg.temperature.toString(),
                    onValueChange = { update(cfg.copy(temperature = it.toDoubleOrNull() ?: cfg.temperature)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("temperature") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
                OutlinedTextField(
                    value = cfg.maxTokens.toString(),
                    onValueChange = { update(cfg.copy(maxTokens = it.toIntOrNull() ?: cfg.maxTokens)) },
                    modifier = Modifier.weight(1f),
                    label = { Text("max_tokens") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("微信内自动分析", fontSize = 15.sp, color = palette.text)
                    Text("关掉后卡片只在手动点「识别」时出现", fontSize = 11.sp, color = palette.sub)
                }
                Switch(checked = cfg.enabled, onCheckedChange = { update(cfg.copy(enabled = it)) })
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("敏感内容检查", fontSize = 15.sp, color = palette.text)
                    Text("命中验证码/银行卡/转账时先拦一次（推荐开）", fontSize = 11.sp, color = palette.sub)
                }
                Switch(checked = !cfg.allowSensitive, onCheckedChange = { update(cfg.copy(allowSensitive = !it)) })
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    store.save(cfg)
                    cfg = store.load()
                    saved = true
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.primary),
            ) { Text(if (saved) "已保存（微信里下次识别即生效）" else "保存") }
        }

        SectionCard {
            Text("隐私与风险", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "· 开启后，聊天页最近几条消息会发送到你填写的接口地址，请自行确认该服务可信。\n" +
                    "· API Key 以明文存放在本应用私有目录的配置 XML 里（不能加密：注入微信进程的代码需要跨进程读取，Keystore 密钥按 UID 隔离读不到）。\n" +
                    "· 本模块只读消息、只把候选回复填进输入框，不会自动发送；但仍属于修改微信客户端行为，有被风控的风险，建议先用小号试。\n" +
                    "· 日志关键字：[Goutou]（LSPosed 日志里可查）。",
                fontSize = 12.sp,
                color = palette.sub,
            )
        }
        SectionCard {
            Text("改配置要不要重启微信？", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = palette.text)
            Spacer(Modifier.height(6.dp))
            Text(
                "不用。模块每次识别前会检查配置文件是否被改过，改了就地重载。\n" +
                    "但「作用域」这种 LSPosed 层面的改动，必须强杀微信重开。",
                fontSize = 12.sp,
                color = palette.sub,
            )
        }
    }
}
