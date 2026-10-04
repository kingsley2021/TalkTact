package dev.goutou.wingman.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.goutou.wingman.config.ConfigStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 配色。
 *
 * 原版用 UI_SCALE = 0.5 把整个界面缩一半来「凑」设计稿 —— 那会导致
 * 字体、点击区、无障碍字号全都乱掉，而且没有深色模式（深色主题下是一块惨白卡片）。
 * 这里按 1x 正常设计，用 LocalPalette 提供深浅两套。
 */
data class Palette(
    val primary: Color,
    val soft: Color,
    val card: Color,
    val text: Color,
    val sub: Color,
    val ok: Color,
    val warn: Color,
    val bad: Color,
    val bgTop: Color,
    val bgBottom: Color,
)

private val LightPalette = Palette(
    primary = Color(0xFF7C3AED),
    soft = Color(0xFFEDE7FA),
    card = Color(0xFFFFFFFF),
    text = Color(0xFF1C1B22),
    sub = Color(0xFF6E6A7C),
    ok = Color(0xFF2FA566),
    warn = Color(0xFFE8A317),
    bad = Color(0xFFD64545),
    bgTop = Color(0xFFF3EEFC),
    bgBottom = Color(0xFFE6E3F3),
)

private val DarkPalette = Palette(
    primary = Color(0xFFB79CFF),
    soft = Color(0xFF2A2440),
    card = Color(0xFF1B1A22),
    text = Color(0xFFEDEAF5),
    sub = Color(0xFF9A96A8),
    ok = Color(0xFF5DD39E),
    warn = Color(0xFFF0B95B),
    bad = Color(0xFFFF8A8A),
    bgTop = Color(0xFF121119),
    bgBottom = Color(0xFF1C1A26),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

@Composable
fun GoutouTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val palette = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides palette) {
        MaterialTheme(
            colorScheme = if (dark) {
                darkColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.card)
            } else {
                lightColorScheme(primary = palette.primary, background = palette.bgTop, surface = palette.card)
            },
            content = content,
        )
    }
}

@Composable
fun App(store: ConfigStore) {
    var tab by remember { mutableIntStateOf(0) }
    GoutouTheme {
        val palette = LocalPalette.current
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(palette.bgTop, palette.bgBottom)))) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                when (tab) {
                    0 -> HomeScreen(store) { tab = 1 }
                    1 -> TrialScreen(store)
                    2 -> PromptScreen(store)
                    else -> SettingsScreen(store)
                }
            }
            NavBar(
                tab = tab,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(14.dp),
            ) { tab = it }
        }
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
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = palette.text)
            Text(sub, fontSize = 13.sp, color = palette.primary)
        }
        actions()
    }
}

@Composable
fun SectionCard(border: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    val palette = LocalPalette.current
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp),
        shape = RoundedCornerShape(20.dp),
        color = palette.card,
        border = border?.let { BorderStroke(1.dp, it) },
    ) {
        Column(Modifier.padding(16.dp), content = content)
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit) {
    val palette = LocalPalette.current
    Box(
        Modifier.clip(RoundedCornerShape(18.dp))
            .background(if (selected) palette.soft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 13.sp, color = if (selected) palette.primary else palette.sub, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun Stat(big: String, small: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(palette.soft).padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(big, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = palette.text)
        Text(small, fontSize = 11.sp, color = palette.sub)
    }
}

enum class Level(val label: String) {
    OK("通过"),
    WARN("待确认"),
    BAD("有问题"),
}

data class Check(val title: String, val desc: String, val level: Level, val onClick: (() -> Unit)? = null)

@Composable
fun CheckRow(check: Check) {
    val palette = LocalPalette.current
    val color = when (check.level) {
        Level.OK -> palette.ok
        Level.WARN -> palette.warn
        Level.BAD -> palette.bad
    }
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(enabled = check.onClick != null) { check.onClick?.invoke() },
        shape = RoundedCornerShape(16.dp),
        color = palette.card,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(check.title, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = palette.text)
                Text(check.desc, fontSize = 12.sp, color = palette.sub)
            }
            Text(check.level.label, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun NavBar(tab: Int, modifier: Modifier = Modifier, onTab: (Int) -> Unit) {
    val palette = LocalPalette.current
    val items = listOf(
        "军师" to Icons.Filled.Pets,
        "试一试" to Icons.Filled.PlayArrow,
        "提示词" to Icons.Filled.Edit,
        "设置" to Icons.Filled.Settings,
    )
    Surface(modifier.shadow(10.dp, RoundedCornerShape(26.dp)), shape = RoundedCornerShape(26.dp), color = palette.card) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            items.forEachIndexed { index, (label, icon) ->
                val selected = index == tab
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) palette.soft else Color.Transparent)
                        .clickable { onTab(index) }
                        .padding(vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(icon, null, tint = if (selected) palette.primary else palette.sub, modifier = Modifier.size(22.dp))
                    Text(label, fontSize = 11.sp, color = if (selected) palette.primary else palette.sub)
                }
            }
        }
    }
}

fun formatTime(ts: Long): String =
    if (ts <= 0) "从未" else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
