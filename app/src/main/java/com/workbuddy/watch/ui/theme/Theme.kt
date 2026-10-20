package com.workbuddy.watch.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Shapes
import androidx.wear.compose.material3.Typography

/**
 * 参考图里所有卡片都是「全圆角药丸」，所以 [extraLarge] 直接给胶囊形；
 * 其余档位按 Wear 的量级递进。
 */
val WatchShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = CircleShape
)

/** 药丸容器形状：整屏宽度的条目 / 按钮统一用它，也就是胶囊形。 */
val PillShape = CircleShape

/**
 * 手表专用配色，对应参考图的「纯黑 + 灰药丸 + 淡紫蓝」。
 *
 * Wear Compose Material3 有自己的一套 ColorScheme（多出 primaryDim / surfaceContainerLow 等角色），
 * 与普通 Material3 的 ColorScheme **不是**同一个类，所以这里单独构造。
 */
val WatchColorScheme = ColorScheme(
    primary = WatchPalette.Accent,
    primaryDim = Color(0xFF8A98C6),
    primaryContainer = WatchPalette.PillHigh,
    onPrimary = WatchPalette.OnAccent,
    onPrimaryContainer = Color(0xFFE3E8FF),

    secondary = Color(0xFFC7CCDA),
    secondaryDim = Color(0xFF8D92A0),
    secondaryContainer = Color(0xFF333842),
    onSecondary = Color(0xFF12171F),
    onSecondaryContainer = Color(0xFFE0E4EC),

    tertiary = Color(0xFFA9CBE8),
    tertiaryDim = Color(0xFF7A9DB8),
    tertiaryContainer = Color(0xFF2A3D4E),
    onTertiary = Color(0xFF0C2434),
    onTertiaryContainer = Color(0xFFC6E6FF),

    surfaceContainerLow = WatchPalette.PillDim,
    surfaceContainer = WatchPalette.Pill,
    surfaceContainerHigh = Color(0xFF3A3A3A),
    onSurface = WatchPalette.OnPill,
    onSurfaceVariant = WatchPalette.OnPillVariant,

    outline = WatchPalette.Outline,
    outlineVariant = WatchPalette.OutlineVariant,
    background = WatchPalette.Background,
    onBackground = Color(0xFFFFFFFF),

    error = WatchPalette.Error,
    errorDim = WatchPalette.ErrorDim,
    errorContainer = WatchPalette.ErrorContainer,
    onError = WatchPalette.OnError,
    onErrorContainer = WatchPalette.OnErrorContainer
)

/**
 * 「宿主」通用 Material3 深色方案。
 *
 * 手表端仍有两处只能借通用 Material3 的组件：底部弹层（ModalBottomSheet）、
 * 以及需要真正唤起输入法的场景。这些组件读的是**通用** `MaterialTheme`，
 * 所以这里再造一份同色系方案嵌在 Wear 主题里，避免它们退回默认的浅紫底。
 */
private val HostColorScheme = darkColorScheme(
    primary = WatchPalette.Accent,
    onPrimary = WatchPalette.OnAccent,
    primaryContainer = WatchPalette.PillHigh,
    onPrimaryContainer = Color(0xFFE3E8FF),
    secondary = Color(0xFFC7CCDA),
    onSecondary = Color(0xFF12171F),
    secondaryContainer = Color(0xFF333842),
    onSecondaryContainer = Color(0xFFE0E4EC),
    tertiary = Color(0xFFA9CBE8),
    onTertiary = Color(0xFF0C2434),
    tertiaryContainer = Color(0xFF2A3D4E),
    onTertiaryContainer = Color(0xFFC6E6FF),
    background = WatchPalette.Background,
    onBackground = Color(0xFFFFFFFF),
    surface = WatchPalette.Pill,
    onSurface = WatchPalette.OnPill,
    surfaceVariant = WatchPalette.PillDim,
    onSurfaceVariant = WatchPalette.OnPillVariant,
    surfaceContainer = WatchPalette.Pill,
    surfaceContainerHigh = Color(0xFF3A3A3A),
    surfaceContainerLow = WatchPalette.PillDim,
    surfaceContainerLowest = Color(0xFF141414),
    outline = WatchPalette.Outline,
    outlineVariant = WatchPalette.OutlineVariant,
    error = WatchPalette.Error,
    onError = WatchPalette.OnError,
    errorContainer = WatchPalette.ErrorContainer,
    onErrorContainer = WatchPalette.OnErrorContainer
)

private val HostShapes = androidx.compose.material3.Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = CircleShape
)

/**
 * 全局主题：外层是 **Wear Compose Material3**（Wear 组件只认它），
 * 内层再套一层通用 Material3，供弹层与输入组件取色。
 */
@Composable
fun WorkBuddyWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = WatchColorScheme,
        typography = Typography(),
        shapes = WatchShapes
    ) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = HostColorScheme,
            shapes = HostShapes,
            typography = androidx.compose.material3.Typography(),
            content = content
        )
    }
}
