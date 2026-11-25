package com.sonata.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sonata.core.audio.engine.DspManager
import com.sonata.core.designsystem.LocalRadius
import com.sonata.core.designsystem.LocalSpacing
import com.sonata.core.designsystem.components.glassSurface
import com.sonata.core.domain.repository.PlayerStyle
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.ThemeMode
import com.sonata.core.domain.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    val dsp: DspManager,
) : ViewModel() {

    val settings = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, Settings())

    fun update(transform: (Settings) -> Settings) = viewModelScope.launch {
        settingsRepository.update(transform)
    }
}

data class SettingsSection(val title: String, val entries: List<SettingsEntry>)

data class SettingsEntry(
    val label: String,
    val keywords: List<String> = emptyList(),
    val kind: Kind = Kind.LINK,
    val booleanValue: Boolean = false,
) {
    enum class Kind { LINK, TOGGLE }
}

val SettingsSections = listOf(
    SettingsSection(
        "Appearance and customization",
        listOf(
            SettingsEntry("Theme", listOf("light", "dark", "amoled", "pure black")),
            SettingsEntry("Accent colors", listOf("accent")),
            SettingsEntry("Material You dynamic color", listOf("dynamic"), SettingsEntry.Kind.TOGGLE),
            SettingsEntry("Cover-adaptive theme", listOf("cover")),
            SettingsEntry("Player style", listOf("vinyl", "cassette", "waveform")),
            SettingsEntry("Blur strength", listOf("glass", "blur")),
            SettingsEntry("Animation speed", listOf("motion", "reduced")),
            SettingsEntry("Haptic intensity", listOf("haptic")),
        ),
    ),
    SettingsSection(
        "Playback",
        listOf(
            SettingsEntry("Gapless and crossfade", listOf("gapless"), SettingsEntry.Kind.TOGGLE),
            SettingsEntry("Loudness normalization", listOf("ebu"), SettingsEntry.Kind.TOGGLE),
            SettingsEntry("Pause on disconnect", listOf("headset"), SettingsEntry.Kind.TOGGLE),
            SettingsEntry("Resume on headset", listOf("resume"), SettingsEntry.Kind.TOGGLE),
            SettingsEntry("Smart Shuffle", listOf("shuffle"), SettingsEntry.Kind.TOGGLE),
            SettingsEntry("Sleep timer defaults", listOf("sleep")),
        ),
    ),
    SettingsSection(
        "Audio and EQ",
        listOf(
            SettingsEntry("Default sound profile", listOf("preset", "eq")),
            SettingsEntry("Device-aware rules", listOf("device", "speaker", "bluetooth")),
            SettingsEntry("AutoEQ library", listOf("autoeq", "headphone", "csv")),
            SettingsEntry("Limiter ceiling", listOf("limiter", "clipping")),
            SettingsEntry("Hearing-protection warning", listOf("volume", "safe")),
        ),
    ),
    SettingsSection(
        "Sources",
        listOf(
            SettingsEntry("Watched folders", listOf("folder", "watch")),
            SettingsEntry("Link resolvers", listOf("resolver", "link")),
            SettingsEntry("Streaming provider switch", listOf("youtube", "stream")),
            SettingsEntry("Lyrics providers", listOf("lyrics")),
            SettingsEntry("Scrobbling (Last.fm, ListenBrainz)", listOf("scrobble")),
        ),
    ),
    SettingsSection(
        "Proxy and network",
        listOf(
            SettingsEntry("HTTP / HTTPS / SOCKS5 proxy", listOf("proxy", "socks")),
            SettingsEntry("Custom DNS", listOf("dns")),
            SettingsEntry("Wi-Fi-only streaming", listOf("metered", "data saver")),
            SettingsEntry("Connection test", listOf("test")),
            SettingsEntry("Request log viewer", listOf("log")),
        ),
    ),
    SettingsSection(
        "Downloads and storage",
        listOf(
            SettingsEntry("Stream cache size", listOf("cache")),
            SettingsEntry("Auto-clean rules", listOf("clean")),
            SettingsEntry("Storage breakdown", listOf("storage")),
            SettingsEntry("Duplicate finder", listOf("duplicates")),
        ),
    ),
    SettingsSection(
        "Suggestions and privacy",
        listOf(
            SettingsEntry("Brain controls", listOf("brain", "suggestions")),
            SettingsEntry("Taste Profile", listOf("taste")),
            SettingsEntry("Permission dashboard", listOf("permission")),
            SettingsEntry("Opt-in crash reports", listOf("crash")),
            SettingsEntry("Data export and delete", listOf("export", "gdpr")),
            SettingsEntry("App lock with biometrics", listOf("lock", "biometric")),
        ),
    ),
    SettingsSection(
        "Advanced",
        listOf(
            SettingsEntry("Feature flags", listOf("flags", "experimental")),
            SettingsEntry("Audio buffer size", listOf("buffer")),
            SettingsEntry("Offload mode", listOf("offload")),
            SettingsEntry("Logging level and debug log export", listOf("log", "debug")),
            SettingsEntry("Database maintenance", listOf("database", "vacuum")),
            SettingsEntry("Encrypted backup and restore", listOf("backup")),
        ),
    ),
    SettingsSection(
        "Notifications",
        listOf(
            SettingsEntry("Media notification style", listOf("notification")),
            SettingsEntry("Daily Fresh Mix", listOf("fresh")),
            SettingsEntry("Quiet hours", listOf("quiet")),
        ),
    ),
    SettingsSection(
        "Language and region",
        listOf(
            SettingsEntry("Language", listOf("language")),
            SettingsEntry("Regional content", listOf("region")),
            SettingsEntry("Date formats", listOf("date")),
        ),
    ),
    SettingsSection(
        "About",
        listOf(
            SettingsEntry("Version and licenses", listOf("version", "oss")),
            SettingsEntry("Changelog and What's New", listOf("changelog")),
            SettingsEntry("Help Center", listOf("help", "tutorial", "replay")),
            SettingsEntry("Credits", listOf("credits")),
        ),
    ),
)

@Composable
fun SettingsScreen(
    onOpenEq: () -> Unit,
    onReplayTutorial: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsState()
    var query by remember { mutableStateOf("") }
    val spacing = LocalSpacing.current

    LazyColumn(
        Modifier
            .fillMaxSize()
            .padding(horizontal = spacing.mdDp),
        verticalArrangement = Arrangement.spacedBy(spacing.smDp),
    ) {
        item {
            Text(
                "Settings",
                style = MaterialTheme.typography.displayLarge,
                modifier = Modifier.padding(top = spacing.lgDp, bottom = spacing.smDp),
            )
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search settings") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Search settings" },
                singleLine = true,
            )
        }
        items(SettingsSections) { section ->
            val entries = section.entries.filter {
                query.isBlank() || it.label.lowercase().contains(query.lowercase()) ||
                    it.keywords.any { k -> query.lowercase().contains(k) }
            }
            if (entries.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .glassSurface()
                        .padding(spacing.mdDp),
                    verticalArrangement = Arrangement.spacedBy(spacing.xsDp),
                ) {
                    Text(section.title, style = MaterialTheme.typography.titleLarge)
                    entries.forEach { entry -> EntryRow(entry, settings, viewModel) }
                }
            }
        }
        item {
            Text(
                "Sonata is offline-first, with no account, no ads and no tracking.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EntryRow(entry: SettingsEntry, settings: Settings, viewModel: SettingsViewModel) {
    val checked = when (entry.label) {
        "Material You dynamic color" -> settings.dynamicColor
        "Gapless and crossfade" -> settings.gapless
        "Loudness normalization" -> settings.loudnessNormalization
        "Pause on disconnect" -> settings.pauseOnDisconnect
        "Resume on headset" -> settings.resumeOnHeadset
        "Smart Shuffle" -> settings.smartShuffle
        else -> false
    }
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(entry.label, Modifier.weight(1f))
        if (entry.kind == SettingsEntry.Kind.TOGGLE) {
            Switch(
                checked = checked,
                onCheckedChange = { on ->
                    viewModel.update { s ->
                        when (entry.label) {
                            "Material You dynamic color" -> s.copy(dynamicColor = on)
                            "Gapless and crossfade" -> s.copy(gapless = on)
                            "Loudness normalization" -> s.copy(loudnessNormalization = on)
                            "Pause on disconnect" -> s.copy(pauseOnDisconnect = on)
                            "Resume on headset" -> s.copy(resumeOnHeadset = on)
                            "Smart Shuffle" -> s.copy(smartShuffle = on)
                            else -> s
                        }
                    }
                },
                modifier = Modifier.semantics { contentDescription = entry.label },
            )
        }
    }
}
