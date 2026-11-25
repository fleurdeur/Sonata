package com.sonata.core.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.sonata.core.domain.repository.PlayerStyle
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.ThemeMode
import com.sonata.core.domain.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.sonataDataStore: DataStore<Preferences> by preferencesDataStore(name = "sonata_settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : SettingsRepository {

    private object Keys {
        val theme = stringPreferencesKey("theme")
        val dynamic = booleanPreferencesKey("dynamic_color")
        val accent = intPreferencesKey("accent")
        val playerStyle = stringPreferencesKey("player_style")
        val blur = intPreferencesKey("blur")
        val animationSpeed = floatPreferencesKey("animation_speed")
        val haptics = floatPreferencesKey("haptics")
        val gapless = booleanPreferencesKey("gapless")
        val loudness = booleanPreferencesKey("loudness_norm")
        val pauseOnDisconnect = booleanPreferencesKey("pause_disconnect")
        val resumeOnHeadset = booleanPreferencesKey("resume_headset")
        val smartShuffle = booleanPreferencesKey("smart_shuffle")
        val autoMix = booleanPreferencesKey("auto_mix")
        val suggestions = intPreferencesKey("suggestions_frequency")
        val onboarding = booleanPreferencesKey("onboarding_done")
        val streaming = booleanPreferencesKey("streaming_enabled")
        val watchedFolders = stringPreferencesKey("watched_folders")
    }

    override val settings: Flow<Settings> = context.sonataDataStore.data.map { p ->
        Settings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.dynamic] ?: true,
            accent = p[Keys.accent] ?: 0xFF4B44C7.toInt(),
            playerStyle = p[Keys.playerStyle]?.let { runCatching { PlayerStyle.valueOf(it) }.getOrNull() }
                ?: PlayerStyle.FULL_BLEED,
            blurStrength = p[Keys.blur] ?: 16,
            animationSpeed = p[Keys.animationSpeed] ?: 1f,
            hapticIntensity = p[Keys.haptics] ?: 1f,
            gapless = p[Keys.gapless] ?: true,
            loudnessNormalization = p[Keys.loudness] ?: true,
            pauseOnDisconnect = p[Keys.pauseOnDisconnect] ?: true,
            resumeOnHeadset = p[Keys.resumeOnHeadset] ?: false,
            smartShuffle = p[Keys.smartShuffle] ?: true,
            autoMix = p[Keys.autoMix] ?: false,
            suggestionsFrequency = p[Keys.suggestions] ?: 2,
            onboardingDone = p[Keys.onboarding] ?: false,
            streamingEnabled = p[Keys.streaming] ?: false,
            watchedFolders = p[Keys.watchedFolders]?.split('|')?.filter { it.isNotBlank() } ?: emptyList(),
        )
    }

    override suspend fun update(transform: (Settings) -> Settings) {
        context.sonataDataStore.edit { p ->
            val s = transform(current(p))
            p[Keys.theme] = s.themeMode.name
            p[Keys.dynamic] = s.dynamicColor
            p[Keys.accent] = s.accent
            p[Keys.playerStyle] = s.playerStyle.name
            p[Keys.blur] = s.blurStrength
            p[Keys.animationSpeed] = s.animationSpeed
            p[Keys.haptics] = s.hapticIntensity
            p[Keys.gapless] = s.gapless
            p[Keys.loudness] = s.loudnessNormalization
            p[Keys.pauseOnDisconnect] = s.pauseOnDisconnect
            p[Keys.resumeOnHeadset] = s.resumeOnHeadset
            p[Keys.smartShuffle] = s.smartShuffle
            p[Keys.autoMix] = s.autoMix
            p[Keys.suggestions] = s.suggestionsFrequency
            p[Keys.onboarding] = s.onboardingDone
            p[Keys.streaming] = s.streamingEnabled
            p[Keys.watchedFolders] = s.watchedFolders.joinToString("|")
        }
    }

    private fun current(p: androidx.datastore.preferences.core.Preferences): Settings =
        Settings(
            themeMode = p[Keys.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            accent = p[Keys.accent] ?: 0xFF4B44C7.toInt(),
            playerStyle = p[Keys.playerStyle]?.let { runCatching { PlayerStyle.valueOf(it) }.getOrNull() }
                ?: PlayerStyle.FULL_BLEED,
            onboardingDone = p[Keys.onboarding] ?: false,
            streamingEnabled = p[Keys.streaming] ?: false,
            watchedFolders = p[Keys.watchedFolders]?.split('|')?.filter { it.isNotBlank() } ?: emptyList(),
        )
}

@Module
@InstallIn(SingletonComponent::class)
object SettingsModule {
    @Provides @Singleton
    fun provideSettingsRepository(store: SettingsDataStore): SettingsRepository = store
}
