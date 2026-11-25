package com.sonata

import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sonata.core.designsystem.LocalSpacing

private data class Step(val title: String, val message: String)

private val steps = listOf(
    Step(
        "Share a TikTok video",
        "Open the video, tap Share, then More, then Sonata. The post is read on this device and its audio is separated immediately.",
    ),
    Step(
        "The sound becomes a track",
        "Sonata fetches the full song when TikTok exposes it. Otherwise it loops the clip into a complete track, keeping the original tempo and reverb character.",
    ),
    Step(
        "Yours to keep",
        "Tracks are stored on your phone with tempo, key and a waveform. No account, no ads, no uploads.",
    ),
)

/** Three honest screens about what the app actually does; shown once. */
@Composable
fun OnboardingPager(onFinish: () -> Unit) {
    val pager = rememberPagerState { steps.size }
    val scope = rememberCoroutineScope()
    val spacing = LocalSpacing.current

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(spacing.lgDp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onFinish, modifier = Modifier.semantics { contentDescription = "Skip tutorial" }) {
                Text("Skip")
            }
        }

        HorizontalPager(
            state = pager,
            modifier = Modifier.weight(1f),
        ) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = spacing.xlDp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    "${page + 1} of ${steps.size}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(spacing.smDp))
                Text(steps[page].title, style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(spacing.mdDp))
                Text(
                    steps[page].message,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(bottom = spacing.mdDp),
            horizontalArrangement = Arrangement.Center,
        ) {
            steps.indices.forEach { index ->
                Box(
                    Modifier
                        .padding(horizontal = 3.dp)
                        .size(if (index == pager.currentPage) 8.dp else 6.dp)
                        .clip(CircleShape)
                        .background(
                            if (index == pager.currentPage) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.outlineVariant,
                        ),
                )
            }
        }

        Button(
            onClick = {
                if (pager.currentPage == steps.lastIndex) {
                    onFinish()
                } else {
                    // Advancing is a one-shot intent; the pager animates.
                    scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        ) {
            Text(if (pager.currentPage == steps.lastIndex) "Start using Sonata" else "Next")
        }
    }
}