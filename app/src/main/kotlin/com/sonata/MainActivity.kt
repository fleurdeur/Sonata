package com.sonata

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.sonata.core.designsystem.SonataTheme
import com.sonata.core.domain.repository.Settings
import com.sonata.core.domain.repository.SettingsRepository
import com.sonata.core.domain.repository.ThemeMode
import com.sonata.core.extractor.tiktok.TikTokUrls
import com.sonata.core.work.pipeline.ImportQueue
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
 * The single activity. Every share-sheet entry point is enqueued here, before
 * the UI exists, so a share works even when Sonata is cold-started:
 *
 * - text/plain: the first URL in the text (TikTok share text is a sentence),
 * - video or audio streams: snapshotted into the app cache while the URI grant
 *   is still valid, then imported by the worker,
 * - images: the same, so a screenshot never becomes a broken row.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var importQueue: ImportQueue

    private val _pendingShare = MutableStateFlow<List<String>>(emptyList())
    val pendingShare: StateFlow<List<String>> = _pendingShare.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // A recreated activity receives the same intent again; importing it a
        // second time would duplicate every share.
        if (savedInstanceState == null) handleIntent(intent)

        val settings = settingsRepository.settings
        setContent {
            // Wait for the stored theme instead of composing a default one for a
            // frame; otherwise every cold start flashes the intro or the wrong
            // light/dark palette.
            val current by produceState<Settings?>(initialValue = null) {
                settings.collect { value = it }
            }
            val shares by pendingShare.collectAsState()
            val themeMode = current?.themeMode ?: ThemeMode.SYSTEM
            val dark = themeMode == ThemeMode.DARK ||
                themeMode == ThemeMode.PURE_BLACK ||
                (themeMode == ThemeMode.SYSTEM && isSystemInDarkTheme())
            val view = LocalView.current
            SideEffect {
                val window = (view.context as android.app.Activity).window
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            SonataTheme(
                themeMode = themeMode,
                accent = Color(current?.accent ?: 0xFF4B44C7.toInt()),
                dynamicColor = current?.dynamicColor ?: true,
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    current?.let { loaded ->
                        SonataApp(
                            pendingShares = shares,
                            onConsumedShare = { _pendingShare.value = emptyList() },
                            // Stored preferences are already present here.
                            initialOnboardingDone = loaded.onboardingDone,
                        )
                    }
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
        if (intent == null) return
        android.util.Log.i(TAG, "intent action=${intent.action} type=${intent.type}")
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val stream = androidx.core.content.IntentCompat.getParcelableExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                )
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                val sharedUrl = text?.let(TikTokUrls::firstUrlIn)
                if (sharedUrl != null) {
                    // TikTok shares often attach the video file too. The link is
                    // richer: it identifies the full sound behind the post.
                    lifecycleScope.launch { enqueueLink(sharedUrl) }
                } else if (stream != null) {
                    val mime = intent.type.orEmpty()
                    val kind = if (mime.startsWith("video/") || mime.startsWith("image/")) "Video" else "Sound"
                    lifecycleScope.launch { enqueueLocal(stream.toString(), kind) }
                } else if (!text.isNullOrBlank()) {
                    android.widget.Toast.makeText(this, "No link found in that share", android.widget.Toast.LENGTH_SHORT).show()
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val uris = androidx.core.content.IntentCompat.getParcelableArrayListExtra(
                    intent,
                    Intent.EXTRA_STREAM,
                    Uri::class.java,
                ) ?: emptyList()
                lifecycleScope.launch {
                    uris.forEach { enqueueLocal(it.toString(), "Sound") }
                }
            }
        }
    }

    private suspend fun enqueueLink(url: String) {
        runCatching { importQueue.enqueue(url, isLink = true, title = titleFor(url)) }
            .onSuccess { _pendingShare.value = listOf(titleFor(url)) }
            .onFailure {
                android.util.Log.e(TAG, "enqueue failed", it)
                showError(it)
            }
    }

    private suspend fun enqueueLocal(source: String, kind: String) {
        runCatching {
            val local = snapshotUri(source)
            importQueue.enqueue(local, isLink = false, title = kind)
            _pendingShare.value = listOf(kind)
        }.onFailure {
            android.util.Log.e(TAG, "enqueue failed", it)
            showError(it)
        }
    }

    private fun showError(t: Throwable) {
        android.widget.Toast.makeText(this, "Could not start the import", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun titleFor(url: String): String =
        if (TikTokUrls.isTikTok(url)) "TikTok video" else "Shared link"

    /**
     * Share-sheet grants only cover this process, so the worker cannot reopen a
     * content:// URI later. Copy it into the inbox cache now.
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
        val safe = (base ?: "sound").replace(Regex("[\\/:*?\"<>|]"), "_")
        return "$stamp-$safe"
    }

    private companion object {
        const val TAG = "SonataShare"
    }
}