package com.meister.nexusradar.transfer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.security.MessageDigest

class TransferHistoryTest {
    @Test
    fun streamChecksumMatchesKnownSha256() {
        val bytes = "verified export".toByteArray()
        val expected = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

        assertEquals(expected, TransferArchives.sha256(ByteArrayInputStream(bytes)))
    }

    @Test
    fun transferKindHasReadableLabels() {
        assertEquals("Geprüfter ZIP-Export", TransferKind.VERIFIED_ZIP.label)
        assertEquals("JSON-Export", TransferKind.JSON_CHUNKS.label)
        assertEquals("Vollbackup", TransferKind.FULL_BACKUP.label)
    }
}
