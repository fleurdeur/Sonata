package com.sonata.feature.settings.eq

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sonata.core.audio.engine.DspManager
import com.sonata.core.audio.engine.DspParameterMapper
import com.sonata.core.audio.engine.EngineKind
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.domain.model.BandFilterType
import com.sonata.core.domain.model.DspConfig
import com.sonata.core.domain.model.ParametricBand
import com.sonata.core.domain.model.Preset
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

private const val MIN_LOG = 1.301029995663981 // log10(20)
private const val MAX_LOG = 4.301029995663981 // log10(20000)

private fun logX(frequency: Float, width: Float): Float =
    ((log10(frequency.toDouble()) - MIN_LOG) / (MAX_LOG - MIN_LOG) * width).toFloat()

private fun inverseLogX(x: Float, width: Float): Float =
    10.0.pow(MIN_LOG + x / width * (MAX_LOG - MIN_LOG)).toFloat()

private fun formatHz(hz: Float): String = when {
    hz >= 10_000f -> (hz / 1000f).roundToInt().toString() + "k"
    hz >= 1000f -> String.format("%.1fk", hz / 1000f)
    else -> hz.roundToInt().toString()
}

private fun DspConfig.withBand(index: Int, transform: (ParametricBand) -> ParametricBand): DspConfig =
    copy(bands = bands.mapIndexed { i, band -> if (i == index) transform(band) else band })

/**
 * Equalizer314-style control surface. Every control is wired to the audio
 * engine: the curve itself is draggable, each node edits a real parametric
 * band, and the compressor runs four real multiband compressors through
 * DynamicsProcessing. Unsupported effect switches were deliberately removed
 * instead of pretending to do something.
 */
@Composable
fun EqScreen(
    dsp: DspManager,
    presets: List<Preset> = emptyList(),
    onApplyPreset: (Preset) -> Unit = {},
    onSavePreset: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val spacing = LocalSpacing.current
    val radius = LocalRadius.current
    val config by dsp.state.collectAsState()
    val engine by dsp.engineKind.collectAsState()
    val accent = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val surfaceColor = MaterialTheme.colorScheme.surface
    var selectedBand by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var showSave by remember { mutableStateOf(false) }
    var presetName by remember { mutableStateOf("") }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = spacing.mdDp, vertical = spacing.smDp),
        verticalArrangement = Arrangement.spacedBy(spacing.smDp),
    ) {
        Surface(
            shape = radius.mdShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = spacing.mdDp, vertical = spacing.smDp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "AUDIO ENGINE",
                        style = MaterialTheme.typography.labelSmall,
                        color = accent,
                        letterSpacing = MaterialTheme.typography.labelSmall.letterSpacing,
                    )
                    Text(
                        when (engine) {
                            EngineKind.DYNAMICS_PROCESSING -> "Dynamics Processing · 8 EQ · 4-band comp · limiter"
                            EngineKind.FRAMEWORK_FALLBACK -> "System equalizer · compression and limiter unavailable"
                            EngineKind.NONE -> "Start a song to attach the audio engine"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("BYPASS", style = MaterialTheme.typography.labelSmall)
                    Switch(
                        checked = config.bypass,
                        onCheckedChange = { on -> dsp.update { it.copy(bypass = on) } },
                        modifier = Modifier.semantics { contentDescription = "Bypass equalizer" },
                    )
                }
            }
        }

        if (presets.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "PRESETS",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { presetName = ""; showSave = true }) {
                    Icon(Icons.Filled.BookmarkAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("Save", Modifier.padding(start = 4.dp))
                }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(presets, key = { it.id }) { preset ->
                    FilterChip(
                        selected = false,
                        onClick = { onApplyPreset(preset) },
                        label = { Text(preset.name, maxLines = 1) },
                    )
                }
            }
        }

        // Frequency response curve, 20 Hz-20 kHz log scale, +/-15 dB.
        Surface(
            shape = radius.mdShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(Modifier.padding(vertical = spacing.smDp)) {
                val latestBands by rememberUpdatedState(config.bands)
                val latestConfig by rememberUpdatedState(config)
                val latestChange by rememberUpdatedState<(DspConfig) -> Unit> { dsp.update { it } }
                // DynamicsProcessing has no Q or filter-type knob: every band
                // is a fixed-bandwidth peaking filter. Draw that reality so the
                // graph never promises a shape the engine cannot produce.
                val graphBands = remember(config.bands, engine) {
                    if (engine == EngineKind.DYNAMICS_PROCESSING) {
                        config.bands.map { band ->
                            band.copy(
                                type = com.sonata.core.domain.model.BandFilterType.PEAK,
                                q = 1f,
                            )
                        }
                    } else {
                        config.bands
                    }
                }
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .padding(horizontal = 4.dp)
                        .pointerInput(Unit) {
                            fun nearest(x: Float): Int {
                                val bands = latestBands
                                return bands.indices.minByOrNull { i ->
                                    abs(logX(bands[i].frequency, size.width.toFloat()) - x)
                                } ?: 0
                            }
                            detectDragGestures(
                                onDragStart = { offset ->
                                    selectedBand = nearest(offset.x)
                                },
                                onDrag = { change, _ ->
                                    val bands = latestBands
                                    if (bands.isEmpty()) return@detectDragGestures
                                    val index = selectedBand.coerceIn(0, bands.lastIndex)
                                    val freq = inverseLogX(
                                        change.position.x.coerceIn(0f, size.width.toFloat()),
                                        size.width.toFloat(),
                                    ).coerceIn(20f, 20_000f)
                                    val half = size.height / 2f
                                    val gain = ((half - change.position.y) / half * 15f)
                                        .coerceIn(-15f, 15f)
                                    latestChange(
                                        latestConfig.copy(
                                            bands = latestBands.mapIndexed { i, band ->
                                                if (i == index) {
                                                    band.copy(frequency = freq, gainDb = gain)
                                                } else {
                                                    band
                                                }
                                            },
                                        ),
                                    )
                                },
                            )
                        }
                        .semantics { contentDescription = "Draggable EQ curve" },
                ) {
                    val mid = size.height / 2f
                    val step = size.height / 6f
                    for (i in -2..2) {
                        val y = mid + step * i
                        drawLine(
                            if (i == 0) grid.copy(alpha = 0.8f) else grid.copy(alpha = 0.35f),
                            Offset(0f, y),
                            Offset(size.width, y),
                            1.5f,
                        )
                    }
                    for (f in listOf(50f, 100f, 200f, 500f, 1000f, 2000f, 5000f, 10000f, 20000f)) {
                        val x = logX(f, size.width)
                        drawLine(grid.copy(alpha = 0.22f), Offset(x, 0f), Offset(x, size.height), 1f)
                    }

                    val points = ArrayList<Offset>(80)
                    var x = 0f
                    while (x <= size.width) {
                        val freq = inverseLogX(x, size.width)
                        val db = DspParameterMapper.totalResponseDb(graphBands, freq)
                        val y = mid - db / 15f * mid
                        points += Offset(x, y)
                        x += 3f
                    }
                    if (points.size > 1) {
                        val path = Path().apply {
                            moveTo(points.first().x, points.first().y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        val fill = Path().apply {
                            addPath(path)
                            lineTo(size.width, mid)
                            lineTo(0f, mid)
                            close()
                        }
                        drawPath(
                            fill,
                            Brush.verticalGradient(
                                listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.02f)),
                                endY = size.height,
                            ),
                        )
                        drawPath(path, accent, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 4f))
                    }
                    config.bands.forEachIndexed { index, band ->
                        val cx = logX(band.frequency, size.width)
                        val cy = mid - band.gainDb / 15f * mid
                        val selected = index == selectedBand
                        drawCircle(
                            color = if (band.enabled) accent else grid,
                            radius = if (selected) 11f else 7f,
                            center = Offset(cx, cy),
                        )
                        drawCircle(
                            color = surfaceColor,
                            radius = if (selected) 5f else 3f,
                            center = Offset(cx, cy),
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    listOf(20f, 100f, 1000f, 10000f, 20000f).forEach {
                        Text(formatHz(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Drag a node to shape the curve",
                Modifier.weight(1f),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(
                onClick = {
                    dsp.update {
                        it.copy(
                            bands = DspConfig.DEFAULT_BANDS,
                            compressor = com.sonata.core.domain.model.CompressorSettings(),
                            limiter = com.sonata.core.domain.model.LimiterSettings(),
                            outputGainDb = 0f,
                            bypass = false,
                        )
                    }
                },
            ) {
                Icon(Icons.Filled.RestartAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Text("Reset", Modifier.padding(start = 4.dp))
            }
        }

        // Compression and limiting only exist on DynamicsProcessing. Hiding the
        // tabs elsewhere is more honest than showing sliders that do nothing.
        val tabs = if (engine == EngineKind.DYNAMICS_PROCESSING) {
            listOf("EQ", "Compression", "Limiter", "Output")
        } else {
            listOf("EQ", "Output")
        }
        val currentTab = tab.coerceIn(0, tabs.lastIndex)
        TabRow(selectedTabIndex = currentTab) {
            tabs.forEachIndexed { i, label ->
                Tab(selected = currentTab == i, onClick = { tab = i }) {
                    Text(label, Modifier.padding(vertical = 12.dp, horizontal = 4.dp), maxLines = 1)
                }
            }
        }

        when (tabs[currentTab]) {
            "EQ" -> BandEditor(
                config = config,
                selectedBand = selectedBand.coerceIn(0, config.bands.lastIndex),
                onSelectBand = { selectedBand = it },
                onChange = { new -> dsp.update { new } },
                showShapeControls = engine == EngineKind.FRAMEWORK_FALLBACK,
            )
            "Compression" -> CompressorPanel(config, onChange = { new -> dsp.update { new } })
            "Limiter" -> LimiterPanel(config, onChange = { new -> dsp.update { new } })
            else -> OutputPanel(
                config = config,
                canAttenuate = engine == EngineKind.DYNAMICS_PROCESSING,
                onChange = { new -> dsp.update { new } },
            )
        }
    }

    if (showSave) {
        AlertDialog(
            onDismissRequest = { showSave = false },
            title = { Text("Save preset") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it.take(40) },
                    singleLine = true,
                    label = { Text("Preset name") },
                )
            },
            confirmButton = {
                TextButton(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        onSavePreset(presetName.trim())
                        showSave = false
                    },
                ) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showSave = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BandEditor(
    config: DspConfig,
    selectedBand: Int,
    onSelectBand: (Int) -> Unit,
    onChange: (DspConfig) -> Unit,
    showShapeControls: Boolean,
) {
    val band = config.bands[selectedBand]
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(config.bands.size) { i ->
                FilterChip(
                    selected = i == selectedBand,
                    onClick = { onSelectBand(i) },
                    label = { Text(formatHz(config.bands[i].frequency)) },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Band " + (selectedBand + 1), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
            Text("On", style = MaterialTheme.typography.labelSmall)
            Switch(
                checked = band.enabled,
                onCheckedChange = { enabled -> onChange(config.withBand(selectedBand) { it.copy(enabled = enabled) }) },
            )
        }
        if (showShapeControls) {
            Text("TYPE", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(BandFilterType.entries) { type ->
                    FilterChip(
                        selected = band.type == type,
                        onClick = { onChange(config.withBand(selectedBand) { it.copy(type = type) }) },
                        label = { Text(type.label) },
                    )
                }
            }
        }
        Text("Frequency  " + formatHz(band.frequency) + " Hz", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = logX(band.frequency.coerceIn(20f, 20_000f), 1f).coerceIn(0f, 1f),
            onValueChange = { norm ->
                onChange(config.withBand(selectedBand) { it.copy(frequency = inverseLogX(norm, 1f)) })
            },
            modifier = Modifier.semantics { contentDescription = "Band frequency" },
        )
        Text("Gain  " + "%+.1f".format(band.gainDb) + " dB", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = band.gainDb.coerceIn(-15f, 15f),
            onValueChange = { gain -> onChange(config.withBand(selectedBand) { it.copy(gainDb = gain) }) },
            valueRange = -15f..15f,
            modifier = Modifier.semantics { contentDescription = "Band gain" },
        )
        if (showShapeControls) {
            Text("Q  " + "%.2f".format(band.q), style = MaterialTheme.typography.bodySmall)
            Slider(
                value = band.q.coerceIn(0.2f, 8f),
                onValueChange = { q -> onChange(config.withBand(selectedBand) { it.copy(q = q) }) },
                valueRange = 0.2f..8f,
                modifier = Modifier.semantics { contentDescription = "Band Q" },
            )
        } else {
            Text(
                "This engine uses fixed-width peaking bands; Q and filter type are not adjustable.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(
            onClick = {
                onChange(config.withBand(selectedBand) { b -> b.copy(gainDb = 0f, q = 1f) })
            },
        ) { Text("Reset this band") }
    }
}

@Composable
private fun CompressorPanel(config: DspConfig, onChange: (DspConfig) -> Unit) {
    val c = config.compressor
    val labels = listOf("Low", "Low mid", "High mid", "High")
    var band by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(labels.size) { i ->
                FilterChip(selected = i == band, onClick = { band = i }, label = { Text(labels[i]) })
            }
        }
        Text(
            "Multiband compression splits the mix at the crossovers below and controls each band independently.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Threshold  " + "%.0f".format(c.thresholds.getOrElse(band) { -20f }) + " dB", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = c.thresholds.getOrElse(band) { -20f }.coerceIn(-60f, 0f),
            onValueChange = { v -> onChange(config.copy(compressor = c.copy(thresholds = c.thresholds.replaceAt(band, v)))) },
            valueRange = -60f..0f,
        )
        Text("Ratio  " + "%.1f".format(c.ratios.getOrElse(band) { 3f }) + ":1", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = c.ratios.getOrElse(band) { 3f }.coerceIn(1f, 20f),
            onValueChange = { v -> onChange(config.copy(compressor = c.copy(ratios = c.ratios.replaceAt(band, v)))) },
            valueRange = 1f..20f,
        )
        Text("Attack  " + c.attacks.getOrElse(band) { 20 } + " ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = c.attacks.getOrElse(band) { 20 }.toFloat().coerceIn(1f, 100f),
            onValueChange = { v -> onChange(config.copy(compressor = c.copy(attacks = c.attacks.replaceAt(band, v.toInt())))) },
            valueRange = 1f..100f,
        )
        Text("Release  " + c.releases.getOrElse(band) { 200 } + " ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = c.releases.getOrElse(band) { 200 }.toFloat().coerceIn(20f, 1000f),
            onValueChange = { v -> onChange(config.copy(compressor = c.copy(releases = c.releases.replaceAt(band, v.toInt())))) },
            valueRange = 20f..1000f,
        )
        Text("Knee  " + "%.1f".format(c.knee) + " dB", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = c.knee.coerceIn(0f, 12f),
            onValueChange = { v -> onChange(config.copy(compressor = c.copy(knee = v))) },
            valueRange = 0f..12f,
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))
        Text("CROSSOVERS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        c.crossovers.take(3).forEachIndexed { index, value ->
            val low = when (index) {
                0 -> 40f
                1 -> c.crossovers[0] + 20f
                else -> c.crossovers[1] + 20f
            }
            val high = when (index) {
                0 -> c.crossovers[1] - 20f
                1 -> c.crossovers[2] - 20f
                else -> 12_000f
            }
            Text("Split " + (index + 1) + ": " + value.roundToInt() + " Hz", style = MaterialTheme.typography.bodySmall)
            Slider(
                value = value.coerceIn(low, high.coerceAtLeast(low + 1f)),
                onValueChange = { v ->
                    val updated = c.crossovers.toMutableList()
                    updated[index] = v
                    onChange(config.copy(compressor = c.copy(crossovers = updated)))
                },
                valueRange = low..high.coerceAtLeast(low + 1f),
            )
        }
    }
}

private fun List<Float>.replaceAt(index: Int, value: Float): List<Float> =
    mapIndexed { i, old -> if (i == index) value else old }

private fun List<Int>.replaceAt(index: Int, value: Int): List<Int> =
    mapIndexed { i, old -> if (i == index) value else old }

@Composable
private fun LimiterPanel(config: DspConfig, onChange: (DspConfig) -> Unit) {
    val limiter = config.limiter
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Ceiling  " + "%.1f".format(limiter.ceilingDb) + " dB", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = limiter.ceilingDb.coerceIn(-12f, 0f),
            onValueChange = { v -> onChange(config.copy(limiter = limiter.copy(ceilingDb = v))) },
            valueRange = -12f..0f,
        )
        Text("Lookahead  " + limiter.lookaheadMs + " ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = limiter.lookaheadMs.toFloat().coerceIn(1f, 20f),
            onValueChange = { v -> onChange(config.copy(limiter = limiter.copy(lookaheadMs = v.toInt()))) },
            valueRange = 1f..20f,
        )
        Text("Release  " + limiter.releaseMs + " ms", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = limiter.releaseMs.toFloat().coerceIn(20f, 500f),
            onValueChange = { v -> onChange(config.copy(limiter = limiter.copy(releaseMs = v.toInt()))) },
            valueRange = 20f..500f,
        )
        Text("Input gain  " + "%+.1f".format(limiter.inputGainDb) + " dB", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = limiter.inputGainDb.coerceIn(-12f, 12f),
            onValueChange = { v -> onChange(config.copy(limiter = limiter.copy(inputGainDb = v))) },
            valueRange = -12f..12f,
        )
        Text(
            "A lookahead limiter catches peaks before they clip. Ceiling is the maximum output level.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OutputPanel(config: DspConfig, canAttenuate: Boolean, onChange: (DspConfig) -> Unit) {
    val low = if (canAttenuate) -12f else 0f
    val shown = config.outputGainDb.coerceIn(low, 12f)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Output gain  " + "%+.1f".format(shown) + " dB", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = shown,
            onValueChange = { v -> onChange(config.copy(outputGainDb = v)) },
            valueRange = low..12f,
            modifier = Modifier.semantics { contentDescription = "Output gain" },
        )
        if (!canAttenuate) {
            Text(
                "This engine can boost output only; negative gain needs DynamicsProcessing.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            "Output gain is applied after the limiter, so lowering it here never changes the compression amount. The bypass switch above is an instant A/B against the untouched signal.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(
            onClick = {
                onChange(
                    config.copy(
                        bands = DspConfig.DEFAULT_BANDS,
                        outputGainDb = 0f,
                        limiter = com.sonata.core.domain.model.LimiterSettings(),
                        compressor = com.sonata.core.domain.model.CompressorSettings(),
                    ),
                )
            },
        ) { Text("Reset output and dynamics") }
    }
}
