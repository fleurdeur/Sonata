package com.sonata.core.domain.model

enum class TransitionType(val label: String) {
    AUTO("Auto"), FADE("Fade"), RISE("Rise"), BLEND("Blend"), ECHO_OUT("Echo Out"),
    REVERB_TAIL("Reverb Tail"), FILTER_SWEEP("Filter Sweep"), TAPE_STOP("Tape Stop"),
    VINYL_BRAKE("Vinyl Brake"), SPIN_UP("Spin-Up"), BEAT_MATCH("Beat-Match"),
    CUT("Cut"), DROP_SWAP("Drop Swap"), WASH_OUT("Wash Out"),
}

enum class TransitionCurve { LINEAR, EXPONENTIAL, S_CURVE, LOGARITHMIC }

data class TransitionSpec(
    val id: Long = 0,
    val mixId: Long = 0,
    val trackIndex: Int = 0,
    val type: TransitionType = TransitionType.AUTO,
    val lengthMs: Long = 4000,
    val curve: TransitionCurve = TransitionCurve.S_CURVE,
    val intensity: Float = 0.7f,
)

data class Mix(
    val id: Long = 0,
    val playlistId: Long,
    val transitions: List<TransitionSpec> = emptyList(),
)
