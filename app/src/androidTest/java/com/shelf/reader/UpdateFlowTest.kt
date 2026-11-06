package com.shelf.reader

import android.app.Application
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shelf.reader.update.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger

class UpdateFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var prefs: SharedPreferences
    private val models = mutableListOf<UpdateViewModel>()
    @Before fun prepare() { prefs = compose.activity.getSharedPreferences("update-flow-test", 0); prefs.edit().clear().commit(); UpdateRequests.info.value = false }
    @After fun cleanup() { compose.runOnUiThread { models.forEach { it.dismiss() } }; prefs.edit().clear().commit(); UpdateRequests.info.value = false }
    private fun model(client: UpdateClient, now: () -> Long): UpdateViewModel {
        lateinit var result: UpdateViewModel
        compose.runOnUiThread { result = UpdateViewModel(compose.activity.application, client, UpdateIdentity(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), false), prefs, now) }
        models.add(result); return result
    }
    private class Client : UpdateClient {
        val calls = AtomicInteger()
        @Volatile var error: Exception? = null
        override fun latest(): UpdateRelease {
            calls.incrementAndGet(); error?.let { throw it }
            return UpdateRelease("0.3.7", "fixture", "https://github.com/Vengeful-Pancake/ShelfAndroid/releases/download/v0.3.7/Shelf-0.3.7.apk", 1000, "a".repeat(64), 12, "Manifest fixture")
        }
    }
    @Test fun latestBuildAndRestartBothDetectNewReleaseDespiteInheritedRecentCheck() {
        val now = 1_000_000L
        prefs.edit().putLong("last_attempt", now).putLong("last_success", now).commit()
        val client = Client(); val first = model(client) { now }
        compose.runOnUiThread { first.check() }
        compose.waitUntil(10_000) { first.state.value is UpdateUi.Available }
        assertEquals("0.3.7", first.status.value.latestVersion)
        compose.runOnUiThread { first.dismiss() }
        val restarted = model(client) { now + 1000 }
        compose.runOnUiThread { restarted.check() }
        compose.waitUntil(10_000) { restarted.state.value is UpdateUi.Available }
        assertEquals(2, client.calls.get())
    }
    @Test fun failedAutomaticCheckKeepsTheErrorAndRetriesAfterBackoff() {
        var now = 1_000_000L
        val client = Client().apply { error = UpdateHttpException(403, now + 120_000, "GitHub HTTP 403 rate limit") }
        val vm = model(client) { now }
        compose.runOnUiThread { vm.check() }
        compose.waitUntil(10_000) { vm.status.value.error.contains("403") && !vm.status.value.checking }
        assertTrue(vm.state.value is UpdateUi.Idle)
        now += 60_000; compose.runOnUiThread { vm.check() }; assertEquals(1, client.calls.get())
        now += 60_001; client.error = null
        compose.runOnUiThread { vm.check() }
        compose.waitUntil(10_000) { vm.state.value is UpdateUi.Available }
        assertEquals(2, client.calls.get()); assertEquals("", vm.status.value.error)
    }
    @Test fun latestBuildRegistersForegroundLifecycleAndAutomaticallyShowsTheNewRelease() {
        val now = 1_000_000L
        prefs.edit().putLong("last_attempt", now).commit()
        val client = Client(); val vm = model(client) { now }
        compose.runOnUiThread { compose.activity.setContent { com.shelf.reader.ui.theme.ShelfTheme { AppUpdateCoordinator(vm) } } }
        compose.waitUntil(10_000) { runCatching { compose.onNodeWithText("Shelf 0.3.7 đã có bản mới").assertExists(); true }.getOrDefault(false) }
        assertEquals(1, client.calls.get())
        compose.onNodeWithText("Tải qua trình duyệt").assertIsDisplayed()
    }
    @Test fun updateOpensReviewedReleasePageOnlyAfterUserAction() {
        val url = java.util.concurrent.atomic.AtomicReference("")
        val vm = model(Client()) { 1_000_000L }
        compose.runOnUiThread { compose.activity.setContent { com.shelf.reader.ui.theme.ShelfTheme {
            AppUpdateCoordinator(vm, openRelease = { url.set(it) })
        } } }
        compose.waitUntil(10_000) { vm.state.value is UpdateUi.Available }
        assertEquals("", url.get())
        compose.onNodeWithText("Tải qua trình duyệt").performClick()
        assertEquals("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/tag/v0.3.7", url.get())
        assertTrue(vm.state.value is UpdateUi.Idle)
    }
    @Test fun installedVersionAndRegisteredManualCheckAreVisibleFromLibraryAndBrowser() {
        compose.onNodeWithText("Shelf ${BuildConfig.VERSION_NAME} · debug").performClick()
        compose.onNodeWithText("Shelf ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        compose.onNodeWithText("Đóng").performClick()
        compose.onNodeWithText("Internet").performClick()
        compose.onNodeWithContentDescription("Browser menu").performClick()
        compose.onNodeWithText("Phiên bản và cập nhật").performClick()
        compose.onNodeWithText("Shelf ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})").assertExists()
        compose.onNodeWithText("Đóng").performClick()
        compose.onNodeWithText("Books").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Kiểm tra cập nhật").performClick()
        compose.waitUntil(5000) { runCatching { compose.onNodeWithText("Bản debug", substring = true).assertExists(); true }.getOrDefault(false) }
    }
    @Test fun manifestPinsPackageRepositoryVersionAndHash() {
        fun metadata() = JSONObject().put("schemaVersion", 1).put("packageName", "com.shelf.reader")
            .put("versionName", "0.3.6").put("versionCode", 11).put("apkUrl", "https://github.com/Vengeful-Pancake/ShelfAndroid/releases/download/v0.3.6/Shelf-0.3.6.apk")
            .put("apkSize", 1000).put("sha256", "a".repeat(64))
        val repo = "Vengeful-Pancake/ShelfAndroid"
        assertEquals("0.3.6", GithubUpdater.decodeManifest(metadata(), repo, "com.shelf.reader").version)
        for ((key, value) in listOf("packageName" to "com.other.app", "apkUrl" to "https://evil.example/Shelf.apk", "sha256" to "invalid", "versionCode" to 0, "apkSize" to 0, "schemaVersion" to 2)) {
            assertThrows(IllegalArgumentException::class.java) { GithubUpdater.decodeManifest(metadata().put(key, value), repo, "com.shelf.reader") }
        }
    }
}
