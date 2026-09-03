package com.meister.nexusradar.domain

import com.meister.nexusradar.data.ModEntity
import com.meister.nexusradar.settings.NexusDateParser

data class ModChangeFields(
    val previousVersion: String?,
    val previousUpdatedAt: String?,
    val changedAt: String?
)

object ModChangeTracker {
    fun track(
        previous: ModEntity?,
        incomingVersion: String?,
        incomingUpdatedAt: String?,
        now: String
    ): ModChangeFields {
        if (previous == null) {
            return ModChangeFields(null, null, now)
        }

        val versionChanged = incomingVersion.isMeaningfullyDifferentVersion(previous.version)
        val updateChanged = incomingUpdatedAt.isMeaningfullyDifferentDate(previous.updatedAt)
        return ModChangeFields(
            previousVersion = if (versionChanged) previous.version else previous.previousVersion,
            previousUpdatedAt = if (updateChanged) previous.updatedAt else previous.previousUpdatedAt,
            changedAt = if (versionChanged || updateChanged) now else previous.changedAt
        )
    }

    private fun String?.isMeaningfullyDifferentVersion(previous: String?): Boolean {
        val incoming = normalizeVersion(this) ?: return false
        val stored = normalizeVersion(previous)
        return stored == null || incoming != stored
    }

    private fun String?.isMeaningfullyDifferentDate(previous: String?): Boolean {
        if (this.isNullOrBlank()) return false
        if (previous.isNullOrBlank()) return true
        val incomingInstant = NexusDateParser.parseInstant(this)
        val previousInstant = NexusDateParser.parseInstant(previous)
        return if (incomingInstant != null && previousInstant != null) {
            incomingInstant != previousInstant
        } else {
            trim() != previous.trim()
        }
    }

    private fun normalizeVersion(value: String?): String? = value
        ?.trim()
        ?.lowercase()
        ?.removePrefix("version")
        ?.trim()
        ?.removePrefix("v")
        ?.trim()
        ?.takeIf(String::isNotBlank)
}

fun ModEntity.hasPendingExport(): Boolean {
    val changed = changedAt?.takeIf(String::isNotBlank) ?: return false
    val exported = lastExportedAt?.takeIf(String::isNotBlank) ?: return true
    val changedInstant = NexusDateParser.parseInstant(changed)
    val exportedInstant = NexusDateParser.parseInstant(exported)
    return if (changedInstant != null && exportedInstant != null) {
        changedInstant.isAfter(exportedInstant)
    } else {
        changed > exported
    }
}
