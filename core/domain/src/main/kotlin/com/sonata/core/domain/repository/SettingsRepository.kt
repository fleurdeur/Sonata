package com.sonata.core.domain.repository

import kotlinx.coroutines.flow.Flow

enum class ThemeMode { LIGHT, DARK, PURE_BLACK, SYSTEM }
enum class PlayerStyle { FULL_BLEED, VINYL, CASSETTE, WAVEFORM, GLASS_CARD }

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val accent: Int = 0xFF6C5CE7.toInt(),
    val coverAdaptiveTheme: Boolean = false,
    val playerStyle: PlayerStyle = PlayerStyle.FULL_BLEED,
    val blurStrength: Int = 16,
    val animationSpeed: Float = 1f,
    val hapticIntensity: Float = 1f,
    val gapless: Boolean = true,
    val loudnessNormalization: Boolean = true,
    val pauseOnDisconnect: Boolean = true,
    val resumeOnHeadset: Boolean = false,
    val smartShuffle: Boolean = true,
    val autoMix: Boolean = false,
    val suggestionsFrequency: Int = 2, // 0 off .. 3 proactive
    val onboardingDone: Boolean = false,
    val streamingEnabled: Boolean = false,
    val watchedFolders: List<String> = emptyList(),
)

interface SettingsRepository {
    val settings: Flow<Settings>
    suspend fun update(transform: (Settings) -> Settings)
}
