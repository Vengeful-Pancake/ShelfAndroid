package com.shelf.reader.update

import org.junit.Assert.*
import org.junit.Test

class UpdateScheduleTest {
    @Test fun everyColdStartChecksEvenIfAnotherBuildCheckedRecently() {
        assertTrue(UpdateSchedule.due(1000, null, true, retryAt = 999999))
    }
    @Test fun foregroundChecksAgainAfterFifteenMinutes() {
        assertFalse(UpdateSchedule.due(1000 + UpdateSchedule.SUCCESS_INTERVAL - 1, 1000, true))
        assertTrue(UpdateSchedule.due(1000 + UpdateSchedule.SUCCESS_INTERVAL, 1000, true))
    }
    @Test fun networkFailuresRetryWithoutASixHourLockoutAndRespectServerBackoff() {
        assertFalse(UpdateSchedule.due(60_999, 1000, false))
        assertTrue(UpdateSchedule.due(61_000, 1000, false))
        assertFalse(UpdateSchedule.due(61_000, 1000, false, retryAt = 120_000))
        assertTrue(UpdateSchedule.due(120_000, 1000, false, retryAt = 120_000))
    }
    @Test fun manualRequestsAndClockChangesDoNotRemainBlocked() {
        assertTrue(UpdateSchedule.due(1001, 1000, true, force = true))
        assertTrue(UpdateSchedule.due(999, 1000, true))
    }
    @Test fun returningToForegroundChecksForNewReleasesWithoutResettingFailureBackoff() {
        assertTrue(UpdateSchedule.due(1001, 1000, true, foreground = true))
        assertFalse(UpdateSchedule.due(1001, 1000, false, foreground = true))
    }
}
