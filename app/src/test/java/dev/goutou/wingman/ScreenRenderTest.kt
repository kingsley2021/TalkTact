package dev.goutou.wingman

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
 * 截图回归（JVM 上跑，不需要真机/模拟器）。
 *
 * 目的不是像素级严丝合缝（那种对比一升依赖就狂飘），而是：
 * ① **界面还能渲染出来** —— 盲改 Screens.kt 时最容易犯的错就是某个 composable 直接崩；
 * ② 关键文案还在（改版顺手把入口删了这种事，断言能立刻发现）；
 * ③ 每次跑完把图丢进 `build/screenshots/`，CI 作为 artifacts 上传，可以肉眼扫一眼。
 *
 * 两个 Robolectric 上的坑（都踩过了）：
 * - `captureToImage()` 底层是 PixelCopy，Robolectric 没有 —— 所以这里改成**直接把 decorView 画到
 *   Bitmap**（Roborazzi 干的就是这件事）；前提是 `@GraphicsMode(NATIVE)`。
 * - Compose 测试要启动的 ComponentActivity 必须由 `ui-test-manifest` 提供，而它得是
 *   **debugImplementation**（放进被测 APK 的 manifest），放 testImplementation 会找不到 Activity。
 *
 * 玻璃的模糊 / AGSL 折射在这个环境里本来就不生效（代码里已按能力降级，见 ui/GlassQuality.kt），
 * 所以这些图是「没有玻璃特效的骨架图」—— 对回归来说够用。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenRenderTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val store: ConfigStore
        get() = ConfigStore(ApplicationProvider.getApplicationContext<Context>())

    /** 把当前界面画成 build/screenshots/<name>.png（CI 会作为 artifacts 上传）。 */
    private fun capture(name: String) {
        val view: View = rule.activity.window.decorView
        // Robolectric 里视图可能还没被测过尺寸：先强制量一遍，否则画出来是空图
        if (view.width <= 0 || view.height <= 0) {
            val w = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
            val h = View.MeasureSpec.makeMeasureSpec(2160, View.MeasureSpec.EXACTLY)
            view.measure(w, h)
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
        }
        val bitmap = Bitmap.createBitmap(
            view.width.coerceAtLeast(1),
            view.height.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888,
        )
        view.draw(Canvas(bitmap))

        val dir = File("build/screenshots").apply { mkdirs() }
        val file = File(dir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertTrue("截图没写出来：$file", file.length() > 0)
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
        // 两个模式都在（用 pill 的文案断言；输入框的 label 不在语义树的文字里）
        rule.onNodeWithText("直通（一套接口）").assertExists()
        rule.onNodeWithText("模型分级").assertExists()
        capture("advanced")
    }
}
