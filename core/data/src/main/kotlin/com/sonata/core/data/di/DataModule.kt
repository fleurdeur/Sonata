package com.sonata.core.data.di

import com.sonata.core.data.lyrics.LrclibProvider
import com.sonata.core.data.lyrics.LyricsOvhProvider
import com.sonata.core.data.lyrics.LyricsProvider
import com.sonata.core.data.lyrics.NetEaseProvider
import com.sonata.core.data.lyrics.QqMusicProvider
import com.sonata.core.data.repository.BrainRepositoryImpl
import com.sonata.core.data.repository.CueRepositoryImpl
import com.sonata.core.data.repository.LyricsRepositoryImpl
import com.sonata.core.data.repository.ImportRepositoryImpl
import com.sonata.core.data.repository.MixRepositoryImpl
import com.sonata.core.data.repository.PlaylistRepositoryImpl
import com.sonata.core.data.repository.PresetRepositoryImpl
import com.sonata.core.data.repository.SoundRepositoryImpl
import com.sonata.core.domain.repository.BrainRepository
import com.sonata.core.domain.repository.CueRepository
import com.sonata.core.domain.repository.LyricsRepository
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.domain.repository.MixRepository
import com.sonata.core.domain.repository.PlaylistRepository
import com.sonata.core.domain.repository.PresetRepository
import com.sonata.core.domain.repository.SoundRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.multibindings.IntoSet
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds @Singleton abstract fun soundRepository(impl: SoundRepositoryImpl): SoundRepository
    @Binds @Singleton abstract fun playlistRepository(impl: PlaylistRepositoryImpl): PlaylistRepository
    @Binds @Singleton abstract fun mixRepository(impl: MixRepositoryImpl): MixRepository
    @Binds @Singleton abstract fun presetRepository(impl: PresetRepositoryImpl): PresetRepository
    @Binds @Singleton abstract fun importRepository(impl: ImportRepositoryImpl): ImportRepository
    @Binds @Singleton abstract fun brainRepository(impl: BrainRepositoryImpl): BrainRepository
    @Binds @Singleton abstract fun lyricsRepository(impl: LyricsRepositoryImpl): LyricsRepository
    @Binds @Singleton abstract fun cueRepository(impl: CueRepositoryImpl): CueRepository

    companion object {
        @Provides @IntoSet fun lrclib(provider: LrclibProvider): LyricsProvider = provider
        @Provides @IntoSet fun lyricsOvh(provider: LyricsOvhProvider): LyricsProvider = provider
        @Provides @IntoSet fun netEase(provider: NetEaseProvider): LyricsProvider = provider
        @Provides @IntoSet fun qqMusic(provider: QqMusicProvider): LyricsProvider = provider
    }
}