package com.dsbot.mobile.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

/**
 * DS Bot theme — mirrors the DSH desktop visual language (dark-first neutral
 * greys, a single blue accent, soft rounded surfaces) with mobile spacing.
 *
 * Palette derived from the DSH web UI: dark canvas #151517, elevated surfaces
 * #1C1C1E / #2C2C2E, primary text #DDE2E8, brand blue #658EFF, danger #EA2D2E,
 * success #00C4B3. Typography follows DSH's system font stack.
 */
object DsBotColors {
    // Dark (default)
    val DarkCanvas = Color(0xFF151517)
    val DarkSurface = Color(0xFF1C1C1E)
    val DarkSurfaceHigh = Color(0xFF2C2C2E)
    val DarkOutline = Color(0xFF3A3A3C)
    val DarkText = Color(0xFFDDE2E8)
    val DarkTextSecondary = Color(0xFF9AA3AD)

    // Light
    val LightCanvas = Color(0xFFFFFFFF)
    val LightSurface = Color(0xFFF4F4F5)
    val LightSurfaceHigh = Color(0xFFE9E9EB)
    val LightOutline = Color(0xFFD8D8DC)
    val LightText = Color(0xFF0F1115)
    val LightTextSecondary = Color(0xFF5F6670)

    // Shared accents
    val Accent = Color(0xFF658EFF)
    val AccentSoft = Color(0xFF1E2A4A)
    val Danger = Color(0xFFEA2D2E)
    val Success = Color(0xFF00C4B3)
    val Warning = Color(0xFFF5A623)

    /** Avatar background palette indexed by `BotProfile.colorIndex`. */
    val AvatarPalette = listOf(
        Color(0xFF3B5BDB),
        Color(0xFF0CA678),
        Color(0xFF9C36B5),
        Color(0xFFE8590C),
        Color(0xFF1098AD),
        Color(0xFFC2255C),
    )
}

private val DarkScheme = darkColorScheme(
    primary = DsBotColors.Accent,
    onPrimary = Color.White,
    primaryContainer = DsBotColors.AccentSoft,
    onPrimaryContainer = DsBotColors.DarkText,
    background = DsBotColors.DarkCanvas,
    onBackground = DsBotColors.DarkText,
    surface = DsBotColors.DarkSurface,
    onSurface = DsBotColors.DarkText,
    surfaceVariant = DsBotColors.DarkSurfaceHigh,
    onSurfaceVariant = DsBotColors.DarkTextSecondary,
    outline = DsBotColors.DarkOutline,
    error = DsBotColors.Danger,
    onError = Color.White,
)

private val LightScheme = lightColorScheme(
    primary = DsBotColors.Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EDFF),
    onPrimaryContainer = DsBotColors.LightText,
    background = DsBotColors.LightCanvas,
    onBackground = DsBotColors.LightText,
    surface = DsBotColors.LightCanvas,
    onSurface = DsBotColors.LightText,
    surfaceVariant = DsBotColors.LightSurface,
    onSurfaceVariant = DsBotColors.LightTextSecondary,
    outline = DsBotColors.LightOutline,
    error = DsBotColors.Danger,
    onError = Color.White,
)

/** DSH-style system font stack; Compose falls back to the platform default. */
val DsBotFontFamily: FontFamily = FontFamily.Default

private val DsBotTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = DsBotFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = DsBotFontFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = DsBotFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = DsBotFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = DsBotFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = DsBotFontFamily,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = DsBotFontFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp,
    ),
)

@Composable
fun DsBotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val scheme = if (darkTheme) DarkScheme else LightScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = scheme,
        typography = DsBotTypography,
        content = content,
    )
}
