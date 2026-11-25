package com.sonata.core.data.repository

import com.sonata.core.common.di.IoDispatcher
import com.sonata.core.database.dao.PresetDao
import com.sonata.core.database.entity.PresetEntity
import com.sonata.core.domain.model.BandFilterType
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
data class BandJson(val frequency: Float, val gain: Float, val q: Float)

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
            ParametricBand(i, BandFilterType.PEAK, b.frequency, b.gain, b.q)
        },
        isFavorite = isFavorite,
    )

    private fun Preset.toEntity() = PresetEntity(
        id = id,
        name = name,
        category = category.name,
        description = description,
        bandsJson = json.encodeToString(
            ListSerializer(BandJson.serializer()),
            bands.map { BandJson(it.frequency, it.gainDb, it.q) },
        ),
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
