package dev.goutou.wingman.wechat

import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.proxy.ProxyProtocol
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors

/**
 * 图片文字识别的**注入侧**那一半：这里不认字，只负责把图送过去、把文字拿回来。
 *
 * 认字在 App 进程里做（ML Kit 只装在 App 里，见 `ocr/Ocr.kt`）——
 * 走的还是 ⑥ 建好的那条回环代理：`POST http://127.0.0.1:PORT/proxy/ocr`（带 token），
 * 响应体就是**纯文本**。刻意不用 JSON：注入侧只用系统 API + kotlin stdlib，不引任何序列化库。
 *
 * ## 为什么是「后台认、认完再问」
 *
 * 读屏这一套跑在**微信的主线程**上（tick 是 handler 消息）。识图要发一次回环请求 + 在 App 侧跑模型，
 * 几百毫秒起步 —— 在主线程上等它，微信就卡住了。所以分成两拍：
 *
 * 1. [prepare]：把这屏的图**排进后台**去认，当场返回「还有没有图在认」；
 *    主循环看到「还有」就这一轮先不分析（只把按钮上的字改成「正在认图…」）。
 * 2. 认完了置一个标志，主循环下一次就带着文字重新读一遍（[takeDirty]）。
 *
 * 为什么不「先用 [图片] 占位问一遍、认完再问一遍」：那样一次分析要花两次 token，
 * 而且用户会先看到一张「不知道图里写了什么」的答案，一秒后又变成另一张 —— 观感很差。
 *
 * ## 五层「别把事搞砸」的保险
 *
 * 1. 开关关着、或没走本地代理 —— 直接不试（没代理就没有地方认，连回环只会白等一次超时）；
 * 2. 同一张图只发一次（按压缩后字节的 sha256 记住结果，LRU 16 张）；
 * 3. 图取不到 / 压完还是太大 / 空 —— 都只记一行状态，回落成 `[图片]` 占位；
 * 4. 失败**不进缓存**，但要冷却 [FAIL_COOLDOWN_MS] —— 否则「代理没起来」会变成每轮都重试，
 *    卡片会永远卡在「正在认图…」；
 * 5. **任何异常都不往上抛** —— 一次识图失败绝不能让整轮分析挂掉。
 */
internal class ImageOcr {

    private val pool = Executors.newSingleThreadExecutor { r ->
        Thread(r, "talktact-ocr").also { it.isDaemon = true }
    }

    /** 图 sha256 -> 认出来的文字（空串 = 确实没字，也算认过，别再发）。按访问顺序 LRU。 */
    private val done = object : LinkedHashMap<String, String>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > DONE_MAX
    }

    /** 正在后台认的（键同上）。 */
    private val inFlight = HashSet<String>()

    /** 失败过、还在冷却期里的：键 -> 失败时间。 */
    private val failed = HashMap<String, Long>()

    /**
     * 这一轮「行里的图」-> 它对应的键。
     *
     * 只在 [prepare] 里填、只在同一轮的 [textOf] 里读 —— RowImage 没有重写 equals，
     * 所以这就是按对象身份查，正好是我们想要的（同一轮里行和图都是同一批对象）。
     */
    private val keysOfTick = HashMap<RowImage, String>()

    /** 有新的识别结果落地（让主循环强制重读一次）。 */
    @Volatile
    private var dirty = false

    /** 最近一次的结果（一句话）。设置页那条状态行显示的就是它。 */
    @Volatile
    private var line: String? = null

    @Volatile
    private var reported: String? = null

    /** 取一条「还没回传过的」状态行；没有新的就返回 null（别把广播通道淹了）。 */
    fun takeLine(): String? = synchronized(this) {
        val cur = line
        if (cur == null || cur == reported) null else cur.also { reported = it }
    }

    /** 刚认完一批？取一次就清掉。 */
    fun takeDirty(): Boolean {
        val d = dirty
        dirty = false
        return d
    }

    /**
     * 把这一屏的图交出去认（**不阻塞**）。
     *
     * @return true = 还有图没认完，这一轮先别分析
     */
    fun prepare(rows: List<RowSnapshot>, cfg: ConfigData): Boolean = synchronized(this) {
        keysOfTick.clear()
        if (!cfg.ocrEnabled) return false
        if (!ProxyProtocol.ocrUsable(true, ProxyProtocol.routeOf(cfg.proxyEnabled, cfg.proxyToken))) {
            line = "跳过：没走本地代理（认字要在 App 进程里做，先把代理打开）"
            return false
        }

        val now = System.currentTimeMillis()
        var pending = false
        for (row in rows) {
            val img = row.image ?: continue

            val bytes = img.jpeg(ProxyProtocol.OCR_MAX_SIDE)
            if (bytes == null || bytes.isEmpty()) {
                line = "跳过：这一行的图取不到字节（不是普通位图 / 已回收）"
                continue
            }
            if (bytes.size > ProxyProtocol.OCR_MAX_BYTES) {
                line = "跳过：这张图压完还是太大（${bytes.size / 1024}KB）"
                continue
            }

            val key = sha256(bytes)
            keysOfTick[img] = key
            if (done.containsKey(key)) continue
            if (inFlight.contains(key)) {
                pending = true
                continue
            }
            val lastFail = failed[key] ?: 0L
            if (now - lastFail < FAIL_COOLDOWN_MS) continue

            inFlight.add(key)
            pending = true
            submit(key, bytes, cfg)
        }
        pending
    }

    /**
     * 这张图认出来什么字？**只看已经认好的** —— 没有就返回 null（这一轮先用 `[图片]` 占位）。
     *
     * 正常流程下调用它时 [prepare] 已经说过「没有在认的了」，所以这里基本都能拿到结果；
     * 兜底返回 null 只是为了让解析这一步永远有个确定的行为。
     */
    fun textOf(img: RowImage): String? = synchronized(this) {
        val key = keysOfTick[img] ?: return null
        done[key]?.ifEmpty { null }
    }

    private fun submit(key: String, bytes: ByteArray, cfg: ConfigData) {
        pool.execute {
            try {
                val started = System.currentTimeMillis()
                val text = post(bytes, cfg)
                val cost = System.currentTimeMillis() - started
                synchronized(this) {
                    done[key] = text
                    inFlight.remove(key)
                    failed.remove(key)
                    line = if (text.isEmpty()) "认了：这张图里没字 · ${cost}ms" else "认到 ${text.length} 字 · ${cost}ms"
                    dirty = true
                }
            } catch (t: Throwable) {
                // 失败不进 done：也可能只是代理刚起来 / App 刚被杀，冷却过后值得重试。
                // 这里绝不 rethrow —— 识图失败只是「这次没有文字可用」。
                synchronized(this) {
                    inFlight.remove(key)
                    failed[key] = System.currentTimeMillis()
                    line = "失败：${t.message}"
                    dirty = true
                }
            }
        }
    }

    /** 真正那一跳：带 token 把 JPEG 丢给 App 的回环端口，响应体就是文字。 */
    private fun post(bytes: ByteArray, cfg: ConfigData): String {
        val conn = URL(ProxyProtocol.ocrUrl(cfg.proxyPort)).openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 3_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "image/jpeg")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.proxyToken}")
            // 长度必须写死：代理那边是按 content-length 读 body 的（分块传输会读到 -1）
            conn.setFixedLengthStreamingMode(bytes.size)
            conn.outputStream.use { it.write(bytes) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty().trim()
            if (code !in 200..299) throw IllegalStateException("HTTP $code ${body.take(120)}")
            body
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    private companion object {
        /** 记住最近多少张图（按访问顺序 LRU）。聊天里同时在看的图没几张。 */
        const val DONE_MAX = 16

        /** 失败之后多久才值得再试 —— 避免「代理没起来」时每轮都重试、卡片永远卡在「正在认图…」。 */
        const val FAIL_COOLDOWN_MS = 60_000L
    }
}
