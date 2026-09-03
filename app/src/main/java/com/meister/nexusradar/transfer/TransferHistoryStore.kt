package com.meister.nexusradar.transfer

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
enum class TransferKind {
    VERIFIED_ZIP,
    JSON_CHUNKS,
    FULL_BACKUP;

    val label: String
        get() = when (this) {
            VERIFIED_ZIP -> "Geprüfter ZIP-Export"
            JSON_CHUNKS -> "JSON-Export"
            FULL_BACKUP -> "Vollbackup"
        }
}

@Serializable
data class TransferFileRef(
    val uri: String,
    val name: String,
    val mimeType: String,
    val sha256: String
)

@Serializable
data class TransferHistoryEntry(
    val id: String,
    val createdAt: String,
    val kind: TransferKind,
    val exportMode: String? = null,
    val rangeDays: Int? = null,
    val modCount: Int = 0,
    val chunkCount: Int = 0,
    val folderName: String,
    val files: List<TransferFileRef>,
    val fileCount: Int = files.size,
    val filesComplete: Boolean = true
)

@Serializable
private data class PersistedTransferHistory(
    val entries: List<TransferHistoryEntry> = emptyList()
)

class TransferHistoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("transfer_history", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    fun load(): List<TransferHistoryEntry> = runCatching {
        json.decodeFromString<PersistedTransferHistory>(
            prefs.getString(KEY_HISTORY, "") ?: ""
        ).entries
    }.getOrDefault(emptyList())

    fun add(entry: TransferHistoryEntry) {
        val safeEntry = entry.copy(
            files = entry.files.take(MAX_FILES_PER_ENTRY),
            fileCount = maxOf(entry.fileCount, entry.files.size),
            filesComplete = entry.filesComplete && entry.files.size <= MAX_FILES_PER_ENTRY
        )
        val updated = (listOf(safeEntry) + load().filterNot { it.id == safeEntry.id })
            .take(MAX_ENTRIES)
        prefs.edit().putString(
            KEY_HISTORY,
            json.encodeToString(PersistedTransferHistory(updated))
        ).apply()
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    companion object {
        private const val KEY_HISTORY = "history"
        private const val MAX_ENTRIES = 30
        private const val MAX_FILES_PER_ENTRY = 100
    }
}
