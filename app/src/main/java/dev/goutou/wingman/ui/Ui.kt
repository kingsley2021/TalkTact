package dev.goutou.wingman.ui

import android.app.ActivityManager
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.ModuleStatus
import dev.goutou.wingman.R
import dev.goutou.wingman.config.ConfigData
import dev.goutou.wingman.config.RemoteSync
import dev.goutou.wingman.config.ConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
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

/**
 * 玻璃扫光的相位（0..1 一轮）。
 *
 * 单独做成一个持有者、而不是塞进 [Backdrop]：动画推进时会让读到它的组件重组，
 * 而 [Backdrop] 是所有玻璃面板都在读的 —— 那样每帧都会重组整屏。
 * 装在这里，配合「只在 draw 阶段读取」的写法，推进相位只会重画，不会重组。
 *
 * 镜面扫光与折射共用同一个相位，所以它们是同一个时钟，不会各动各的。
 */
private class GlassPhase {
    var phase by mutableFloatStateOf(0f)
}

private val LocalGlassPhase = staticCompositionLocalOf { GlassPhase() }

/**
 * 当前生效的玻璃档位（折射 / 模糊 / 扫光 各开不开）。
 *
 * 由 [App] 按「设置里的选择 + 设备能力」算一次后提供；玻璃面板只读它，不各自去问系统 ——
 * 读 `isLowRamDevice` 有成本，而且设置页要能显示「自动 = 实际判成了哪一档」。
 */
val LocalGlassQuality = staticCompositionLocalOf { GlassQuality.HIGH }

/**
 * 低内存设备（`ActivityManager.isLowRamDevice`）。拿不到就按「不是」处理 ——
 * 宁可多开点效果，也别因为一个查询失败把所有人降级。
 */
internal fun isLowRamDevice(context: Context): Boolean = runCatching {
    (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
}.getOrDefault(false)

/**
 * 一支 AGSL 折射着色器（对应 liquidGL 的 refraction + aberration + bevel）。
 *
 * 只说一句实话：这是整次改造里**唯一没法在这儿验证**的部分 —— 着色器是运行时编译的，
 * 编译不过只会在真机上抛异常。所以全都包了 try/catch：任何一步出问题都返回 null，
 * 上层自动退回「模糊 / 只染色」，最坏情况只是没有折射，不会崩。
 *
 * 需要 API 33+（RuntimeShader）。minSdk 是 31，31/32 直接走降级。
 */
private const val AGSL_GLASS = """
uniform shader uBackdrop;
uniform float2 uSize;
uniform float uRadius;
uniform float uStrength;
uniform float uAberration;

half4 main(float2 fragCoord) {
    float2 c = uSize * 0.5;
    float2 p = fragCoord - c;
    float m = min(uSize.x, uSize.y);
    float r = min(uRadius, m * 0.5);
    // 圆角矩形 SDF：面板内部为负、边缘为 0、外部为正
    float2 q = abs(p) - c + float2(r, r);
    float sd = min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0, 0.0))) - r;
    // t: 边缘 0 → 内部 1
    float t = clamp(-sd / (m * 0.24), 0.0, 1.0);
    // 近似外法线；加个极小量避免正中心取到 0 向量
    float2 n = normalize(p + float2(0.0001, 0.0001));
    // 越靠边位移越大 —— 斜面折射就是这个平方衰减
    float k = (1.0 - t) * (1.0 - t) * uStrength * m;
    float2 uv = fragCoord - n * k;
    // 色散：R / B 往两边错开一点点采样
    float ab = uAberration * m * (1.0 - t);
    half3 col;
    col.r = uBackdrop.eval(uv + n * ab).r;
    col.g = uBackdrop.eval(uv).g;
    col.b = uBackdrop.eval(uv - n * ab).b;
    // 斜面上一圈冷白高光
    float rim = pow(1.0 - t, 5.0);
    col += half3(0.55, 0.60, 0.75) * rim * 0.30;
    return half4(col, 1.0);
}
"""

private class AgslGlass {
    private val shader: RuntimeShader? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try { RuntimeShader(AGSL_GLASS) } catch (t: Throwable) { null }
        } else null

    /** 尺寸 → RenderEffect 的缓存：尺寸不变的场景下不用反复创建。 */
    private val cache = HashMap<Long, RenderEffect?>()

    val available: Boolean get() = shader != null

    fun effect(w: Int, h: Int): RenderEffect? {
        val sh = shader ?: return null
        if (w <= 0 || h <= 0) return null
        return cache.getOrPut((w.toLong() shl 32) or h.toLong()) {
            try {
                sh.setFloatUniform("uSize", w.toFloat(), h.toFloat())
                sh.setFloatUniform("uRadius", min(w, h) * 0.30f)
                // 3.2% 短边：看得出边缘弯折，又不至于把背景图案揉变形
                sh.setFloatUniform("uStrength", 0.032f)
                sh.setFloatUniform("uAberration", 0.006f)
                RenderEffect.createRuntimeShaderEffect(sh, "uBackdrop")
            } catch (t: Throwable) { null }
        }
    }
}

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
 * 也能画出「面板背后那块背景」—— 后者再套一层折射 / 模糊就是真实的玻璃。
 *
 * 背景就是**一张图**：内置的默认图，或者用户在设置里自己选的那张（选了就以他的为准）。
 * 固定底色渐变永远压在最下面 —— 图还没解码完、或者某天换成一张很亮的图时，兜住文字对比度。
 */
private fun DrawScope.drawBackdropArt(b: Backdrop, w: Float, h: Float) {
    drawRect(
        brush = Brush.verticalGradient(listOf(b.bgTop, b.bgBottom), startY = 0f, endY = h),
        topLeft = Offset.Zero,
        size = Size(w, h),
    )

    val img = b.bitmap ?: return
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
}

/**
 * 玻璃扫光的节奏（镜面扫光与折射共用同一个相位）。
 *
 * 想彻底关掉这层动画（比如觉得费电）把 [GLASS_SWEEP_ENABLED] 改成 false 就行 ——
 * 玻璃的折射、高光、描边都还在，只是不再缓缓移动。
 */
private const val GLASS_SWEEP_ENABLED = true

/** 一道扫光走完整屏的时长。够慢才是「流动」，短了就成了「闪烁」。 */
private const val GLASS_SWEEP_PERIOD_MS = 26_000L

/** 最短重绘间隔：扫光变化很慢，没必要跑满 60fps，省一半的电。 */
private const val GLASS_SWEEP_MIN_FRAME_MS = 32L

/**
 * 背景层。
 *
 * 背景只有一张图（内置默认图，或者用户自选的那张），本身是静态的。这里唯一要做的事，
 * 是推进「玻璃扫光」的相位：
 * - 动画值只在**绘制阶段**读取（`drawBehind` 里的 `phase`），所以每帧只重画，
 *   **不会触发重组**，玻璃面板不会被拖着一起重组。
 * - 图还没解码完时只画底色渐变，不闪白也不闪黑。
 */
@Composable
fun BackgroundLayer(backdrop: Backdrop) {
    val root = LocalRootSize.current
    val phase = LocalGlassPhase.current
    val quality = LocalGlassQuality.current

    // 低档位连相位都不推：省掉每帧的动画开销
    if (GLASS_SWEEP_ENABLED && quality != GlassQuality.LOW) {
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                if (now - last < GLASS_SWEEP_MIN_FRAME_MS * 1_000_000L) continue
                last = now
                phase.phase = (now / 1_000_000L % GLASS_SWEEP_PERIOD_MS) / GLASS_SWEEP_PERIOD_MS.toFloat()
            }
        }
    }

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

/** 内置默认背景。跟着 APK 走，不要任何权限，也不会被系统清理掉。 */
private val DEFAULT_BG_RES = R.drawable.bg_app

private fun decodeRes(context: Context, resId: Int): ImageBitmap? = try {
    BitmapFactory.decodeResource(context.resources, resId)?.asImageBitmap()
} catch (t: Throwable) {
    null
}

@Composable
private fun decodeBackdrop(uriStr: String): ImageBitmap? {
    val context = LocalContext.current
    // 解码放到 IO 线程，避免切页时卡一下
    val bitmap by produceState<ImageBitmap?>(initialValue = null, uriStr) {
        value = withContext(Dispatchers.IO) {
            // 没选自定义背景 → 用内置那张。现在「默认背景」本身就是一张图了。
            if (uriStr.isBlank()) decodeRes(context, DEFAULT_BG_RES) else decodeImage(context, uriStr)
        }
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
    refract: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val palette = LocalPalette.current
    val backdrop = LocalBackdrop.current
    val root = LocalRootSize.current
    val phase = LocalGlassPhase.current
    val quality = LocalGlassQuality.current
    var pos by remember { mutableStateOf(Offset.Zero) }
    // 面板自身的像素尺寸：折射着色器要按它算 SDF 和折射位移。
    // 用布局实测值而不是 graphicsLayer 作用域里的 size —— 后者的类型随版本变，
    // 实测值既明确又一定是 px。
    var panel by remember { mutableStateOf(IntSize.Zero) }
    val top = tintTop ?: (palette.glassTopAlpha * glassAlpha)
    val bottom = tintBottom ?: (palette.glassBottomAlpha * glassAlpha)
    val sized = root.width > 0 && root.height > 0
    // 低档位连模糊都不做，只留半透明染色（弱机上 RenderEffect 的离屏模糊很贵）
    val hasImage = sized && backdrop.bitmap != null && backdrop.blur > 0.dp && quality != GlassQuality.LOW
    val glass = remember { AgslGlass() }
    // 折射只在「背景里有东西可折 + 设备撑得住」时才做
    val wantsRefraction = refract && hasImage && quality == GlassQuality.HIGH

    Box(
        modifier
            .onGloballyPositioned {
                pos = it.positionInRoot()
                panel = it.size
            }
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        if (wantsRefraction && glass.available) {
            // ①a 真折射：先把「这一格背后的背景」画进图层，再让 AGSL 按边缘斜面把它折一下
            Spacer(
                Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        renderEffect = glass.effect(panel.width, panel.height)?.asComposeRenderEffect()
                    }
                    .clipToBounds()
                    .drawBehind {
                        withTransform({ translate(-pos.x, -pos.y) }) {
                            drawBackdropArt(backdrop, root.width.toFloat(), root.height.toFloat())
                        }
                    },
            )
        } else if (hasImage) {
            // ①b 降级路径：API < 33 或着色器编译失败时，退回原来的模糊
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
        // ② 玻璃染色
        Spacer(
            Modifier.matchParentSize().background(
                Brush.verticalGradient(listOf(palette.glass.copy(alpha = top), palette.glass.copy(alpha = bottom))),
            ),
        )
        // ③ 镜面扫光（liquidGL 的 specular）：一道很淡的斜光缓缓扫过。
        //    相位由 BackgroundLayer 推，所以不需要额外动画驱动；只在 draw 阶段读，不触发重组。
        if (GLASS_SWEEP_ENABLED && quality != GlassQuality.LOW) {
            Spacer(
                Modifier.matchParentSize().drawBehind {
                    val p = (phase.phase * 2f) % 1f
                    val band = size.width * 0.30f
                    val cx = -band + (size.width + 2f * band) * p
                    drawRect(
                        brush = Brush.linearGradient(
                            0f to Color.Transparent,
                            0.5f to Color.White.copy(alpha = 0.085f * glassAlpha),
                            1f to Color.Transparent,
                            start = Offset(cx - band, -size.height * 0.35f),
                            end = Offset(cx + band, size.height * 1.35f),
                        ),
                        size = size,
                    )
                },
            )
        }
        // ④ 顶边一条极淡的高光
        Spacer(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(1.dp)
                .background(
                    Brush.horizontalGradient(
                        listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.40f * glassAlpha),
                            Color.Transparent,
                        ),
                    ),
                ),
        )
        content()
        // ⑤ 边缘：报错卡片之类沿用纯色描边；其余用「左上亮、右下暗」的斜面渐变当 bevel
        Spacer(
            Modifier.matchParentSize().border(
                width = 1.dp,
                brush = if (borderColor != null) {
                    SolidColor(borderColor)
                } else {
                    Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.60f * glassAlpha),
                        0.45f to Color.White.copy(alpha = 0.08f * glassAlpha),
                        1f to Color.White.copy(alpha = 0.34f * glassAlpha),
                        start = Offset.Zero,
                        end = Offset.Infinite,
                    )
                },
                shape = shape,
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
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 5.dp)
            // 一点点外投影：让卡片从彩色背景上「浮」起来，而不是贴上去
            .shadow(10.dp, RoundedCornerShape(22.dp), clip = false),
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
    // 「设置」这条线上现在有四层：设置(0) → 高级设置(1) → 诊断(2) / 拉取到的联系人(3)
    var settingsPage by remember { mutableIntStateOf(0) }
    // 只关心「影响外观」的那几个字段：玻璃透明度/模糊、背景
    var ui by remember { mutableStateOf(store.load()) }
    val health = healthOf(store)
    // 设备能力只问一次：isLowRamDevice 有成本，运行中也不会变
    val context = LocalContext.current
    val lowRam = remember { isLowRamDevice(context) }
    val glassQuality = remember(ui.glassQuality) {
        decideGlassQuality(ui.glassQuality, lowRam, Build.VERSION.SDK_INT)
    }

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

        val phase = remember { GlassPhase() }
        CompositionLocalProvider(
            LocalBackdrop provides backdrop,
            LocalRootSize provides rootSize,
            LocalGlassPhase provides phase,
            LocalGlassQuality provides glassQuality,
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
                        else -> when (settingsPage) {
                            1 -> AdvancedScreen(
                                store = store,
                                ui = ui,
                                onSaved = { ui = store.load() },
                                onOpenDiag = { settingsPage = 2 },
                                onOpenCandidates = { settingsPage = 3 },
                                onBack = { settingsPage = 0 },
                            )
                            // 诊断从「高级设置」里进，所以返回也应该回到高级设置
                            2 -> DiagScreen(store, ui.glassAlpha) { settingsPage = 1 }
                            // 「拉取到的联系人」也是从高级设置里进的（白名单卡），返回同理
                            3 -> ChatCandidatesScreen(
                                store = store,
                                ui = ui,
                                onSaved = { ui = store.load() },
                                onBack = { settingsPage = 1 },
                            )
                            else -> SettingsScreen(
                                store = store,
                                ui = ui,
                                onUi = { ui = it },
                                onOpenAdvanced = { settingsPage = 1 },
                            )
                        }
                    }
                }
                NavBar(
                    tab = tab,
                    health = health,
                    glassAlpha = ui.glassAlpha,
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
                ) { next ->
                    // 离开这一栏就把二级页收掉：切走再回来应该回到列表，而不是停在上次那个二级页；
                    // 点自己这一栏也当成「退出二级页」。
                    if (next != 3 || next == tab) roleOpen = null
                    if (next != 4 || next == tab) settingsPage = 0
                    tab = next
                }
            }
        }
    }
}

private val NavShape = RoundedCornerShape(30.dp)
private val NavIndicatorShape = RoundedCornerShape(20.dp)
private val NavBarHeight = 62.dp

/**
 * 底部导航。
 *
 * 和上一版的区别：
 * - 选中态从「每一格各自变色」改成**一整块会滑动的指示块**（弹簧跟随），切页时是连续的位移，
 *   而不是两块背景直接交换；
 * - 指示块用 `offset { }` 的 lambda 重载 → 只走布局阶段、不触发重组，滑动是满帧的；
 * - 图标随选中进度缩放 + 变色，文字跟着变重；
 * - 切页给一次轻触觉反馈，点起来「有实体感」；
 * - 徽标外加了一圈底色，从玻璃上浮出来，不再糊在背景里。
 */
@Composable
private fun NavBar(
    tab: Int,
    health: Health,
    glassAlpha: Float,
    modifier: Modifier = Modifier,
    onTab: (Int) -> Unit,
) {
    val palette = LocalPalette.current
    val haptics = LocalHapticFeedback.current
    val items = listOf(
        "运行状态" to Icons.Filled.Pets,
        "试一试" to Icons.Filled.PlayArrow,
        "军师" to Icons.Filled.Edit,
        "角色" to Icons.Filled.Person,
        "设置" to Icons.Filled.Settings,
    )

    var rowWidth by remember { mutableIntStateOf(0) }
    val cellPx = if (rowWidth > 0) rowWidth.toFloat() / items.size else 0f
    val slide = remember { Animatable(0f) }
    LaunchedEffect(tab, cellPx) {
        if (cellPx <= 0f) return@LaunchedEffect
        slide.animateTo(tab.toFloat(), spring(dampingRatio = 0.76f, stiffness = Spring.StiffnessMediumLow))
    }

    GlassSurface(
        shape = NavShape,
        glassAlpha = glassAlpha,
        modifier = modifier.shadow(18.dp, NavShape, clip = false),
    ) {
        Box(Modifier.fillMaxWidth().height(NavBarHeight)) {
            if (cellPx > 0f) {
                Box(
                    Modifier
                        .align(Alignment.CenterStart)
                        .offset { IntOffset((slide.value * cellPx).roundToInt(), 0) }
                        .width(with(LocalDensity.current) { cellPx.toDp() })
                        .fillMaxHeight()
                        .padding(horizontal = 3.dp, vertical = 5.dp)
                        .clip(NavIndicatorShape)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    palette.primary.copy(alpha = 0.30f),
                                    palette.primary.copy(alpha = 0.12f),
                                ),
                            ),
                        )
                        .border(1.dp, palette.primary.copy(alpha = 0.34f), NavIndicatorShape),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(NavBarHeight)
                    .onGloballyPositioned { rowWidth = it.size.width },
            ) {
                items.forEachIndexed { index, (label, icon) ->
                    NavItem(
                        label = label,
                        icon = icon,
                        selected = index == tab,
                        palette = palette,
                        badge = if (index == 0) health else null,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            if (index != tab) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            onTab(index)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    palette: Palette,
    badge: Health?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    // 0 → 1 的「选中进度」，图标缩放/变色/字重都跟着它走，切换才有连续感
    val p by animateFloatAsState(if (selected) 1f else 0f, tween(260))
    val interaction = remember { MutableInteractionSource() }
    val tint = lerp(palette.sub, palette.primary, p)

    Column(
        modifier
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            // 自带指示块了，所以不要涟漪 —— 否则会闪出一个和指示块不重合的方块
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .graphicsLayer {
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
                translationY = -1.5f * p
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Icon(
                icon,
                contentDescription = label,
                tint = tint,
                modifier = Modifier.size(21.dp + 2.dp * p),
            )
            if (badge != null) {
                val badgeColor = when (badge) {
                    Health.OK -> palette.ok
                    Health.WARN -> palette.warn
                    Health.BAD -> palette.bad
                }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-5).dp)
                        .size(14.dp)
                        .clip(CircleShape)
                        .background(palette.bgTop.copy(alpha = 0.90f))
                        .padding(1.5.dp)
                        .clip(CircleShape)
                        .background(badgeColor),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (badge == Health.BAD) Icons.Filled.Close else Icons.Filled.Check,
                        contentDescription = badge.label,
                        tint = Color.White,
                        modifier = Modifier.size(8.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            label,
            fontSize = 10.sp,
            letterSpacing = 0.2.sp,
            fontWeight = if (p > 0.5f) FontWeight.SemiBold else FontWeight.Normal,
            color = tint,
        )
    }
}
