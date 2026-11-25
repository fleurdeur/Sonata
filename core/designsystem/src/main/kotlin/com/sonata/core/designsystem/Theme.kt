package com.sonata.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.sonata.core.domain.repository.ThemeMode

val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalRadius = staticCompositionLocalOf { Radius() }

val AccentPresets = listOf(
    Color(0xFF6C5CE7), Color(0xFF00B8A9), Color(0xFFF65D9B),
    Color(0xFFF0932B), Color(0xFF3EBB78), Color(0xFF0AB6FF),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF5B4BC4),
    secondary = Color(0xFF3EBB78),
    tertiary = Color(0xFFF0932B),
    surface = Color(0xFFFBF9FF),
    surfaceContainer = Color(0xFFF2EFF9),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFB7AFFF),
    secondary = Color(0xFF6FE0B5),
    tertiary = Color(0xFFFFB866),
    surface = Color(0xFF141220),
    surfaceContainer = Color(0xFF1D1A2B),
)

private val PureBlackScheme = DarkScheme.copy(
    background = Color(0xFF000000),
    surface = Color(0xFF000000),
    surfaceContainer = Color(0xFF0A0A0A),
)

@Composable
fun SonataTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: Color = AccentPresets.first(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.PURE_BLACK ||
        (themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val base = when {
        themeMode == ThemeMode.PURE_BLACK -> PureBlackScheme
        dark -> DarkScheme
        else -> LightScheme
    }
    val context = LocalContext.current
    val scheme: ColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context))
            .let { if (themeMode == ThemeMode.PURE_BLACK) it.copy(background = Color.Black, surface = Color.Black) else it }
    } else {
        base.copy(primary = accent)
    }
    CompositionLocalProvider(LocalSpacing provides Spacing(), LocalRadius provides Radius()) {
        MaterialTheme(colorScheme = scheme, typography = SonataTypography(), content = content)
    }
}
