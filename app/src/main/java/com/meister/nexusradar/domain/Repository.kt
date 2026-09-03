package com.meister.nexusradar.domain

import com.meister.nexusradar.data.*
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

class Repository(private val dao: ModDao) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

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
            val cleanAuthor = r.author?.takeUnless {
                it.equals("My mods", true) ||
                    it.equals("My profile", true) ||
                    it.equals("guest", true)
            }
            val previousAuthor = previous?.author?.takeUnless {
                it.equals("My mods", true) ||
                    it.equals("My profile", true) ||
                    it.equals("guest", true)
            }
            val signal = (r.name + " " + r.summary.orEmpty() + " " + r.tags.joinToString(" ")).lowercase()
            ModEntity(
                modId = r.mod_id,
                name = r.name.ifBlank { previous?.name.orEmpty() },
                author = cleanAuthor ?: previousAuthor,
                version = r.version ?: previous?.version,
                category = r.category ?: previous?.category,
                summary = r.summary ?: previous?.summary,
                publishedAt = r.published_at ?: previous?.publishedAt,
                updatedAt = r.updated_at ?: previous?.updatedAt,
                adult = r.adult || previous?.adult == true,
                nexusUrl = r.url ?: previous?.nexusUrl,
                fileSizeBytes = r.file_size_bytes ?: previous?.fileSizeBytes,
                mainFilesCount = maxOf(r.main_files_count, previous?.mainFilesCount ?: 0),
                endorsements = r.endorsements ?: previous?.endorsements,
                uniqueDownloads = r.unique_downloads ?: previous?.uniqueDownloads,
                totalDownloads = r.total_downloads ?: previous?.totalDownloads,
                requirementsCount = maxOf(
                    r.requirements_count,
                    r.requirements.size,
                    previous?.requirementsCount ?: 0
                ),
                requiredByCount = maxOf(
                    r.required_by_count,
                    r.required_by.size,
                    previous?.requiredByCount ?: 0
                ),
                firstSeenAt = previous?.firstSeenAt ?: now, lastSeenAt = now,
                hasSkseHint = "skse" in signal || previous?.hasSkseHint == true,
                hasDllHint = ".dll" in signal || r.tags.any { it.equals("dll", true) } || previous?.hasDllHint == true,
                collectionState = r.collection_state ?: previous?.collectionState ?: "DISCOVERED",
                inSelectedRange = r.in_selected_range ?: previous?.inSelectedRange ?: true,
                diagnostics = r.diagnostics.joinToString("|")
            )
        }
        dao.upsertMods(entities)
        accepted.forEach { record ->
            if (record.tags.isNotEmpty()) dao.deleteTagsFor(listOf(record.mod_id))
            if (record.requirements.isNotEmpty()) {
                dao.deleteDependenciesFor(record.mod_id, "REQUIREMENT")
            }
            if (record.required_by.isNotEmpty()) {
                dao.deleteDependenciesFor(record.mod_id, "REQUIRED_BY")
            }
        }
        dao.upsertTags(accepted.flatMap { record ->
            record.tags
                .filterNot { it.equals("Tag this mod", true) || it.equals("Manage tags", true) }
                .map { TagEntity(record.mod_id, it) }
        })
        dao.upsertDependencies(accepted.flatMap { r ->
            r.requirements.map { DependencyEntity(r.mod_id, it.mod_id, it.name, it.url, "REQUIREMENT") } +
                r.required_by.map { DependencyEntity(r.mod_id, it.mod_id, it.name, it.url, "REQUIRED_BY") }
        })
        return ImportResult(accepted.size, rejected)
    }

    suspend fun exportChunks(
        chunkSize: Int = 100,
        onlyInRange: Boolean = true,
        onlyChanged: Boolean = true,
        rangeDays: Int = 14,
        scanStartedAt: String? = null
    ): List<String> {
        val size = chunkSize.coerceIn(10, 500)
        val generatedAt = Instant.now()
        val rangeEnd = generatedAt.toString()
        val rangeStart = generatedAt.minus(Duration.ofDays(rangeDays.coerceIn(1, 2190).toLong())).toString()
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
                    file_size_bytes = m.fileSizeBytes,
                    main_files_count = m.mainFilesCount,
                    endorsements = m.endorsements,
                    unique_downloads = m.uniqueDownloads,
                    total_downloads = m.totalDownloads,
                    tags = tags[m.modId].orEmpty().map { it.tag },
                    requirements = deps[m.modId].orEmpty().filter { it.relationType == "REQUIREMENT" }
                        .map { NexusRelation(it.relatedModId, it.relatedName, it.relatedUrl) },
                    required_by = deps[m.modId].orEmpty().filter { it.relationType == "REQUIRED_BY" }
                        .map { NexusRelation(it.relatedModId, it.relatedName, it.relatedUrl) },
                    requirements_count = m.requirementsCount,
                    required_by_count = m.requiredByCount,
                    collection_state = m.collectionState, in_selected_range = m.inSelectedRange,
                    diagnostics = m.diagnostics.split('|').filter { it.isNotBlank() }
                )
            }
            json.encodeToString(
                ImportChunk(
                    schema_version = 8,
                    generated_at = generatedAt.toString(),
                    scan_started_at = scanStartedAt,
                    range_start = rangeStart,
                    range_end = rangeEnd,
                    chunk = index + 1,
                    chunk_size = size,
                    mods = records
                )
            )
        }
    }
}

data class ImportResult(val accepted: Int, val rejected: Int)
