package com.sonata.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design-system tokens: spacing, radius, elevation, type — no raw values in features. */
@Immutable
data class Spacing(
    val xs: Int = 4,
    val sm: Int = 8,
    val md: Int = 16,
    val lg: Int = 24,
    val xl: Int = 32,
) {
    val xsDp get() = xs.dp; val smDp get() = sm.dp; val mdDp get() = md.dp
    val lgDp get() = lg.dp; val xlDp get() = xl.dp
}

@Immutable
data class Radius(
    val sm: Int = 12,
    val md: Int = 20,
    val lg: Int = 28,
    val pill: Int = 100,
) {
    val smShape get() = RoundedCornerShape(sm.dp)
    val mdShape get() = RoundedCornerShape(md.dp)
    val lgShape get() = RoundedCornerShape(lg.dp)
    val pillShape get() = RoundedCornerShape(percent = 50)
}

object Elevations {
    val card = 2.dp
    val floating = 12.dp
    val overlay = 24.dp
}

object Type {
    // Inter is the app font; drop Inter-Regular/Medium/SemiBold/Bold.ttf into res/font.
    val display = 32.sp
    val headline = 22.sp
    val title = 16.sp
    val body = 14.sp
    val label = 11.sp
    val code = 13.sp
}

object Dimensions {
    val bottomBarHeight = 64.dp
    val bottomBarInset = 16.dp
    val miniPlayerHeight = 64.dp
    val miniPlayerGap = 8.dp
    val importButton = 56.dp
    val soundRowHeight = 72.dp
    val soundCover = 56.dp
    val playlistCardCover = 200.dp
    val carouselCard = 160.dp
    val playButton = 72.dp
    val waveformHeight = 48.dp
    val trimWaveformHeight = 240.dp
    val handleWidth = 48.dp
    val nowPlayingCoverCorner = 32.dp
}
