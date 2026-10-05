package dev.goutou.wingman

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.goutou.wingman.config.ConfigStore
import dev.goutou.wingman.ui.AdvancedScreen
import dev.goutou.wingman.ui.SettingsScreen
import dev.goutou.wingman.ui.TrialScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 截图回归（JVM 上跑，不需要真机也不需要模拟器）。
 *
 * 目的不是像素级严丝合缝（那类对比一升依赖就狂飘），而是：
 * ① **界面还能渲染出来** —— 我在改 Screens.kt 时最容易犯的错就是某个 composable 直接崩；
 * ② 关键文案还在（改版时顺手把入口删了这种事，靠断言能立刻发现）；
 * ③ 每次跑完把图丢进 `build/screenshots/`，CI 作为 artifacts 上传，可以肉眼扫一眼。
 *
 * 渲染用的是 Robolectric 的 NATIVE 图形模式，所以 `captureToImage()` 能出真图。
 * 玻璃的模糊 / AGSL 折射在这里本来就不可用，代码里已按能力降级（见 ui/GlassQuality.kt），
 * 所以这些图是「没有玻璃特效的骨架图」—— 对回归来说够用。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {

    @get:Rule
    val rule = createComposeRule()

    private val store: ConfigStore
        get() = ConfigStore(ApplicationProvider.getApplicationContext<Context>())

    /** 把当前根节点画到 build/screenshots/<name>.png（CI 会作为 artifacts 上传）。 */
    private fun capture(name: String) {
        val dir = File("build/screenshots").apply { mkdirs() }
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(dir, "$name.png")).use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        assertTrue("截图没写出来：$name.png", File(dir, "$name.png").length() > 0)
    }

    @Test
    fun `试一试页能渲染`() {
        rule.setContent { TrialScreen(store, 0.92f) }
        rule.onNodeWithText("试一试").assertExists()
        rule.onNodeWithText("生成候选回复").assertExists()
        capture("trial")
    }

    @Test
    fun `设置页能渲染 且含玻璃效果三档`() {
        rule.setContent { SettingsScreen(store, store.load(), {}, {}) }
        rule.onNodeWithText("外观").assertExists()
        rule.onNodeWithText("玻璃效果", substring = true).assertExists()
        rule.onNodeWithText("自动").assertExists()
        capture("settings")
    }

    @Test
    fun `高级设置能渲染 且含生成模式开关`() {
        rule.setContent { AdvancedScreen(store, store.load(), {}, {}, {}) }
        rule.onNodeWithText("生成模式").assertExists()
        rule.onNodeWithText("接口地址", substring = true).assertExists()
        capture("advanced")
    }
}
