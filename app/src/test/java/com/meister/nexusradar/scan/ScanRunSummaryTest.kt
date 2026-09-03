package com.meister.nexusradar.scan

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ScanRunSummaryTest {
    @Test
    fun completedSummaryKeepsCountsFailuresAndDuration() {
        val state = PersistedScanState(
            startedAt = "2026-09-03T20:00:00Z",
            discoveredCount = 80,
            queuedNewCount = 12,
            queuedUpdateCount = 5,
            processedIds = setOf(1, 2, 3),
            skippedUnchangedCount = 63,
            retryAttemptCount = 2,
            excludedCount = 1,
            failedItems = listOf(
                FailedScanItem(3, "Failed", "https://example.invalid/3", "UPDATED", "Timeout", 3)
            )
        )

        val summary = ScanRunSummary.completed(
            state,
            Instant.parse("2026-09-03T20:02:00Z")
        )

        assertEquals(120L, summary.durationSeconds)
        assertEquals(3, summary.processedCount)
        assertEquals(1, summary.failedCount)
        assertEquals("Timeout", summary.failedItems.single().lastError)
    }
}
