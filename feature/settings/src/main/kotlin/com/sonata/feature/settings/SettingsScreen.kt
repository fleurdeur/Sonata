package com.sonata.feature.settings

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Surface
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
import com.sonata.core.domain.model.Lyrics
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.model.Preset
import com.sonata.core.domain.model.PresetCategory
import com.sonata.core.domain.model.Sound
import com.sonata.core.domain.repository.LyricsRepository
import com.sonata.core.domain.repository.PresetRepository
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.domain.repository.SoundRepository
import com.sonata.core.domain.repository.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import org.json.JSONArray
import org.json.JSONObject
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
    private val presetRepository: PresetRepository,
    private val lyricsRepository: LyricsRepository,
    @ApplicationContext private val context: Context,
    val dsp: DspManager,
) : ViewModel() {

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    val sounds: StateFlow<List<Sound>> = soundRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _cacheSize = MutableStateFlow(0L)
    val cacheSize: StateFlow<Long> = _cacheSize

    private val _storage = MutableStateFlow(StorageBreakdown())
    val storage: StateFlow<StorageBreakdown> = _storage

    private val _backupNotice = MutableStateFlow<String?>(null)
    val backupNotice: StateFlow<String?> = _backupNotice

    val presets: StateFlow<List<Preset>> = presetRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init { refreshStorage() }

    fun applyPreset(preset: Preset) = dsp.applyPreset(preset)

    fun savePreset(name: String) = viewModelScope.launch {
        val current = dsp.state.value
        presetRepository.upsert(
            Preset(
                name = name.take(40),
                category = PresetCategory.MINE,
                description = "Saved from the EQ",
                bands = current.bands,
                compressor = current.compressor,
                limiter = current.limiter,
                isFavorite = true,
            ),
        )
    }

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch {
        settingsRepository.update(transform)
    }

    fun refreshStorage() = viewModelScope.launch {
        _storage.value = withContext(Dispatchers.IO) { measureStorage() }
        _cacheSize.value = _storage.value.cacheBytes
    }

    fun clearCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        _cacheSize.value = 0L
        refreshStorage()
    }

    fun dismissBackupNotice() { _backupNotice.value = null }

    fun exportBackup(uri: android.net.Uri) = viewModelScope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) { writeBackup(uri) }
        }
        _backupNotice.value = result.fold(
            onSuccess = { "Backup saved · " + it + " songs and their lyrics" },
            onFailure = { "Could not save the backup: " + (it.message ?: "write failed") },
        )
    }

    fun restoreBackup(uri: android.net.Uri) = viewModelScope.launch {
        val result = runCatching {
            withContext(Dispatchers.IO) { readBackup(uri) }
        }
        _backupNotice.value = result.fold(
            onSuccess = { "Restored metadata for " + it + " matching songs" },
            onFailure = { "Could not restore: " + (it.message ?: "invalid backup") },
        )
        refreshStorage()
    }

    private fun measureStorage(): StorageBreakdown {
        val all = sounds.value
        val audio = all.sumOf { sound ->
            sound.filePath.takeIf { it.isNotBlank() }?.let { File(it).length() } ?: 0L
        }
        val covers = all.sumOf { sound ->
            sound.coverPath?.takeIf { it.isNotBlank() }?.let { File(it).length() } ?: 0L
        }
        val db = context.getDatabasePath("sonata.db").let { file ->
            file.length() + File(file.path + "-wal").length() + File(file.path + "-shm").length()
        }
        val cache = directorySize(context.cacheDir)
        return StorageBreakdown(audio, covers, db, cache)
    }

    private suspend fun writeBackup(uri: android.net.Uri): Int {
        val root = JSONObject()
            .put("format", 1)
            .put("exportedAt", System.currentTimeMillis())
            .put("app", "Sonata")
        val array = JSONArray()
        // A record without a fingerprint cannot be matched again; writing it
        // would inflate the count and restore nothing.
        sounds.value.filter { !it.fingerprint.isNullOrBlank() }.forEach { sound ->
            val row = JSONObject()
                .put("fingerprint", sound.fingerprint.orEmpty())
                .put("title", sound.title)
                .put("artist", sound.artist.orEmpty())
                .put("album", sound.album.orEmpty())
                .put("tags", JSONArray(sound.tags))
                .put("bpm", sound.bpm ?: JSONObject.NULL)
                .put("key", sound.key?.name ?: JSONObject.NULL)
                .put("favorite", sound.isFavorite)
                .put("playCount", sound.playCount)
            lyricsRepository.get(sound.id)?.let { lyrics ->
                row.put(
                    "lyrics",
                    JSONObject()
                        .put("provider", lyrics.provider)
                        .put("syncedLrc", lyrics.rawLrc ?: JSONObject.NULL)
                        .put("plainText", lyrics.plainText)
                        .put("trackName", lyrics.trackName ?: JSONObject.NULL)
                        .put("artistName", lyrics.artistName ?: JSONObject.NULL)
                        .put("speed", lyrics.speed)
                        .put("offsetMs", lyrics.offsetMs),
                )
            }
            array.put(row)
        }
        root.put("sounds", array)
        context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { writer ->
            writer.write(root.toString(2))
        } ?: error("This location cannot be written")
        return array.length()
    }

    private suspend fun readBackup(uri: android.net.Uri): Int {
        val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            ?: error("This file cannot be read")
        val root = JSONObject(text)
        if (root.optInt("format") != 1) error("Unsupported backup format")
        val array = root.optJSONArray("sounds") ?: JSONArray()
        var restored = 0
        for (i in 0 until array.length()) {
            val row = array.optJSONObject(i) ?: continue
            val fingerprint = row.optString("fingerprint").takeIf { it.isNotBlank() } ?: continue
            val sound = soundRepository.findByFingerprint(fingerprint) ?: continue
            val tags = row.optJSONArray("tags")?.let { tagsArray ->
                (0 until tagsArray.length()).mapNotNull { tagsArray.optString(it).takeIf(String::isNotBlank) }
            } ?: sound.tags
            soundRepository.upsert(
                sound.copy(
                    title = row.optString("title", sound.title).ifBlank { sound.title },
                    artist = row.optString("artist").takeIf { it.isNotBlank() },
                    album = row.optString("album").takeIf { it.isNotBlank() },
                    tags = tags,
                    bpm = row.optInt("bpm", sound.bpm ?: 0).takeIf { it > 0 },
                    key = row.optString("key").takeIf { it.isNotBlank() }?.let { key ->
                        MusicalKey.entries.firstOrNull { it.name == key }
                    },
                    isFavorite = row.optBoolean("favorite", sound.isFavorite),
                    playCount = row.optInt("playCount", sound.playCount),
                ),
            )
            row.optJSONObject("lyrics")?.let { lyrics ->
                val raw = if (lyrics.isNull("syncedLrc")) null else lyrics.optString("syncedLrc")
                val plain = lyrics.optString("plainText")
                if (raw.isNullOrBlank() && plain.isBlank()) return@let
                lyricsRepository.save(
                    sound.id,
                    Lyrics(
                        soundId = sound.id,
                        provider = lyrics.optString("provider", "Backup"),
                        plainText = plain,
                        trackName = lyrics.optString("trackName").takeIf { it.isNotBlank() },
                        artistName = lyrics.optString("artistName").takeIf { it.isNotBlank() },
                        speed = lyrics.optDouble("speed", 1.0).toFloat(),
                        offsetMs = lyrics.optLong("offsetMs", 0L),
                        rawLrc = raw,
                    ),
                )
            }
            restored++
        }
        return restored
    }

    fun replayIntroduction() = update { it.copy(onboardingDone = false) }

    private fun directorySize(dir: File): Long =
        dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}

data class StorageBreakdown(
    val audioBytes: Long = 0,
    val coverBytes: Long = 0,
    val databaseBytes: Long = 0,
    val cacheBytes: Long = 0,
) {
    val totalBytes: Long get() = audioBytes + coverBytes + databaseBytes + cacheBytes
}

/**
 * Settings contains only switches that change something you can observe:
 * the theme, the accent, the equalizer, the import cache and the first-run
 * tour. Everything else was removed rather than left as a label.
 */
@Composable
fun SettingsScreen(
    onOpenEq: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    val sounds by viewModel.sounds.collectAsState()
    val cacheSize by viewModel.cacheSize.collectAsState()
    val storage by viewModel.storage.collectAsState()
    val backupNotice by viewModel.backupNotice.collectAsState()
    val spacing = LocalSpacing.current
    val context = LocalContext.current
    var showCacheDialog by remember { mutableStateOf(false) }

    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> uri?.let(viewModel::exportBackup) }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(viewModel::restoreBackup) }

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

        item { SectionTitle("Audio") }
        item {
            SettingsRow(
                title = "Equalizer",
                subtitle = "Parametric EQ, compression and limiter",
                onClick = onOpenEq,
            )
        }

        item { SectionTitle("Storage") }
        item {
            SettingsRow(
                title = "Sounds on this device",
                subtitle = "${sounds.size} files",
                onClick = null,
                showChevron = false,
            )
        }
        item {
            Column(Modifier.padding(top = spacing.smDp)) {
                Text("Library " + formatBytes(storage.audioBytes), style = MaterialTheme.typography.bodySmall)
                Text("Covers " + formatBytes(storage.coverBytes), style = MaterialTheme.typography.bodySmall)
                Text("Database " + formatBytes(storage.databaseBytes), style = MaterialTheme.typography.bodySmall)
                Text(
                    "Cache " + formatBytes(storage.cacheBytes) + " · total " + formatBytes(storage.totalBytes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SettingsRow(
                title = "Import cache",
                subtitle = if (cacheSize > 0) formatBytes(cacheSize) + " of temporary files" else "Empty",
                onClick = { showCacheDialog = true },
            )
        }
        item {
            SettingsRow(
                title = "Back up metadata and lyrics",
                subtitle = "JSON file · audio stays on this device",
                onClick = { exportBackup.launch("sonata-backup.json") },
            )
        }
        item {
            SettingsRow(
                title = "Restore from backup",
                subtitle = "Matches songs by acoustic fingerprint",
                onClick = { importBackup.launch(arrayOf("application/json")) },
            )
        }
        backupNotice?.let { notice ->
            item {
                Surface(
                    Modifier.fillMaxWidth().padding(vertical = spacing.xsDp),
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        notice,
                        Modifier.padding(spacing.smDp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item { SectionTitle("About") }
        item {
            SettingsRow(
                title = "Sonata",
                subtitle = "Version ${versionName(context)} · offline, no account",
                onClick = null,
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
    onClick: (() -> Unit)?,
    showChevron: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
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
}.getOrDefault("unknown")