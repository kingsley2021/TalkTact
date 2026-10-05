package dev.goutou.wingman.config

import android.content.Context
import android.content.SharedPreferences
import dev.goutou.wingman.llm.DEFAULT_PROMPT

/**
 * 装到系统里的包名（= build.gradle 里的 applicationId）。
 *
 * 注意它和 [NAMESPACE] 是**两回事**：namespace 是 Kotlin 包名 / R 类所在的位置，
 * 一直没变；applicationId 为了上 LSPosed 官方模块库（要求包名可验证）改成了
 * io.github.shibry88_netizen.talktact。
 * 用到类名的地方（比如 hook 自己）必须用 NAMESPACE，别用这个。
 */
const val MODULE_PKG = "io.github.shibry88_netizen.talktact"

/** Kotlin 包名。类名、R 类都在这儿，改名要动整个源码树，所以刻意保持不变。 */
const val NAMESPACE = "dev.goutou.wingman"
const val PREF_NAME = "cfg"
const val DEFAULT_BASE = "https://api.openai.com/v1"
const val DEFAULT_MODEL = "gpt-4o-mini"

/** 跨进程读写的 key 集中放一处，免得上游（App）和下游（注入微信的代码）写错字符串。 */
object Keys {
    const val BASE = "base_url"
    const val KEY = "api_key"
    const val MODEL = "model"
    const val PROMPT = "prompt_v3"
    const val ENABLED = "enabled"
    const val CTX = "ctx"
    const val TEMPERATURE = "temperature"
    const val MAX_TOKENS = "max_tokens"
    const val MIN_INTERVAL = "min_interval"
    const val SENSITIVE = "allow_sensitive"
    const val SCOPE_OK = "scope_ok"
    const val HEARTBEAT = "heartbeat_at"
    const val CALLS = "stat_calls"
    const val TOKENS = "stat_tokens"
    const val DIAG = "diag"
    const val DIAG_AT = "diag_at"
    const val LAST_CALL = "last_call"
    const val LAST_CALL_AT = "last_call_at"
    /** App 里点「抓当前微信界面」时写一个时间戳，注入侧看到比上次新就去 dump 当前界面 */
    const val DIAG_REQ = "diag_req"
    /** 「角色」页：每个微信联系人的档案 + 平时记下来的聊天记录（JSON） */
    const val ROLES = "roles"
    const val LEARNED = "learned_classes"
    const val GLASS = "glass_alpha"
    const val GLASS_BLUR = "glass_blur"
    const val BG_URI = "bg_uri"
    const val BG_DIM = "bg_dim"
    const val SKILL = "skill_id"
    const val MENTOR_ADV = "mentor_adv"
    /** 「本人」那条的开关：把我的说话风格做成 skill（默认关） */
    const val SELF_STYLE_ON = "self_style_on"
    /** 自动提炼出来的说话风格档案 */
    const val SELF_SKILL = "self_skill"
    const val SELF_SKILL_AT = "self_skill_at"
    /** 每天几点跑（0..23，本地时间） */
    const val SELF_STYLE_HOUR = "self_style_hour"
    /** 模型分级模式：开 = 风险一路 + 写回复一路（可各用一套接口、各带一份提示词） */
    const val GRADED = "graded_mode"
    /** 第二套接口：给「风险评估」那一路用；留空 = 逐项复用第一套 */
    const val BASE2 = "base_url_2"
    const val KEY2 = "api_key_2"
    const val MODEL2 = "model_2"
}

/** 默认几点跑。 */
const val DEFAULT_SELF_STYLE_HOUR = 12

data class ConfigData(
    val baseUrl: String = DEFAULT_BASE,
    val apiKey: String = "",
    val model: String = DEFAULT_MODEL,
    val prompt: String = DEFAULT_PROMPT,
    val enabled: Boolean = true,
    /** 参考最近几条消息（2..20） */
    val ctx: Int = 8,
    val temperature: Double = 0.8,
    /** 0 = 不传 max_tokens（「无限制」档）；其余按 200/400/600 三档 */
    val maxTokens: Int = 400,
    /** 两次自动分析之间的最短间隔，防止刷屏式调用把额度烧完 */
    val minIntervalSec: Int = 15,
    /** 关掉的话，命中敏感内容时不会再拦你 */
    val allowSensitive: Boolean = false,
    /** 玻璃面板不透明度：1 = 不透明，越小越透（0.30..1.00） */
    val glassAlpha: Float = 0.92f,
    /** 玻璃面板背后的真实背景模糊半径（dp，0 = 不模糊）。只在设了自定义背景图时看得出来。 */
    val glassBlur: Float = 24f,
    /** 自定义背景图（OpenDocument 的持久化 URI），空 = 用默认渐变 */
    val bgUri: String = "",
    /** 背景压暗程度，保证玻璃上的字看得清 */
    val bgDim: Float = 0.30f,
    /** 当前选中的 skill：classic / full / coder / custom */
    val skillId: String = "classic",
    /** 「军师」页停在进阶视图。以前是用 maxTokens==0 猜的，会粘住，改成独立记住 */
    val mentorAdvanced: Boolean = false,
    /**
     * 「本人」那条的「把我的说话风格做成 skill」开关。
     *
     * 注意这个字段是**只读**的：它由 [ConfigStore.setSelfStyleEnabled] 单独写，
     * save() 刻意不碰它 —— 免得设置页拿着一份旧快照把这个开关覆盖回去。
     */
    val selfStyleEnabled: Boolean = false,
    /**
     * 每天几点跑（0..23）。和 [selfStyleEnabled] 一样是**只读**字段：
     * 由 [ConfigStore.setSelfStyleHour] 单独写，save() 不碰它。
     */
    val selfStyleHour: Int = DEFAULT_SELF_STYLE_HOUR,
    /**
     * 模型分级模式。关（默认）= 直通：一套接口、一份提示词、一次调用，和以前完全一样。
     * 开 = 风险一路 + 写回复一路，两路各带自己的提示词（见 llm/Graded.kt）。
     */
    val graded: Boolean = false,
    /** 第二套接口（给风险评估那一路用）。留空 = 复用第一套 —— 那样等于「同一个模型拆两路提示词」。 */
    val baseUrl2: String = "",
    val apiKey2: String = "",
    val model2: String = "",
) {
    /**
     * 分级模式下「风险评估」那一路要用的接口。
     *
     * 第二套一个字都没填 → 直接用第一套（合法用法：同一个模型、拆两路提示词）；
     * 只填了一部分 → 把填了的字段覆盖过去。不用逼用户把两套都填满。
     */
    fun riskEndpoint(): ConfigData =
        if (baseUrl2.isBlank() && apiKey2.isBlank() && model2.isBlank()) {
            this
        } else {
            copy(
                baseUrl = baseUrl2.ifBlank { baseUrl },
                apiKey = apiKey2.ifBlank { apiKey },
                model = model2.ifBlank { model },
            )
        }

    companion object {
        /** 老版本存过的 max_tokens 不在四档里（比如 500），归一化到最近的档，免得设置页四档都没选中。 */
        fun snapTier(v: Int): Int = when {
            v <= 0 -> 0
            v <= 300 -> 200
            v <= 500 -> 400
            v <= 800 -> 600
            else -> 0
        }

        fun from(p: SharedPreferences): ConfigData = ConfigData(
            baseUrl = p.getString(Keys.BASE, DEFAULT_BASE).orEmpty().ifBlank { DEFAULT_BASE },
            apiKey = p.getString(Keys.KEY, "").orEmpty(),
            model = p.getString(Keys.MODEL, DEFAULT_MODEL).orEmpty().ifBlank { DEFAULT_MODEL },
            prompt = p.getString(Keys.PROMPT, null).orEmpty().ifBlank { DEFAULT_PROMPT },
            enabled = p.getBoolean(Keys.ENABLED, true),
            ctx = p.getInt(Keys.CTX, 8).coerceIn(2, 20),
            glassAlpha = p.getFloat(Keys.GLASS, 0.92f).coerceIn(0.30f, 1f),
            glassBlur = p.getFloat(Keys.GLASS_BLUR, 24f).coerceIn(0f, 48f),
            bgUri = p.getString(Keys.BG_URI, "").orEmpty(),
            bgDim = p.getFloat(Keys.BG_DIM, 0.30f).coerceIn(0f, 0.8f),
            skillId = p.getString(Keys.SKILL, "classic").orEmpty().ifBlank { "classic" },
            mentorAdvanced = p.getBoolean(Keys.MENTOR_ADV, false),
            temperature = p.getFloat(Keys.TEMPERATURE, 0.8f).toDouble(),
            maxTokens = snapTier(p.getInt(Keys.MAX_TOKENS, 400)),
            minIntervalSec = p.getInt(Keys.MIN_INTERVAL, 15).coerceIn(0, 600),
            allowSensitive = p.getBoolean(Keys.SENSITIVE, false),
            selfStyleEnabled = p.getBoolean(Keys.SELF_STYLE_ON, false),
            selfStyleHour = p.getInt(Keys.SELF_STYLE_HOUR, DEFAULT_SELF_STYLE_HOUR).coerceIn(0, 23),
            graded = p.getBoolean(Keys.GRADED, false),
            baseUrl2 = p.getString(Keys.BASE2, "").orEmpty(),
            apiKey2 = p.getString(Keys.KEY2, "").orEmpty(),
            model2 = p.getString(Keys.MODEL2, "").orEmpty(),
        )

    }
}

/**
 * 配置仓库。
 *
 * API Key 落盘是明文（SharedPreferences 的 XML）。
 * 试过用 EncryptedSharedPreferences，但那样注入到微信进程的代码就读不到了 ——
 * Keystore 的密钥按 UID 隔离，跨进程就是打不开。所以这里保持明文，并在「设置」页明说。
 * 真正的解法是让 App 起一个本地代理、Key 不出 App 进程（见 IMPROVEMENTS.md 的路线图）。
 */
class ConfigStore(context: Context) {
    /**
     * 本地配置。它仍然是 App 侧唯一的事实来源（界面、导出导入都读它）。
     *
     * 迁移到现代 API 之后这里不再需要 MODE_WORLD_READABLE：以前靠它让注入进程（不同 UID）
     * 能读这个 XML 文件，现在注入侧读的是框架推送的配置副本（见 RemoteSync），
     * 两个进程之间不再需要共享同一个文件。
     */
    private val sp: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    fun load(): ConfigData = ConfigData.from(sp)

    fun save(d: ConfigData) {
        sp.edit()
            .putString(Keys.BASE, d.baseUrl.trim())
            .putString(Keys.KEY, d.apiKey.trim())
            .putString(Keys.MODEL, d.model.trim())
            .putString(Keys.PROMPT, d.prompt)
            .putBoolean(Keys.ENABLED, d.enabled)
            .putInt(Keys.CTX, d.ctx)
            .putFloat(Keys.TEMPERATURE, d.temperature.toFloat())
            .putInt(Keys.MAX_TOKENS, d.maxTokens)
            .putInt(Keys.MIN_INTERVAL, d.minIntervalSec)
            .putBoolean(Keys.SENSITIVE, d.allowSensitive)
            .putFloat(Keys.GLASS, d.glassAlpha.coerceIn(0.30f, 1f))
            .putFloat(Keys.GLASS_BLUR, d.glassBlur.coerceIn(0f, 48f))
            .putString(Keys.BG_URI, d.bgUri)
            .putFloat(Keys.BG_DIM, d.bgDim.coerceIn(0f, 0.8f))
            .putString(Keys.SKILL, d.skillId)
            .putBoolean(Keys.MENTOR_ADV, d.mentorAdvanced)
            .putBoolean(Keys.GRADED, d.graded)
            .putString(Keys.BASE2, d.baseUrl2.trim())
            .putString(Keys.KEY2, d.apiKey2.trim())
            .putString(Keys.MODEL2, d.model2.trim())
            .apply()
    }

    fun usage(): Pair<Int, Int> = sp.getInt(Keys.CALLS, 0) to sp.getInt(Keys.TOKENS, 0)

    fun addUsage(tokens: Int) {
        sp.edit()
            .putInt(Keys.CALLS, sp.getInt(Keys.CALLS, 0) + 1)
            .putInt(Keys.TOKENS, sp.getInt(Keys.TOKENS, 0) + tokens.coerceAtLeast(0))
            .apply()
    }

    fun heartbeatAt(): Long = sp.getLong(Keys.HEARTBEAT, 0L)

    /** 微信进程最近一次写回来的结构诊断（排查「读不到消息」用）。 */
    fun diag(): String = sp.getString(Keys.DIAG, "").orEmpty()

    fun diagAt(): Long = sp.getLong(Keys.DIAG_AT, 0L)

    /**
     * 注入侧回传的「最近一次真正发出去的请求」存档。
     * 排查「App 里选的是 A，用起来像 B」时必须看它 —— App 显示的是本进程读到的配置，
     * 而这里是微信进程实际拿去调接口的那一份，两者走的读取路径完全不同。
     */
    fun lastCall(): String = sp.getString(Keys.LAST_CALL, "").orEmpty()

    fun lastCallAt(): Long = sp.getLong(Keys.LAST_CALL_AT, 0L)

    /**
     * 请求注入侧抓一次「当前微信界面」的结构。
     *
     * 为什么需要它：出问题的聊天页是「连卡片都不弹」的，而诊断入口原本是长按卡片标题 ——
     * 没有卡片就没有入口，永远拿不到那几个页面的证据。所以改成由 App 主动发起。
     * 只动这一个 key，不走 save()，免得把别的字段一起写回去。
     */
    // ---------------- 角色 ----------------

    /** 角色列表。统一走 [Roles.withSelf]：「本人」那条永远是第一条、永远在。 */
    fun roles(): List<Role> = Roles.withSelf(Roles.decode(sp.getString(Keys.ROLES, "").orEmpty()))

    // ---------------- 「本人」的说话风格 skill ----------------

    fun selfStyleEnabled(): Boolean = sp.getBoolean(Keys.SELF_STYLE_ON, false)

    /**
     * 开关。
     *
     * 关掉时**连采集到的原始记录一起清掉** —— 这就是「不存储我的聊天记录」的字面意思。
     * 已生成的 skill 先留着：它只是提炼结果，不重新打开就不会被用上。
     */
    fun setSelfStyleEnabled(on: Boolean) {
        sp.edit().putBoolean(Keys.SELF_STYLE_ON, on).apply()
        if (!on) clearRoleMsgs(SELF_ROLE_KEY)
    }

    /** 每天几点跑（本地时间 0..23）。 */
    fun selfStyleHour(): Int =
        sp.getInt(Keys.SELF_STYLE_HOUR, DEFAULT_SELF_STYLE_HOUR).coerceIn(0, 23)

    fun setSelfStyleHour(hour: Int) {
        sp.edit().putInt(Keys.SELF_STYLE_HOUR, hour.coerceIn(0, 23)).apply()
    }

    fun selfSkill(): String = sp.getString(Keys.SELF_SKILL, "").orEmpty()

    fun selfSkillAt(): Long = sp.getLong(Keys.SELF_SKILL_AT, 0L)

    fun saveSelfSkill(text: String) {
        sp.edit()
            .putString(Keys.SELF_SKILL, text)
            .putLong(Keys.SELF_SKILL_AT, System.currentTimeMillis())
            .apply()
    }

    /** 「本人」这条线攒到的样本（都是我自己发出去的话）。 */
    fun selfSamples(): List<RoleMsg> =
        roles().firstOrNull { it.key == SELF_ROLE_KEY }?.msgs.orEmpty().filter { it.fromMe }

    fun saveRoles(roles: List<Role>) {
        sp.edit().putString(Keys.ROLES, Roles.encode(roles)).apply()
    }

    /** 注入侧回传的一批新消息，合并进对应角色（1 小时内重复只留一条）。 */
    fun mergeRoles(incomingJson: String) {
        val incoming = Roles.decodeIncoming(incomingJson)
        if (incoming.isEmpty()) return
        saveRoles(Roles.merge(roles(), incoming))
    }

    /** 写「TA 是你什么人 / 平时的关系」。参数是识别名（key）。 */
    fun setRoleProfile(key: String, relation: String, note: String) {
        saveRoles(Roles.setProfile(roles(), key, relation, note))
    }

    /** 改显示名（不动 key，所以后续消息还是记到这一条）。 */
    fun renameRole(key: String, newName: String) {
        saveRoles(Roles.rename(roles(), key, newName))
    }

    /** 把 [from] 这个角色的记录并到 [to] 上（同一个人被记成了两个名字时，手动合并）。 */
    fun mergeRoles(from: String, to: String) {
        saveRoles(Roles.mergeTwo(roles(), from, to))
    }

    fun removeRole(key: String) {
        saveRoles(roles().filterNot { it.key == key })
    }

    /** 只清聊天记录，保留档案。 */
    fun clearRoleMsgs(key: String) {
        saveRoles(roles().map { if (it.key == key) it.copy(msgs = emptyList()) else it })
    }

    fun requestDiag(): Long {
        val now = System.currentTimeMillis()
        sp.edit().putLong(Keys.DIAG_REQ, now).apply()
        return now
    }

    /** 已经学会「自己画字」的控件类（模块下次启动就先挂钩子）。 */
    fun learnedClasses(): Set<String> = sp.getStringSet(Keys.LEARNED, emptySet()).orEmpty()

    fun scopeConfirmed(): Boolean = sp.getBoolean(Keys.SCOPE_OK, false)

    fun setScopeConfirmed(v: Boolean) {
        sp.edit().putBoolean(Keys.SCOPE_OK, v).apply()
    }
}
