package dev.goutou.wingman.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.material.icons.filled.Person
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.ModuleStatus
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.RemoteSync
import dev.goutou.wingman.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 配色 + 玻璃参数。
 *
 * 「液态玻璃」= 面板后面压一块**真正被模糊过的背景**（Modifier.blur 走 RenderEffect）。
 * 因为 minSdk = 31（Android 12），这里不再需要低版本降级分支。
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

/**
 * 背景内容。图片在这里解码**一次**，所有玻璃面板共用，
 * 免得每个面板各自 produceState 重新解码一遍。
 */
data class Backdrop(
    val bitmap: ImageBitmap?,
    val dim: Float,
    val bgTop: Color,
    val bgBottom: Color,
    val primary: Color,
    /** 玻璃面板背后的模糊半径（dp） */
    val blur: Dp,
)

val LocalBackdrop = staticCompositionLocalOf {
    Backdrop(
        bitmap = null,
        dim = 0f,
        bgTop = Color(0xFFF3EEFC),
        bgBottom = Color(0xFFE3E1F3),
        primary = Color(0xFF7C3AED),
        blur = 24.dp,
    )
}

/** 根容器尺寸（px）。玻璃面板靠它把整屏背景平移对齐到自己身上。 */
val LocalRootSize = staticCompositionLocalOf { IntSize.Zero }

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

// ================= 背景绘制 =================

/**
 * 把「整屏背景」画一遍。
 *
 * 调用方负责先把坐标系平移到目标区域（面板）再裁剪，所以同一个函数既能画最底层背景，
 * 也能画出「面板背后那块背景」——后者再套一层 blur 就是真实的玻璃。
 */
private fun DrawScope.drawBackdropArt(b: Backdrop, w: Float, h: Float) {
    drawRect(
        brush = Brush.verticalGradient(listOf(b.bgTop, b.bgBottom), startY = 0f, endY = h),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )
    val img = b.bitmap
    if (img != null) {
        // 等价于 ContentScale.Crop：按「铺满」的比例缩放后居中
        val scale = max(w / img.width.toFloat(), h / img.height.toFloat())
        val dw = (img.width * scale).roundToInt()
        val dh = (img.height * scale).roundToInt()
        drawImage(
            image = img,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(img.width, img.height),
            dstOffset = IntOffset(((w - dw) / 2f).roundToInt(), ((h - dh) / 2f).roundToInt()),
            dstSize = IntSize(dw, dh),
        )
        drawRect(color = Color.Black.copy(alpha = b.dim), topLeft = Offset.Zero, size = Size(w, h))
    } else {
        // 默认背景：几团柔光，让玻璃面板背后有颜色层次可透
        softGlow(b.primary.copy(alpha = 0.32f), w * 0.10f, h * 0.09f, w * 0.44f, w, h)
        softGlow(Color(0xFF3BC8D8).copy(alpha = 0.26f), w * 0.85f, h * 0.20f, w * 0.38f, w, h)
        softGlow(Color(0xFFF08BC0).copy(alpha = 0.22f), w * 0.30f, h * 0.33f, w * 0.34f, w, h)
    }
}

private fun DrawScope.softGlow(color: Color, cx: Float, cy: Float, radius: Float, w: Float, h: Float) {
    drawRect(
        brush = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset(cx, cy), radius = radius),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )
}

@Composable
fun BackgroundLayer(backdrop: Backdrop) {
    val root = LocalRootSize.current
    Spacer(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val w = if (root.width > 0) root.width.toFloat() else size.width
                val h = if (root.height > 0) root.height.toFloat() else size.height
                drawBackdropArt(backdrop, w, h)
            },
    )
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

@Composable
private fun decodeBackdrop(uriStr: String): ImageBitmap? {
    val context = LocalContext.current
    // 解码放到 IO 线程，避免切页时卡一下
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uriStr) {
        value = if (uriStr.isBlank()) null else withContext(Dispatchers.IO) { decodeImage(context, uriStr) }
    }
    return bitmap
}

// ================= 玻璃组件 =================

/**
 * 真·液态玻璃。
 *
 * 面板内容分四层，从下往上：
 *   ① 被模糊的真实背景 —— 把整屏背景按 -面板位置 平移进来，裁剪成面板形状，再 blur
 *   ② 玻璃染色（半透明白/黑 + 顶部高光）
 *   ③ 面板内容
 *   ④ 1px 亮边
 *
 * 关键点：模糊层的尺寸只有**面板那么大**（不是整屏），所以代价和面板面积成正比。
 * 默认渐变背景没有细节可模糊，这时直接跳过 ①（省一次离屏渲染）。
 */
@Composable
fun GlassSurface(
    shape: Shape,
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    borderColor: Color? = null,
    tintTop: Float? = null,
    tintBottom: Float? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val backdrop = LocalBackdrop.current
    val root = LocalRootSize.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    val top = tintTop ?: (palette.glassTopAlpha * glassAlpha)
    val bottom = tintBottom ?: (palette.glassBottomAlpha * glassAlpha)
    val hasBackdrop = backdrop.bitmap != null && root.width > 0 && root.height > 0 && backdrop.blur > 0.dp

    Box(
        modifier
            .onGloballyPositioned { pos = it.positionInRoot() }
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (hasBackdrop) {
            Spacer(
                Modifier
                    .matchParentSize()
                    .blur(backdrop.blur)
                    .clipToBounds()
                    .drawBehind {
                        withTransform({ translate(-pos.x, -pos.y) }) {
                            drawBackdropArt(backdrop, root.width.toFloat(), root.height.toFloat())
                        }
                    },
            )
        }
        Spacer(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(palette.glass.copy(alpha = top), palette.glass.copy(alpha = bottom))),
            ),
        )
        content()
        Spacer(
            Modifier.matchParentSize().border(
                1.dp,
                borderColor ?: palette.glassBorder.copy(alpha = 0.55f * glassAlpha),
                shape,
            ),
        )
    }
}

/** 玻璃卡片：模糊背景 + 半透明底 + 顶部高光 + 亮边。 */
@Composable
fun GlassCard(
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    border: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    GlassSurface(
        shape = RoundedCornerShape(22.dp),
        glassAlpha = glassAlpha,
        borderColor = border,
        modifier = modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

/** 玻璃胶囊（筛选、档位选择都用它）。它一般落在卡片里，所以只做染色、不再重复模糊。 */
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

/** skillId → 给人看的名字。运行状态页和军师页共用，别再各写一份 if-else。 */
fun skillName(id: String): String = when (id) {
    "full" -> "狗头军师·满血版"
    "coder" -> "程序员搭子"
    "custom" -> "自定义 skill"
    else -> "原版狗头军师"
}

/** 顶栏那个小圆点/勾叉就靠它：模块激活 + Key + （心跳或手动确认）。 */
/**
 * 「模块到底生效了没」的判定，返回能说清缘由的说明；没生效返回 null。
 *
 * 为什么要三个信号：现代 API 之后，模块被注入哪些进程**严格跟随作用域勾选**。
 * legacy 时代框架会无条件把模块也注入它自己的 App 进程（那是 New XSharedPreferences
 * 机制的一部分），所以老的「自注入探针」一直成立；换成现代 API 后这条不再成立 ——
 * 用户只勾了微信时探针永远不亮，但模块其实工作得好好的。所以改成
 * 「任意一个信号成立即算生效」：
 *
 * 1. 自注入探针：用户显式把本模块也勾进作用域时成立（scope.list 里已放了本模块的包名）；
 * 2. service 通道已建立：框架认得本模块、并且正在跟它通信（见 RemoteSync）；
 * 3. 微信进程报过心跳：最硬的证据 —— 模块真的在微信里跑起来了。
 */
fun moduleActiveReason(store: ConfigStore): String? = when {
    ModuleStatus.isActive() -> "框架已把模块注入本应用"
    RemoteSync.bound -> "框架已连上本模块（service 通道已建立）"
    store.heartbeatAt() > 0 -> "微信进程里跑过本模块"
    else -> null
}

fun moduleActive(store: ConfigStore): Boolean = moduleActiveReason(store) != null

/** 顶栏那个小圆点/勾叉就靠它：模块激活 + Key + （心跳或手动确认）。 */
fun healthOf(store: ConfigStore): Health {
    val active = moduleActive(store)
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
    // 「角色」的二级页（打开了某个人）也放在这一层：切走 tab 再回来时能回到原位
    var roleOpen by remember { mutableStateOf<String?>(null) }
    // 只关心「影响外观」的那几个字段：玻璃透明度/模糊、背景
    var ui by remember { mutableStateOf(store.load()) }
    val health = healthOf(store)

    GoutouTheme {
        val palette = LocalPalette.current
        var rootSize by remember { mutableStateOf(IntSize.Zero) }
        val backdrop = Backdrop(
            bitmap = decodeBackdrop(ui.bgUri),
            dim = ui.bgDim,
            bgTop = palette.bgTop,
            bgBottom = palette.bgBottom,
            primary = palette.primary,
            blur = ui.glassBlur.dp,
        )

        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalRootSize provides rootSize,
        ) {
            var contentAlpha by remember { mutableStateOf(0f) }
            LaunchedEffect(tab) {
                contentAlpha = 0f
                animate(0f, 1f, animationSpec = tween(220)) { value, _ -> contentAlpha = value }
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { rootSize = it.size },
            ) {
                BackgroundLayer(backdrop)
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
                        3 -> RolesScreen(store, ui.glassAlpha, roleOpen) { roleOpen = it }
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
                ) { next ->
                    if (next != 3) roleOpen = null
                    tab = next
                }
            }
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
    val items = listOf(
        "运行状态" to Icons.Filled.Pets,
        "试一试" to Icons.Filled.PlayArrow,
        "军师" to Icons.Filled.Edit,
        "角色" to Icons.Filled.Person,
        "设置" to Icons.Filled.Settings,
    )
    GlassSurface(
        shape = RoundedCornerShape(28.dp),
        glassAlpha = glassAlpha,
        modifier = modifier,
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
