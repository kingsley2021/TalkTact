package dev.goutou.wingman.config

import dev.goutou.wingman.llm.Json
import dev.goutou.wingman.llm.JsonValue
import dev.goutou.wingman.llm.arr
import dev.goutou.wingman.llm.asBool
import dev.goutou.wingman.llm.asDouble
import dev.goutou.wingman.llm.asObj
import dev.goutou.wingman.llm.asStr
import dev.goutou.wingman.llm.bool
import dev.goutou.wingman.llm.num
import dev.goutou.wingman.llm.str

/**
 * 配置导出 / 导入。
 *
 * 存在的理由很具体：改 applicationId 等于换一个应用，数据目录是新的 ——
 * API Key、提示词、攒下来的角色记录全部会没。有了这个，旧包导出、新包导入，就是点两下的事。
 * （上官方模块库要求包名可验证，所以这是必须走的一趟。）
 *
 * 纯函数，不碰 Android，可以直接单测。
 */
object Backup {

    const val SCHEMA = 1
    const val FILE_NAME = "talktact-backup.json"

    fun export(cfg: ConfigData, roles: List<Role>, includeApiKey: Boolean): String {
        val c = LinkedHashMap<String, JsonValue>()
        c["baseUrl"] = str(cfg.baseUrl)
        if (includeApiKey) c["apiKey"] = str(cfg.apiKey)
        c["model"] = str(cfg.model)
        c["prompt"] = str(cfg.prompt)
        c["enabled"] = bool(cfg.enabled)
        c["ctx"] = num(cfg.ctx)
        c["temperature"] = num(cfg.temperature)
        c["maxTokens"] = num(cfg.maxTokens)
        c["minIntervalSec"] = num(cfg.minIntervalSec)
        c["allowSensitive"] = bool(cfg.allowSensitive)
        c["glassAlpha"] = num(cfg.glassAlpha)
        c["glassBlur"] = num(cfg.glassBlur)
        c["bgUri"] = str(cfg.bgUri)
        c["bgDim"] = num(cfg.bgDim)
        c["skillId"] = str(cfg.skillId)
        c["mentorAdvanced"] = bool(cfg.mentorAdvanced)

        val fields = LinkedHashMap<String, JsonValue>()
        fields["app"] = str("TalkTact")
        fields["schema"] = num(SCHEMA)
        fields["config"] = JsonValue.Obj(c)
        // 角色直接复用 Roles 自己的格式，免得两套序列化各写一遍
        fields["roles"] = Json.parse(Roles.encode(roles)) ?: arr(emptyList())
        return Json.encode(JsonValue.Obj(fields))
    }

    data class ImportResult(val config: ConfigData, val roles: List<Role>, val roleCount: Int)

    /**
     * 导入。
     *
     * 逐字段「有才覆盖」，缺的保持原样 —— 这样即使备份里没带 API Key（导出时关掉了），
     * 也不会把新包里已经填好的 Key 冲掉。
     * 角色是**合并**不是替换：同名覆盖，导入里没有的保留。
     * 任何坏数据一律返回 null，绝不拿半个配置去覆盖现有内容。
     */
    fun import(text: String, current: ConfigData, currentRoles: List<Role>): ImportResult? {
        val root = Json.parse(text).asObj() ?: return null
        val c = root["config"].asObj() ?: return null

        var cfg = current
        c["baseUrl"].asStr()?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(baseUrl = it) }
        c["apiKey"].asStr()?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(apiKey = it) }
        c["model"].asStr()?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(model = it) }
        c["prompt"].asStr()?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(prompt = it) }
        c["enabled"].asBool()?.let { cfg = cfg.copy(enabled = it) }
        c["ctx"].asDouble()?.let { cfg = cfg.copy(ctx = it.toInt().coerceIn(2, 20)) }
        c["temperature"].asDouble()?.let { cfg = cfg.copy(temperature = it) }
        c["maxTokens"].asDouble()?.let { cfg = cfg.copy(maxTokens = ConfigData.snapTier(it.toInt())) }
        c["minIntervalSec"].asDouble()?.let { cfg = cfg.copy(minIntervalSec = it.toInt().coerceIn(0, 600)) }
        c["allowSensitive"].asBool()?.let { cfg = cfg.copy(allowSensitive = it) }
        c["glassAlpha"].asDouble()?.let { cfg = cfg.copy(glassAlpha = it.toFloat().coerceIn(0.30f, 1f)) }
        c["glassBlur"].asDouble()?.let { cfg = cfg.copy(glassBlur = it.toFloat().coerceIn(0f, 48f)) }
        c["bgUri"].asStr()?.let { cfg = cfg.copy(bgUri = it) }
        c["bgDim"].asDouble()?.let { cfg = cfg.copy(bgDim = it.toFloat().coerceIn(0f, 0.8f)) }
        c["skillId"].asStr()?.takeIf { it.isNotBlank() }?.let { cfg = cfg.copy(skillId = it) }
        c["mentorAdvanced"].asBool()?.let { cfg = cfg.copy(mentorAdvanced = it) }

        val imported = root["roles"].let { Roles.decode(Json.encode(it)) }
        val byName = LinkedHashMap<String, Role>()
        currentRoles.forEach { byName[it.name] = it }
        imported.forEach { byName[it.name] = it }
        return ImportResult(cfg, byName.values.sortedByDescending { it.lastAt }, imported.size)
    }
}
