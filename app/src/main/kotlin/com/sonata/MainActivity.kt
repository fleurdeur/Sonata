package com.sonata

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.lifecycleScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.sonata.core.designsystem.SonataTheme
import com.sonata.core.domain.repository.ThemeMode
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.work.pipeline.ImportQueue
import com.sonata.core.work.pipeline.ImportWorker
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The single activity. Handles every share-sheet entry point
 * (ACTION_SEND / ACTION_SEND_MULTIPLE) by enqueuing imports before the UI.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var importQueue: ImportQueue

    private val _pendingShare = MutableStateFlow<List<String>>(emptyList())
    val pendingShare: StateFlow<List<String>> = _pendingShare.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
        lifecycleScope.launch {
            val settings = settingsRepository.settings
            setContent {
                val current by settings.collectAsState(initial = com.sonata.core.domain.repository.Settings())
                val shares by pendingShare.collectAsState()
                SonataTheme(
                    themeMode = current.themeMode,
                    accent = androidx.compose.ui.graphics.Color(current.accent),
                    dynamicColor = current.dynamicColor,
                ) {
                    SonataApp(
                        pendingShares = shares,
                        onConsumedShare = { _pendingShare.value = emptyList() },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND) {
            when (intent.type) {
                "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { link ->
                    lifecycleScope.launch { importQueue.enqueue(link, isLink = true) }
                }
                else -> {
                    val stream = androidx.core.content.IntentCompat.getParcelableExtra(
                        intent,
                        Intent.EXTRA_STREAM,
                        android.net.Uri::class.java,
                    )
                    stream?.let { uri ->
                        lifecycleScope.launch { importQueue.enqueue(uri.toString(), isLink = false) }
                    }
                }
            }
        }
        if (intent?.action == Intent.ACTION_SEND_MULTIPLE) {
            val uris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                intent,
                Intent.EXTRA_STREAM,
                android.net.Uri::class.java,
            ) ?: emptyList()
            lifecycleScope.launch {
                uris.forEach { importQueue.enqueue(it.toString(), isLink = false) }
            }
        }
    }
}
