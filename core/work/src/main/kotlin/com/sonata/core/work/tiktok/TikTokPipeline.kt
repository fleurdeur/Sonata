package com.sonata.core.work.tiktok

import android.content.Context
import com.sonata.core.audio.render.EffectSpec
import com.sonata.core.audio.render.PcmTools
import com.sonata.core.audio.render.SpeedEstimator
import com.sonata.core.audio.render.TrackRenderer
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
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.roundToInt
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
)

/**
 * Turns a shared TikTok link into a complete, playable track:
 *
 * 1. read the post's metadata (title, artist, music, media URLs),
 * 2. download the video and decode its audio,
 * 3. obtain the **full-length** song: TikTok's own music stream when it is
 *    genuinely longer than the clip, otherwise the original recording from
 *    YouTube / YouTube Music,
 * 4. measure the clip against the full song to recover the edit's speed,
 *    pitch coupling, reverb and bass character,
 * 5. apply exactly those effects to the full song.
 *
 * A short clip is never looped into a fake full song. If no full version can
 * be found the real clip is kept as-is and tagged honestly.
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
        onStage(ImportStage.RESOLVING, 0.04f, "Connecting to TikTok")
        val video = api.resolve(url) { fraction, label ->
            onStage(ImportStage.RESOLVING, 0.04f + fraction * 0.12f, label)
        }

        val clipFile = audioStore.cacheFile("tiktok_" + video.awemeId + ".mp4")
        val tempFiles = mutableListOf<File>(clipFile)
        if (!clipFile.exists() || clipFile.length() == 0L) {
            val media = video.videoUrl ?: throw IllegalStateException("TikTok did not expose this video")
            onStage(ImportStage.DOWNLOADING, 0.2f, "Saving the video")
            downloader.download(media, clipFile) { fraction ->
                onStage(ImportStage.DOWNLOADING, 0.2f + fraction * 0.28f, "Saving the video")
            }
        }

        try {
            onStage(ImportStage.EXTRACTING, 0.52f, "Separating the audio")
            val clipPcm = audioStore.extractToPcm(clipFile.absolutePath)
            if (clipPcm.isEmpty()) throw IllegalStateException("This video has no audible track")

            onStage(ImportStage.ANALYZING, 0.60f, "Analysing the sound")
            val clipKey = AudioAnalysis.detectKey(clipPcm, PcmTools.SAMPLE_RATE)
            val tailRatio = PcmTools.reverbTailRatio(clipPcm)
            val keywords = (video.musicTitle.orEmpty() + " " + video.description).lowercase()
            val clipMs = clipPcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE

            val tiktokFull = downloadFullSound(video, clipMs, onStage)
            tiktokFull?.let(tempFiles::add)
            var fullPcm = tiktokFull?.let { decodeFull(it, "Matching the full song", onStage) }

            var sourceLabel = if (fullPcm != null) "tiktok" else null
            var sourceCandidate: FullTrackCandidate? = null

            // A short TikTok music stream is usually just the clip. Only a
            // clearly longer stream counts as the full song.
            if (fullPcm != null && !isFullLength(fullPcm, clipMs)) {
                fullPcm = null
                sourceLabel = null
            }

            if (fullPcm == null) {
                onStage(ImportStage.ANALYZING, 0.70f, "Finding the full song")
                val queryTitle = video.displayTitle
                // An "original sound" is a user recording, not a licensed
                // track; searching its caption would often download an
                // unrelated song. Keep the real clip instead.
                val candidate = if (video.isOriginalSound) {
                    null
                } else {
                    fullTrackFinder.find(queryTitle, video.displayArtist, clipMs)
                }
                if (candidate != null) {
                    onStage(ImportStage.DOWNLOADING, 0.74f, "Downloading the full song")
                    val stream = runCatching { extractor.resolveStream(candidate.videoId) }.getOrNull()
                    if (stream is ResolveOutcome.Ok) {
                        val file = audioStore.cacheFile("youtube_" + candidate.videoId + ".m4a")
                        tempFiles.add(file)
                        val downloaded = runCatching {
                            if (!file.exists() || file.length() == 0L) {
                                downloader.download(
                                    url = stream.url,
                                    destination = file,
                                    onProgress = { fraction ->
                                        onStage(ImportStage.DOWNLOADING, 0.74f + fraction * 0.10f, "Downloading the full song")
                                    },
                                    userAgent = stream.client.userAgent,
                                    referer = "https://www.youtube.com/",
                                )
                            }
                            file
                        }.getOrNull()
                        if (downloaded != null) {
                            fullPcm = decodeFull(downloaded, "Matching the full song", onStage)
                            if (fullPcm != null && isFullLength(fullPcm, clipMs)) {
                                sourceLabel = "youtube"
                                sourceCandidate = candidate
                            } else {
                                fullPcm = null
                            }
                        }
                    }
                }
            }

            val coverPath = coroutineScope {
                val remote = sourceCandidate?.thumbnailUrl
                    ?: sourceCandidate?.let { "https://i.ytimg.com/vi/" + it.videoId + "/hqdefault.jpg" }
                    ?: video.coverUrl
                async {
                    remote?.let { downloadCover(video.awemeId, it) }
                }.await()
            }

            val effect = if (fullPcm != null) {
                detectEffect(clipKey, clipPcm, fullPcm, tailRatio, keywords)
            } else {
                EffectSpec()
            }

            onStage(ImportStage.RENDERING, 0.90f, "Rendering the track")
            val fromFull = fullPcm != null
            val rendered = if (fullPcm != null) {
                TrackRenderer.render(
                    // Keep the full recording's timeline intact: trimming its
                    // intro would shift every downloaded lyric line.
                    source = fullPcm,
                    spec = effect,
                    targetDurationMs = null,
                    loopClip = false,
                )
            } else {
                // Keep the actual clip. Do not loop it into a fabricated
                // two-minute track; a short real recording is the honest result.
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
            val track = TikTokTrack(
                video = video,
                title = video.displayTitle,
                artist = artist,
                pcm = rendered,
                tags = buildTags(video, effect, fromFull, sourceLabel),
                coverPath = coverPath,
                fromFullSong = fromFull,
                effect = effect,
            )
            onStage(ImportStage.ANALYZING, 0.96f, "Finishing")
            return track
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }
    }

    private suspend fun decodeFull(
        file: File,
        label: String,
        onStage: suspend (ImportStage, Float, String) -> Unit,
    ): ShortArray? {
        onStage(ImportStage.ANALYZING, 0.66f, label)
        return runCatching { audioStore.extractToPcm(file.absolutePath) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun isFullLength(pcm: ShortArray, clipMs: Long): Boolean {
        val ms = pcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE
        return ms >= 45_000 && ms >= clipMs + 15_000
    }

    /** Downloads TikTok's longer music stream when it is genuinely long. */
    private suspend fun downloadFullSound(
        video: TikTokVideo,
        clipMs: Long,
        onStage: suspend (ImportStage, Float, String) -> Unit,
    ): File? {
        val url = video.musicAudioUrl ?: return null
        if (video.isOriginalSound) return null
        if (video.musicDurationMs in 1..(clipMs + 15_000)) return null
        if (video.musicDurationMs in 1..44_999) return null
        val file = audioStore.cacheFile("tiktok_music_" + video.awemeId + ".m4a")
        return runCatching {
            if (!file.exists() || file.length() == 0L) {
                onStage(ImportStage.DOWNLOADING, 0.64f, "Fetching the full song")
                downloader.download(url, file) { fraction ->
                    onStage(ImportStage.DOWNLOADING, 0.64f + fraction * 0.07f, "Fetching the full song")
                }
            }
            file
        }.getOrNull()
    }

    private suspend fun downloadCover(awemeId: String, url: String): String? = runCatching {
        val dir = File(context.filesDir, "covers").apply { mkdirs() }
        val file = File(dir, awemeId + ".jpg")
        if (!file.exists() || file.length() == 0L) downloader.download(url, file)
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

        // Key analysis on the first 90 s keeps a three-minute song quick.
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

        // The clip and the full track may be different masters, so compare
        // their tails instead of assuming either one is already processed.
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
    ): List<String> = buildList {
        add("tiktok")
        video.authorHandle?.takeIf { it.isNotBlank() }?.let { add("@" + it) }
        add(if (fullSong) "full song" else "clip only")
        if (source == "youtube") add("youtube")
        effect.labels().forEach { add(it.lowercase()) }
        if (abs(effect.speed - 1f) >= 0.01f) {
            val value = (effect.speed * 100f).roundToInt() / 100f
            add(value.toString().removeSuffix(".0") + "x")
        }
    }
}