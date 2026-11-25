package com.sonata.core.work.tiktok

import com.sonata.core.audio.render.EffectSpec
import com.sonata.core.audio.render.PcmTools
import com.sonata.core.audio.render.TrackRenderer
import com.sonata.core.domain.model.ImportStage
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
import android.content.Context

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
 * 3. fetch the full-length sound when TikTok exposes it,
 * 4. measure the clip against the full song to recover the edit's speed,
 *    pitch coupling, reverb and bass character,
 * 5. render the final audio, looping a short clip into a full track when the
 *    sound only exists as part of the video.
 */
@Singleton
class TikTokPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: TikTokApi,
    private val downloader: MediaDownloader,
    private val audioStore: AudioStore,
) {

    suspend fun process(
        url: String,
        onStage: suspend (ImportStage, Float, String) -> Unit,
    ): TikTokTrack {
        onStage(ImportStage.RESOLVING, 0.04f, "Connecting to TikTok")
        val video = api.resolve(url) { fraction, label ->
            onStage(ImportStage.RESOLVING, 0.04f + fraction * 0.12f, label)
        }

        val clipFile = audioStore.cacheFile("tiktok_${video.awemeId}.mp4")
        if (!clipFile.exists() || clipFile.length() == 0L) {
            val media = video.videoUrl ?: throw IllegalStateException("TikTok did not expose this video")
            onStage(ImportStage.DOWNLOADING, 0.2f, "Saving the video")
            downloader.download(media, clipFile) { fraction ->
                onStage(ImportStage.DOWNLOADING, 0.2f + fraction * 0.3f, "Saving the video")
            }
        }

        onStage(ImportStage.EXTRACTING, 0.55f, "Separating the audio")
        val clipPcm = audioStore.extractToPcm(clipFile.absolutePath)
        if (clipPcm.isEmpty()) throw IllegalStateException("This video has no audible track")

        onStage(ImportStage.ANALYZING, 0.62f, "Analysing the sound")
        val clipBpm = AudioAnalysis.detectBpm(clipPcm, PcmTools.SAMPLE_RATE)
        val clipKey = AudioAnalysis.detectKey(clipPcm, PcmTools.SAMPLE_RATE)
        val tailRatio = PcmTools.reverbTailRatio(clipPcm)
        val keywords = "${video.musicTitle.orEmpty()} ${video.description}".lowercase()

        val fullFile = downloadFullSound(video, clipPcm, onStage)
        val fullPcm = fullFile?.let { file ->
            onStage(ImportStage.ANALYZING, 0.78f, "Matching the full song")
            audioStore.extractToPcm(file.absolutePath).takeIf { it.isNotEmpty() }
        }

        val coverPath = video.coverUrl?.let { url ->
            runCatching {
                val file = File(File(context.filesDir, "covers").apply { mkdirs() }, "${video.awemeId}.jpg")
                if (!file.exists()) downloader.download(url, file)
                file.absolutePath
            }.getOrNull()
        }

        onStage(ImportStage.RENDERING, 0.86f, "Rendering the track")
        val track = if (fullPcm != null) {
            val effect = detectEffect(clipKey, fullPcm, clipBpm, tailRatio, keywords)
            val trimmed = trimSilence(fullPcm)
            val rendered = TrackRenderer.render(
                source = trimmed,
                spec = effect,
                targetDurationMs = null,
                loopClip = false,
            )
            TikTokTrack(
                video = video,
                title = video.displayTitle,
                artist = video.displayArtist,
                pcm = rendered,
                tags = buildTags(video, effect, fullSong = true),
                coverPath = coverPath,
                fromFullSong = true,
                effect = effect,
            )
        } else {
            // The sound only exists inside the video: keep its character and
            // loop it into a full-length track instead of shipping a 15s file.
            val targetMs = if (clipPcm.size < 90 * PcmTools.SAMPLE_RATE) 120_000L
            else clipPcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE
            val effect = keywordEffect(keywords)
            val rendered = TrackRenderer.render(
                source = trimSilence(clipPcm),
                spec = effect,
                targetDurationMs = targetMs,
                loopClip = true,
            )
            TikTokTrack(
                video = video,
                title = video.displayTitle,
                artist = video.displayArtist,
                pcm = rendered,
                tags = buildTags(video, effect, fullSong = false),
                coverPath = coverPath,
                fromFullSong = false,
                effect = effect,
            )
        }
        clipFile.delete()
        fullFile?.delete()
        onStage(ImportStage.ANALYZING, 0.94f, "Finishing")
        return track
    }

    /** Downloads the longer sound when TikTok exposes one. */
    private suspend fun downloadFullSound(
        video: TikTokVideo,
        clipPcm: ShortArray,
        onStage: suspend (ImportStage, Float, String) -> Unit,
    ): File? {
        val url = video.musicAudioUrl ?: return null
        if (video.isOriginalSound) return null
        val clipMs = clipPcm.size.toLong() * 1000 / PcmTools.SAMPLE_RATE
        // Only worth it when the sound is meaningfully longer than the clip.
        if (video.musicDurationMs in 1..(clipMs + 15_000)) return null
        val file = audioStore.cacheFile("tiktok_music_${video.awemeId}.m4a")
        return runCatching {
            if (!file.exists() || file.length() == 0L) {
                onStage(ImportStage.DOWNLOADING, 0.68f, "Fetching the full song")
                downloader.download(url, file) { fraction ->
                    onStage(ImportStage.DOWNLOADING, 0.68f + fraction * 0.08f, "Fetching the full song")
                }
            }
            file
        }.getOrNull()
    }

    /**
     * Recovers the edit from the clip: tempo ratio against the original song,
     * whether the pitch moved with it, the reverb tail and bass character.
     */
    private fun detectEffect(
        clipKey: Int,
        fullPcm: ShortArray,
        clipBpm: Int,
        tailRatio: Double,
        keywords: String,
    ): EffectSpec {
        val fullBpm = AudioAnalysis.detectBpm(fullPcm, PcmTools.SAMPLE_RATE)
        var speed = if (fullBpm in 40..220 && clipBpm in 40..220) clipBpm.toFloat() / fullBpm else 1f
        if (speed !in 0.75f..1.4f || abs(speed - 1f) < 0.02f) speed = 1f

        val fullKey = AudioAnalysis.detectKey(fullPcm, PcmTools.SAMPLE_RATE)
        val semitones = PcmTools.semitoneDelta(fullKey, clipKey)
        val expected = 12f * log2(speed)
        val keywordNightcore = keywords.contains("nightcore") || keywords.contains("sped up + pitch")
        val coupled = when {
            speed == 1f -> false
            keywordNightcore -> true
            abs(semitones - expected) <= 1.2f -> true
            else -> keywords.contains("slowed + reverb").not() && abs(semitones) >= 1 &&
                abs(semitones - expected) < 2.2f
        }

        var reverb = when {
            tailRatio >= 0.55 -> 0.34f
            tailRatio >= 0.42 -> 0.22f
            tailRatio >= 0.34 -> 0.12f
            else -> 0f
        }
        if (keywords.contains("reverb") || keywords.contains("slowed + reverb")) reverb = maxOf(reverb, 0.28f)

        val bass = if (keywords.contains("bass boost") || keywords.contains("bass boosted")) 5f else 0f
        return EffectSpec(speed = speed, pitchCoupled = coupled, reverbMix = reverb, bassDb = bass)
    }

    /**
     * When the sound only exists inside the video its effects are already
     * baked in, so no reverb is added again. A small lift restores the low end
     * lost in the mono downmix when the metadata says the sound was boosted.
     */
    private fun keywordEffect(keywords: String): EffectSpec {
        val bass = if (keywords.contains("bass boost") || keywords.contains("bass boosted")) 1.5f else 0f
        return EffectSpec(speed = 1f, pitchCoupled = false, reverbMix = 0f, bassDb = bass)
    }

    private fun trimSilence(pcm: ShortArray): ShortArray {
        val bounds = PcmTools.contentBounds(pcm)
        if (bounds.first <= 0 && bounds.last >= pcm.size - 1) return pcm
        val pad = PcmTools.SAMPLE_RATE / 50
        val start = (bounds.first - pad).coerceAtLeast(0)
        val end = (bounds.last + pad).coerceAtMost(pcm.size)
        return pcm.copyOfRange(start, end)
    }

    private fun buildTags(video: TikTokVideo, effect: EffectSpec, fullSong: Boolean): List<String> = buildList {
        add("tiktok")
        video.authorHandle?.takeIf { it.isNotBlank() }?.let { add("@$it") }
        add(if (fullSong) "full song" else "looped")
        effect.labels().forEach { add(it.lowercase()) }
    }
}