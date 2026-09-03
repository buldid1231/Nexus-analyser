package com.meister.nexusradar.domain

import com.meister.nexusradar.data.ModEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ListingUpdateDeciderTest {
    @Test
    fun newModIsQueued() {
        assertEquals(ListingScanReason.NEW, ListingUpdateDecider.decide(link(), null))
    }

    @Test
    fun unchangedModIsSkipped() {
        assertNull(
            ListingUpdateDecider.decide(
                link(updatedAt = "2026-09-01T10:00:00Z", version = "v1.2"),
                stored(updatedAt = "2026-09-01T10:00:00Z", version = "1.2")
            )
        )
    }

    @Test
    fun newerListingDateQueuesUpdate() {
        assertEquals(
            ListingScanReason.UPDATED,
            ListingUpdateDecider.decide(
                link(updatedAt = "2026-09-02T10:00:00Z", version = "1.2"),
                stored(updatedAt = "2026-09-01T10:00:00Z", version = "1.2")
            )
        )
    }

    @Test
    fun changedVersionQueuesUpdate() {
        assertEquals(
            ListingScanReason.UPDATED,
            ListingUpdateDecider.decide(
                link(updatedAt = null, version = "v2.0"),
                stored(updatedAt = "2026-09-01T10:00:00Z", version = "1.9")
            )
        )
    }

    @Test
    fun knownModWithoutListingFingerprintIsSkipped() {
        assertNull(ListingUpdateDecider.decide(link(updatedAt = null, version = null), stored()))
    }

    private fun link(
        updatedAt: String? = null,
        version: String? = null
    ) = VisibleLink(
        mod_id = 42,
        url = "https://www.nexusmods.com/skyrimspecialedition/mods/42",
        name = "Test Mod",
        updated_at = updatedAt,
        version = version
    )

    private fun stored(
        updatedAt: String? = null,
        version: String? = null
    ) = ModEntity(
        modId = 42,
        name = "Test Mod",
        updatedAt = updatedAt,
        version = version,
        firstSeenAt = "2026-08-01T00:00:00Z",
        lastSeenAt = "2026-09-01T00:00:00Z"
    )
}
