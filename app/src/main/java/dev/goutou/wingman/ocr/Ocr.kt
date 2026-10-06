package dev.goutou.wingman.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * App 进程里的图片文字识别。
 *
 * 用的是 ML Kit 的**内置**中文模型（`com.google.mlkit:text-recognition-chinese`）：
 * 完全离线、不要 Play 服务、也不需要联网下模型 —— 代价只是 APK 里多一份模型和原生库。
 *
 * 为什么绕一圈回 App 里认：注入到微信里的那段代码**不能引 ML Kit**（模型 + 11MB 原生库太重，
 * 而且「注入侧只用系统 API + kotlin stdlib」是硬规矩 —— 引第三方库会和微信自带的同名类撞车）。
 * 所以图片走本地代理回到这里认（见 proxy/ProxyServer.kt 的 `/proxy/ocr`），只把**文字**还回去：
 * 图不落盘、不上传，认完就丢。
 *
 * 线程模型：代理那边是线程池里的一个工作线程，这里就**同步阻塞**等结果（最多 [DEFAULT_TIMEOUT_MS]）。
 * ML Kit 的完成回调默认落在主线程，而跑前台服务时主线程是活的，所以阻塞不会互相卡死。
 */
object Ocr {

    private const val DEFAULT_TIMEOUT_MS = 15_000L

    /** 同一个识别器只建一次（第一次调用会做一次初始化，几百毫秒）。 */
    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /**
     * 同一张图别重复认。
     *
     * 注入侧每一轮读屏都会把同一张图再送一次（微信重绘、列表滚动都会触发），
     * 而「同一张图」在字节层面是一模一样的 —— 拿 sha256 当键正好。
     * 只留最近 [CACHE_MAX] 张（按访问顺序 LRU）：聊天里同时在看的图没几张，够用又不占内存。
     */
    private const val CACHE_MAX = 32

    private val cache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > CACHE_MAX
    }

    /**
     * 认一张 JPEG。
     *
     * 返回识别到的文字；**空串是正常结果**（这张图里确实没有字，比如表情包）。
     * 识别失败**抛异常** —— 由代理那边包成 HTTP 错误码，微信侧看到非 200 就回落成图片占位。
     */
    @Synchronized
    fun recognize(image: ByteArray, timeoutMs: Long = DEFAULT_TIMEOUT_MS): String {
        if (image.isEmpty()) return ""
        val key = sha256(image)
        cache[key]?.let { return it }
        val text = runRecognize(image, timeoutMs)
        cache[key] = text
        return text
    }

    /** 缓存里现在有几张（诊断 / 单测用）。 */
    fun cachedCount(): Int = synchronized(cache) { cache.size }

    private fun runRecognize(image: ByteArray, timeoutMs: Long): String {
        // JPEG 的宽高：InputImage 其实不看它，但传 0 在个别版本上会判为非法输入，
        // 所以只解一次「边界」（不解像素）拿真实尺寸 —— 这一步几乎不花钱。
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(image, 0, image.size, bounds)
        val w = bounds.outWidth.coerceAtLeast(1)
        val h = bounds.outHeight.coerceAtLeast(1)

        val input = InputImage.fromByteArray(image, w, h, 0, InputImage.IMAGE_FORMAT_JPEG)
        val latch = CountDownLatch(1)
        var text: String? = null
        var failure: Throwable? = null

        recognizer.process(input)
            .addOnSuccessListener { result ->
                // 用整段文本再切行：块 / 行 / 词那棵树对「一条消息」来说太细了，行才是人读的单位
                text = ocrText(result.text.split('\n'))
                latch.countDown()
            }
            .addOnFailureListener { t ->
                failure = t
                latch.countDown()
            }

        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            throw IllegalStateException("识别超时（${timeoutMs}ms）")
        }
        failure?.let { throw IllegalStateException("识别失败：${it.message}", it) }
        return text.orEmpty()
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }
}
