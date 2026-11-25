package com.sonata

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.sonata.core.data.repository.PresetRepositoryImpl
import com.sonata.core.common.di.ApplicationScope
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@HiltAndroidApp
class SonataApplication : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var presetRepository: PresetRepositoryImpl
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Seed the large factory preset library once.
        appScope.launch { presetRepository.seedIfEmpty() }
    }
}
