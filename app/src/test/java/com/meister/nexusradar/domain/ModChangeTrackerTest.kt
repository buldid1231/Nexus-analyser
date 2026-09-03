package com.meister.nexusradar.domain

import com.meister.nexusradar.data.ModEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModChangeTrackerTest {
    @Test
    fun newModStartsPendingExport() {
        val tracked = ModChangeTracker.track(null, "1.0", "2026-09-03T10:00:00Z", NOW)

        assertEquals(NOW, tracked.changedAt)
        assertNull(tracked.previousVersion)
        assertNull(tracked.previousUpdatedAt)
    }

    @Test
    fun unchangedValuesKeepExistingChangeHistory() {
        val previous = mod(
            version = "1.2",
            updatedAt = "2026-09-03T10:00:00Z",
            previousVersion = "1.1",
            previousUpdatedAt = "2026-09-01T10:00:00Z",
            changedAt = "2026-09-03T11:00:00Z"
        )

        val tracked = ModChangeTracker.track(
            previous,
            incomingVersion = "v1.2",
            incomingUpdatedAt = "2026-09-03T10:00:00Z",
            now = NOW
        )

        assertEquals(previous.previousVersion, tracked.previousVersion)
        assertEquals(previous.previousUpdatedAt, tracked.previousUpdatedAt)
        assertEquals(previous.changedAt, tracked.changedAt)
    }

    @Test
    fun changedVersionAndDateRememberPreviousValues() {
        val previous = mod(version = "1.2", updatedAt = "2026-09-03T10:00:00Z")

        val tracked = ModChangeTracker.track(
            previous,
            incomingVersion = "1.3",
            incomingUpdatedAt = "2026-09-04T10:00:00Z",
            now = NOW
        )

        assertEquals("1.2", tracked.previousVersion)
        assertEquals("2026-09-03T10:00:00Z", tracked.previousUpdatedAt)
        assertEquals(NOW, tracked.changedAt)
    }

    @Test
    fun pendingStatusChangesOnlyAfterSuccessfulExportOrNewChange() {
        assertTrue(mod(changedAt = "2026-09-04T10:00:00Z").hasPendingExport())
        assertFalse(
            mod(
                changedAt = "2026-09-04T10:00:00Z",
                lastExportedAt = "2026-09-04T10:01:00Z"
            ).hasPendingExport()
        )
        assertTrue(
            mod(
                changedAt = "2026-09-04T10:02:00Z",
                lastExportedAt = "2026-09-04T10:01:00Z"
            ).hasPendingExport()
        )
    }

    private fun mod(
        version: String? = null,
        updatedAt: String? = null,
        previousVersion: String? = null,
        previousUpdatedAt: String? = null,
        changedAt: String? = null,
        lastExportedAt: String? = null
    ) = ModEntity(
        modId = 42,
        name = "Test Mod",
        version = version,
        updatedAt = updatedAt,
        firstSeenAt = "2026-09-01T00:00:00Z",
        lastSeenAt = "2026-09-03T00:00:00Z",
        previousVersion = previousVersion,
        previousUpdatedAt = previousUpdatedAt,
        changedAt = changedAt,
        lastExportedAt = lastExportedAt
    )

    private companion object {
        const val NOW = "2026-09-04T12:00:00Z"
    }
}
