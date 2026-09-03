package com.meister.nexusradar.scan

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable data class QueueItem(val modId: Long, val url: String, val name: String = "")
@Serializable data class PersistedScanState(
    val queue: List<QueueItem> = emptyList(),
    val processedIds: Set<Long> = emptySet(),
    val running: Boolean = false,
    val lastUrl: String? = null,
    val delayMs: Long = 3000L,
    val startedWith: Int = 0,
    val startedAt: String? = null,
    val failedIds: Set<Long> = emptySet()
) {
    val processedCount: Int get() = processedIds.size
    val totalForRun: Int get() = if (startedWith > 0) startedWith else queue.size + processedIds.size
}

class ScanStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan_state", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): PersistedScanState = runCatching {
        json.decodeFromString<PersistedScanState>(prefs.getString("state", "") ?: "")
    }.getOrDefault(PersistedScanState())

    fun save(state: PersistedScanState) {
        prefs.edit().putString("state", json.encodeToString(state)).apply()
    }

    fun clear() = prefs.edit().remove("state").apply()
}
