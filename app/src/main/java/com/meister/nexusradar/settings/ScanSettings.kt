package com.meister.nexusradar.settings

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Duration
import java.time.Instant

@Serializable
data class ScanSettings(
    val rangeDays: Int = 14,
    val delayMs: Long = 3000L,
    val pageLimit: Int = 5,
    val chunkSize: Int = 100,
    val exportOnlyChanged: Boolean = true
) {
    fun normalized(): ScanSettings = copy(
        rangeDays = rangeDays.coerceIn(1, 2190),
        delayMs = delayMs.coerceIn(1500L, 15000L),
        pageLimit = pageLimit.coerceIn(1, 500),
        chunkSize = chunkSize.coerceIn(10, 500)
    )
}

class ScanSettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan_settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): ScanSettings = runCatching {
        json.decodeFromString<ScanSettings>(prefs.getString("settings", "") ?: "")
    }.getOrDefault(ScanSettings()).normalized()

    fun save(settings: ScanSettings) {
        prefs.edit().putString("settings", json.encodeToString(settings.normalized())).apply()
    }
}

object RangeClassifier {
    fun classify(publishedAt: String?, updatedAt: String?, rangeDays: Int, now: Instant = Instant.now()): Pair<String, Boolean> {
        val start = now.minus(Duration.ofDays(rangeDays.toLong()))
        val published = parseInstant(publishedAt)
        val updated = parseInstant(updatedAt)
        val publishedInRange = published?.let { !it.isBefore(start) && !it.isAfter(now) } ?: false
        val updatedInRange = updated?.let { !it.isBefore(start) && !it.isAfter(now) } ?: false
        return when {
            publishedInRange -> "NEW" to true
            updatedInRange -> "UPDATED" to true
            else -> "UNCHANGED" to false
        }
    }

    private fun parseInstant(value: String?): Instant? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value) }.getOrNull()
    }
}
