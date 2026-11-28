package com.sonata.core.work.pipeline

import android.content.Context
import android.os.SystemClock
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.sonata.core.audio.render.AcousticMatcher
import com.sonata.core.audio.render.PcmTools
import com.sonata.core.audio.render.TrackRenderer
import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.SoundDao
import com.sonata.core.database.entity.SoundEntity
import com.sonata.core.database.entity.encodeWaveform
import com.sonata.core.domain.model.ImportFailureKind
import com.sonata.core.domain.model.ImportJob
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.domain.model.MusicalKey
import com.sonata.core.domain.model.CueKind
import com.sonata.core.domain.repository.CueRepository
import com.sonata.core.domain.repository.ImportRepository
import com.sonata.core.domain.repository.LyricsRepository
import com.sonata.core.extractor.bitchord.BitchordExtractor
import com.sonata.core.extractor.bitchord.ResolveOutcome
import com.sonata.core.extractor.provider.LinkResolverRegistry
import com.sonata.core.extractor.tiktok.MediaDownloader
import com.sonata.core.extractor.tiktok.TikTokUrls
import com.sonata.core.work.analysis.AudioAnalysis
import com.sonata.core.work.analysis.EffectProfiler
import com.sonata.core.work.tiktok.TikTokPipeline
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.withContext

/**
 * One measured import pipeline for every entry point:
 *
 * FETCH_SOURCE -> EXTRACT_AUDIO -> IDENTIFY_SONG -> FETCH_FULL_TRACK ->
 * ALIGN_EDIT -> ENCODE_AAC -> PERSIST_LIBRARY -> FETCH_LYRICS -> DONE.
 *
 * Network downloads are atomic and cached by source identity, so a bounded
 * WorkManager retry resumes from the last completed stage instead of fetching
 * everything again. Failures carry a typed reason and a readable diagnostic.
 */
@HiltWorker
class ImportWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val resolvers: LinkResolverRegistry,
    private val importRepository: ImportRepository,
    private val soundDao: SoundDao,
    private val audioStore: AudioStore,
    private val downloader: MediaDownloader,
    private val tiktokPipeline: TikTokPipeline,
    private val extractor: BitchordExtractor,
    private val lyricsRepository: LyricsRepository,
    private val cueRepository: CueRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(io) {
        val jobId = inputData.getString(KEY_JOB_ID) ?: return@withContext Result.failure()
        val sourceUri = inputData.getString(KEY_SOURCE_URI) ?: return@withContext Result.failure()
        val isLink = inputData.getBoolean(KEY_IS_LINK, false)
        val storedJob = importRepository.get(jobId)
        var currentStage = storedJob?.stage?.takeUnless { it.terminal } ?: ImportStage.QUEUED
        var currentTitle = storedJob?.title.orEmpty()
        val attempt = runAttemptCount + 1
        var lastReportedAt = 0L
        var lastReportedStage: ImportStage? = null
        var lastReportedFraction = -1f

        // Show the progress notification immediately, even before the first
        // network call, so a slow resolve never looks like a dead button.
        runCatching {
            setForeground(
                ImportNotifications.foregroundInfo(
                    applicationContext,
                    jobId,
                    currentTitle,
                    currentStage,
                    storedJob?.progress ?: 0f,
                ),
            )
        }

        suspend fun progress(
            stage: ImportStage,
            fraction: Float,
            title: String = "",
            error: String? = null,
            failure: ImportFailureKind? = null,
            diagnostics: String? = null,
            verified: Boolean? = null,
            confidence: Float? = null,
            soundId: Long? = null,
        ) {
            val previousTitle = currentTitle
            if (title.isNotBlank()) currentTitle = title
            val titleChanged = title.isNotBlank() && title != previousTitle
            val now = SystemClock.elapsedRealtime()
            val stageChanged = stage != lastReportedStage
            val quiet = error == null && failure == null && verified == null &&
                soundId == null && fraction < 0.999f
            if (quiet && !stageChanged && !titleChanged && now - lastReportedAt < 250L &&
                abs(fraction - lastReportedFraction) < 0.03f
            ) {
                return
            }
            currentStage = stage
            lastReportedAt = now
            lastReportedStage = stage
            lastReportedFraction = fraction
            runCatching {
                importRepository.report(
                    jobId = jobId,
                    stage = stage,
                    progress = fraction,
                    title = currentTitle,
                    error = error,
                    failure = failure,
                    diagnostics = diagnostics,
                    attempt = attempt,
                    fullSongVerified = verified,
                    matchConfidence = confidence,
                    soundId = soundId,
                )
            }
            runCatching {
                setForeground(
                    ImportNotifications.foregroundInfo(
                        applicationContext,
                        jobId,
                        currentTitle,
                        stage,
                        fraction,
                    ),
                )
            }
        }

        try {
            val attachSoundId = inputData.getLong(KEY_ATTACH_SOUND_ID, 0L)
            val result: Stored = if (attachSoundId > 0L) {
                attachFullSong(
                    soundId = attachSoundId,
                    videoId = inputData.getString(KEY_ATTACH_VIDEO_ID).orEmpty(),
                    artistOverride = inputData.getString(KEY_ATTACH_ARTIST),
                    parentJobId = inputData.getString(KEY_PARENT_JOB_ID),
                    force = inputData.getBoolean(KEY_ATTACH_FORCE, false),
                    progress = ::progress,
                )
            } else {
                when {
                    isLink && TikTokUrls.isTikTok(sourceUri) -> importTikTok(sourceUri, ::progress)
                    isLink -> importLink(sourceUri, ::progress)
                    else -> importLocal(sourceUri, ::progress)
                }
            }
            progress(ImportStage.DONE, 1f, result.title, soundId = result.soundId)
            Result.success(
                workDataOf(
                    KEY_SOUND_ID to result.soundId,
                    KEY_DUPLICATE to result.duplicate,
                    KEY_TRIM_START to result.trimStart,
                    KEY_TRIM_END to result.trimEnd,
                ),
            )
        } catch (t: Throwable) {
            val failure = classify(t, currentStage)
            val detail = diagnosticsFor(t, currentStage, attempt)
            android.util.Log.e("SonataImport", "import failed at " + currentStage.name, t)
            if (failure.retryable && attempt < MAX_ATTEMPTS) {
                // Keep the stage visible: WorkManager retries with backoff and
                // cached sources let the next attempt skip completed downloads.
                progress(
                    currentStage,
                    0f,
                    error = "Retrying automatically (" + attempt + " of " + (MAX_ATTEMPTS - 1) +
                        "): " + friendlyError(t),
                    failure = failure,
                    diagnostics = detail,
                )
                Result.retry()
            } else {
                progress(
                    ImportStage.FAILED,
                    1f,
                    error = friendlyError(t),
                    failure = failure,
                    diagnostics = detail,
                )
                Result.failure()
            }
        }
    }

    // ------------------------------------------------------------- TikTok

    private suspend fun importTikTok(
        url: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        val track = tiktokPipeline.process(url) { stage, fraction, title ->
            progress(stage, fraction, title, null, null, null, null, null)
        }
        progress(
            ImportStage.ENCODE_AAC,
            0.90f,
            "Encoding " + track.title,
            null,
            null,
            track.diagnostics,
            track.fromFullSong,
            track.matchConfidence,
        )
        val clipFile = audioStore.newSoundFile("m4a")
        val clipWritten = PcmFileWriter.write(track.clipPcm, PcmTools.SAMPLE_RATE, clipFile)
        val fullPcm = track.fullSongPcm
        var fullWritten: File? = null
        if (fullPcm != null && fullPcm.isNotEmpty()) {
            val fullFile = audioStore.newSoundFile("m4a")
            fullWritten = runCatching {
                PcmFileWriter.write(fullPcm, PcmTools.SAMPLE_RATE, fullFile)
            }.onFailure {
                // Keep the proven clip; a failed full encode must not lose the import.
                fullFile.delete()
            }.getOrNull()
        }
        // A failed full-song encode must degrade honestly to the real clip,
        // not keep "verified"/"full song" tags on a three-second file.
        val fullReady = fullWritten != null
        val tags = if (fullReady) {
            track.tags
        } else {
            track.tags.filterNot { it == "full song" || it == "verified" || it.startsWith("match ") } +
                "clip only"
        }
        val stored = persist(
            pcm = if (fullReady) fullPcm ?: track.clipPcm else track.clipPcm,
            file = fullWritten ?: clipWritten,
            title = track.title,
            artist = track.artist,
            source = "TIKTOK",
            coverPath = track.coverPath,
            tags = tags,
            progress = progress,
            speed = if (fullReady) track.effect.speed else 1f,
            fetchLyrics = track.fromFullSong && fullReady,
            verified = track.fromFullSong && fullReady,
            confidence = if (fullReady) track.matchConfidence else null,
            clipFile = clipWritten,
            fullSongFile = fullWritten,
            matchOffsetMs = if (fullReady) track.matchOffsetMs ?: 0L else 0L,
            tiktokMusicId = track.video.musicId,
            tiktokSoundName = track.video.musicTitle,
            tiktokCaption = track.video.description,
        )
        if (track.fromFullSong && fullReady && (track.matchOffsetMs ?: 0L) > 0L) {
            // A verified offset is the TikTok moment; save it as a real cue.
            runCatching {
                cueRepository.ensure(
                    soundId = stored.soundId,
                    positionMs = track.matchOffsetMs ?: 0L,
                    label = "TikTok moment",
                    kind = CueKind.MEMORY,
                )
            }
        }
        // The import is safely in the library; only now discard source caches.
        track.cacheFiles.forEach { runCatching { it.delete() } }
        return stored
    }

    // ------------------------------------------------------- chosen full song

    /**
     * The honest fallback: the user picked a specific YouTube/YouTube Music
     * recording. It is downloaded and checked against the clip before anything
     * is replaced. Only a verified match is rendered with the measured edit.
     */
    private suspend fun attachFullSong(
        soundId: Long,
        videoId: String,
        artistOverride: String?,
        parentJobId: String?,
        force: Boolean,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        if (videoId.length !in 6..20) {
            throw ImportFailureException(ImportFailureKind.CHOSEN_TRACK_MISMATCH, "No recording was selected")
        }
        val sound = soundDao.getById(soundId)
            ?: throw ImportFailureException(ImportFailureKind.SOURCE_UNAVAILABLE, "This sound is no longer in the library")
        val clipPath = sound.clipFilePath?.takeIf { it.isNotBlank() } ?: sound.filePath
        progress(ImportStage.FETCH_FULL_TRACK, 0.08f, "Opening your clip", null, null, null, null, null)
        val clipPcm = audioStore.extractToPcm(clipPath)
        if (clipPcm.size < PcmTools.SAMPLE_RATE * 3) {
            throw ImportFailureException(ImportFailureKind.NO_AUDIO, "The saved clip is too short to verify")
        }

        progress(ImportStage.FETCH_FULL_TRACK, 0.18f, "Resolving the chosen recording", null, null, null, null, null)
        val stream = runCatching { extractor.resolveStream(videoId) }.getOrNull()
        val resolved = stream as? ResolveOutcome.Ok
            ?: throw ImportFailureException(
                when (stream) {
                    is ResolveOutcome.AgeRestricted -> ImportFailureKind.SOURCE_UNAVAILABLE
                    is ResolveOutcome.RegionBlocked -> ImportFailureKind.SOURCE_UNAVAILABLE
                    else -> ImportFailureKind.SOURCE_UNAVAILABLE
                },
                when (stream) {
                    is ResolveOutcome.AgeRestricted -> "That recording is age-restricted"
                    is ResolveOutcome.RegionBlocked -> "That recording is not available in this region"
                    is ResolveOutcome.Unavailable -> stream.reason
                    else -> "That recording has no playable audio"
                },
            )
        val cache = audioStore.cacheFile("attach_" + videoId + ".m4a")
        if (!cache.isFile || cache.length() < 1_024L) {
            progress(ImportStage.FETCH_FULL_TRACK, 0.22f, "Downloading the chosen recording", null, null, null, null, null)
            runCatching {
                downloader.download(
                    url = resolved.url,
                    destination = cache,
                    onProgress = { fraction ->
                        progress(
                            ImportStage.FETCH_FULL_TRACK,
                            0.22f + fraction * 0.22f,
                            "Downloading the chosen recording",
                            null, null, null, null, null,
                        )
                    },
                    userAgent = resolved.client.userAgent,
                    referer = "https://www.youtube.com/",
                )
            }.onFailure {
                throw ImportFailureException(ImportFailureKind.NETWORK, "The chosen recording did not download", it)
            }
        }

        progress(ImportStage.EXTRACT_AUDIO, 0.48f, "Decoding the full song", null, null, null, null, null)
        val fullPcm = runCatching { audioStore.extractToPcm(cache.absolutePath) }.getOrElse {
            cache.delete()
            throw ImportFailureException(ImportFailureKind.EXTRACTION_FAILED, "Could not decode the chosen recording", it)
        }
        if (fullPcm.size < PcmTools.SAMPLE_RATE * 20) {
            cache.delete()
            throw ImportFailureException(ImportFailureKind.SOURCE_UNAVAILABLE, "That upload is too short to be a full song")
        }

        progress(ImportStage.ALIGN_EDIT, 0.62f, "Checking the recording against your clip", null, null, null, null, null)
        val proof = runCatching { AcousticMatcher.match(clipPcm, fullPcm) }.getOrNull()
        if (!force && (proof == null || !proof.verified)) {
            val confidence = proof?.score ?: 0f
            progress(ImportStage.ALIGN_EDIT, 0.62f, "", null, null, null, null, confidence)
            // Keep the downloaded file: "Use it anyway" re-runs this exact
            // candidate without making the user download it twice.
            throw ImportFailureException(
                ImportFailureKind.CHOSEN_TRACK_MISMATCH,
                "Audio match is only " + (confidence * 100).roundToInt() +
                    "%. You can keep this recording anyway.",
            )
        }
        val alignment = proof ?: AcousticMatcher.Match(verified = false)
        val verified = alignment.verified
        val applied = verified || force

        progress(ImportStage.ALIGN_EDIT, 0.74f, "Measuring the edit", null, null, null, null, null)
        val keywords = listOfNotNull(
            sound.tags.replace('|', ' ').takeIf { it.isNotBlank() },
            sound.tiktokCaption,
            sound.tiktokSoundName,
        ).joinToString(" ").lowercase()
        val effect = EffectProfiler.profile(
            clipKey = AudioAnalysis.detectKey(clipPcm, PcmTools.SAMPLE_RATE),
            clipPcm = clipPcm,
            fullPcm = fullPcm,
            tailRatio = PcmTools.reverbTailRatio(clipPcm),
            keywords = keywords,
            matched = proof,
            allowUnverified = force,
        )
        // Rendering and encoding are one long stage. Report a live local
        // fraction and an ETA based on actual elapsed work instead of jumping
        // from 86% to done with no idea whether the worker is alive.
        val encodePhaseStart = AtomicReference(SystemClock.elapsedRealtime())
        val encodeFraction = AtomicReference(0f)
        val encodePhase = AtomicReference("Rendering the full song")
        var renderedPcm: ShortArray? = null
        var writtenFile: File? = null
        coroutineScope {
            val ticker = launch {
                while (isActive) {
                    val fraction = encodeFraction.get().coerceIn(0f, 1f)
                    val phaseFraction = if (fraction <= 0.55f) {
                        fraction / 0.55f
                    } else {
                        ((fraction - 0.55f) / 0.45f).coerceIn(0f, 1f)
                    }
                    progress(
                        ImportStage.ENCODE_AAC,
                        0.80f + fraction * 0.18f,
                        encodeLabel(
                            encodePhase.get(),
                            phaseFraction,
                            SystemClock.elapsedRealtime() - encodePhaseStart.get(),
                        ),
                        null, null, null, null, null,
                    )
                    delay(1_000L)
                }
            }
            try {
                progress(ImportStage.ENCODE_AAC, 0.80f, "Rendering the full song", null, null, null, null, null)
                renderedPcm = TrackRenderer.render(
                    source = fullPcm,
                    spec = effect,
                    targetDurationMs = null,
                    loopClip = false,
                ) { fraction ->
                    encodeFraction.set(fraction.coerceIn(0f, 1f) * 0.55f)
                }
                encodePhase.set("Writing the audio file")
                encodePhaseStart.set(SystemClock.elapsedRealtime())
                encodeFraction.set(0.55f)
                progress(ImportStage.ENCODE_AAC, 0.899f, "Writing the audio file", null, null, null, null, null)
                val destination = audioStore.newSoundFile("m4a")
                writtenFile = PcmFileWriter.write(
                    pcm = renderedPcm ?: ShortArray(0),
                    sampleRate = PcmTools.SAMPLE_RATE,
                    m4a = destination,
                    onProgress = { fraction ->
                        val next = 0.55f + fraction.coerceIn(0f, 1f) * 0.45f
                        encodeFraction.accumulateAndGet(next) { old, candidate -> maxOf(old, candidate) }
                    },
                )
            } finally {
                ticker.cancel()
            }
        }
        val rendered = renderedPcm ?: throw IllegalStateException("The rendered audio was empty")
        val written = writtenFile ?: throw IllegalStateException("The encoded audio file was not written")
        val durationMs = rendered.size.toLong() * 1_000L / PcmTools.SAMPLE_RATE
        // The file is already safe on disk at this point. A metadata analyser
        // must never turn a successful encode into a failed import.
        val bpm = runCatching { AudioAnalysis.detectBpm(rendered, PcmTools.SAMPLE_RATE) }
            .getOrDefault(sound.bpm ?: 0)
        val key = runCatching {
            AudioAnalysis.detectKeyEstimate(rendered, PcmTools.SAMPLE_RATE).let {
                MusicalKey.fromPitchClass(it.pitchClass, minor = it.minor && it.confidence >= 0.04f)
            }
        }.getOrDefault(MusicalKey.fromPitchClass(0))
        val clipFile = sound.clipFilePath?.takeIf { it.isNotBlank() } ?: sound.filePath
        val oldFull = sound.fullSongFilePath?.takeIf { it.isNotBlank() }
        val oldTags = sound.tags.split('|').filterNot { tag ->
            tag == "clip only" || tag == "full song" || tag == "verified" ||
                tag == "youtube" || tag == "tiktok" || tag.startsWith("match ") ||
                tag == "slowed" || tag == "sped up" || tag == "nightcore" ||
                tag == "reverb" || tag == "bass boosted" ||
                Regex("""^\d+(?:\.\d+)?x$""").matches(tag)
        }
        val newTags = (oldTags + buildList {
            add("full song")
            add(if (verified) "verified" else "chosen")
            add("youtube")
            add("tiktok")
            effect.labels().forEach { add(it.lowercase()) }
            if (abs(effect.speed - 1f) >= 0.01f) {
                val value = (effect.speed * 100f).roundToInt() / 100f
                add(value.toString().removeSuffix(".0") + "x")
            }
            add("match " + (alignment.score * 100).roundToInt().coerceIn(0, 100) + "%")
        }).distinct()
        val artist = artistOverride
            ?.replace(Regex("(?i)\\s*[-–—]\\s*topic$"), "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        soundDao.updateFullSong(
            id = soundId,
            filePath = written.absolutePath,
            fullSongFilePath = written.absolutePath,
            clipFilePath = clipFile,
            offsetMs = if (applied) alignment.offsetMs else 0L,
            confidence = alignment.score,
            speed = effect.speed,
            durationMs = durationMs,
            sampleRate = PcmTools.SAMPLE_RATE,
            bpm = bpm,
            key = key.name,
            tags = newTags.joinToString("|"),
            artist = artist,
            waveformJson = runCatching {
                AudioAnalysis.waveform(rendered).toList().encodeWaveform()
            }.getOrElse { sound.waveformJson },
            loudnessLufs = runCatching { AudioAnalysis.loudnessLufs(rendered) }
                .getOrDefault(sound.loudnessLufs ?: -14f),
        )
        if (oldFull != null && oldFull != written.absolutePath) {
            runCatching { File(oldFull).delete() }
        }
        cache.delete()
        if (applied && alignment.offsetMs > 0L) {
            runCatching {
                cueRepository.ensure(
                    soundId = soundId,
                    positionMs = alignment.offsetMs,
                    label = "TikTok moment",
                    kind = CueKind.MEMORY,
                )
            }
        }
        parentJobId?.takeIf { it.isNotBlank() }?.let { parent ->
            runCatching {
                importRepository.report(
                    jobId = parent,
                    stage = ImportStage.DONE,
                    progress = 1f,
                    fullSongVerified = verified,
                    matchConfidence = alignment.score,
                )
            }
        }
        return Stored(soundId, sound.title, duplicate = false)
    }

    // -------------------------------------------------------------- links

    private suspend fun importLink(
        url: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        progress(ImportStage.FETCH_SOURCE, 0.04f, "Resolving link", null, null, null, null, null)
        val resolver = resolvers.forUrl(url)
            ?: throw ImportFailureException(ImportFailureKind.SOURCE_UNAVAILABLE, "No importer handles this link")
        val resolved = resolver.resolve(url) { fraction, label ->
            runBlocking {
                progress(
                    ImportStage.FETCH_SOURCE,
                    0.06f + fraction * 0.30f,
                    label,
                    null,
                    null,
                    null,
                    null,
                    null,
                )
            }
        }
        val cache = audioStore.cacheFile("link_" + stableName(url) + ".media")
        progress(ImportStage.FETCH_SOURCE, 0.40f, "Saving audio", null, null, null, null, null)
        try {
            if (!cache.isFile || cache.length() < 1_024L) {
                downloader.download(resolved.audioUrl, cache) { fraction ->
                    runBlocking {
                        progress(
                            ImportStage.FETCH_SOURCE,
                            0.40f + fraction * 0.12f,
                            "Saving audio",
                            null,
                            null,
                            null,
                            null,
                            null,
                        )
                    }
                }
            }
            val pcm = decode(cache.absolutePath, progress, 0.56f)
            val title = resolved.title.ifBlank { "Imported sound" }
            val stored = finishPcm(
                pcm = pcm,
                title = title,
                artist = resolved.author,
                source = "LINK",
                coverPath = null,
                tags = listOf("link"),
                progress = progress,
            )
            cache.delete()
            return stored
        } catch (t: Throwable) {
            // Keep the cache on failure so the next attempt resumes download.
            throw t
        }
    }

    // -------------------------------------------------------------- local

    private suspend fun importLocal(
        uri: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        progress(ImportStage.FETCH_SOURCE, 0.05f, "Reading file", null, null, null, null, null)
        val info = audioStore.info(uri)
        val pcm = decode(uri, progress, 0.20f)
        val title = displayName(uri)
        return if (info.hasVideo) {
            finishPcm(
                pcm = pcm,
                title = title,
                artist = null,
                source = "SHARE",
                coverPath = null,
                tags = listOf("shared"),
                progress = progress,
            )
        } else {
            val stored = audioStore.store(uri)
            persist(
                pcm = pcm,
                file = stored,
                title = title,
                artist = null,
                source = "SHARE",
                coverPath = null,
                tags = listOf("shared"),
                progress = progress,
            )
        }
    }

    // ------------------------------------------------------------- shared

    private suspend fun finishPcm(
        pcm: ShortArray,
        title: String,
        artist: String?,
        source: String,
        coverPath: String?,
        tags: List<String>,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
    ): Stored {
        progress(ImportStage.ENCODE_AAC, 0.80f, "Encoding audio", null, null, null, null, null)
        val file = audioStore.newSoundFile("m4a")
        val written = PcmFileWriter.write(pcm, PcmTools.SAMPLE_RATE, file)
        return persist(
            pcm = pcm,
            file = written,
            title = title,
            artist = artist,
            source = source,
            coverPath = coverPath,
            tags = tags,
            progress = progress,
        )
    }

    private suspend fun decode(
        path: String,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
        fraction: Float,
    ): ShortArray {
        progress(ImportStage.EXTRACT_AUDIO, fraction, "Extracting audio", null, null, null, null, null)
        return try {
            audioStore.extractToPcm(path)
        } catch (t: Throwable) {
            throw if (t.message?.contains("truncated", ignoreCase = true) == true) {
                ImportFailureException(ImportFailureKind.TRUNCATED_AUDIO, "The source audio stopped early", t)
            } else {
                ImportFailureException(ImportFailureKind.EXTRACTION_FAILED, "Could not decode this audio", t)
            }
        }
    }

    private suspend fun persist(
        pcm: ShortArray,
        file: File,
        title: String,
        artist: String?,
        source: String,
        coverPath: String?,
        tags: List<String>,
        progress: suspend (
            ImportStage,
            Float,
            String,
            String?,
            ImportFailureKind?,
            String?,
            Boolean?,
            Float?,
        ) -> Unit,
        speed: Float = 1f,
        fetchLyrics: Boolean = true,
        verified: Boolean? = null,
        confidence: Float? = null,
        clipFile: File? = null,
        fullSongFile: File? = null,
        matchOffsetMs: Long = 0L,
        tiktokMusicId: String? = null,
        tiktokSoundName: String? = null,
        tiktokCaption: String? = null,
    ): Stored {
        progress(ImportStage.PERSIST_LIBRARY, 0.94f, title, null, null, null, verified, confidence)
        val fingerprint = AudioAnalysis.fingerprint(pcm, PcmTools.SAMPLE_RATE)
        val existing = soundDao.findByFingerprint(fingerprint)
        if (existing != null) {
            // Never leave a half-orphaned variant behind on a duplicate.
            setOfNotNull(file, clipFile, fullSongFile).forEach { it.delete() }
            return Stored(existing.id, existing.title, duplicate = true)
        }
        val durationMs = pcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE
        val id = soundDao.upsert(
            SoundEntity(
                title = title.take(120),
                artist = artist?.take(120),
                source = source,
                durationMs = durationMs,
                sampleRate = PcmTools.SAMPLE_RATE,
                channels = 1,
                bpm = AudioAnalysis.detectBpm(pcm, PcmTools.SAMPLE_RATE),
                key = AudioAnalysis.detectKeyEstimate(pcm, PcmTools.SAMPLE_RATE).let {
                    // Only claim a minor mode when the profile is actually
                    // distinguishable from the relative major.
                    MusicalKey.fromPitchClass(
                        it.pitchClass,
                        minor = it.minor && it.confidence >= 0.04f,
                    ).name
                },
                loudnessLufs = AudioAnalysis.loudnessLufs(pcm),
                addedAt = System.currentTimeMillis(),
                filePath = file.absolutePath,
                coverPath = coverPath,
                waveformJson = AudioAnalysis.waveform(pcm).toList().encodeWaveform(),
                tags = tags.joinToString("|"),
                fingerprint = fingerprint,
                importStatus = "READY",
                clipFilePath = clipFile?.absolutePath,
                fullSongFilePath = fullSongFile?.absolutePath,
                matchOffsetMs = matchOffsetMs,
                matchConfidence = confidence,
                effectSpeed = speed,
                activeVariant = if (fullSongFile != null || source != "TIKTOK") "FULL" else "CLIP",
                tiktokMusicId = tiktokMusicId,
                tiktokSoundName = tiktokSoundName,
                tiktokCaption = tiktokCaption,
            ),
        )
        val (silenceStart, silenceEnd) = runCatching {
            AudioAnalysis.detectSilence(pcm, PcmTools.SAMPLE_RATE)
        }.getOrDefault(0L to durationMs)
        runCatching {
            File(applicationContext.filesDir, "trim_suggestions").apply { mkdirs() }
                .resolve(id.toString() + ".json")
                .writeText("{\"start\":" + silenceStart + ",\"end\":" + silenceEnd + "}")
        }
        if (fetchLyrics && durationMs >= 30_000) {
            progress(ImportStage.FETCH_LYRICS, 0.97f, "Downloading lyrics", null, null, null, verified, confidence)
            runCatching {
                lyricsRepository.getOrFetch(id, title, artist, durationMs, speed)
            }
        }
        return Stored(id, title, duplicate = false, trimStart = silenceStart, trimEnd = silenceEnd)
    }

    /** Progress line with a measured ETA, e.g. "Writing the audio file · 72% · about 8s left". */
    private fun encodeLabel(phase: String, fraction: Float, elapsedMs: Long): String {
        val clamped = fraction.coerceIn(0f, 1f)
        if (clamped <= 0.01f) return phase
        val percent = (clamped * 100f).roundToInt().coerceIn(1, 100)
        val remainingMs = if (clamped > 0f) (elapsedMs * (1f - clamped) / clamped).toLong() else 0L
        val seconds = (remainingMs / 1_000L).coerceAtLeast(1L)
        val eta = if (seconds < 60L) {
            "about " + seconds + "s left"
        } else {
            "about " + ((seconds + 59L) / 60L) + " min left"
        }
        return phase + " · " + percent + "% · " + eta
    }

    private fun classify(t: Throwable, stage: ImportStage): ImportFailureKind {
        if (t is ImportFailureException) return t.kind
        val message = t.message.orEmpty()
        return when {
            t is UnknownHostException || t is ConnectException || t is SocketTimeoutException ||
                t is SSLException || t is IOException && !message.contains("No space", true) ->
                ImportFailureKind.NETWORK
            message.contains("HTTP 429") || message.contains("rate limit", true) ->
                ImportFailureKind.RATE_LIMITED
            message.contains("HTTP 404") || message.contains("HTTP 410") ->
                ImportFailureKind.SOURCE_UNAVAILABLE
            message.contains("truncated", true) || message.contains("incomplete", true) ->
                ImportFailureKind.TRUNCATED_AUDIO
            message.contains("no audio", true) -> ImportFailureKind.NO_AUDIO
            message.contains("no space", true) || t is OutOfMemoryError ->
                ImportFailureKind.STORAGE
            stage == ImportStage.EXTRACT_AUDIO -> ImportFailureKind.EXTRACTION_FAILED
            else -> ImportFailureKind.UNKNOWN
        }
    }

    private fun friendlyError(t: Throwable): String {
        val kind = runCatching { classify(t, ImportStage.QUEUED) }.getOrDefault(ImportFailureKind.UNKNOWN)
        return when (kind) {
            ImportFailureKind.NETWORK -> "No internet connection. Your download cache is safe; retry when online."
            ImportFailureKind.RATE_LIMITED -> "The source is rate limiting requests. Try again in a minute."
            ImportFailureKind.SOURCE_UNAVAILABLE -> t.message?.take(160) ?: "This source is unavailable."
            ImportFailureKind.NO_AUDIO -> "This source has no audible audio track."
            ImportFailureKind.EXTRACTION_FAILED -> "The audio could not be decoded. Try another file."
            ImportFailureKind.TRUNCATED_AUDIO -> "The audio ended early. Retry to finish the download."
            ImportFailureKind.NO_RELIABLE_MATCH -> "No reliable full song was found; the real clip was kept."
            ImportFailureKind.LOW_CONFIDENCE_MATCH -> "The full song match was not confident; the real clip was kept."
            ImportFailureKind.CHOSEN_TRACK_MISMATCH -> t.message?.take(160)
                ?: "The chosen recording did not match this clip."
            ImportFailureKind.STORAGE -> "There is not enough free storage for this import."
            ImportFailureKind.CANCELLED -> "Import cancelled."
            ImportFailureKind.UNKNOWN -> t.message?.take(160)?.takeIf { it.isNotBlank() } ?: "Import failed."
        }
    }

    /** Local-only diagnostics; absolute paths and device names are stripped. */
    private fun diagnosticsFor(t: Throwable, stage: ImportStage, attempt: Int): String {
        val raw = (t::class.simpleName ?: "Error") + ": " + (t.message ?: "no message")
        val safe = raw
            .replace(Regex("""[A-Za-z]:\\[^\s"']+"""), "<local path>")
            .replace(Regex("""/Users/[^\s"']+"""), "<local path>")
            .replace(Regex("""/home/[^\s"']+"""), "<local path>")
            .take(900)
        return "stage=" + stage.name + " attempt=" + attempt + " " + safe
    }

    private fun stableName(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    /** "1654632929123-@user-video.mp4" -> "@user-video". */
    private fun displayName(uri: String): String {
        val raw = uri.substringAfterLast('/').substringBefore('?')
        val unescaped = runCatching { android.net.Uri.decode(raw) }.getOrDefault(raw)
        return unescaped
            .replace(Regex("^\\d{8,}-"), "")
            .substringBeforeLast('.')
            .replace('_', ' ')
            .trim()
            .ifBlank { "Imported sound" }
    }

    private data class Stored(
        val soundId: Long,
        val title: String,
        val duplicate: Boolean,
        val trimStart: Long = 0,
        val trimEnd: Long = 0,
    )

    companion object {
        const val KEY_JOB_ID = "jobId"
        const val KEY_SOURCE_URI = "sourceUri"
        const val KEY_IS_LINK = "isLink"
        const val KEY_SOUND_ID = "soundId"
        const val KEY_ATTACH_SOUND_ID = "attachSoundId"
        const val KEY_ATTACH_VIDEO_ID = "attachVideoId"
        const val KEY_ATTACH_ARTIST = "attachArtist"
        const val KEY_ATTACH_FORCE = "attachForce"
        const val KEY_PARENT_JOB_ID = "parentJobId"
        const val KEY_DUPLICATE = "duplicate"
        const val KEY_TRIM_START = "trimStart"
        const val KEY_TRIM_END = "trimEnd"
        private const val MAX_ATTEMPTS = 3
    }
}
