package com.meister.nexusradar.transfer

import com.meister.nexusradar.data.CatalogSnapshot
import com.meister.nexusradar.data.DependencyEntity
import com.meister.nexusradar.data.ModEntity
import com.meister.nexusradar.domain.CatalogFilterState
import com.meister.nexusradar.domain.ImportChunk
import com.meister.nexusradar.domain.NexusModRecord
import com.meister.nexusradar.scan.PersistedScanState
import com.meister.nexusradar.settings.ScanSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TransferArchivesTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun exportArchiveRoundTripChecksManifestAndChunks() {
        val chunks = listOf(
            chunk(1, NexusModRecord(mod_id = 11, name = "First")),
            chunk(2, NexusModRecord(mod_id = 22, name = "Second"))
        )
        val output = ByteArrayOutputStream()

        val written = TransferArchives.writeExport(
            output = output,
            chunks = chunks,
            appVersion = "0.15.0",
            exportMode = "ALL",
            rangeDays = 14
        )
        val verified = TransferArchives.verifyExport(ByteArrayInputStream(output.toByteArray()))

        assertEquals(written, verified)
        assertEquals(2, verified.total_mods)
        assertEquals(2, verified.chunk_count)
        assertTrue(verified.files.all { it.sha256.length == 64 })
    }

    @Test(expected = IllegalArgumentException::class)
    fun exportRejectsDuplicateModIdsAcrossChunks() {
        TransferArchives.writeExport(
            output = ByteArrayOutputStream(),
            chunks = listOf(
                chunk(1, NexusModRecord(mod_id = 11, name = "First")),
                chunk(2, NexusModRecord(mod_id = 11, name = "Duplicate"))
            ),
            appVersion = "0.15.0",
            exportMode = "ALL",
            rangeDays = 14
        )
    }

    @Test
    fun fullBackupRoundTripKeepsCatalogSettingsQueueAndReports() {
        val backup = AppBackupPayload(
            app_version = "0.15.0",
            created_at = "2026-09-04T04:00:00Z",
            settings = ScanSettings(rangeDays = 30, pageLimit = 20),
            catalog_filters = CatalogFilterState(showAdult = true),
            scan_state = PersistedScanState(startedWith = 3),
            scan_history = emptyList(),
            catalog = CatalogSnapshot(
                mods = listOf(
                    ModEntity(
                        modId = 11,
                        name = "First",
                        firstSeenAt = "2026-09-03T00:00:00Z",
                        lastSeenAt = "2026-09-04T00:00:00Z"
                    )
                )
            )
        )
        val output = ByteArrayOutputStream()

        val manifest = TransferArchives.writeBackup(output, backup)
        val restored = TransferArchives.readBackup(ByteArrayInputStream(output.toByteArray()))

        assertEquals(1, manifest.mod_count)
        assertEquals(30, restored.settings.rangeDays)
        assertTrue(restored.catalog_filters.showAdult)
        assertEquals(11L, restored.catalog.mods.single().modId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun backupRejectsDependencyWithoutOwnerMod() {
        val backup = AppBackupPayload(
            app_version = "0.15.0",
            created_at = "2026-09-04T04:00:00Z",
            settings = ScanSettings(),
            catalog_filters = CatalogFilterState(),
            scan_state = PersistedScanState(),
            scan_history = emptyList(),
            catalog = CatalogSnapshot(
                dependencies = listOf(
                    DependencyEntity(99, 1, "Missing owner", null, "REQUIREMENT")
                )
            )
        )

        TransferArchives.writeBackup(ByteArrayOutputStream(), backup)
    }

    private fun chunk(index: Int, mod: NexusModRecord): String = json.encodeToString(
        ImportChunk(
            generated_at = "2026-09-04T04:00:00Z",
            chunk = index,
            chunk_size = 1,
            mods = listOf(mod)
        )
    )
}
