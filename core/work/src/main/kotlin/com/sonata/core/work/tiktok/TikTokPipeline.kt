package com.sonata.core.work.tiktok

import android.content.Context
import com.sonata.core.audio.render.EffectSpec
import com.sonata.core.audio.render.PcmTools
import com.sonata.core.audio.render.SpeedEstimator
import com.sonata.core.audio.render.TrackRenderer
import com.sonata.core.domain.model.ImportFailureKind
import com.sonata.core.domain.model.ImportStage
import com.sonata.core.extractor.bitchord.BitchordExtractor
import com.sonata.core.extractor.bitchord.FullTrackCandidate
import com.sonata.core.extractor.bitchord.FullTrackFinder
import com.sonata.core.extractor.bitchord.ResolveOutcome
import com.sonata.core.extractor.tiktok.MediaDownloader
import com.sonata.core.extractor.tiktok.TikTokApi
import com.sonata.core.extractor.tiktok.TikTokVideo
import com.sonata.core.work.analysis.AudioAnalysis
import com.sonata.core.work.pipeline.AudioStore
import com.sonata.core.work.pipeline.ImportFailureException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** A finished TikTok import, ready to be inserted into the library. */
data class TikTokTrack(
    val video: TikTokVideo,
    val title: String,
    val artist: String?,
    val pcm: ShortArray,
    val tags: List<String>,
    val coverPath: String?,
    val fromFullSong: Boolean,
    val effect: EffectSpec,
    val matchConfidence: Float? = null,
    val matchOffsetMs: Long? = null,
    val diagnostics: String? = null,
    /** Source caches kept for a retry and deleted only after a successful save. */
    val cacheFiles: List<File> = emptyList(),
)

/**
 * Turns a shared TikTok link into a complete, playable track:
 *
 * 1. read the post's metadata and save the video,
 * 2. decode the clip to PCM,
 * 3. obtain the full-length song: TikTok's own music stream when it is
 *    genuinely longer, otherwise the original recording from YouTube,
 * 4. verify that the full recording is really the same song (acoustic
 *    correlation + metadata + spectral similarity),
 * 5. measure speed/pitch/reverb/bass and apply exactly those effects.
 *
 * A short clip is never looped into a fake full song. If no full version can
 * be verified the real clip is kept as-is and tagged honestly.
 */
@Singleton
class TikTokPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: TikTokApi,
    private val downloader: MediaDownloader,
    private val audioStore: AudioStore,
    private val fullTrackFinder: FullTrackFinder,
    private val extractor: BitchordExtractor,
) {

    suspend fun process(
        url: String,
        onStage: suspend (ImportStage, Float, String) -> Unit,
    ): TikTokTrack {
        onStage(ImportStage.FETCH_SOURCE, 0.04f, "Connecting to TikTok")
        val video = ioRetry("TikTok metadata") {
            api.resolve(url) { fraction, label ->
                onStage(ImportStage.FETCH_SOURCE, 0.04f + fraction * 0.10f, label)
            }
        }

        val cacheFiles = mutableListOf<File>()
        val clipFile = audioStore.cacheFile("tiktok_" + video.awemeId + ".mp4")
        cacheFiles += clipFile
        if (!isUsableMedia(clipFile)) {
            val media = video.videoUrl
                ?: throw ImportFailureException(
                    ImportFailureKind.SOURCE_UNAVAILABLE,
                    "TikTok did not expose a downloadable video",
                )
            onStage(ImportStage.FETCH_SOURCE, 0.18f, "Saving the video")
            ioRetry("TikTok video") {
                downloader.download(media, clipFile) { fraction ->
                    onStage(ImportStage.FETCH_SOURCE, 0.18f + fraction * 0.22f, "Saving the video")
                }
            }
        }
        if (!isUsableMedia(clipFile)) {
            throw ImportFailureException(ImportFailureKind.NO_AUDIO, "This video has no audio track")
        }

        onStage(ImportStage.EXTRACT_AUDIO, 0.42f, "Separating the audio")
        val clipPcm = try {
            audioStore.extractToPcm(clipFile.absolutePath)
        } catch (t: Throwable) {
            throw ImportFailureException(
                ImportFailureKind.EXTRACTION_FAILED,
                "Could not decode this video's audio",
                t,
            )
        }
        if (clipPcm.isEmpty()) {
            throw ImportFailureException(ImportFailureKind.NO_AUDIO, "This video has no audible track")
        }

        onStage(ImportStage.IDENTIFY_SONG, 0.54f, "Analysing the sound")
        val clipKey = AudioAnalysis.detectKey(clipPcm, PcmTools.SAMPLE_RATE)
        val tailRatio = PcmTools.reverbTailRatio(clipPcm)
        val keywords = (video.musicTitle.orEmpty() + " " + video.description).lowercase()
        val clipMs = clipPcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE

        var sourceLabel: String? = null
        var sourceCandidate: FullTrackCandidate? = null
        var fullPcm: ShortArray? = null
        var matchConfidence: Float? = null
        var matchOffsetMs: Long? = null
        var diagnostics: String? = null
        var verified = false

        // TikTok's music stream is the same recording by definition; use it
        // when it is genuinely full length, without a risky web search.
        val tiktokFullFile = downloadFullSound(video, clipMs, cacheFiles, onStage)
        if (tiktokFullFile != null) {
            onStage(ImportStage.EXTRACT_AUDIO, 0.62f, "Checking TikTok's full sound")
            val candidatePcm = decodeOrNull(tiktokFullFile)
            if (candidatePcm != null && isFullLength(candidatePcm, clipMs)) {
                fullPcm = candidatePcm
                sourceLabel = "tiktok"
                matchConfidence = 1f
                verified = true
            } else if (candidatePcm != null) {
                diagnostics = "TikTok's music stream was only the edit; searched for the full song."
            }
        }

        if (fullPcm == null) {
            onStage(ImportStage.IDENTIFY_SONG, 0.66f, "Finding the full song")
            val query = video.fullTrackQuery ?: video.displayTitle
            val candidate = runCatching {
                fullTrackFinder.find(query, video.displayArtist, clipMs)
            }.getOrNull()
            if (candidate != null) {
                onStage(ImportStage.FETCH_FULL_TRACK, 0.70f, "Downloading the full song")
                val stream = runCatching {
                    ioRetry("YouTube stream") { extractor.resolveStream(candidate.videoId) }
                }.getOrNull()
                when (stream) {
                    is ResolveOutcome.Ok -> {
                        val file = audioStore.cacheFile("youtube_" + candidate.videoId + ".m4a")
                        cacheFiles += file
                        val downloaded = runCatching {
                            if (!isUsableMedia(file)) {
                                ioRetry("YouTube audio") {
                                    downloader.download(
                                        url = stream.url,
                                        destination = file,
                                        onProgress = { fraction ->
                                            onStage(
                                                ImportStage.FETCH_FULL_TRACK,
                                                0.70f + fraction * 0.10f,
                                                "Downloading the full song",
                                            )
                                        },
                                        userAgent = stream.client.userAgent,
                                        referer = "https://www.youtube.com/",
                                    )
                                }
                            }
                            file
                        }.getOrNull()
                        if (downloaded != null) {
                            onStage(ImportStage.EXTRACT_AUDIO, 0.81f, "Matching the full recording")
                            val candidatePcm = decodeOrNull(downloaded)
                            if (candidatePcm != null && isFullLength(candidatePcm, clipMs)) {
                                val estimate = SpeedEstimator.estimate(clipPcm, candidatePcm, PcmTools.SAMPLE_RATE)
                                val spectral = AudioAnalysis.spectralSimilarity(clipPcm, candidatePcm)
                                val acoustic = estimate.confidence.toFloat()
                                val metadata = candidate.score
                                val confidence = (
                                    0.55f * acoustic +
                                        0.30f * metadata +
                                        0.15f * spectral
                                    ).coerceIn(0f, 0.99f)
                                val accepted = acoustic >= MIN_ACOUSTIC_CONFIDENCE ||
                                    (metadata >= 0.72f && spectral >= MIN_SPECTRAL_SIMILARITY)
                                if (accepted) {
                                    fullPcm = candidatePcm
                                    sourceLabel = "youtube"
                                    sourceCandidate = candidate
                                    matchConfidence = confidence
                                    matchOffsetMs = estimate.offsetMs
                                    verified = true
                                    diagnostics = "Verified with acoustic " +
                                        (acoustic * 100).roundToInt() + "%, metadata " +
                                        (metadata * 100).roundToInt() + "%, spectrum " +
                                        (spectral * 100).roundToInt() + "%."
                                } else {
                                    diagnostics = "Rejected a possible full-song match: acoustic " +
                                        (acoustic * 100).roundToInt() + "%, metadata " +
                                        (metadata * 100).roundToInt() + "%, spectrum " +
                                        (spectral * 100).roundToInt() +
                                        "%. Kept the real clip instead."
                                }
                            } else {
                                diagnostics = "The full-song download was incomplete or too short; kept the real clip."
                            }
                        }
                    }
                    is ResolveOutcome.AgeRestricted ->
                        diagnostics = "The full song on YouTube is age-restricted; kept the real clip."
                    is ResolveOutcome.RegionBlocked ->
                        diagnostics = "The full song is not available in this region; kept the real clip."
                    is ResolveOutcome.Unavailable ->
                        diagnostics = "No playable full-song stream was available; kept the real clip."
                    null -> diagnostics = "Could not resolve the full-song stream; kept the real clip."
                }
            }
        }

        val coverPath = downloadCover(
            video.awemeId,
            sourceCandidate?.thumbnailUrl
                ?: sourceCandidate?.let { "https://i.ytimg.com/vi/" + it.videoId + "/hqdefault.jpg" }
                ?: video.coverUrl,
        )

        onStage(ImportStage.ALIGN_EDIT, 0.86f, "Matching the edit's effects")
        val effect = if (fullPcm != null) {
            detectEffect(clipKey, clipPcm, fullPcm, tailRatio, keywords)
        } else {
            EffectSpec()
        }

        val fromFull = fullPcm != null && verified
        onStage(ImportStage.ALIGN_EDIT, 0.89f, "Rendering the track")
        val rendered = if (fullPcm != null && fromFull) {
            TrackRenderer.render(
                source = fullPcm,
                spec = effect,
                targetDurationMs = null,
                loopClip = false,
            )
        } else {
            TrackRenderer.render(
                source = trimSilence(clipPcm),
                spec = effect,
                targetDurationMs = null,
                loopClip = false,
                fadeOutMs = 600,
            )
        }

        val artist = when {
            sourceLabel == "youtube" -> sourceCandidate?.artist ?: video.displayArtist
            else -> video.displayArtist ?: sourceCandidate?.artist
        }
        val title = if (sourceLabel == "youtube") {
            sourceCandidate?.title?.takeIf { it.isNotBlank() } ?: video.displayTitle
        } else {
            video.displayTitle
        }
        return TikTokTrack(
            video = video,
            title = title,
            artist = artist,
            pcm = rendered,
            tags = buildTags(video, effect, fromFull, sourceLabel, verified, matchConfidence),
            coverPath = coverPath,
            fromFullSong = fromFull,
            effect = effect,
            matchConfidence = matchConfidence,
            matchOffsetMs = matchOffsetMs,
            diagnostics = diagnostics,
            cacheFiles = cacheFiles,
        )
    }

    private suspend fun isUsableMedia(file: File): Boolean {
        if (!file.isFile || file.length() < 1_024L) return false
        val info = runCatching { audioStore.info(file.absolutePath) }.getOrNull()
        return info?.hasAudio == true && info.durationMs > 500L
    }

    private suspend fun decodeOrNull(file: File): ShortArray? =
        runCatching { audioStore.extractToPcm(file.absolutePath) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }

    private fun isFullLength(pcm: ShortArray, clipMs: Long): Boolean {
        val ms = pcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE
        return ms >= 45_000 && ms >= clipMs + 15_000
    }

    /** Downloads TikTok's longer music stream when it is genuinely long. */
    private suspend fun downloadFullSound(
        video: TikTokVideo,
        clipMs: Long,
        cacheFiles: MutableList<File>,
        onStage: suspend (ImportStage, Float, String) -> Unit,
    ): File? {
        val url = video.musicAudioUrl ?: return null
        if (video.isOriginalSound) return null
        if (video.musicDurationMs in 1..(clipMs + 15_000)) return null
        if (video.musicDurationMs in 1..44_999) return null
        val file = audioStore.cacheFile("tiktok_music_" + video.awemeId + ".m4a")
        cacheFiles += file
        return runCatching {
            if (!isUsableMedia(file)) {
                onStage(ImportStage.FETCH_FULL_TRACK, 0.58f, "Fetching TikTok's full sound")
                ioRetry("TikTok full sound") {
                    downloader.download(url, file) { fraction ->
                        onStage(
                            ImportStage.FETCH_FULL_TRACK,
                            0.58f + fraction * 0.05f,
                            "Fetching TikTok's full sound",
                        )
                    }
                }
            }
            file
        }.getOrNull()
    }

    private suspend fun downloadCover(awemeId: String, url: String?): String? = runCatching {
        if (url.isNullOrBlank()) return@runCatching null
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, awemeId + ".jpg")
        if (!file.isFile || file.length() < 64L) {
            ioRetry("Cover download") { downloader.download(url, file) }
        }
        file.absolutePath
    }.getOrNull()

    /**
     * Recovers the edit from the clip. Speed comes from matching the clip's
     * onset envelope against the long sound at several candidate speeds, never
     * from a beat counter that can report half or double tempo. If the match is
     * not convincing the song is left at its own tempo.
     */
    private fun detectEffect(
        clipKey: Int,
        clipPcm: ShortArray,
        fullPcm: ShortArray,
        tailRatio: Double,
        keywords: String,
    ): EffectSpec {
        val estimate = SpeedEstimator.estimate(clipPcm, fullPcm, PcmTools.SAMPLE_RATE)
        val speed = estimate.speed

        val analysisWindow = if (fullPcm.size > 90 * PcmTools.SAMPLE_RATE) {
            fullPcm.copyOfRange(0, 90 * PcmTools.SAMPLE_RATE)
        } else {
            fullPcm
        }
        val fullKey = AudioAnalysis.detectKey(analysisWindow, PcmTools.SAMPLE_RATE)
        val semitones = PcmTools.semitoneDelta(fullKey, clipKey)
        val expected = 12f * log2(speed)
        val saysNightcore = keywords.contains("nightcore")
        val coupled = when {
            speed == 1f -> false
            saysNightcore -> true
            abs(semitones - expected) <= 1.2f && abs(semitones) >= 1 -> true
            else -> false
        }

        val fullTail = PcmTools.reverbTailRatio(analysisWindow)
        val extraTail = tailRatio - fullTail
        val saysReverb = keywords.contains("reverb")
        val reverb = when {
            extraTail >= 0.22 -> 0.26f
            extraTail >= 0.13 -> 0.16f
            saysReverb && extraTail >= 0.04 -> 0.14f
            else -> 0f
        }
        val bass = if (keywords.contains("bass boost") || keywords.contains("bass boosted")) {
            3f
        } else {
            0f
        }
        return EffectSpec(speed = speed, pitchCoupled = coupled, reverbMix = reverb, bassDb = bass)
    }

    private fun trimSilence(pcm: ShortArray): ShortArray {
        val bounds = PcmTools.contentBounds(pcm)
        if (bounds.first <= 0 && bounds.last >= pcm.size - 1) return pcm
        val pad = PcmTools.SAMPLE_RATE / 50
        val start = (bounds.first - pad).coerceAtLeast(0)
        val end = (bounds.last + pad).coerceAtMost(pcm.size)
        return pcm.copyOfRange(start, end)
    }

    private fun buildTags(
        video: TikTokVideo,
        effect: EffectSpec,
        fullSong: Boolean,
        source: String?,
        verified: Boolean,
        confidence: Float?,
    ): List<String> = buildList {
        add("tiktok")
        video.authorHandle?.takeIf { it.isNotBlank() }?.let { add("@" + it) }
        add(if (fullSong) "full song" else "clip only")
        if (fullSong && verified) add("verified")
        if (verified && confidence != null) {
            add("match " + (confidence * 100).roundToInt().coerceIn(0, 100) + "%")
        }
        if (source == "youtube") add("youtube")
        effect.labels().forEach { add(it.lowercase()) }
        if (abs(effect.speed - 1f) >= 0.01f) {
            val value = (effect.speed * 100f).roundToInt() / 100f
            add(value.toString().removeSuffix(".0") + "x")
        }
    }

    private suspend fun <T> ioRetry(label: String, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(MAX_IO_ATTEMPTS) { attempt ->
            try {
                return block()
            } catch (t: Throwable) {
                last = t
                val transient = t is IOException ||
                    t.message?.contains("HTTP 5") == true ||
                    t.message?.contains("HTTP 429") == true
                if (!transient || attempt == MAX_IO_ATTEMPTS - 1) throw t
                delay(500L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("$label failed")
    }

    private companion object {
        const val MAX_IO_ATTEMPTS = 3
        const val MIN_ACOUSTIC_CONFIDENCE = 0.30f
        const val MIN_SPECTRAL_SIMILARITY = 0.50f
    }
}
