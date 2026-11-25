package com.sonata.core.data.di

import com.sonata.core.data.repository.BrainRepositoryImpl
import com.sonata.core.data.repository.ImportRepositoryImpl
import com.sonata.core.data.repository.MixRepositoryImpl
import com.sonata.core.data.repository.PlaylistRepositoryImpl
import com.sonata.core.data.repository.PresetRepositoryImpl
import com.sonata.core.data.repository.SoundRepositoryImpl
import com.sonata.core.domain.repository.BrainRepository
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.domain.repository.MixRepository
import com.sonata.core.domain.repository.PlaylistRepository
import com.sonata.core.domain.repository.PresetRepository
import com.sonata.core.domain.repository.SoundRepository
import dagger.Binds
import dagger.Module
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
}
