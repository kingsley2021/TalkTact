package dev.goutou.wingman.ocr

/**
 * 识别结果的「清洗」—— 纯函数，单测在 OcrTextTest。
 *
 * 为什么单独抽一段：ML Kit 还回来的是一棵「块 / 行 / 词」的树，直接拼会带一堆空行和缩进；
 * 而这段逻辑在真机上根本没法验证（改一次要发版，还得等聊天里正好出现一张图）。
 * 抽成纯函数之后，照着真实形状构造输入就能一遍遍跑。
 */

/** 一段识别结果的字符上限：图里的字可能很多，全塞给模型会把上下文挤爆。 */
const val OCR_MAX_CHARS = 300

/**
 * 把识别出来的一行行文字拼成一条消息的内容。
 *
 * - 去掉空行和每行首尾空白（截图里常有一行只有标点或数字的噪声行）
 * - **行间保留换行**：长图 / 聊天截图的排版信息对模型有用，压成一坨反而看不懂谁跟谁说话
 * - 超过 [max] 字就截断并加省略号 —— 宁可少给，也不要让一张图把整个上下文占满
 */
fun ocrText(lines: List<String>, max: Int = OCR_MAX_CHARS): String {
    val joined = lines.asSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
    if (max <= 0 || joined.length <= max) return joined
    return joined.take(max) + "…"
}
