package com.shelf.reader

import androidx.test.platform.app.InstrumentationRegistry
import com.shelf.reader.update.GithubUpdater
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UpdatePackageValidationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    @Test fun validButSameVersionApkIsRejected() {
        val context = instrumentation.targetContext
        val installedApk = File(context.applicationInfo.sourceDir)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            GithubUpdater(context).verifyPackage(installedApk, BuildConfig.VERSION_NAME)
        }
        assertTrue(failure.message.orEmpty().contains("bản cũ"))
    }
    @Test fun differentPackageCannotBeInstalledAsSelfUpdate() {
        val testApk = File(instrumentation.context.applicationInfo.sourceDir)
        val failure = assertThrows(IllegalArgumentException::class.java) {
            GithubUpdater(instrumentation.targetContext).verifyPackage(testApk, BuildConfig.VERSION_NAME)
        }
        assertTrue(failure.message.orEmpty().contains("APK không phải"))
    }
    @Test fun fakeApkFailsBeforeInstaller() {
        val context = instrumentation.targetContext
        val file = File.createTempFile("invalid-update", ".apk", context.cacheDir)
        try {
            file.writeText("Not an APK")
            assertThrows(IllegalStateException::class.java) { GithubUpdater(context).verifyPackage(file, "0.3.2") }
        } finally { file.delete() }
    }
}
