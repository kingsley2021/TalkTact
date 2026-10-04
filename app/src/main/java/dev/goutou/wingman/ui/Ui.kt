package dev.goutou.wingman.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.ModuleStatus
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配色 + 玻璃参数。
 *
 * 「液态玻璃」的真实背景模糊需要 API 31+（Modifier.blur 走 RenderEffect），
 * 低版本上会自动退化成「不模糊但半透明」，观感仍然成立：
 * 背景层放柔光/图片 → 面板用半透明白 + 顶部高光渐变 + 1px 亮边。
 */
data class Palette(
    val primary: Color,
    val soft: Color,
    val text: Color,
    val sub: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val bgTop: Color,
    val bgBottom: Color,
    val glass: Color,
    val glassBorder: Color,
    val glassTopAlpha: Float,
    val glassBottomAlpha: Float,
    val dark: Boolean,
)

private val LightPalette = Palette(
    primary = Color(0xFF7C3AED),
    soft = Color(0xFFEDE7FA),
    text = Color(0xFF17161D),
    sub = Color(0xFF6B6878),
    ok = Color(0xFF1FA463),
    warn = Color(0xFFD9910A),
    bad = Color(0xFFD64545),
    bgTop = Color(0xFFF3EEFC),
    bgBottom = Color(0xFFE3E1F3),
    glass = Color(0xFFFFFFFF),
    glassBorder = Color(0xB3FFFFFF),
    glassTopAlpha = 0.62f,
    glassBottomAlpha = 0.42f,
    dark = false,
)

private val DarkPalette = Palette(
    primary = Color(0xFFB79CFF),
    soft = Color(0xFF322A4D),
    text = Color(0xFFF2EFFA),
    sub = Color(0xFFA9A4BA),
    ok = Color(0xFF4FD296),
    warn = Color(0xFFF0B95B),
    bad = Color(0xFFFF8A8A),
    bgTop = Color(0xFF100F16),
    bgBottom = Color(0xFF1B1926),
    glass = Color(0xFF2A2734),
    glassBorder = Color(0x33FFFFFF),
    glassTopAlpha = 0.72f,
    glassBottomAlpha = 0.52f,
    dark = true,
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun GoutouTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) {
                darkColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.glass)
            } else {
                lightColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.glass)
            },
            content = content,
        )
    }
}

// ================= 背景层 =================

@Composable
fun BackgroundLayer(bgUri: String, dim: Float) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    // 解码放到 IO 线程，避免切页时卡一下
    val bitmap by produceState<ImageBitmap?>(initialValue = null, bgUri) {
        value = if (bgUri.isBlank()) null else withContext(Dispatchers.IO) { decodeImage(context, bgUri) }
    }
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom))))
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().blur(26.dp),
                contentScale = ContentScale.Crop,
            )
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
        } else {
            // 默认背景：几团柔光，让玻璃面板背后有颜色层次可透
            Box(
                Modifier
                    .size(320.dp)
                    .offset(x = (-80).dp, y = 20.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(palette.primary.copy(alpha = 0.32f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                Modifier
                    .size(280.dp)
                    .offset(x = 200.dp, y = 430.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFF3BC8D8).copy(alpha = 0.26f), Color.Transparent),
                        ),
                    ),
            )
            Box(
                Modifier
                    .size(240.dp)
                    .offset(x = 40.dp, y = 700.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(Color(0xFFF08BC0).copy(alpha = 0.22f), Color.Transparent),
                        ),
                    ),
            )
        }
    }
}

private fun decodeImage(context: Context, uriStr: String): ImageBitmap? = try {
    val uri = Uri.parse(uriStr)
    context.contentResolver.openInputStream(uri)?.use { input ->
        val options = BitmapFactory.Options().apply { inSampleSize = 2 }
        BitmapFactory.decodeStream(input, null, options)?.asImageBitmap()
    }
} catch (t: Throwable) {
    null
}

// ================= 玻璃组件 =================

/** 玻璃卡片：半透明底 + 顶部高光 + 亮边。 */
@Composable
fun GlassCard(
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(22.dp)
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.glass.copy(alpha = palette.glassTopAlpha * glassAlpha),
                        palette.glass.copy(alpha = palette.glassBottomAlpha * glassAlpha),
                    ),
                ),
            )
            .border(1.dp, border ?: palette.glassBorder.copy(alpha = 0.55f * glassAlpha), shape),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** 玻璃胶囊（筛选、档位选择都用它）。 */
@Composable
fun GlassPill(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(18.dp)
    Box(
        modifier
            .clip(shape)
            .background(
                if (selected) {
                    Brush.verticalGradient(
                        listOf(palette.primary.copy(alpha = 0.92f), palette.primary.copy(alpha = 0.72f)),
                    )
                } else {
                    Brush.verticalGradient(
                        listOf(
                            palette.glass.copy(alpha = 0.30f),
                            palette.glass.copy(alpha = 0.16f),
                        ),
                    )
                },
            )
            .border(1.dp, if (selected) palette.primary else palette.glassBorder.copy(alpha = 0.45f), shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            fontSize = 13.sp,
            color = if (selected) Color.White else palette.text,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
        )
    }
}

@Composable
fun ScreenHeader(title: String, sub: String, actions: @Composable RowScope.() -> Unit = {}) {
    val palette = LocalPalette.current
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 16.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 25.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Text(sub, fontSize = 12.sp, color = palette.primary)
        }
        actions()
    }
}

@Composable
fun StatCell(big: String, small: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(palette.glass.copy(alpha = 0.22f))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(big, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Text(small, fontSize = 11.sp, color = palette.sub)
    }
}

// ================= 运行状态 =================

enum class Health(val label: String) { OK("就绪"), WARN("待确认"), BAD("有问题") }

/** 顶栏那个小圆点/勾叉就靠它：模块激活 + Key + （心跳或手动确认）。 */
fun healthOf(store: ConfigStore): Health {
    val active = ModuleStatus.isActive()
    val cfg = store.load()
    val heartbeat = store.heartbeatAt()
    val fresh = heartbeat > 0 && System.currentTimeMillis() - heartbeat < 6 * 3600_000L
    return when {
        !active || cfg.apiKey.isBlank() -> Health.BAD
        !fresh && !store.scopeConfirmed() -> Health.WARN
        else -> Health.OK
    }
}

fun appVersion(context: Context): String = try {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    "v${info.versionName}(${info.versionCode})"
} catch (t: Throwable) {
    "v?"
}

fun formatTime(ts: Long): String =
    if (ts <= 0) "从未" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

// ================= 主界面 =================

@Composable
fun App(store: ConfigStore) {
    var tab by remember { mutableIntStateOf(0) }
    // 只关心「影响外观」的那几个字段：玻璃透明度、背景
    var ui by remember { mutableStateOf(store.load()) }
    val health = healthOf(store)

    GoutouTheme {
        var contentAlpha by remember { mutableStateOf(0f) }
        LaunchedEffect(tab) {
            contentAlpha = 0f
            animate(0f, 1f, animationSpec = tween(220)) { value, _ -> contentAlpha = value }
        }
        Box(Modifier.fillMaxSize()) {
            BackgroundLayer(ui.bgUri, ui.bgDim)
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .graphicsLayer {
                        alpha = contentAlpha
                        translationY = (1f - contentAlpha) * 36f
                    },
            ) {
                when (tab) {
                    0 -> StatusScreen(store) { tab = 1 }
                    1 -> TrialScreen(store, ui.glassAlpha)
                    2 -> MentorScreen(store, ui.glassAlpha) { ui = store.load() }
                    else -> SettingsScreen(
                        store = store,
                        ui = ui,
                        onUi = { ui = it },
                        onSaved = { ui = store.load() },
                    )
                }
            }
            NavBar(
                tab = tab,
                health = health,
                glassAlpha = ui.glassAlpha,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
            ) { tab = it }
        }
    }
}

@Composable
private fun NavBar(
    tab: Int,
    health: Health,
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    onTab: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(28.dp)
    val items = listOf(
        "运行状态" to Icons.Filled.Pets,
        "试一试" to Icons.Filled.PlayArrow,
        "军师" to Icons.Filled.Edit,
        "设置" to Icons.Filled.Settings,
    )
    Box(
        modifier
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        palette.glass.copy(alpha = palette.glassTopAlpha * glassAlpha),
                        palette.glass.copy(alpha = palette.glassBottomAlpha * glassAlpha),
                    ),
                ),
            )
            .border(1.dp, palette.glassBorder.copy(alpha = 0.55f * glassAlpha), shape),
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, (label, icon) ->
                val selected = index == tab
                val scale by animateFloatAsState(if (selected) 1f else 0.94f, tween(200))
                Column(
                    Modifier
                        .weight(1f)
                        .graphicsLayer { scaleX = scale; scaleY = scale }
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (selected) {
                                Brush.verticalGradient(
                                    listOf(
                                        palette.primary.copy(alpha = 0.22f),
                                        palette.primary.copy(alpha = 0.10f),
                                    ),
                                )
                            } else {
                                Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent))
                            },
                        )
                        .clickable { onTab(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box {
                        Icon(
                            icon,
                            contentDescription = label,
                            tint = if (selected) palette.primary else palette.sub,
                            modifier = Modifier.size(22.dp),
                        )
                        if (index == 0) {
                            val badgeColor = when (health) {
                                Health.OK -> palette.ok
                                Health.WARN -> palette.warn
                                Health.BAD -> palette.bad
                            }
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 7.dp, y = (-5).dp)
                                    .size(13.dp)
                                    .clip(CircleShape)
                                    .background(badgeColor),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    if (health == Health.BAD) Icons.Filled.Close else Icons.Filled.Check,
                                    contentDescription = health.label,
                                    tint = Color.White,
                                    modifier = Modifier.size(9.dp),
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        label,
                        fontSize = 10.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) palette.primary else palette.sub,
                    )
                }
            }
        }
    }
}
