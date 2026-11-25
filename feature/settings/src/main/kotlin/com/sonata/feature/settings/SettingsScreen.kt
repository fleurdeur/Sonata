package com.sonata.feature.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.audio.engine.DspManager
import com.sonata.core.designsystem.AccentPresets
import com.sonata.core.designsystem.Dimensions
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.ScreenHeader
import com.sonata.core.designsystem.components.SectionTitle
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.domain.repository.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val soundRepository: SoundRepository,
    @ApplicationContext private val context: Context,
    val dsp: DspManager,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val sounds: StateFlow<List<Sound>> = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize

    init { refreshStorage() }

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch {
        settingsRepository.update(transform)
    }

    fun refreshStorage() = viewModelScope.launch {
        _cacheSize.value = withContext(Dispatchers.IO) { directorySize(context.cacheDir) }
    }

    fun clearCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        _cacheSize.value = 0L
    }

    fun replayIntroduction() = update { it.copy(onboardingDone = false) }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

/**
 * Settings contains only switches that change something you can observe:
 * the theme, the accent, the glass blur, the equalizer, the import cache and
 * the first-run tour. Everything else was removed rather than left as a label.
 */
@Composable
fun SettingsScreen(
    onOpenEq: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val sounds by viewModel.sounds.collectAsState()
    val cacheSize by viewModel.cacheSize.collectAsState()
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    var showCacheDialog by remember { mutableStateOf(false) }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.mdDp),
        contentPadding = PaddingValues(bottom = Dimensions.listBottomPadding),
    ) {
        item { ScreenHeader(title = "Settings") }

        item { SectionTitle("Appearance") }
        item {
            Column {
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                SingleChoiceSegmentedButtonRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.smDp),
                ) {
                    val modes = listOf(
                        ThemeMode.SYSTEM to "System",
                        ThemeMode.LIGHT to "Light",
                        ThemeMode.DARK to "Dark",
                        ThemeMode.PURE_BLACK to "Black",
                    )
                    modes.forEachIndexed { index, (mode, label) ->
                        SegmentedButton(
                            selected = settings.themeMode == mode,
                            onClick = { viewModel.update { it.copy(themeMode = mode) } },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size),
                        ) { Text(label) }
                    }
                }
            }
        }
        item {
            SettingToggle(
                title = "Material You colors",
                subtitle = "Use the wallpaper palette when the system supports it",
                checked = settings.dynamicColor,
                onCheckedChange = { value -> viewModel.update { it.copy(dynamicColor = value) } },
            )
        }
        item {
            Column(Modifier.padding(top = spacing.mdDp)) {
                Text("Accent", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (settings.dynamicColor) "Turn off Material You to pick an accent"
                    else "Used by buttons, sliders and the mini player",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = spacing.smDp),
                    horizontalArrangement = Arrangement.spacedBy(spacing.smDp),
                ) {
                    AccentPresets.forEach { preset ->
                        val selected = settings.accent == preset.light.value.toInt()
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(preset.light)
                                .then(
                                    if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                    else Modifier,
                                )
                                .clickable(enabled = !settings.dynamicColor) {
                                    viewModel.update { it.copy(accent = preset.light.value.toInt()) }
                                }
                                .semantics { contentDescription = "${preset.name} accent" },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = preset.lightOn)
                        }
                    }
                }
            }
        }
        item {
            Column(Modifier.padding(top = spacing.mdDp)) {
                Text("Glass blur", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${settings.blurStrength} dp behind the player and the bars",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Slider(
                    value = settings.blurStrength.toFloat(),
                    onValueChange = { value -> viewModel.update { it.copy(blurStrength = value.toInt()) } },
                    valueRange = 0f..32f,
                    steps = 7,
                )
            }
        }

        item { SectionTitle("Audio") }
        item {
            SettingsRow(
                title = "Equalizer",
                subtitle = "10-band parametric EQ with presets",
                onClick = onOpenEq,
            )
        }

        item { SectionTitle("Storage") }
        item {
            SettingsRow(
                title = "Sounds on this device",
                subtitle = "${sounds.size} files",
                onClick = {},
                showChevron = false,
            )
        }
        item {
            SettingsRow(
                title = "Import cache",
                subtitle = if (cacheSize > 0) "${formatBytes(cacheSize)} of temporary files" else "Empty",
                onClick = { showCacheDialog = true },
            )
        }

        item { SectionTitle("About") }
        item {
            SettingsRow(
                title = "Sonata",
                subtitle = "Version ${versionName(context)} · offline, no account",
                onClick = {},
                showChevron = false,
            )
        }
        item {
            SettingsRow(
                title = "Show the introduction again",
                subtitle = "Share sheet, imports and effects",
                onClick = viewModel::replayIntroduction,
            )
        }
        item {
            Text(
                "Sonata keeps every sound on this device. It has no accounts, ads, analytics or network upload of your library.",
                Modifier.padding(top = spacing.mdDp, bottom = spacing.lgDp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            title = { Text("Clear import cache?") },
            text = { Text("Temporary downloads and decoded files are removed. Your sounds stay in the library.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearCache()
                    showCacheDialog = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showCacheDialog = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun SettingsRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showChevron: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (showChevron) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%d KB".format(bytes / 1_000)
    else -> "$bytes B"
}

private fun versionName(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    "${info.versionName} ($code)"
}.getOrDefault("1.1.0")