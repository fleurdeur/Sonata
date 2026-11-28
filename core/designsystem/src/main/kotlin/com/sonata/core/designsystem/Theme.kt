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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.sonata.core.domain.repository.ThemeMode

val LocalSpacing = staticCompositionLocalOf { Spacing() }
val LocalRadius = staticCompositionLocalOf { Radius() }

/**
 * One accent, two hand-checked tones. Every preset guarantees readable text on
 * both the accent fill and the surface, unlike a generated tint of a seed.
 */
data class AccentPreset(
    val name: String,
    val light: Color,
    val lightOn: Color,
    val dark: Color,
    val darkOn: Color,
)

val AccentPresets = listOf(
    AccentPreset("Indigo", Color(0xFF4B44C7), Color.White, Color(0xFFBFB9FF), Color(0xFF191540)),
    AccentPreset("Teal", Color(0xFF00695C), Color.White, Color(0xFF7FE0D4), Color(0xFF00231F)),
    AccentPreset("Rose", Color(0xFFB02A5B), Color.White, Color(0xFFFFB1C8), Color(0xFF3B001C)),
    AccentPreset("Amber", Color(0xFF8A5300), Color.White, Color(0xFFFFC470), Color(0xFF2B1700)),
    AccentPreset("Green", Color(0xFF2F6B3D), Color.White, Color(0xFFA3D9AF), Color(0xFF0A2A12)),
    AccentPreset("Blue", Color(0xFF0B5AA6), Color.White, Color(0xFFA8C8FF), Color(0xFF001B3D)),
)

private val LightNeutral = lightColorScheme(
    primary = AccentPresets.first().light,
    onPrimary = AccentPresets.first().lightOn,
    primaryContainer = Color(0xFFE4E1FF),
    onPrimaryContainer = Color(0xFF1B1740),
    secondary = Color(0xFF5D5B66),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6E2F0),
    onSecondaryContainer = Color(0xFF1A1822),
    tertiary = Color(0xFF6B5A45),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3E3CC),
    onTertiaryContainer = Color(0xFF241A0B),
    background = Color(0xFFFAF9F7),
    onBackground = Color(0xFF1B1A18),
    surface = Color(0xFFFAF9F7),
    onSurface = Color(0xFF1B1A18),
    surfaceVariant = Color(0xFFE6E2DC),
    onSurfaceVariant = Color(0xFF4B4843),
    outline = Color(0xFF78746D),
    outlineVariant = Color(0xFFCEC9C2),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF4F2EF),
    surfaceContainer = Color(0xFFEEECE8),
    surfaceContainerHigh = Color(0xFFE8E5E0),
    surfaceContainerHighest = Color(0xFFE2DED8),
    inverseSurface = Color(0xFF302E2B),
    inverseOnSurface = Color(0xFFF3F0EC),
    inversePrimary = AccentPresets.first().dark,
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
    scrim = Color.Black,
)

private val DarkNeutral = darkColorScheme(
    primary = AccentPresets.first().dark,
    onPrimary = AccentPresets.first().darkOn,
    primaryContainer = Color(0xFF322C63),
    onPrimaryContainer = Color(0xFFE4E1FF),
    secondary = Color(0xFFC8C4D0),
    onSecondary = Color(0xFF2F2D36),
    secondaryContainer = Color(0xFF45424E),
    onSecondaryContainer = Color(0xFFE6E1EE),
    tertiary = Color(0xFFDCC4A2),
    onTertiary = Color(0xFF3B2F1B),
    tertiaryContainer = Color(0xFF53452E),
    onTertiaryContainer = Color(0xFFF8E3C4),
    background = Color(0xFF131211),
    onBackground = Color(0xFFE9E6E1),
    surface = Color(0xFF131211),
    onSurface = Color(0xFFE9E6E1),
    surfaceVariant = Color(0xFF2A2825),
    onSurfaceVariant = Color(0xFFC6C2BB),
    outline = Color(0xFF918D86),
    outlineVariant = Color(0xFF45423D),
    surfaceContainerLowest = Color(0xFF0C0B0A),
    surfaceContainerLow = Color(0xFF191817),
    surfaceContainer = Color(0xFF1E1C1A),
    surfaceContainerHigh = Color(0xFF252320),
    surfaceContainerHighest = Color(0xFF2C2A27),
    inverseSurface = Color(0xFFE9E6E1),
    inverseOnSurface = Color(0xFF302E2B),
    inversePrimary = AccentPresets.first().light,
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color.Black,
)

/**
 * Applies an accent preset to the neutral scheme. Containers are mixed from
 * the accent and the surface instead of being copied from a seed, so contrast
 * stays predictable (>= 4.5:1 for body text on every container).
 */
fun colorSchemeFor(dark: Boolean, pureBlack: Boolean, accent: AccentPreset): ColorScheme {
    val base = if (dark) DarkNeutral else LightNeutral
    val surface = if (pureBlack) Color.Black else base.surface
    val background = if (pureBlack) Color.Black else base.background
    val container = if (pureBlack) Color(0xFF0A0A0A) else base.surfaceContainer
    val high = if (pureBlack) Color(0xFF141414) else base.surfaceContainerHigh
    val highest = if (pureBlack) Color(0xFF1C1C1C) else base.surfaceContainerHighest
    val primary = if (dark) accent.dark else accent.light
    val onPrimary = if (dark) accent.darkOn else accent.lightOn
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = lerp(container, primary, if (dark) 0.24f else 0.16f),
        onPrimaryContainer = lerp(primary, if (dark) Color.White else Color.Black, 0.55f),
        secondary = if (dark) lerp(primary, Color.Gray, 0.55f) else lerp(primary, Color.Gray, 0.45f),
        inversePrimary = if (dark) accent.light else accent.dark,
        background = background,
        surface = surface,
        onBackground = base.onBackground,
        onSurface = base.onSurface,
        surfaceContainer = container,
        surfaceContainerLow = if (pureBlack) Color(0xFF050505) else base.surfaceContainerLow,
        surfaceContainerHigh = high,
        surfaceContainerHighest = highest,
    )
}

@Composable
fun SonataTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    accent: Color = AccentPresets.first().light,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = themeMode == ThemeMode.DARK || themeMode == ThemeMode.PURE_BLACK ||
        (themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme())
    val preset = AccentPresets.minByOrNull { colorDistance(it, accent) } ?: AccentPresets.first()
    val context = LocalContext.current
    val scheme: ColorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val dynamic = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        // Material You supplies the neutrals, but the accent the user picked
        // still has to win. Pure black also has to reach every surface
        // container, not only background/surface.
        withAccent(dynamic, dark, themeMode == ThemeMode.PURE_BLACK, preset)
    } else {
        colorSchemeFor(dark, themeMode == ThemeMode.PURE_BLACK, preset)
    }
    CompositionLocalProvider(LocalSpacing provides Spacing(), LocalRadius provides Radius()) {
        MaterialTheme(colorScheme = scheme, typography = SonataTypography(), content = content)
    }
}

/**
 * Re-applies the chosen accent to any scheme (including a Material You one)
 * and, for pure black, flattens every tonal container to neutral black.
 */
private fun withAccent(
    base: ColorScheme,
    dark: Boolean,
    pureBlack: Boolean,
    accent: AccentPreset,
): ColorScheme {
    val primary = if (dark) accent.dark else accent.light
    val surface = if (pureBlack) Color.Black else base.surface
    val background = if (pureBlack) Color.Black else base.background
    val container = if (pureBlack) Color(0xFF0A0A0A) else base.surfaceContainer
    return base.copy(
        primary = primary,
        onPrimary = if (dark) accent.darkOn else accent.lightOn,
        primaryContainer = lerp(container, primary, if (dark) 0.24f else 0.16f),
        onPrimaryContainer = lerp(primary, if (dark) Color.White else Color.Black, 0.55f),
        secondary = if (dark) lerp(primary, Color.Gray, 0.55f) else lerp(primary, Color.Gray, 0.45f),
        inversePrimary = if (dark) accent.light else accent.dark,
        background = background,
        surface = surface,
        surfaceContainer = container,
        surfaceContainerLow = if (pureBlack) Color(0xFF050505) else base.surfaceContainerLow,
        surfaceContainerLowest = if (pureBlack) Color.Black else base.surfaceContainerLowest,
        surfaceContainerHigh = if (pureBlack) Color(0xFF141414) else base.surfaceContainerHigh,
        surfaceContainerHighest = if (pureBlack) Color(0xFF1C1C1C) else base.surfaceContainerHighest,
    )
}

/** Picks the stored accent preset; tolerates colours saved by older builds. */
private fun colorDistance(preset: AccentPreset, accent: Color): Float {
    val dr = preset.light.red - accent.red
    val dg = preset.light.green - accent.green
    val db = preset.light.blue - accent.blue
    val darkDr = preset.dark.red - accent.red
    val darkDg = preset.dark.green - accent.green
    val darkDb = preset.dark.blue - accent.blue
    return minOf(dr * dr + dg * dg + db * db, darkDr * darkDr + darkDg * darkDg + darkDb * darkDb)
}
