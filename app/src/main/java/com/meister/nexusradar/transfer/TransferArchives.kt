package com.meister.nexusradar.transfer

import com.meister.nexusradar.data.CatalogSnapshot
import com.meister.nexusradar.domain.CatalogFilterState
import com.meister.nexusradar.domain.ImportChunk
import com.meister.nexusradar.scan.PersistedScanState
import com.meister.nexusradar.scan.ScanRunSummary
import com.meister.nexusradar.settings.ScanSettings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class ArchiveFileInfo(
    val name: String,
    val sha256: String,
    val size_bytes: Int,
    val mod_count: Int
)

@Serializable
data class ExportArchiveManifest(
    val archive_type: String = EXPORT_ARCHIVE_TYPE,
    val format_version: Int = ARCHIVE_FORMAT_VERSION,
    val app_version: String,
    val game: String = "skyrimspecialedition",
    val schema_version: Int = 8,
    val generated_at: String,
    val export_mode: String,
    val range_days: Int,
    val total_mods: Int,
    val chunk_count: Int,
    val files: List<ArchiveFileInfo>
)

@Serializable
data class AppBackupPayload(
    val backup_type: String = BACKUP_ARCHIVE_TYPE,
    val format_version: Int = ARCHIVE_FORMAT_VERSION,
    val app_version: String,
    val created_at: String,
    val settings: ScanSettings,
    val catalog_filters: CatalogFilterState,
    val scan_state: PersistedScanState,
    val scan_history: List<ScanRunSummary>,
    val catalog: CatalogSnapshot
)

@Serializable
data class BackupArchiveManifest(
    val archive_type: String = BACKUP_ARCHIVE_TYPE,
    val format_version: Int = ARCHIVE_FORMAT_VERSION,
    val app_version: String,
    val created_at: String,
    val data_file: String = BACKUP_DATA_FILE,
    val data_sha256: String,
    val data_size_bytes: Int,
    val mod_count: Int,
    val dependency_count: Int,
    val tag_count: Int,
    val report_count: Int
)

object TransferArchives {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun writeExport(
        output: OutputStream,
        chunks: List<String>,
        appVersion: String,
        exportMode: String,
        rangeDays: Int
    ): ExportArchiveManifest {
        val parsedChunks = validateChunks(chunks)
        val files = chunks.mapIndexed { index, contents ->
            val bytes = contents.toByteArray(Charsets.UTF_8)
            ArchiveFileInfo(
                name = "chunks/skyrimse_chunk_${(index + 1).toString().padStart(4, '0')}.json",
                sha256 = sha256(bytes),
                size_bytes = bytes.size,
                mod_count = parsedChunks[index].mods.size
            )
        }
        val manifest = ExportArchiveManifest(
            app_version = appVersion,
            generated_at = parsedChunks.first().generated_at,
            export_mode = exportMode,
            range_days = rangeDays,
            total_mods = parsedChunks.sumOf { it.mods.size },
            chunk_count = chunks.size,
            files = files
        )

        ZipOutputStream(output.buffered()).use { zip ->
            writeEntry(zip, MANIFEST_FILE, json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            files.forEachIndexed { index, file ->
                writeEntry(zip, file.name, chunks[index].toByteArray(Charsets.UTF_8))
            }
        }
        return manifest
    }

    fun verifyExport(input: InputStream): ExportArchiveManifest {
        val entries = readEntries(input)
        val manifestBytes = entries[MANIFEST_FILE] ?: error("manifest.json fehlt im Export")
        val manifest = json.decodeFromString<ExportArchiveManifest>(
            manifestBytes.toString(Charsets.UTF_8)
        )
        require(manifest.archive_type == EXPORT_ARCHIVE_TYPE) { "Falscher Archivtyp" }
        require(manifest.format_version == ARCHIVE_FORMAT_VERSION) {
            "Nicht unterstützte Exportversion ${manifest.format_version}"
        }
        require(manifest.game == "skyrimspecialedition") { "Falsches Spiel im Export" }
        require(manifest.schema_version == 8) { "Nicht unterstütztes JSON-Schema" }
        require(manifest.chunk_count == manifest.files.size && manifest.files.isNotEmpty()) {
            "Chunk-Anzahl im Manifest ist ungültig"
        }
        require(entries.keys == manifest.files.map { it.name }.toSet() + MANIFEST_FILE) {
            "Export enthält fehlende oder unerwartete Dateien"
        }

        val chunks = manifest.files.map { file ->
            requireSafeEntryName(file.name)
            val bytes = entries[file.name] ?: error("${file.name} fehlt")
            require(bytes.size == file.size_bytes) { "Dateigröße stimmt nicht: ${file.name}" }
            require(sha256(bytes) == file.sha256) { "Prüfsumme stimmt nicht: ${file.name}" }
            json.decodeFromString<ImportChunk>(bytes.toString(Charsets.UTF_8)).also { chunk ->
                require(chunk.mods.size == file.mod_count) { "Modanzahl stimmt nicht: ${file.name}" }
            }
        }
        validateParsedChunks(chunks)
        val ids = chunks.flatMap { it.mods }.map { it.mod_id }
        require(ids.size == manifest.total_mods) { "Gesamtzahl der Mods stimmt nicht" }
        require(ids.distinct().size == ids.size) { "Export enthält doppelte Mod-IDs" }
        return manifest
    }

    fun writeBackup(output: OutputStream, backup: AppBackupPayload): BackupArchiveManifest {
        validateBackup(backup)
        val backupBytes = json.encodeToString(backup).toByteArray(Charsets.UTF_8)
        require(backupBytes.size <= MAX_BACKUP_BYTES) { "Backup ist zu groß" }
        val manifest = BackupArchiveManifest(
            app_version = backup.app_version,
            created_at = backup.created_at,
            data_sha256 = sha256(backupBytes),
            data_size_bytes = backupBytes.size,
            mod_count = backup.catalog.mods.size,
            dependency_count = backup.catalog.dependencies.size,
            tag_count = backup.catalog.tags.size,
            report_count = backup.scan_history.size
        )
        ZipOutputStream(output.buffered()).use { zip ->
            writeEntry(zip, MANIFEST_FILE, json.encodeToString(manifest).toByteArray(Charsets.UTF_8))
            writeEntry(zip, BACKUP_DATA_FILE, backupBytes)
        }
        return manifest
    }

    fun readBackup(input: InputStream): AppBackupPayload {
        val entries = readEntries(input)
        require(entries.keys == setOf(MANIFEST_FILE, BACKUP_DATA_FILE)) {
            "Backup enthält fehlende oder unerwartete Dateien"
        }
        val manifest = json.decodeFromString<BackupArchiveManifest>(
            entries.getValue(MANIFEST_FILE).toString(Charsets.UTF_8)
        )
        require(manifest.archive_type == BACKUP_ARCHIVE_TYPE) { "Das ist kein Nexus-Radar-Backup" }
        require(manifest.format_version == ARCHIVE_FORMAT_VERSION) {
            "Nicht unterstützte Backupversion ${manifest.format_version}"
        }
        require(manifest.data_file == BACKUP_DATA_FILE) { "Ungültige Backupdatei" }
        val data = entries.getValue(BACKUP_DATA_FILE)
        require(data.size == manifest.data_size_bytes) { "Backupgröße stimmt nicht" }
        require(sha256(data) == manifest.data_sha256) { "Backup-Prüfsumme stimmt nicht" }
        val backup = json.decodeFromString<AppBackupPayload>(data.toString(Charsets.UTF_8))
        validateBackup(backup)
        require(backup.catalog.mods.size == manifest.mod_count) { "Modanzahl stimmt nicht" }
        require(backup.catalog.dependencies.size == manifest.dependency_count) {
            "Abhängigkeitsanzahl stimmt nicht"
        }
        require(backup.catalog.tags.size == manifest.tag_count) { "Tag-Anzahl stimmt nicht" }
        require(backup.scan_history.size == manifest.report_count) { "Berichtsanzahl stimmt nicht" }
        return backup
    }

    private fun validateChunks(chunks: List<String>): List<ImportChunk> {
        require(chunks.isNotEmpty()) { "Keine JSON-Chunks vorhanden" }
        require(chunks.size <= MAX_ZIP_ENTRIES - 1) { "Zu viele JSON-Chunks" }
        return chunks.map { json.decodeFromString<ImportChunk>(it) }.also(::validateParsedChunks)
    }

    private fun validateParsedChunks(chunks: List<ImportChunk>) {
        require(chunks.isNotEmpty()) { "Keine JSON-Chunks vorhanden" }
        chunks.forEachIndexed { index, chunk ->
            require(chunk.game.equals("skyrimspecialedition", true)) { "Falsches Spiel in Chunk ${index + 1}" }
            require(chunk.schema_version == 8) { "Falsches Schema in Chunk ${index + 1}" }
            require(chunk.chunk == index + 1) { "Falsche Chunk-Reihenfolge" }
            require(chunk.mods.isNotEmpty()) { "Leerer JSON-Chunk" }
        }
        val ids = chunks.flatMap { it.mods }.map { it.mod_id }
        require(ids.size <= MAX_MODS) { "Export enthält zu viele Mods" }
        require(ids.distinct().size == ids.size) { "Export enthält doppelte Mod-IDs" }
    }

    private fun validateBackup(backup: AppBackupPayload) {
        require(backup.backup_type == BACKUP_ARCHIVE_TYPE) { "Falscher Backuptyp" }
        require(backup.format_version == ARCHIVE_FORMAT_VERSION) {
            "Nicht unterstützte Backupversion ${backup.format_version}"
        }
        require(backup.app_version.isNotBlank()) { "App-Version fehlt im Backup" }
        runCatching { Instant.parse(backup.created_at) }
            .getOrElse { error("Ungültiges Erstellungsdatum im Backup") }
        require(backup.catalog.mods.size <= MAX_MODS) { "Backup enthält zu viele Mods" }
        val ids = backup.catalog.mods.map { it.modId }
        require(ids.size == ids.distinct().size) { "Backup enthält doppelte Mod-IDs" }
        require(backup.catalog.mods.all { it.modId > 0 && it.name.isNotBlank() }) {
            "Backup enthält ungültige Mods"
        }
        val knownIds = ids.toSet()
        require(backup.catalog.dependencies.all { it.ownerModId in knownIds }) {
            "Backup enthält verwaiste Abhängigkeiten"
        }
        require(backup.catalog.tags.all { it.modId in knownIds }) {
            "Backup enthält verwaiste Tags"
        }
        require(backup.scan_history.size <= MAX_REPORTS_IN_BACKUP) {
            "Backup enthält zu viele Scanberichte"
        }
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, bytes: ByteArray) {
        requireSafeEntryName(name)
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val entries = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                require(!entry.isDirectory) { "Ordner sind im Archiv nicht erlaubt" }
                requireSafeEntryName(entry.name)
                require(entry.name !in entries) { "Doppelter ZIP-Eintrag: ${entry.name}" }
                require(entries.size < MAX_ZIP_ENTRIES) { "Zu viele Dateien im Archiv" }
                val bytes = readEntryLimited(zip)
                totalBytes += bytes.size
                require(totalBytes <= MAX_TOTAL_UNCOMPRESSED_BYTES) { "Archiv ist entpackt zu groß" }
                entries[entry.name] = bytes
                zip.closeEntry()
            }
        }
        require(entries.isNotEmpty()) { "Leeres oder beschädigtes ZIP-Archiv" }
        return entries
    }

    private fun readEntryLimited(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_ENTRY_BYTES) { "ZIP-Datei ist entpackt zu groß" }
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun requireSafeEntryName(name: String) {
        require(
            name.isNotBlank() &&
                !name.startsWith('/') &&
                !name.startsWith('\\') &&
                ".." !in name &&
                '\\' !in name
        ) { "Unsicherer ZIP-Dateiname" }
    }

    internal fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private const val MAX_ZIP_ENTRIES = 1_000
    private const val MAX_ENTRY_BYTES = 128 * 1024 * 1024
    private const val MAX_BACKUP_BYTES = MAX_ENTRY_BYTES
    private const val MAX_TOTAL_UNCOMPRESSED_BYTES = 256L * 1024L * 1024L
    private const val MAX_MODS = 500_000
    private const val MAX_REPORTS_IN_BACKUP = 100
}

const val ARCHIVE_FORMAT_VERSION = 1
const val EXPORT_ARCHIVE_TYPE = "nexus_radar_export"
const val BACKUP_ARCHIVE_TYPE = "nexus_radar_full_backup"
const val MANIFEST_FILE = "manifest.json"
const val BACKUP_DATA_FILE = "backup.json"
