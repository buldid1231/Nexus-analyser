package com.meister.nexusradar.scan

import org.junit.Assert.assertEquals
import org.junit.Test

class QueueOrderingTest {
    @Test
    fun newestUpdatedModIsScannedFirstAndUnknownDatesLast() {
        val ordered = QueueOrdering.newestFirst(
            listOf(
                item(2, "2026-09-01T12:00:00Z"),
                item(3, null),
                item(1, "2026-09-03T08:00:00Z")
            )
        )

        assertEquals(listOf(1L, 2L, 3L), ordered.map { it.modId })
    }

    private fun item(id: Long, updatedAt: String?) = QueueItem(
        modId = id,
        url = "https://www.nexusmods.com/skyrimspecialedition/mods/$id",
        listedUpdatedAt = updatedAt
    )
}
