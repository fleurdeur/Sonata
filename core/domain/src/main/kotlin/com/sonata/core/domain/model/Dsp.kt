package com.sonata.core.domain.model

enum class BandFilterType(val label: String) {
    PEAK("Peak"), LOW_SHELF("Low shelf"), HIGH_SHELF("High shelf"),
    LOW_PASS("Low pass"), HIGH_PASS("High pass"), NOTCH("Notch"),
}

data class ParametricBand(
    val index: Int,
    val type: BandFilterType = BandFilterType.PEAK,
    val frequency: Float = 1000f,
    val gainDb: Float = 0f,
    val q: Float = 1f,
    val enabled: Boolean = true,
)

enum class PresetCategory { GENRES, SITUATIONS, EFFECTS, MINE, SUGGESTED }

data class Preset(
    val id: Long = 0,
    val name: String,
    val category: PresetCategory = PresetCategory.MINE,
    val description: String = "",
    val bands: List<ParametricBand> = emptyList(),
    val compressor: CompressorSettings? = null,
    val limiter: LimiterSettings? = null,
    val effects: Set<DspEffect> = emptySet(),
    val isFavorite: Boolean = false,
) {
    enum class DspEffect { REVERB, CHORUS, STEREO_WIDTH, BASS_ENHANCER, SPATIAL, MONO_COMPAT }
}

data class CompressorSettings(
    val crossovers: List<Float> = listOf(150f, 600f, 4000f),
    val thresholds: List<Float> = listOf(-20f, -20f, -20f, -20f),
    val ratios: List<Float> = listOf(3f, 3f, 3f, 3f),
    val attacks: List<Int> = listOf(20, 20, 20, 20),
    val releases: List<Int> = listOf(200, 200, 200, 200),
    val knee: Float = 6f,
)

data class LimiterSettings(
    val ceilingDb: Float = -1f,
    val lookaheadMs: Int = 5,
    val releaseMs: Int = 120,
    val inputGainDb: Float = 0f,
)

data class DspConfig(
    val enabled: Boolean = true,
    val bypass: Boolean = false,
    val bands: List<ParametricBand> = DEFAULT_BANDS,
    val compressor: CompressorSettings = CompressorSettings(),
    val limiter: LimiterSettings = LimiterSettings(),
    val outputGainDb: Float = 0f,
    val loudnessNormalization: Boolean = true,
    val effects: Set<Preset.DspEffect> = emptySet(),
) {
    companion object {
        val DEFAULT_BANDS = listOf(
            ParametricBand(0, BandFilterType.PEAK, 60f, 0f, 1f),
            ParametricBand(1, BandFilterType.PEAK, 150f, 0f, 1f),
            ParametricBand(2, BandFilterType.PEAK, 400f, 0f, 1f),
            ParametricBand(3, BandFilterType.PEAK, 1000f, 0f, 1f),
            ParametricBand(4, BandFilterType.PEAK, 2500f, 0f, 1f),
            ParametricBand(5, BandFilterType.PEAK, 6000f, 0f, 1f),
            ParametricBand(6, BandFilterType.PEAK, 12000f, 0f, 1f),
            ParametricBand(7, BandFilterType.HIGH_SHELF, 16000f, 0f, 0.7f),
        )
    }
}
