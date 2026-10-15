package com.shelf.reader.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionsTest {
    @Test
    fun newerVersionsAreDetectedAcrossSegmentLengths() {
        assertTrue(AppVersions.isNewer("v0.2", "0.1"))
        assertTrue(AppVersions.isNewer("1.0.1", "1.0"))
        assertTrue(AppVersions.isNewer("2.0", "1.99.99"))
    }

    @Test
    fun equalOlderAndInvalidVersionsAreRejected() {
        assertFalse(AppVersions.isNewer("v0.1", "0.1.0"))
        assertFalse(AppVersions.isNewer("0.0.9", "0.1"))
        assertFalse(AppVersions.isNewer("latest", "0.1"))
    }
}
