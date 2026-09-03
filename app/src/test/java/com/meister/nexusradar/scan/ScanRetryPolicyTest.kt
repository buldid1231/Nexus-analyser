package com.meister.nexusradar.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanRetryPolicyTest {
    @Test
    fun firstAndSecondFailureAreRetried() {
        assertTrue(ScanRetryPolicy.shouldRetry(1))
        assertTrue(ScanRetryPolicy.shouldRetry(2))
    }

    @Test
    fun thirdFailureIsFinal() {
        assertFalse(ScanRetryPolicy.shouldRetry(3))
    }

    @Test
    fun retryDelayIncreases() {
        assertEquals(2_000L, ScanRetryPolicy.backoffMs(1))
        assertEquals(5_000L, ScanRetryPolicy.backoffMs(2))
    }
}
