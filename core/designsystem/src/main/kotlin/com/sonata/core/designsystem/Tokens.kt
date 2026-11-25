package com.sonata.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Design tokens: one source of truth for spacing, radii and fixed sizes. */
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
    val xs: Int = 8,
    val sm: Int = 12,
    val md: Int = 16,
    val lg: Int = 24,
    val xl: Int = 32,
    val pill: Int = 100,
) {
    val xsShape get() = RoundedCornerShape(xs.dp)
    val smShape get() = RoundedCornerShape(sm.dp)
    val mdShape get() = RoundedCornerShape(md.dp)
    val lgShape get() = RoundedCornerShape(lg.dp)
    val xlShape get() = RoundedCornerShape(xl.dp)
    val pillShape get() = RoundedCornerShape(percent = 50)
}

object Elevations {
    val card = 1.dp
    val floating = 8.dp
    val overlay = 16.dp
}

/** Text sizes used outside MaterialTheme (waveform labels, badges). */
object Type {
    val code = 13.sp
}

object Dimensions {
    val bottomBarHeight = 80.dp
    val bottomBarInset = 12.dp
    val miniPlayerHeight = 64.dp
    val miniPlayerGap = 8.dp
    val importButton = 56.dp
    val topBarHeight = 64.dp
    val soundRowHeight = 68.dp
    val soundCover = 52.dp
    val carouselCard = 152.dp
    val playButton = 72.dp
    val waveformHeight = 48.dp
    val trimWaveformHeight = 240.dp
    val handleWidth = 48.dp
    val nowPlayingCoverCorner = 24.dp
    /** Room for the floating bar, mini player and the FAB. */
    val listBottomPadding = 300.dp
}