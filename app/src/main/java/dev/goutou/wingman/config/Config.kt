package dev.goutou.wingman.config

import android.content.Context
import android.content.SharedPreferences
import dev.goutou.wingman.llm.DEFAULT_PROMPT

const val MODULE_PKG = "dev.goutou.wingman"
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
    const val LEARNED = "learned_classes"
    const val GLASS = "glass_alpha"
    const val GLASS_BLUR = "glass_blur"
    const val BG_URI = "bg_uri"
    const val BG_DIM = "bg_dim"
    const val SKILL = "skill_id"
    const val MENTOR_ADV = "mentor_adv"
}

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
    /** 当前选中的 skill：classic / coder / custom */
    val skillId: String = "classic",
    /** 「军师」页停在进阶视图。以前是用 maxTokens==0 猜的，会粘住，改成独立记住 */
    val mentorAdvanced: Boolean = false,
) {
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
    private val sp: SharedPreferences = try {
        @Suppress("DEPRECATION")
        context.getSharedPreferences(PREF_NAME, Context.MODE_WORLD_READABLE)
    } catch (t: Throwable) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

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

    /** 已经学会「自己画字」的控件类（模块下次启动就先挂钩子）。 */
    fun learnedClasses(): Set<String> = sp.getStringSet(Keys.LEARNED, emptySet()).orEmpty()

    fun scopeConfirmed(): Boolean = sp.getBoolean(Keys.SCOPE_OK, false)

    fun setScopeConfirmed(v: Boolean) {
        sp.edit().putBoolean(Keys.SCOPE_OK, v).apply()
    }
}
