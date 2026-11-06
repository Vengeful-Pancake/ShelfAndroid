package com.shelf.reader.update

/** Per-process cadence: a cold start always checks, never inherits another build's six-hour lockout. */
internal object UpdateSchedule {
    const val SUCCESS_INTERVAL = 15 * 60_000L
    const val FAILURE_INTERVAL = 60_000L
    fun due(now: Long, lastAttempt: Long?, succeeded: Boolean, retryAt: Long = 0, force: Boolean = false, foreground: Boolean = false): Boolean {
        if (force || lastAttempt == null || now < lastAttempt) return true
        if (foreground && succeeded && now >= retryAt) return true
        val interval = if (succeeded) SUCCESS_INTERVAL else FAILURE_INTERVAL
        return now >= retryAt && now - lastAttempt >= interval
    }
}
