package com.sonata.core.designsystem.components

import android.os.Build
import android.graphics.RenderEffect
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp

/**
 * Liquid-glass surface: real background blur via RenderEffect on Android 12+,
 * tinted translucent fallback below. Blur radius in dp from the design tokens.
 */
@Composable
fun Modifier.glassSurface(
    blurDp: Int = 16,
    shape: Shape = RoundedCornerShape(28.dp),
    tintAlpha: Float = 0.62f,
): Modifier {
    val tint = Color(0x88201D33).compositeOver(Color(0x33101C28))
    val base = clip(shape)
        .background(
            MaterialTheme.colorScheme.surface.copy(alpha = tintAlpha).compositeOver(tint),
            shape,
        )
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        base.graphicsLayer {
            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
            renderEffect = RenderEffect
                .createBlurEffect(
                    blurDp * 2f,
                    blurDp * 2f,
                    android.graphics.Shader.TileMode.CLAMP,
                )
                .asComposeRenderEffect()
        }
    } else {
        base
    }
}
