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
import java.time.Duration
import java.time.Instant

@Serializable
data class ScanRunSummary(
    val id: String,
    val startedAt: String? = null,
    val completedAt: String,
    val durationSeconds: Long? = null,
    val discoveredCount: Int = 0,
    val queuedNewCount: Int = 0,
    val queuedUpdateCount: Int = 0,
    val processedCount: Int = 0,
    val skippedUnchangedCount: Int = 0,
    val retryAttemptCount: Int = 0,
    val excludedCount: Int = 0,
    val failedItems: List<FailedScanItem> = emptyList()
) {
    val failedCount: Int get() = failedItems.size

    companion object {
        fun completed(
            state: PersistedScanState,
            completedAt: Instant = Instant.now()
        ): ScanRunSummary {
            val started = state.startedAt?.let { value ->
                runCatching { Instant.parse(value) }.getOrNull()
            }
            return ScanRunSummary(
                id = completedAt.toString(),
                startedAt = state.startedAt,
                completedAt = completedAt.toString(),
                durationSeconds = started?.let {
                    Duration.between(it, completedAt).seconds.coerceAtLeast(0)
                },
                discoveredCount = state.discoveredCount,
                queuedNewCount = state.queuedNewCount,
                queuedUpdateCount = state.queuedUpdateCount,
                processedCount = state.processedCount,
                skippedUnchangedCount = state.skippedUnchangedCount,
                retryAttemptCount = state.retryAttemptCount,
                excludedCount = state.excludedCount,
                failedItems = state.failedItems
            )
        }
    }
}

@Serializable
private data class PersistedScanHistory(
    val runs: List<ScanRunSummary> = emptyList()
)

class ScanHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("scan_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<ScanRunSummary> = runCatching {
        json.decodeFromString<PersistedScanHistory>(prefs.getString(KEY_HISTORY, "") ?: "").runs
    }.getOrDefault(emptyList())

    fun add(summary: ScanRunSummary) {
        val updated = (listOf(summary) + load().filterNot { it.id == summary.id }).take(MAX_RUNS)
        replaceAll(updated)
    }

    fun replaceAll(runs: List<ScanRunSummary>) {
        prefs.edit().putString(
            KEY_HISTORY,
            json.encodeToString(PersistedScanHistory(runs.distinctBy { it.id }.take(MAX_RUNS)))
        ).apply()
    }

    fun observe(): Flow<List<ScanRunSummary>> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_HISTORY) trySend(load())
        }
        trySend(load())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    companion object {
        private const val KEY_HISTORY = "history"
        private const val MAX_RUNS = 20
    }
}
