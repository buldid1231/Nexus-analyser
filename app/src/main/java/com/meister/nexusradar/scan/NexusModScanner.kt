package com.meister.nexusradar.scan

import android.webkit.WebView
import com.meister.nexusradar.browser.NexusPageParser
import com.meister.nexusradar.domain.NexusFileMetrics
import com.meister.nexusradar.domain.NexusModRecord
import com.meister.nexusradar.domain.Repository
import com.meister.nexusradar.settings.RangeClassifier
import com.meister.nexusradar.settings.ScanSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlin.coroutines.resume

/**
 * Reads a Nexus mod page from a WebView and stores the parsed record.
 *
 * The caller must invoke this class on the main thread because Android WebView
 * instances are bound to the thread on which they were created.
 */
class NexusModScanner(
    private val repository: Repository,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    suspend fun scan(
        web: WebView,
        settings: ScanSettings,
        setStatus: (String) -> Unit,
        expectedId: Long?
    ): Boolean {
        return try {
            var record = parseRecordWithRetry(web, expectedId)
                ?: error("Nexus-Metadaten wurden nicht rechtzeitig geladen")
            if (settings.scanFileSizes && !record.url.isNullOrBlank()) {
                setStatus("Lese Dateigröße: ${record.name}")
                web.loadUrl(record.url + "?tab=files")
                delay(settings.delayMs.coerceAtLeast(1500L))
                val metrics = parseFileMetricsWithRetry(web)
                if (metrics != null) {
                    record = record.copy(
                        file_size_bytes = metrics.file_size_bytes,
                        main_files_count = metrics.main_files_count
                    )
                }
            }
            val (state, inRange) = RangeClassifier.classify(
                record.published_at,
                record.updated_at,
                settings.rangeDays
            )
            val enriched = record.copy(
                collection_state = state,
                in_selected_range = inRange
            )
            val result = repository.importSingle(enriched)
            val missing = record.diagnostics.size
            val detail = if (missing == 0) "" else " • $missing Metadaten offen"
            setStatus(
                if (result.accepted == 1) "$state: ${record.name}$detail"
                else "Ausgeschlossen: ${record.name}"
            )
            result.accepted == 1
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            setStatus("Parserfehler: ${error.message ?: "unbekannt"}")
            false
        }
    }

    private suspend fun parseRecordWithRetry(
        web: WebView,
        expectedId: Long?
    ): NexusModRecord? {
        var best: NexusModRecord? = null
        var bestScore = -1
        runCatching { evaluateJavascript(web, NexusPageParser.expandMetadataSections) }
        delay(350)
        repeat(10) { attempt ->
            val candidate = runCatching {
                val raw = evaluateJavascript(web, NexusPageParser.parseCurrentMod)
                json.decodeFromString<NexusModRecord>(decodeJsString(raw))
            }.getOrNull()
            if (candidate != null && (expectedId == null || candidate.mod_id == expectedId)) {
                val score = metadataScore(candidate)
                if (score > bestScore) {
                    best = candidate
                    bestScore = score
                }
                if (
                    candidate.mod_id > 0 &&
                    candidate.name.isNotBlank() &&
                    candidate.version != null &&
                    candidate.category != null &&
                    candidate.published_at != null &&
                    candidate.updated_at != null &&
                    attempt >= 2
                ) {
                    return candidate
                }
            }
            delay(700)
        }
        return best
    }

    private fun metadataScore(record: NexusModRecord): Int =
        listOf(
            record.mod_id > 0,
            record.name.isNotBlank(),
            !record.version.isNullOrBlank(),
            !record.category.isNullOrBlank(),
            !record.published_at.isNullOrBlank(),
            !record.updated_at.isNullOrBlank(),
            !record.author.isNullOrBlank(),
            record.endorsements != null,
            record.total_downloads != null
        ).count { it }

    private suspend fun parseFileMetricsWithRetry(web: WebView): NexusFileMetrics? {
        var best: NexusFileMetrics? = null
        repeat(8) {
            val candidate = runCatching {
                val raw = evaluateJavascript(web, NexusPageParser.parseFilesTab)
                json.decodeFromString<NexusFileMetrics>(decodeJsString(raw))
            }.getOrNull()
            if (candidate != null) {
                best = candidate
                if (candidate.file_size_bytes != null) return candidate
            }
            delay(500)
        }
        return best
    }

    private suspend fun evaluateJavascript(web: WebView, script: String): String =
        suspendCancellableCoroutine { continuation ->
            web.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
            }
        }

    private fun decodeJsString(raw: String): String = runCatching {
        json.decodeFromString<String>(raw)
    }.getOrElse {
        raw.trim('"').replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
