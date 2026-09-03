package com.meister.nexusradar.domain

import com.meister.nexusradar.data.ModEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class CatalogFiltersTest {
    @Test
    fun pendingExportFilterKeepsOnlyUnexportedChanges() {
        val pending = mod(1, changedAt = "2026-09-04T10:00:00Z")
        val exported = mod(
            2,
            changedAt = "2026-09-04T10:00:00Z",
            lastExportedAt = "2026-09-04T11:00:00Z"
        )
        val changedAgain = mod(
            3,
            changedAt = "2026-09-04T12:00:00Z",
            lastExportedAt = "2026-09-04T11:00:00Z"
        )

        val result = applyCatalogFilters(
            listOf(exported, changedAgain, pending),
            query = "",
            filters = CatalogFilterState(onlyPendingExport = true)
        )

        assertEquals(setOf(1L, 3L), result.map { it.modId }.toSet())
    }

    private fun mod(id: Long, changedAt: String?, lastExportedAt: String? = null) = ModEntity(
        modId = id,
        name = "Mod $id",
        firstSeenAt = "2026-09-01T00:00:00Z",
        lastSeenAt = "2026-09-03T00:00:00Z",
        changedAt = changedAt,
        lastExportedAt = lastExportedAt
    )
}
