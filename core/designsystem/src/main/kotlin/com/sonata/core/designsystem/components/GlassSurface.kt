package com.sonata.core.designsystem.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/**
 * Provided by the app shell with a single shared [HazeState]. The nav content
 * registers as the haze source; floating chrome (bottom bar, mini player,
 * sheets, onboarding) blurs that backdrop — never its own content.
 */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

private fun Color.luminance(): Float =
    0.2126f * red + 0.7152f * green + 0.0722f * blue

@Composable
private fun surfaceColor(alpha: Float): Color =
    MaterialTheme.colorScheme.surfaceContainer
        .copy(alpha = alpha)
        .compositeOver(MaterialTheme.colorScheme.background)

@Composable
private fun hairlineColor(): Color =
    MaterialTheme.colorScheme.outline.copy(
        alpha = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) 0.28f else 0.18f,
    )

/**
 * Crisp rounded surface for in-screen cards, chips and panels. Deliberately
 * NOT blurred: an earlier version applied a RenderEffect to the card's own
 * layer, smearing every piece of content inside it. This stays sharp and is
 * cheap to render (one opaque layer, no effects).
 */
@Composable
fun Modifier.glassSurface(
    blurDp: Int = 16,
    shape: Shape = RoundedCornerShape(28.dp),
    tintAlpha: Float = 0.62f,
): Modifier {
    val container = surfaceColor(0.96f)
    return clip(shape)
        .background(container, shape)
        .border(1.dp, hairlineColor(), shape)
}

/**
 * Liquid-glass floating chrome over the app backdrop (bottom bar, mini
 * player, sheets, onboarding). Real hardware-accelerated backdrop blur via
 * Haze on API 31+; an opaque tinted fallback keeps full readability below.
 * Content drawn ON TOP of this modifier is never blurred.
 *
 * [blurDp] and [tintAlpha] are retained for call-site compatibility.
 */
@Composable
fun Modifier.backdropGlass(
    blurDp: Int = 20,
    shape: Shape = RoundedCornerShape(28.dp),
    tintAlpha: Float = 0.62f,
): Modifier {
    val state = LocalHazeState.current
    val tint = surfaceColor(0.74f)
    val fallback = surfaceColor(0.97f)
    return if (state != null) {
        val style = remember(blurDp, tint, fallback) {
            HazeStyle(
                backgroundColor = fallback,
                tints = listOf(HazeTint(tint)),
                blurRadius = blurDp.dp,
                fallbackTint = HazeTint(fallback),
            )
        }
        clip(shape)
            .hazeEffect(state = state, style = style)
            .border(1.dp, hairlineColor(), shape)
    } else {
        clip(shape)
            .background(fallback, shape)
            .border(1.dp, hairlineColor(), shape)
    }
}
