package com.sonata

import android.content.Intent
import android.net.Uri
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
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
        runCatching { doHandleIntent(intent) }
            .onFailure { android.util.Log.e(TAG, "handleIntent failed", it) }
    }

    private fun doHandleIntent(intent: Intent?) {
        android.util.Log.i(TAG, "intent action=${intent?.action} type=${intent?.type}")
        if (intent?.action == Intent.ACTION_SEND) {
            when (intent.type) {
                "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { link ->
                    android.util.Log.i(TAG, "enqueue link $link")
                    lifecycleScope.launch { runEnqueue(link, isLink = true) }
                }
                else -> {
                    val stream = androidx.core.content.IntentCompat.getParcelableExtra(
                        intent,
                        Intent.EXTRA_STREAM,
                        android.net.Uri::class.java,
                    )
                    android.util.Log.i(TAG, "stream=$stream")
                    stream?.let { uri ->
                        lifecycleScope.launch { enqueueLocal(uri.toString(), isLink = false) }
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
                uris.forEach { enqueueLocal(it.toString(), isLink = false) }
            }
        }
    }

    private suspend fun runEnqueue(source: String, isLink: Boolean) {
        runCatching { importQueue.enqueue(source, isLink = isLink) }
            .onFailure { android.util.Log.e(TAG, "enqueue failed", it) }
            .onSuccess { android.util.Log.i(TAG, "enqueued ok: $source") }
    }

    /**
     * Share-sheet and media-provider grants only cover this activity's process, so a
     * background ImportWorker cannot reopen the URI later. Snapshot every content URI
     * into the inbox cache while the grant is still valid, and hand the worker a
     * local file it can always read.
     */
    private suspend fun snapshotUri(source: String): String = withContext(Dispatchers.IO) {
        val uri = Uri.parse(source)
        if (uri.scheme != "content") return@withContext source
        val dir = File(cacheDir, "inbox").apply { mkdirs() }
        val name = displayName(uri) ?: "sound-${System.currentTimeMillis()}"
        val out = File(dir, name)
        val input = contentResolver.openInputStream(uri) ?: error("cannot open $source")
        input.use { ins -> out.outputStream().use { ins.copyTo(it) } }
        Uri.fromFile(out).toString()
    }

    private fun displayName(uri: Uri): String? {
        val base = runCatching {
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getString(idx) else null
            }
        }.getOrNull() ?: uri.lastPathSegment
        val stamp = System.currentTimeMillis()
        val safe = (base ?: "sound").replace(Regex("[\\\\/:*?\"<>|]"), "_")
        return "$stamp-$safe"
    }

    private suspend fun enqueueLocal(source: String, isLink: Boolean) {
        runCatching {
            val local = if (isLink) source else snapshotUri(source)
            importQueue.enqueue(local, isLink = isLink)
        }.onFailure { android.util.Log.e(TAG, "enqueue failed", it) }
            .onSuccess { android.util.Log.i(TAG, "enqueued ok: $source -> $it") }
    }

    private companion object {
        const val TAG = "SonataShare"
    }
}
