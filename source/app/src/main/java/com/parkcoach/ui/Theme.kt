package com.parkcoach.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.TextUnit

/**
 * One surface, one ink, and a single colour for each meaning: green go, amber care, red stop,
 * blue information. Every colour has a dark and a light version; [light] switches the whole app.
 */
object Palette {
    var light by mutableStateOf(true)

    private fun pick(dark: Long, bright: Long): Color = Color(if (light) bright else dark)

    val Bg: Color get() = pick(0xFF08090B, 0xFFF5F5F7)
    val Panel: Color get() = pick(0xFF1C1C1E, 0xFFFFFFFF)
    val PanelHigh: Color get() = pick(0xFF2C2C2E, 0xFFE9E9EE)
    val Line: Color get() = pick(0xFF3A3A3C, 0xFFD1D1D6)
    val Text: Color get() = pick(0xFFF5F5F7, 0xFF1D1D1F)
    val Dim: Color get() = pick(0xFF9A9AA1, 0xFF6E6E73)
    val Go: Color get() = pick(0xFF30D158, 0xFF1FA84A)
    val Watch: Color get() = pick(0xFFFFD60A, 0xFFD98A00)
    val Slow: Color get() = pick(0xFFFF453A, 0xFFE0352B)
    val Accent: Color get() = pick(0xFF0A84FF, 0xFF0071E3)
    val Wait: Color get() = pick(0xFF2C2C2E, 0xFFE5E5EA)

    /** What is written on top of the ink colour, as on the primary button. */
    val OnBright: Color get() = pick(0xFF000000, 0xFFFFFFFF)

    /** A faint tint of the ink, for quiet buttons and unselected choices. */
    val Soft: Color get() = Text.copy(alpha = if (light) 0.07f else 0.10f)

    /** The see-through surface laid over the moving picture. */
    val Glass: Color get() = pick(0xD1141518, 0xE6FFFFFF)
}

private val Dark = darkColorScheme(
    primary = Color(0xFF30D158),
    onPrimary = Color(0xFF000000),
    secondary = Color(0xFF0A84FF),
    background = Color(0xFF08090B),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF1C1C1E),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFF9A9AA1),
    outline = Color(0xFF3A3A3C),
    error = Color(0xFFFF453A),
)

private val Bright = lightColorScheme(
    primary = Color(0xFF1FA84A),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFF0071E3),
    background = Color(0xFFF5F5F7),
    onBackground = Color(0xFF1D1D1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1D1D1F),
    surfaceVariant = Color(0xFFE5E5EA),
    onSurfaceVariant = Color(0xFF6E6E73),
    outline = Color(0xFFD1D1D6),
    error = Color(0xFFE0352B),
)

/**
 * Every text sets its own size where it is used. The stock styles carry a fixed line height meant for
 * small text, which would squeeze the large titles into their neighbours, so the line height is left
 * to follow the size.
 */
private val Type = Typography().let { stock ->
    stock.copy(
        bodyLarge = stock.bodyLarge.copy(lineHeight = TextUnit.Unspecified),
        labelLarge = stock.labelLarge.copy(lineHeight = TextUnit.Unspecified),
    )
}

@Composable
fun ParkCoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (Palette.light) Bright else Dark, typography = Type, content = content)
}
