package com.meister.nexusradar.scan

import com.meister.nexusradar.settings.NexusDateParser
import java.time.Instant

object QueueOrdering {
    /** Most recently updated Nexus entries first; unknown dates always last. */
    fun newestFirst(items: List<QueueItem>): List<QueueItem> = items.sortedWith(
        compareByDescending<QueueItem> {
            NexusDateParser.parseInstant(it.listedUpdatedAt) ?: Instant.MIN
        }.thenByDescending { it.modId }
    )
}
