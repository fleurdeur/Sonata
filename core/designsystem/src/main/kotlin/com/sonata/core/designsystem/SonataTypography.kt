package com.sonata.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/**
 * Complete Material 3 type scale on the system font. Sizes and line heights are
 * paired so nothing clips at 200% font scale; weights carry the hierarchy
 * instead of oversized display text.
 */
fun SonataTypography(): Typography {
    fun style(
        size: TextUnit,
        line: TextUnit,
        weight: FontWeight = FontWeight.Normal,
        spacing: TextUnit = 0.sp,
    ) = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size,
        lineHeight = line,
        letterSpacing = spacing,
    )

    return Typography(
        displayLarge = style(48.sp, 56.sp, FontWeight.SemiBold, (-0.5).sp),
        displayMedium = style(40.sp, 48.sp, FontWeight.SemiBold),
        displaySmall = style(34.sp, 42.sp, FontWeight.SemiBold),
        headlineLarge = style(30.sp, 38.sp, FontWeight.SemiBold),
        headlineMedium = style(26.sp, 34.sp, FontWeight.SemiBold),
        headlineSmall = style(22.sp, 30.sp, FontWeight.SemiBold),
        titleLarge = style(20.sp, 28.sp, FontWeight.SemiBold),
        titleMedium = style(16.sp, 24.sp, FontWeight.Medium, 0.1.sp),
        titleSmall = style(14.sp, 20.sp, FontWeight.Medium),
        bodyLarge = style(16.sp, 24.sp),
        bodyMedium = style(14.sp, 20.sp),
        bodySmall = style(12.sp, 16.sp),
        labelLarge = style(14.sp, 20.sp, FontWeight.Medium),
        labelMedium = style(12.sp, 16.sp, FontWeight.Medium, 0.2.sp),
        labelSmall = style(11.sp, 16.sp, FontWeight.Medium, 0.3.sp),
    )
}
