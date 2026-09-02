package com.meister.nexusradar.domain

import com.meister.nexusradar.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.time.Instant

class Repository(private val dao: ModDao) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    fun observeMods(): Flow<List<ModEntity>> = dao.observeAll()

    suspend fun importSingle(record: NexusModRecord): ImportResult = importRecords(listOf(record))

    suspend fun importJson(text: String): ImportResult {
        val chunk = json.decodeFromString<ImportChunk>(text)
        require(chunk.game.equals("skyrimspecialedition", true)) { "Falsches Spiel: ${chunk.game}" }
        return importRecords(chunk.mods)
    }

    private suspend fun importRecords(records: List<NexusModRecord>): ImportResult {
        val now = Instant.now().toString()
        val accepted = records.filter(ModFilter::isActualMod)
        val rejected = records.size - accepted.size
        val entities = accepted.map { r ->
            val previous = dao.byId(r.mod_id)
            val signal = (r.name + " " + r.summary.orEmpty() + " " + r.tags.joinToString(" ")).lowercase()
            ModEntity(
                modId = r.mod_id,
                name = r.name.ifBlank { previous?.name.orEmpty() },
                author = r.author ?: previous?.author,
                version = r.version ?: previous?.version,
                category = r.category ?: previous?.category,
                summary = r.summary ?: previous?.summary,
                publishedAt = r.published_at ?: previous?.publishedAt,
                updatedAt = r.updated_at ?: previous?.updatedAt,
                adult = r.adult || previous?.adult == true,
                nexusUrl = r.url ?: previous?.nexusUrl,
                firstSeenAt = previous?.firstSeenAt ?: now, lastSeenAt = now,
                hasSkseHint = "skse" in signal,
                hasDllHint = ".dll" in signal || r.tags.any { it.equals("dll", true) },
                collectionState = r.collection_state ?: previous?.collectionState ?: "DISCOVERED",
                inSelectedRange = r.in_selected_range ?: previous?.inSelectedRange ?: true,
                diagnostics = r.diagnostics.joinToString("|")
            )
        }
        dao.upsertMods(entities)
        dao.upsertTags(accepted.flatMap { r -> r.tags.map { TagEntity(r.mod_id, it) } })
        dao.upsertDependencies(accepted.flatMap { r ->
            r.requirements.map { DependencyEntity(r.mod_id, it.mod_id, it.name, it.url, "REQUIREMENT") } +
                r.required_by.map { DependencyEntity(r.mod_id, it.mod_id, it.name, it.url, "REQUIRED_BY") }
        })
        return ImportResult(accepted.size, rejected)
    }

    suspend fun exportChunks(chunkSize: Int = 100, onlyInRange: Boolean = true, onlyChanged: Boolean = true): List<String> {
        val size = chunkSize.coerceIn(10, 500)
        val total = when {
            onlyInRange && onlyChanged -> dao.countChangedInRange()
            onlyInRange -> dao.countInRange()
            else -> dao.count()
        }
        if (total == 0) return emptyList()
        val chunkCount = (total + size - 1) / size
        return (0 until chunkCount).map { index ->
            val mods = when {
                onlyInRange && onlyChanged -> dao.changedRangeChunk(size, index * size)
                onlyInRange -> dao.rangeChunk(size, index * size)
                else -> dao.chunk(size, index * size)
            }
            val ids = mods.map { it.modId }
            val deps = dao.dependenciesFor(ids).groupBy { it.ownerModId }
            val tags = dao.tagsFor(ids).groupBy { it.modId }
            val records = mods.map { m ->
                NexusModRecord(
                    mod_id = m.modId, name = m.name, author = m.author, version = m.version,
                    category = m.category, summary = m.summary, published_at = m.publishedAt,
                    updated_at = m.updatedAt, adult = m.adult, url = m.nexusUrl,
                    tags = tags[m.modId].orEmpty().map { it.tag },
                    requirements = deps[m.modId].orEmpty().filter { it.relationType == "REQUIREMENT" }
                        .map { NexusRelation(it.relatedModId, it.relatedName, it.relatedUrl) },
                    required_by = deps[m.modId].orEmpty().filter { it.relationType == "REQUIRED_BY" }
                        .map { NexusRelation(it.relatedModId, it.relatedName, it.relatedUrl) },
                    collection_state = m.collectionState, in_selected_range = m.inSelectedRange,
                    diagnostics = m.diagnostics.split('|').filter { it.isNotBlank() }
                )
            }
            json.encodeToString(
                ImportChunk(
                    schema_version = 7,
                    generated_at = Instant.now().toString(),
                    chunk = index + 1,
                    chunk_size = size,
                    mods = records
                )
            )
        }
    }
}

data class ImportResult(val accepted: Int, val rejected: Int)
