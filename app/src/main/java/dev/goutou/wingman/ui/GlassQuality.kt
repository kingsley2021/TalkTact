package dev.goutou.wingman.ui

import dev.goutou.wingman.config.GLASS_QUALITY_HIGH
import dev.goutou.wingman.config.GLASS_QUALITY_LOW

/**
 * 玻璃效果的档位。
 *
 * - [HIGH]：AGSL 折射 + 背景模糊 + 镜面扫光，全开。
 * - [MEDIUM]：模糊 + 扫光，不做折射（Android 13 以下没有 RuntimeShader）。
 * - [LOW]：只留半透明染色 —— 不模糊、不折射、不扫光。低内存 / 老旧设备用这档，
 *   既省电也避免 `Modifier.blur` 在弱机上把界面拖卡。
 */
enum class GlassQuality { HIGH, MEDIUM, LOW }

/**
 * 决定实际用哪一档。
 *
 * 刻意做成**纯函数**（不碰任何 Android API），所以每种组合都能直接单测：
 * - 用户手动选了「高 / 低」就照办 —— **手动永远优先于自动判断**（他觉得好看就是好看）；
 * - 「自动」时按设备能力：低内存设备直接降到底；没有 AGSL 的（API < 33）最多给到中级。
 */
fun decideGlassQuality(mode: String, lowRam: Boolean, sdkInt: Int): GlassQuality = when (mode) {
    GLASS_QUALITY_HIGH -> GlassQuality.HIGH
    GLASS_QUALITY_LOW -> GlassQuality.LOW
    else -> when {
        lowRam -> GlassQuality.LOW
        // 33 = Android 13（TIRAMISU）：AGSL 的 RuntimeShader 从这一版才有
        sdkInt < 33 -> GlassQuality.MEDIUM
        else -> GlassQuality.HIGH
    }
}

/** 给设置页用的一句话说明。 */
fun glassQualityLabel(q: GlassQuality): String = when (q) {
    GlassQuality.HIGH -> "高：折射 + 模糊 + 扫光"
    GlassQuality.MEDIUM -> "中：模糊 + 扫光（没有折射）"
    GlassQuality.LOW -> "低：只留半透明，最省电"
}
