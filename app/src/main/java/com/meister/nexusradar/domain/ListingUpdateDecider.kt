package com.meister.nexusradar.domain

import com.meister.nexusradar.data.ModEntity
import com.meister.nexusradar.settings.NexusDateParser

enum class ListingScanReason {
    NEW,
    UPDATED
}

data class PlannedListingMod(
    val link: VisibleLink,
    val reason: ListingScanReason
)

data class ListingScanPlan(
    val candidates: List<PlannedListingMod>,
    val newCount: Int,
    val updateCount: Int,
    val unchangedCount: Int
)

object ListingUpdateDecider {
    fun decide(link: VisibleLink, previous: ModEntity?): ListingScanReason? {
        if (previous == null) return ListingScanReason.NEW

        val listedUpdate = NexusDateParser.parseInstant(link.updated_at)
        val storedUpdate = NexusDateParser.parseInstant(previous.updatedAt)
        if (listedUpdate != null && storedUpdate == null) return ListingScanReason.UPDATED
        if (listedUpdate != null && storedUpdate != null && listedUpdate.isAfter(storedUpdate)) {
            return ListingScanReason.UPDATED
        }

        val listedVersion = normalizeVersion(link.version)
        val storedVersion = normalizeVersion(previous.version)
        if (listedVersion != null && storedVersion == null) return ListingScanReason.UPDATED
        if (listedVersion != null && storedVersion != null && listedVersion != storedVersion) {
            return ListingScanReason.UPDATED
        }

        return null
    }

    private fun normalizeVersion(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.removePrefix("version")
        ?.trim()
        ?.removePrefix("v")
        ?.trim()
        ?.takeIf {
            it.isNotBlank() &&
                it.length <= 40 &&
                it.any(Char::isDigit) &&
                it.matches(Regex("[a-z0-9][a-z0-9._+\\- ]*"))
        }
}
