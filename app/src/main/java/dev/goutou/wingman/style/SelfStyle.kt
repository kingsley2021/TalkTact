package dev.goutou.wingman.style

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.config.SELF_ROLE_KEY
import dev.goutou.wingman.llm.LlmClient
import dev.goutou.wingman.llm.StyleSkill
import java.util.Calendar
import java.util.concurrent.TimeUnit

/** 提炼相关的失败。[retryable] 决定这次要不要让系统稍后重试。 */
class SelfStyleError(message: String, val retryable: Boolean) : Exception(message)

/**
 * 「把我的说话风格做成 skill」这件事的调度与执行。
 *
 * 为什么用 WorkManager 而不是 AlarmManager：需求是「每天固定某个点做，时间可调」，
 * 而 AlarmManager 要做到准点得申请精确闹钟权限（Android 12+），还得自己处理重启。
 * WorkManager 的 24 小时周期任务 + 首延迟算到下一个 12:00，App 没开、手机重启过都照跑，
 * 错过（关机/没网）也会由系统补跑 —— 代价是"12:00 左右"而不是"12:00:00 整"。
 *
 * 这个文件只跑在 App 进程里，不涉及被注入微信的那一侧。
 */
object SelfStyle {

    const val WORK_NAME = "talktact-self-style"

    /** 提炼一次。成功返回给人看的说明；失败抛异常（见 [SelfStyleError]）。 */
    fun generateNow(store: ConfigStore): String {
        val cfg = store.load()
        if (cfg.apiKey.isBlank()) throw SelfStyleError("还没填 API Key，到「设置」里填", retryable = false)

        val samples = store.selfSamples()
        val n = StyleSkill.count(samples)
        if (n < StyleSkill.MIN_SAMPLES) {
            throw SelfStyleError(
                "样本还不够：现在 $n 条，至少 ${StyleSkill.MIN_SAMPLES} 条（多在微信里聊几句）",
                retryable = false,
            )
        }

        // 网络异常直接往外抛 → 交给 WorkManager 重试
        val (raw, tokens) = LlmClient(cfg).complete(StyleSkill.SYSTEM, StyleSkill.buildUserPrompt(samples))
        val skill = StyleSkill.clean(raw)
        if (skill.isBlank()) throw SelfStyleError("模型返回是空的", retryable = true)

        store.saveSelfSkill(skill)
        return "已生成 ${skill.length} 字（用了 $n 条样本、$tokens token）"
    }

    /**
     * 排上「每天 [hour] 点」的任务。重复调用是安全的（同名的周期任务会被更新）——
     * 用户改了时间就靠这个把首延迟重算一遍。
     */
    fun schedule(context: Context, hour: Int) {
        val request = PeriodicWorkRequestBuilder<SelfStyleWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(millisToNextHour(hour), TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
    }

    /**
     * 距下一个本地时间 [hour]:00 还有多少毫秒（今天这一点已经过了就指到明天）。
     *
     * [hour] 允许 0..23 —— 挑 0 点就是「凌晨跑」，挑 23 点就是「睡前跑」，
     * 一天 24 小时任意一点都能选。
     */
    fun millisToNextHour(hour: Int, now: Long = System.currentTimeMillis()): Long {
        val h = hour.coerceIn(0, 23)
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis - now
    }
}

/**
 * 定时任务的入口。
 *
 * 每次都被重新拉起来算一次（而不是在 App 里常驻什么状态）—— 周期任务可能在一个
 * 完全新的进程里跑，读配置、看开关、干活、退出，最省心。
 */
class SelfStyleWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val store = ConfigStore(applicationContext)
        // 关掉了就别干活：顺带把已经排上的这轮吞掉，等下次周期再说
        if (!store.selfStyleEnabled()) return Result.success()
        return try {
            SelfStyle.generateNow(store)
            Result.success()
        } catch (t: SelfStyleError) {
            // 配置类问题（没 Key / 样本不够）跳过这一轮；别拿它去反复重试烧电
            if (t.retryable) Result.retry() else Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
