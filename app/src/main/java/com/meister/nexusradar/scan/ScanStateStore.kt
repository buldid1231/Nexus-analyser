package com.meister.nexusradar.scan

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class QueueItem(
    val modId: Long,
    val url: String,
    val name: String = "",
    val listedUpdatedAt: String? = null,
    val listedVersion: String? = null,
    val reason: String = "NEW",
    /** Number of failed attempts already made for this queue item. */
    val retryCount: Int = 0
)

@Serializable data class PersistedScanState(
    val queue: List<QueueItem> = emptyList(),
    val processedIds: Set<Long> = emptySet(),
    val running: Boolean = false,
    val collecting: Boolean = false,
    val lastUrl: String? = null,
    val delayMs: Long = 3000L,
    val startedWith: Int = 0,
    val startedAt: String? = null,
    val failedIds: Set<Long> = emptySet(),
    val discoveredCount: Int = 0,
    val queuedNewCount: Int = 0,
    val queuedUpdateCount: Int = 0,
    val skippedUnchangedCount: Int = 0,
    val listingBatches: Int = 0,
    /** True while a full run still has listing pages left to collect. */
    val collectionPending: Boolean = false,
    /** Listing URL that can be reopened after a pause or process restart. */
    val currentListingUrl: String? = null,
    /** Prevents duplicate counters and queue entries when collection is resumed. */
    val listingSeenIds: Set<Long> = emptySet(),
    val retryAttemptCount: Int = 0,
    val excludedCount: Int = 0,
    val failedMessages: Map<Long, String> = emptyMap(),
    val statusMessage: String = ""
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

    fun observe(): Flow<PersistedScanState> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "state") trySend(load())
        }
        trySend(load())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun clear() = prefs.edit().remove("state").apply()
}
