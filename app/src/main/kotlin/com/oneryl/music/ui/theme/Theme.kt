package com.oneryl.music.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Onyx: black with a green accent, the theme the old app ended up with. */
object Onyx {
    val Green = Color(0xFF20C45F)
    val Black = Color(0xFF000000)
    val Surface = Color(0xFF121212)
    val SurfaceHigh = Color(0xFF1E1E1E)
    val SurfaceHighest = Color(0xFF2A2A2A)
    val Text = Color(0xFFFFFFFF)
    val TextMuted = Color(0xFFB3B3B3)
}

private val colors = darkColorScheme(
    primary = Onyx.Green,
    onPrimary = Onyx.Black,
    secondary = Onyx.Green,
    onSecondary = Onyx.Black,
    background = Onyx.Black,
    onBackground = Onyx.Text,
    surface = Onyx.Black,
    onSurface = Onyx.Text,
    surfaceVariant = Onyx.SurfaceHigh,
    onSurfaceVariant = Onyx.TextMuted,
    surfaceContainerLowest = Onyx.Black,
    surfaceContainerLow = Onyx.Surface,
    surfaceContainer = Onyx.Surface,
    surfaceContainerHigh = Onyx.SurfaceHigh,
    surfaceContainerHighest = Onyx.SurfaceHighest,
    secondaryContainer = Onyx.SurfaceHighest,
    onSecondaryContainer = Onyx.Text,
    outline = Onyx.SurfaceHighest,
    outlineVariant = Onyx.SurfaceHigh,
)

private val base = Typography()

private val typography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.4.sp),
)

@Composable
fun OnerylTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
