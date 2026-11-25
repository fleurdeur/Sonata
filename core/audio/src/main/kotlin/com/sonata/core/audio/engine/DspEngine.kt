package com.sonata.core.audio.engine

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import com.sonata.core.domain.model.CompressorSettings
import com.sonata.core.domain.model.DspConfig
import com.sonata.core.domain.model.LimiterSettings
import com.sonata.core.domain.model.Preset
import javax.inject.Inject
import javax.inject.Singleton

/** The active sound engine, always displayed in the EQ screen. */
enum class EngineKind { DYNAMICS_PROCESSING, FRAMEWORK_FALLBACK, NONE }

/**
 * Native reimplementation of the Equalizer314 chain, attached to the ExoPlayer
 * audio session: per-channel parametric pre-EQ, multiband compressor with
 * adjustable crossovers, post-EQ, master limiter and loudness normalization.
 */
interface DspEngine {
    val kind: EngineKind
    fun attach(audioSessionId: Int)
    fun release()
    fun apply(config: DspConfig)
}

/**
 * Mapping helpers between Sonata's DspConfig and the DynamicsProcessing API,
 * plus the analytic response curve used to draw the EQ graph. Unit tested.
 */
object DspParameterMapper {

    /** Analytic bell response at [atFrequency] for one parametric band. */
    fun frequencyResponseDb(
        band: com.sonata.core.domain.model.ParametricBand,
        atFrequency: Float,
    ): Float {
        val ratio = atFrequency / band.frequency
        val q = band.q.coerceAtLeast(0.05f)
        val octaveDistance = (Math.log(ratio.toDouble()) / Math.log(2.0)).toFloat() / q
        val response = when (band.type) {
            com.sonata.core.domain.model.BandFilterType.PEAK ->
                band.gainDb / (1f + octaveDistance * octaveDistance)
            com.sonata.core.domain.model.BandFilterType.LOW_SHELF ->
                if (atFrequency < band.frequency * 0.5f) band.gainDb
                else band.gainDb / (1f + octaveDistance * octaveDistance)
            com.sonata.core.domain.model.BandFilterType.HIGH_SHELF ->
                if (atFrequency > band.frequency * 2f) band.gainDb
                else band.gainDb / (1f + octaveDistance * octaveDistance)
            com.sonata.core.domain.model.BandFilterType.LOW_PASS ->
                if (atFrequency > band.frequency) -24f * octaveDistance else 0f
            com.sonata.core.domain.model.BandFilterType.HIGH_PASS ->
                if (atFrequency < band.frequency) -24f * octaveDistance else 0f
            com.sonata.core.domain.model.BandFilterType.NOTCH ->
                (-band.gainDb.coerceAtLeast(24f)) / (1f + octaveDistance * octaveDistance)
        }
        return response.coerceIn(-15f, 15f)
    }

    fun totalResponseDb(
        bands: List<com.sonata.core.domain.model.ParametricBand>,
        atFrequency: Float,
    ): Float = bands.filter { it.enabled }
        .sumOf { frequencyResponseDb(it, atFrequency).toDouble() }
        .toFloat()

    fun compressGainDb(settings: CompressorSettings, inputDb: Float, bandIndex: Int): Float {
        val threshold = settings.thresholds.getOrElse(bandIndex) { -20f }
        val ratio = settings.ratios.getOrElse(bandIndex) { 3f }
        val over = inputDb - threshold
        if (over <= 0f) return 0f
        return -(over - over / ratio) * 0.6f // soft-knee approximation
    }

    fun limiterGainDb(settings: LimiterSettings, inputDb: Float): Float {
        val over = inputDb - settings.ceilingDb
        return if (over > 0f) -over else 0f
    }
}

private const val CHANNELS = 2
private const val BANDS = 8
private const val MBC_BANDS = 4

@Singleton
class DynamicsProcessingEngine @Inject constructor() : DspEngine {

    override val kind = EngineKind.DYNAMICS_PROCESSING

    private var dp: DynamicsProcessing? = null

    override fun attach(audioSessionId: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        release()
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                true, BANDS,   // pre-EQ
                true, MBC_BANDS, // multiband compressor
                true, BANDS,   // post-EQ
                true,          // limiter
            ).build()
            dp = DynamicsProcessing(audioSessionId, 0, config)
        }.onFailure { dp = null }
    }

    override fun apply(config: DspConfig) {
        val engine = dp ?: return
        runCatching {
            engine.setEnabled(config.enabled && !config.bypass)
            val limiter = DynamicsProcessing.Limiter(
                true,
                !config.bypass,
                0,
                1f,
                config.limiter.releaseMs.toFloat(),
                10f,
                config.limiter.ceilingDb,
                config.outputGainDb,
            )
            engine.setLimiterAllChannelsTo(limiter)

            config.bands.take(BANDS).forEachIndexed { index, band ->
                val eqBand = DynamicsProcessing.EqBand(
                    band.enabled && !config.bypass,
                    band.frequency.coerceIn(20f, 20000f),
                    band.gainDb.coerceIn(-15f, 15f),
                )
                engine.setPreEqBandAllChannelsTo(index, eqBand)
                engine.setPostEqBandAllChannelsTo(
                    index,
                    DynamicsProcessing.EqBand(
                        band.enabled && !config.bypass,
                        band.frequency.coerceIn(20f, 20000f),
                        config.outputGainDb.coerceIn(-15f, 15f),
                    ),
                )
            }
            val c = config.compressor
            val bandCutoffs = c.crossovers + listOf(20000f)
            (0 until MBC_BANDS).forEach { i ->
                val mbcBand = DynamicsProcessing.MbcBand(
                    true,
                    bandCutoffs.getOrElse(i) { 20000f }.coerceIn(20f, 20000f),
                    c.attacks.getOrElse(i) { 20 }.toFloat(),
                    c.releases.getOrElse(i) { 200 }.toFloat(),
                    c.ratios.getOrElse(i) { 3f },
                    c.thresholds.getOrElse(i) { -20f },
                    c.knee,
                    -80f, // noise gate threshold
                    1f,   // expander ratio
                    0f,   // pre gain
                    0f,   // post gain
                )
                engine.setMbcBandAllChannelsTo(i, mbcBand)
            }
        }
    }

    override fun release() {
        runCatching { dp?.release() }
        dp = null
    }
}

/** Fallback to framework Equalizer / BassBoost / LoudnessEnhancer on older devices. */
@Singleton
class FallbackEngine @Inject constructor() : DspEngine {

    override val kind = EngineKind.FRAMEWORK_FALLBACK

    private var equalizer: Equalizer? = null
    private var bassBoost: android.media.audiofx.BassBoost? = null
    private var loudness: LoudnessEnhancer? = null

    override fun attach(audioSessionId: Int) {
        release()
        runCatching {
            equalizer = Equalizer(0, audioSessionId)
            bassBoost = android.media.audiofx.BassBoost(0, audioSessionId)
            loudness = LoudnessEnhancer(audioSessionId)
        }
    }

    override fun apply(config: DspConfig) {
        val eq = equalizer ?: return
        runCatching {
            eq.setEnabled(config.enabled && !config.bypass)
            val bandCount = eq.numberOfBands
            val range = eq.bandLevelRange
            config.bands.forEachIndexed { i, band ->
                if (i < bandCount) {
                    val center = eq.getCenterFreq(i.toShort()) / 1000f
                    val deltaDb = DspParameterMapper.frequencyResponseDb(band, center)
                    val millibels = (deltaDb * 100).toInt().coerceIn(range[0].toInt(), range[1].toInt())
                    eq.setBandLevel(i.toShort(), millibels.toShort())
                }
            }
            val bassGain = config.bands.filter { it.frequency <= 120f }
                .maxOfOrNull { it.gainDb } ?: 0f
            bassBoost?.setEnabled(config.enabled && !config.bypass)
            bassBoost?.setStrength(bassGain.coerceIn(0f, 15f).toInt().toShort())
            loudness?.setEnabled(config.enabled && !config.bypass)
            if (config.outputGainDb > 0f) {
                loudness?.setTargetGain((config.outputGainDb * 100).toInt())
            }
        }
    }

    override fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { loudness?.release() }
        equalizer = null; bassBoost = null; loudness = null
    }
}

/** Picks the best available engine and mirrors every change to it. */
@Singleton
class DspManager @Inject constructor(
    private val dynamicsProcessingEngine: DynamicsProcessingEngine,
    private val fallbackEngine: FallbackEngine,
) {
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(DspConfig())
    val state: kotlinx.coroutines.flow.StateFlow<DspConfig> = _state

    private val _engineKind = kotlinx.coroutines.flow.MutableStateFlow(EngineKind.NONE)
    val engineKind: kotlinx.coroutines.flow.StateFlow<EngineKind> = _engineKind

    private var engine: DspEngine? = null

    fun attach(audioSessionId: Int) {
        val chosen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessingEngine
        } else {
            fallbackEngine
        }
        engine = chosen
        _engineKind.value = chosen.kind
        chosen.attach(audioSessionId)
        chosen.apply(_state.value)
    }

    fun update(transform: (DspConfig) -> DspConfig) {
        _state.value = transform(_state.value)
        engine?.apply(_state.value)
    }

    fun applyPreset(preset: Preset) = update {
        it.copy(
            bands = preset.bands.ifEmpty { DspConfig.DEFAULT_BANDS },
            effects = preset.effects,
        )
    }

    fun release() {
        engine?.release()
        engine = null
        _engineKind.value = EngineKind.NONE
    }
}
