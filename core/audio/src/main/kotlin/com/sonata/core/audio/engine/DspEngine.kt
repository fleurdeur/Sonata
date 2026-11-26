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

    /** Returns true only when the effect is actually attached and usable. */
    fun attach(audioSessionId: Int): Boolean
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

    override fun attach(audioSessionId: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false
        release()
        runCatching {
            val config = DynamicsProcessing.Config.Builder(
                DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                CHANNELS,
                true, BANDS,   // pre-EQ
                true, MBC_BANDS, // multiband compressor
                true, BANDS,   // post-EQ (left flat: output gain belongs to the limiter)
                true,          // limiter
            ).build()
            dp = DynamicsProcessing(audioSessionId, 0, config)
        }.onFailure { dp = null }
        return dp != null
    }

    override fun apply(config: DspConfig) {
        val engine = dp ?: return
        runCatching {
            engine.setEnabled(config.enabled && !config.bypass)
            val limiter = DynamicsProcessing.Limiter(
                true,
                !config.bypass,
                0,
                config.limiter.lookaheadMs.toFloat().coerceIn(1f, 20f),
                config.limiter.releaseMs.toFloat().coerceIn(20f, 500f),
                10f,
                config.limiter.ceilingDb.coerceIn(-20f, 0f),
                (config.outputGainDb + config.limiter.inputGainDb).coerceIn(-15f, 15f),
            )
            engine.setLimiterAllChannelsTo(limiter)

            config.bands.take(BANDS).forEachIndexed { index, band ->
                val eqBand = DynamicsProcessing.EqBand(
                    band.enabled && !config.bypass,
                    band.frequency.coerceIn(20f, 20000f),
                    band.gainDb.coerceIn(-15f, 15f),
                )
                engine.setPreEqBandAllChannelsTo(index, eqBand)
                // Post-EQ stays flat. Applying the same curve twice doubled
                // every boost and made presets sound nothing like their graph.
                engine.setPostEqBandAllChannelsTo(
                    index,
                    DynamicsProcessing.EqBand(false, band.frequency.coerceIn(20f, 20000f), 0f),
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

    override fun attach(audioSessionId: Int): Boolean {
        release()
        runCatching {
            equalizer = Equalizer(0, audioSessionId)
            bassBoost = android.media.audiofx.BassBoost(0, audioSessionId)
            loudness = LoudnessEnhancer(audioSessionId)
        }
        return equalizer != null
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
            loudness?.setTargetGain((config.outputGainDb.coerceAtLeast(0f) * 100).toInt())
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
    @dagger.hilt.android.qualifiers.ApplicationContext context: android.content.Context,
) {
    private val prefs = context.getSharedPreferences("sonata_dsp", android.content.Context.MODE_PRIVATE)
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(loadConfig())
    val state: kotlinx.coroutines.flow.StateFlow<DspConfig> = _state

    private val _engineKind = kotlinx.coroutines.flow.MutableStateFlow(EngineKind.NONE)
    val engineKind: kotlinx.coroutines.flow.StateFlow<EngineKind> = _engineKind

    private var engine: DspEngine? = null

    fun attach(audioSessionId: Int) {
        var chosen: DspEngine = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            dynamicsProcessingEngine
        } else {
            fallbackEngine
        }
        val attached = chosen.attach(audioSessionId)
        if (!attached && chosen !== fallbackEngine) {
            chosen.release()
            chosen = fallbackEngine
            if (!chosen.attach(audioSessionId)) {
                chosen.release()
                engine = null
                _engineKind.value = EngineKind.NONE
                return
            }
        } else if (!attached) {
            chosen.release()
            engine = null
            _engineKind.value = EngineKind.NONE
            return
        }
        engine = chosen
        _engineKind.value = chosen.kind
        chosen.apply(_state.value)
    }

    fun update(transform: (DspConfig) -> DspConfig) {
        val next = transform(_state.value)
        _state.value = next
        saveConfig(next)
        engine?.apply(next)
    }

    fun applyPreset(preset: Preset) = update {
        it.copy(
            bands = preset.bands.ifEmpty { DspConfig.DEFAULT_BANDS },
            compressor = preset.compressor ?: it.compressor,
            limiter = preset.limiter ?: it.limiter,
            effects = preset.effects,
        )
    }

    fun release() {
        engine?.release()
        engine = null
        _engineKind.value = EngineKind.NONE
    }

    private fun loadConfig(): DspConfig = runCatching {
        val raw = prefs.getString(KEY_CONFIG, null) ?: return DspConfig()
        val o = org.json.JSONObject(raw)
        val bandsJson = o.optJSONArray("bands")
        val bands = if (bandsJson == null) {
            DspConfig.DEFAULT_BANDS
        } else {
            (0 until bandsJson.length()).mapNotNull { i ->
                bandsJson.optJSONObject(i)?.let { b ->
                    com.sonata.core.domain.model.ParametricBand(
                        index = b.optInt("index", i),
                        type = runCatching {
                            com.sonata.core.domain.model.BandFilterType.valueOf(b.optString("type", "PEAK"))
                        }.getOrDefault(com.sonata.core.domain.model.BandFilterType.PEAK),
                        frequency = b.optDouble("frequency", 1000.0).toFloat(),
                        gainDb = b.optDouble("gainDb", 0.0).toFloat(),
                        q = b.optDouble("q", 1.0).toFloat(),
                        enabled = b.optBoolean("enabled", true),
                    )
                }
            }.ifEmpty { DspConfig.DEFAULT_BANDS }
        }
        val c = o.optJSONObject("compressor")
        val l = o.optJSONObject("limiter")
        val effects = o.optJSONArray("effects")?.let { array ->
            (0 until array.length()).mapNotNull { i ->
                runCatching {
                    Preset.DspEffect.valueOf(array.optString(i))
                }.getOrNull()
            }.toSet()
        }.orEmpty()
        DspConfig(
            enabled = o.optBoolean("enabled", true),
            bypass = o.optBoolean("bypass", false),
            bands = bands,
            compressor = c?.let {
                com.sonata.core.domain.model.CompressorSettings(
                    crossovers = floats(c.optJSONArray("crossovers"), listOf(150f, 600f, 4000f)),
                    thresholds = floats(c.optJSONArray("thresholds"), listOf(-20f, -20f, -20f, -20f)),
                    ratios = floats(c.optJSONArray("ratios"), listOf(3f, 3f, 3f, 3f)),
                    attacks = ints(c.optJSONArray("attacks"), listOf(20, 20, 20, 20)),
                    releases = ints(c.optJSONArray("releases"), listOf(200, 200, 200, 200)),
                    knee = c.optDouble("knee", 6.0).toFloat(),
                )
            } ?: com.sonata.core.domain.model.CompressorSettings(),
            limiter = l?.let {
                com.sonata.core.domain.model.LimiterSettings(
                    ceilingDb = l.optDouble("ceilingDb", -1.0).toFloat(),
                    lookaheadMs = l.optInt("lookaheadMs", 5),
                    releaseMs = l.optInt("releaseMs", 120),
                    inputGainDb = l.optDouble("inputGainDb", 0.0).toFloat(),
                )
            } ?: com.sonata.core.domain.model.LimiterSettings(),
            outputGainDb = o.optDouble("outputGainDb", 0.0).toFloat(),
            loudnessNormalization = o.optBoolean("loudnessNormalization", true),
            effects = effects,
        )
    }.getOrDefault(DspConfig())

    private fun saveConfig(config: DspConfig) {
        runCatching {
            val o = org.json.JSONObject()
                .put("enabled", config.enabled)
                .put("bypass", config.bypass)
                .put("outputGainDb", config.outputGainDb.toDouble())
                .put("loudnessNormalization", config.loudnessNormalization)
            val bands = org.json.JSONArray()
            config.bands.forEach { band ->
                bands.put(
                    org.json.JSONObject()
                        .put("index", band.index)
                        .put("type", band.type.name)
                        .put("frequency", band.frequency.toDouble())
                        .put("gainDb", band.gainDb.toDouble())
                        .put("q", band.q.toDouble())
                        .put("enabled", band.enabled),
                )
            }
            o.put("bands", bands)
            o.put(
                "compressor",
                org.json.JSONObject()
                    .put("crossovers", org.json.JSONArray(config.compressor.crossovers))
                    .put("thresholds", org.json.JSONArray(config.compressor.thresholds))
                    .put("ratios", org.json.JSONArray(config.compressor.ratios))
                    .put("attacks", org.json.JSONArray(config.compressor.attacks))
                    .put("releases", org.json.JSONArray(config.compressor.releases))
                    .put("knee", config.compressor.knee.toDouble()),
            )
            o.put(
                "limiter",
                org.json.JSONObject()
                    .put("ceilingDb", config.limiter.ceilingDb.toDouble())
                    .put("lookaheadMs", config.limiter.lookaheadMs)
                    .put("releaseMs", config.limiter.releaseMs)
                    .put("inputGainDb", config.limiter.inputGainDb.toDouble()),
            )
            val effects = org.json.JSONArray()
            config.effects.forEach { effects.put(it.name) }
            o.put("effects", effects)
            prefs.edit().putString(KEY_CONFIG, o.toString()).apply()
        }
    }

    private fun floats(array: org.json.JSONArray?, fallback: List<Float>): List<Float> {
        if (array == null) return fallback
        return (0 until array.length()).map { array.optDouble(it).toFloat() }
    }

    private fun ints(array: org.json.JSONArray?, fallback: List<Int>): List<Int> {
        if (array == null) return fallback
        return (0 until array.length()).map { array.optInt(it) }
    }

    private companion object {
        const val KEY_CONFIG = "config"
    }
}
