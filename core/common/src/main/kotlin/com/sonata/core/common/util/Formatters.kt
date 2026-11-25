package com.sonata.core.common.util

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return if (m >= 60) String.format("%d:%02d:%02d", m / 60, m % 60, s)
    else String.format("%d:%02d", m, s)
}
