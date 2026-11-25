package com.sonata.feature.settings.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sonata.core.audio.engine.DspManager
import com.sonata.core.audio.engine.DspParameterMapper
import com.sonata.core.audio.engine.EngineKind
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.domain.model.BandFilterType
import com.sonata.core.domain.model.DspConfig
import kotlin.math.log10
import kotlin.math.pow

private const val MIN_LOG = 1.301029995663981 // log10(20)
private const val MAX_LOG = 4.301029995663981 // log10(20000)

private fun logX(frequency: Float, width: Float): Float =
    ((log10(frequency.toDouble()) - MIN_LOG) / (MAX_LOG - MIN_LOG) * width).toFloat()

private fun inverseLogX(x: Float, width: Float): Float =
    10.0.pow(MIN_LOG + x / width * (MAX_LOG - MIN_LOG)).toFloat()

/**
 * EQ screen: engine badge + bypass, draggable frequency-response curve over a
 * live spectrum, per-band editor panel and the five tabs (Parametric EQ,
 * Compressor, Limiter, Effects, Output).
 */
@Composable
fun EqScreen(
    dsp: DspManager,
    onOpenPresets: () -> Unit,
    suggestedPreset: String? = null,
    onTrySuggested: () -> Unit = {},
) {
    val spacing = LocalSpacing.current
    val config by dsp.state.collectAsState()
    val engine by dsp.engineKind.collectAsState()
    var selectedBand by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(spacing.mdDp),
        verticalArrangement = Arrangement.spacedBy(spacing.smDp),
    ) {
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                "Engine: " + when (engine) {
                    EngineKind.DYNAMICS_PROCESSING -> "Dynamics Processing"
                    EngineKind.FRAMEWORK_FALLBACK -> "Fallback (Equalizer/BassBoost)"
                    EngineKind.NONE -> "Not attached"
                },
                style = MaterialTheme.typography.labelSmall,
            )
            Row {
                Text("Bypass", style = MaterialTheme.typography.labelSmall)
                Switch(
                    checked = config.bypass,
                    onCheckedChange = { on -> dsp.update { c -> c.copy(bypass = on) } },
                    modifier = Modifier.semantics { contentDescription = "Bypass equalizer" },
                )
                AssistChip(onClick = onOpenPresets, label = { Text("Presets") })
            }
        }

        suggestedPreset?.let {
            AssistChip(onClick = onTrySuggested, label = { Text("✦ Suggested: $it — Try") })
        }

        // Frequency response curve, 20 Hz–20 kHz log scale, ±15 dB.
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .semantics { contentDescription = "EQ curve canvas" },
        ) {
            for (f in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)) {
                val x = logX(f, size.width)
                drawLine(Color.Gray.copy(alpha = 0.25f), Offset(x, 0f), Offset(x, size.height), 1f)
            }
            val path = Path()
            var x = 0f
            while (x < size.width) {
                val freq = inverseLogX(x, size.width)
                val db = DspParameterMapper.totalResponseDb(config.bands, freq)
                val y = size.height / 2 - db / 15f * (size.height / 2)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 2
            }
            drawPath(path, Color(0xFF6C5CE7), style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
        }

        TabRow(selectedTabIndex = tab) {
            listOf("Parametric EQ", "Compressor", "Limiter", "Effects", "Output").forEachIndexed { i, label ->
                Tab(selected = tab == i, onClick = { tab = i }) { Text(label, Modifier.padding(8.dp)) }
            }
        }

        when (tab) {
            0 -> BandEditor(
                config = config,
                selectedBand = selectedBand,
                onSelectBand = { selectedBand = it },
                onChange = { new -> dsp.update { new }; new },
            )
            1 -> CompressorPanel(config, onChange = { new -> dsp.update { new }; new })
            2 -> LimiterPanel(config, onChange = { new -> dsp.update { new }; new })
            3 -> EffectsPanel(config, onChange = { new -> dsp.update { new }; new })
            4 -> OutputPanel(config, onChange = { new -> dsp.update { new }; new })
        }
    }
}

@Composable
private fun BandEditor(
    config: DspConfig,
    selectedBand: Int,
    onSelectBand: (Int) -> Unit,
    onChange: (DspConfig) -> Unit,
) {
    val band = config.bands[selectedBand]
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        config.bands.forEachIndexed { i, b ->
            FilterChip(
                selected = i == selectedBand,
                onClick = { onSelectBand(i) },
                label = { Text("${b.frequency.toInt()}Hz") },
            )
        }
    }
    Text("Type")
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        BandFilterType.entries.take(4).forEach { type ->
            FilterChip(
                selected = band.type == type,
                onClick = {
                    onChange(
                        config.copy(
                            bands = config.bands.mapIndexed { idx, b ->
                                if (idx == selectedBand) b.copy(type = type) else b
                            },
                        ),
                    )
                },
                label = { Text(type.label) },
            )
        }
    }
    Text("Frequency: ${band.frequency.toInt()} Hz")
    Slider(
        value = band.frequency.coerceIn(20f, 20000f),
        onValueChange = { f ->
            onChange(config.copy(bands = config.bands.mapIndexed { idx, b -> if (idx == selectedBand) b.copy(frequency = f) else b }))
        },
        valueRange = 20f..20000f,
        modifier = Modifier.semantics { contentDescription = "Band frequency" },
    )
    Text("Gain: ${"%+.1f".format(band.gainDb)} dB")
    Slider(
        value = band.gainDb,
        onValueChange = { g ->
            onChange(config.copy(bands = config.bands.mapIndexed { idx, b -> if (idx == selectedBand) b.copy(gainDb = g) else b }))
        },
        valueRange = -15f..15f,
        modifier = Modifier.semantics { contentDescription = "Band gain" },
    )
    Text("Q: ${"%.2f".format(band.q)}")
    Slider(
        value = band.q,
        onValueChange = { q ->
            onChange(config.copy(bands = config.bands.mapIndexed { idx, b -> if (idx == selectedBand) b.copy(q = q) else b }))
        },
        valueRange = 0.2f..8f,
        modifier = Modifier.semantics { contentDescription = "Band Q" },
    )
}

@Composable
private fun CompressorPanel(config: DspConfig, onChange: (DspConfig) -> Unit) {
    val c = config.compressor
    Text("Threshold: ${c.thresholds[0]} dB")
    Slider(c.thresholds[0], onValueChange = { v -> onChange(config.copy(compressor = c.copy(thresholds = listOf(v, v, v, v)))) }, valueRange = -60f..0f)
    Text("Ratio: ${c.ratios[0]}:1")
    Slider(c.ratios[0], onValueChange = { v -> onChange(config.copy(compressor = c.copy(ratios = listOf(v, v, v, v)))) }, valueRange = 1f..20f)
    Text("Attack: ${c.attacks[0]} ms")
    Slider(c.attacks[0].toFloat(), onValueChange = { v -> onChange(config.copy(compressor = c.copy(attacks = listOf(v.toInt(), v.toInt(), v.toInt(), v.toInt())))) }, valueRange = 1f..100f)
    Text("Release: ${c.releases[0]} ms")
    Slider(c.releases[0].toFloat(), onValueChange = { v -> onChange(config.copy(compressor = c.copy(releases = listOf(v.toInt(), v.toInt(), v.toInt(), v.toInt())))) }, valueRange = 20f..1000f)
    Text("Knee: ${c.knee} dB")
    Slider(c.knee, onValueChange = { v -> onChange(config.copy(compressor = c.copy(knee = v))) }, valueRange = 0f..12f)
}

@Composable
private fun LimiterPanel(config: DspConfig, onChange: (DspConfig) -> Unit) {
    val l = config.limiter
    Text("Ceiling: ${l.ceilingDb} dB")
    Slider(l.ceilingDb, onValueChange = { v -> onChange(config.copy(limiter = l.copy(ceilingDb = v))) }, valueRange = -12f..0f)
    Text("Lookahead: ${l.lookaheadMs} ms")
    Slider(l.lookaheadMs.toFloat(), onValueChange = { v -> onChange(config.copy(limiter = l.copy(lookaheadMs = v.toInt()))) }, valueRange = 1f..20f)
    Text("Release: ${l.releaseMs} ms")
    Slider(l.releaseMs.toFloat(), onValueChange = { v -> onChange(config.copy(limiter = l.copy(releaseMs = v.toInt()))) }, valueRange = 20f..500f)
}

@Composable
private fun EffectsPanel(config: DspConfig, onChange: (DspConfig) -> Unit) {
    val effects = listOf("Reverb", "Chorus", "Stereo width", "Bass enhancer", "Spatial", "Mono compat")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        com.sonata.core.domain.model.Preset.DspEffect.entries.forEachIndexed { i, effect ->
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(effects[i])
                Switch(
                    checked = effect in config.effects,
                    onCheckedChange = { on ->
                        onChange(config.copy(effects = if (on) config.effects + effect else config.effects - effect))
                    },
                )
            }
        }
    }
}

@Composable
private fun OutputPanel(config: DspConfig, onChange: (DspConfig) -> Unit) {
    Text("Output gain: ${"%+.1f".format(config.outputGainDb)} dB")
    Slider(config.outputGainDb, onValueChange = { v -> onChange(config.copy(outputGainDb = v)) }, valueRange = -12f..12f)
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        Text("Loudness normalization (EBU R128)")
        Switch(checked = config.loudnessNormalization, onCheckedChange = { on -> onChange(config.copy(loudnessNormalization = on)) })
    }
    OutlinedButton(onClick = {}) { Text("Save current as preset") }
}
