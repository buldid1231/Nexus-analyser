package com.meister.nexusradar.domain

import com.meister.nexusradar.data.ModEntity
import com.meister.nexusradar.settings.NexusDateParser

enum class CatalogSort(val label: String) {
    UPDATED_DESC("Neueste Updates"),
    UPDATED_ASC("Alte Updates zuerst"),
    NAME_ASC("Name A–Z"),
    SIZE_DESC("Größte Dateien"),
    SIZE_ASC("Kleinste Dateien"),
    ENDORSEMENTS_DESC("Meiste Endorsements"),
    DOWNLOADS_DESC("Meiste Downloads")
}

enum class SizeFilter(val label: String) {
    ALL("Alle Größen"),
    UNDER_10_MB("Unter 10 MB"),
    FROM_10_TO_100_MB("10–100 MB"),
    FROM_100_MB_TO_1_GB("100 MB–1 GB"),
    OVER_1_GB("Über 1 GB"),
    UNKNOWN("Größe unbekannt")
}

data class CatalogFilterState(
    val selectedCategories: Set<String> = emptySet(),
    val selectedStates: Set<String> = setOf("NEW", "UPDATED", "UNCHANGED", "DISCOVERED"),
    val showAdult: Boolean = false,
    val onlyInRange: Boolean = false,
    val onlySkseOrDll: Boolean = false,
    val onlyWithRequirements: Boolean = false,
    val sizeFilter: SizeFilter = SizeFilter.ALL,
    val sort: CatalogSort = CatalogSort.UPDATED_DESC,
    val groupByCategory: Boolean = true
)

fun applyCatalogFilters(
    mods: List<ModEntity>,
    query: String,
    filters: CatalogFilterState
): List<ModEntity> {
    val normalizedQuery = query.trim().lowercase()
    val filtered = mods.filter { mod ->
        val category = mod.category?.takeIf(String::isNotBlank) ?: UNKNOWN_CATEGORY
        val haystack = listOf(
            mod.name,
            mod.author.orEmpty(),
            category,
            mod.summary.orEmpty(),
            mod.modId.toString()
        ).joinToString(" ").lowercase()

        val stateMatches = mod.collectionState in filters.selectedStates
        val categoryMatches = filters.selectedCategories.isEmpty() || category in filters.selectedCategories
        val sizeMatches = when (filters.sizeFilter) {
            SizeFilter.ALL -> true
            SizeFilter.UNKNOWN -> mod.fileSizeBytes == null
            SizeFilter.UNDER_10_MB -> mod.fileSizeBytes?.let { it < 10L * MEBIBYTE } == true
            SizeFilter.FROM_10_TO_100_MB -> mod.fileSizeBytes?.let {
                it >= 10L * MEBIBYTE && it < 100L * MEBIBYTE
            } == true
            SizeFilter.FROM_100_MB_TO_1_GB -> mod.fileSizeBytes?.let {
                it >= 100L * MEBIBYTE && it < GIBIBYTE
            } == true
            SizeFilter.OVER_1_GB -> mod.fileSizeBytes?.let { it >= GIBIBYTE } == true
        }

        stateMatches &&
            categoryMatches &&
            (filters.showAdult || !mod.adult) &&
            (!filters.onlyInRange || mod.inSelectedRange) &&
            (!filters.onlySkseOrDll || mod.hasSkseHint || mod.hasDllHint) &&
            (!filters.onlyWithRequirements || mod.requirementsCount > 0) &&
            sizeMatches &&
            (normalizedQuery.isEmpty() || normalizedQuery in haystack)
    }

    return when (filters.sort) {
        CatalogSort.UPDATED_DESC -> filtered.sortedWith(
            compareBy<ModEntity> { updateTime(it) == null }
                .thenByDescending { updateTime(it) ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() }
        )
        CatalogSort.UPDATED_ASC -> filtered.sortedWith(
            compareBy<ModEntity> { updateTime(it) == null }
                .thenBy { updateTime(it) ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
        CatalogSort.NAME_ASC -> filtered.sortedBy { it.name.lowercase() }
        CatalogSort.SIZE_DESC -> filtered.sortedWith(
            compareBy<ModEntity> { it.fileSizeBytes == null }
                .thenByDescending { it.fileSizeBytes ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() }
        )
        CatalogSort.SIZE_ASC -> filtered.sortedWith(
            compareBy<ModEntity> { it.fileSizeBytes == null }
                .thenBy { it.fileSizeBytes ?: Long.MAX_VALUE }
                .thenBy { it.name.lowercase() }
        )
        CatalogSort.ENDORSEMENTS_DESC -> filtered.sortedWith(
            compareBy<ModEntity> { it.endorsements == null }
                .thenByDescending { it.endorsements ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() }
        )
        CatalogSort.DOWNLOADS_DESC -> filtered.sortedWith(
            compareBy<ModEntity> { it.totalDownloads == null }
                .thenByDescending { it.totalDownloads ?: Long.MIN_VALUE }
                .thenBy { it.name.lowercase() }
        )
    }
}

fun CatalogFilterState.activeCount(): Int = listOf(
    selectedCategories.isNotEmpty(),
    selectedStates != setOf("NEW", "UPDATED", "UNCHANGED", "DISCOVERED"),
    showAdult,
    onlyInRange,
    onlySkseOrDll,
    onlyWithRequirements,
    sizeFilter != SizeFilter.ALL,
    sort != CatalogSort.UPDATED_DESC,
    !groupByCategory
).count { it }

const val UNKNOWN_CATEGORY = "Kategorie nicht erkannt"
private const val MEBIBYTE = 1024L * 1024L
private const val GIBIBYTE = 1024L * MEBIBYTE

private fun updateTime(mod: ModEntity): Long? =
    NexusDateParser.parseInstant(mod.updatedAt ?: mod.publishedAt ?: mod.lastSeenAt)?.toEpochMilli()
