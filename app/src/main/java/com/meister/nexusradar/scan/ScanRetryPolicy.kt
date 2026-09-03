package com.meister.nexusradar.scan

/** Retry policy for temporary WebView, network and parser failures. */
object ScanRetryPolicy {
    const val MAX_RETRIES = 2

    fun shouldRetry(failedAttempts: Int): Boolean = failedAttempts <= MAX_RETRIES

    fun backoffMs(failedAttempts: Int): Long = when (failedAttempts) {
        1 -> 2_000L
        else -> 5_000L
    }
}
