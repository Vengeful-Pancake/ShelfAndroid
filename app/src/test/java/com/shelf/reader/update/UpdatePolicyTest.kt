package com.shelf.reader.update

import org.junit.Assert.*
import org.junit.Test

class UpdatePolicyTest {
    @Test fun releaseMustBeStableAndDigestMustBeComplete() {
        assertEquals("0.3.1", UpdatePolicy.stableVersion("v0.3.1"))
        assertNull(UpdatePolicy.stableVersion("v0.3.2-beta"))
        assertNull(UpdatePolicy.stableVersion("latest"))
        assertEquals("a".repeat(64), UpdatePolicy.checksum("sha256:" + "A".repeat(64)))
        assertNull(UpdatePolicy.checksum("abc"))
        assertNull(UpdatePolicy.checksum(""))
    }
    @Test fun assetMustBelongToConfiguredRepository() {
        val repo = "reader-owner/ShelfAndroid"
        assertTrue(UpdatePolicy.releaseAsset("https://github.com/$repo/releases/download/v0.4.0/Shelf-0.4.0.apk", repo))
        for (url in listOf("http://github.com/$repo/releases/download/v1/a.apk",
            "https://github.com/attacker/fake/releases/download/v1/a.apk",
            "https://github.com.evil.test/$repo/releases/download/v1/a.apk",
            "https://user:secret@github.com/$repo/releases/download/v1/a.apk",
            "https://github.com:8443/$repo/releases/download/v1/a.apk")) {
            assertFalse(url, UpdatePolicy.releaseAsset(url, repo))
        }
    }
    @Test fun redirectsCannotLeaveGithubHttpsDownloadHosts() {
        assertTrue(UpdatePolicy.trustedDownload("https://release-assets.githubusercontent.com/file?token=temporary"))
        assertFalse(UpdatePolicy.trustedDownload("https://githubusercontent.com.evil.test/file"))
        assertFalse(UpdatePolicy.trustedDownload("http://objects.githubusercontent.com/file"))
        assertFalse(UpdatePolicy.trustedDownload("file:///cache/update.apk"))
    }
}
