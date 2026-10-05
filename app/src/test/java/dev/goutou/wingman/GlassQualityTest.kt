package dev.goutou.wingman

import dev.goutou.wingman.config.GLASS_QUALITY_AUTO
import dev.goutou.wingman.config.GLASS_QUALITY_HIGH
import dev.goutou.wingman.config.GLASS_QUALITY_LOW
import dev.goutou.wingman.ui.GlassQuality
import dev.goutou.wingman.ui.decideGlassQuality
import org.junit.Assert.assertEquals
import org.junit.Test

/** 设备分级：玻璃效果自动降级的判定（纯函数，每种组合都钉住）。 */
class GlassQualityTest {

    @Test
    fun `手动选高档永远优先于自动判断`() {
        assertEquals(GlassQuality.HIGH, decideGlassQuality(GLASS_QUALITY_HIGH, lowRam = true, sdkInt = 31))
    }

    @Test
    fun `手动选低档也一样`() {
        assertEquals(GlassQuality.LOW, decideGlassQuality(GLASS_QUALITY_LOW, lowRam = false, sdkInt = 36))
    }

    @Test
    fun `自动 低内存设备直接降到底`() {
        assertEquals(GlassQuality.LOW, decideGlassQuality(GLASS_QUALITY_AUTO, lowRam = true, sdkInt = 36))
    }

    @Test
    fun `自动 Android13 以下没有 AGSL 最多给中级`() {
        assertEquals(GlassQuality.MEDIUM, decideGlassQuality(GLASS_QUALITY_AUTO, lowRam = false, sdkInt = 32))
        assertEquals(GlassQuality.MEDIUM, decideGlassQuality(GLASS_QUALITY_AUTO, lowRam = false, sdkInt = 31))
    }

    @Test
    fun `自动 正常设备全开`() {
        assertEquals(GlassQuality.HIGH, decideGlassQuality(GLASS_QUALITY_AUTO, lowRam = false, sdkInt = 33))
    }

    @Test
    fun `低内存优先于版本判断`() {
        // API 32 且低内存：应该是 LOW（低内存更严重），不是 MEDIUM
        assertEquals(GlassQuality.LOW, decideGlassQuality(GLASS_QUALITY_AUTO, lowRam = true, sdkInt = 32))
    }

    @Test
    fun `没存过的值当自动处理`() {
        assertEquals(GlassQuality.HIGH, decideGlassQuality("", lowRam = false, sdkInt = 34))
        assertEquals(GlassQuality.LOW, decideGlassQuality("whatever", lowRam = true, sdkInt = 34))
    }
}
