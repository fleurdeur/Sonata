package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.PresetDao
import com.sonata.core.database.entity.PresetEntity
import com.sonata.core.domain.model.BandFilterType
import com.sonata.core.domain.model.CompressorSettings
import com.sonata.core.domain.model.LimiterSettings
import com.sonata.core.domain.model.ParametricBand
import com.sonata.core.domain.model.Preset
import com.sonata.core.domain.model.PresetCategory
import com.sonata.core.domain.repository.PresetRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@kotlinx.serialization.Serializable
data class BandJson(
    val frequency: Float,
    val gain: Float,
    val q: Float,
    val type: String = "PEAK",
    val enabled: Boolean = true,
)

@kotlinx.serialization.Serializable
data class CompressorJson(
    val crossovers: List<Float> = listOf(150f, 600f, 4000f),
    val thresholds: List<Float> = listOf(-20f, -20f, -20f, -20f),
    val ratios: List<Float> = listOf(3f, 3f, 3f, 3f),
    val attacks: List<Int> = listOf(20, 20, 20, 20),
    val releases: List<Int> = listOf(200, 200, 200, 200),
    val knee: Float = 6f,
)

@kotlinx.serialization.Serializable
data class LimiterJson(
    val ceilingDb: Float = -1f,
    val lookaheadMs: Int = 5,
    val releaseMs: Int = 120,
    val inputGainDb: Float = 0f,
)

@Singleton
class PresetRepositoryImpl @Inject constructor(
    private val dao: PresetDao,
    @IoDispatcher private val io: CoroutineDispatcher,
) : PresetRepository {

    private val json = Json { ignoreUnknownKeys = true }

    override fun observeAll(): Flow<List<Preset>> = dao.observeAll().map { it.map { e -> e.toModel() } }

    override fun observeFavorites(): Flow<List<Preset>> =
        observeAll().map { all -> all.filter { it.isFavorite } }

    override suspend fun upsert(preset: Preset): Long = withContext(io) { dao.upsert(preset.toEntity()) }

    override suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    override suspend fun rename(id: Long, name: String) = withContext(io) { dao.rename(id, name) }

    override suspend fun duplicate(id: Long): Long = withContext(io) {
        val entity = dao.getById(id) ?: return@withContext 0
        dao.upsert(entity.copy(id = 0, name = entity.name + " copy"))
    }

    override suspend fun seedIfEmpty() = withContext(io) {
        if (dao.count() > 0) return@withContext
        PresetLibrary.all.forEach { dao.upsert(it.toEntity()) }
    }

    private fun PresetEntity.toModel() = Preset(
        id = id,
        name = name,
        category = runCatching { PresetCategory.valueOf(category) }.getOrDefault(PresetCategory.MINE),
        description = description,
        bands = runCatching {
            json.decodeFromString(ListSerializer(BandJson.serializer()), bandsJson)
        }.getOrDefault(emptyList()).mapIndexed { i, b ->
            ParametricBand(
                index = i,
                type = runCatching { BandFilterType.valueOf(b.type) }.getOrDefault(BandFilterType.PEAK),
                frequency = b.frequency,
                gainDb = b.gain,
                q = b.q,
                enabled = b.enabled,
            )
        },
        compressor = compressorJson?.let { raw ->
            runCatching {
                val c = json.decodeFromString(CompressorJson.serializer(), raw)
                CompressorSettings(c.crossovers, c.thresholds, c.ratios, c.attacks, c.releases, c.knee)
            }.getOrNull()
        },
        limiter = limiterJson?.let { raw ->
            runCatching {
                val l = json.decodeFromString(LimiterJson.serializer(), raw)
                LimiterSettings(l.ceilingDb, l.lookaheadMs, l.releaseMs, l.inputGainDb)
            }.getOrNull()
        },
        effects = effects.split("|").mapNotNull { raw ->
            runCatching { Preset.DspEffect.valueOf(raw) }.getOrNull()
        }.toSet(),
        isFavorite = isFavorite,
    )

    private fun Preset.toEntity() = PresetEntity(
        id = id,
        name = name,
        category = category.name,
        description = description,
        bandsJson = json.encodeToString(
            ListSerializer(BandJson.serializer()),
            bands.map { BandJson(it.frequency, it.gainDb, it.q, it.type.name, it.enabled) },
        ),
        compressorJson = compressor?.let { c ->
            json.encodeToString(
                CompressorJson.serializer(),
                CompressorJson(c.crossovers, c.thresholds, c.ratios, c.attacks, c.releases, c.knee),
            )
        },
        limiterJson = limiter?.let { l ->
            json.encodeToString(
                LimiterJson.serializer(),
                LimiterJson(l.ceilingDb, l.lookaheadMs, l.releaseMs, l.inputGainDb),
            )
        },
        effects = effects.joinToString("|") { it.name },
        isFavorite = isFavorite,
    )
}

/** Factory preset library: genres, situations, effects — every entry lists its bands. */
object PresetLibrary {
    val all: List<Preset> by lazy {
        val defs = listOf(
            "Vaporwave" to listOf(60f to 4f, 10000f to -6f),
            "Nightcore" to listOf(4000f to 2.5f),
            "Slowed + Reverb" to listOf(120f to 2f, 14000f to -3f),
            "Hardstyle" to listOf(80f to 5f, 250f to -2f, 3000f to 2f),
            "Phonk" to listOf(70f to 6f, 900f to -2f, 7000f to -2f),
            "Lo-fi" to listOf(12000f to -4f, 300f to 1.5f),
            "Trap" to listOf(50f to 4f, 150f to 2f, 8000f to 2f),
            "Jazz" to listOf(250f to 1.5f, 3000f to 1f),
            "Classical" to listOf(500f to -1f, 8000f to 1.5f),
            "Gym" to listOf(60f to 5f, 200f to 3f, 5000f to 1.5f),
            "Car" to listOf(80f to 4.5f, 300f to 2f),
            "Subway" to listOf(100f to 3f, 2500f to 3f),
            "Late Night" to listOf(120f to -2f, 6000f to -2f),
            "Bass Boost" to listOf(70f to 7f),
            "Treble Boost" to listOf(10000f to 5f),
            "Vocal Clarity" to listOf(250f to -2.5f, 3000f to 4f),
            "Loudness" to listOf(120f to 3f, 2000f to 1f, 9000f to 2f),
            "Flat" to emptyList(),
            "Studio Reference" to listOf(1000f to 0.5f),
            "Underwater" to listOf(15000f to -9f, 400f to 3f),
            "Telephone" to listOf(100f to -12f, 1800f to 4f, 12000f to -12f),
            "Radio" to listOf(90f to -8f, 2000f to 3f),
            "Cathedral" to listOf(200f to 1f, 12000f to -2f),
            "Vinyl Crackle" to listOf(20000f to -3f),
            "Wide Stereo" to listOf(6000f to 1.5f),
            "Mono Compatibility" to listOf(300f to -1f),
            "Karaoke" to listOf(1500f to -6f),
        )
        defs.map { (name, bands) ->
            Preset(
                name = name,
                category = PresetCategory.GENRES,
                description = bands.joinToString { (f, g) -> "${f}Hz ${if (g >= 0) "+$g" else "$g"}dB" },
                bands = bands.mapIndexed { i, (f, g) ->
                    ParametricBand(i, BandFilterType.PEAK, f, g, 1.1f)
                },
            )
        }
    }
}
