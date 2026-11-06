package com.shelf.reader

import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shelf.reader.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class LibraryFoldersTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val tree = DocumentsContract.buildTreeDocumentUri("com.shelf.reader.test.folders", "root")
    private val repository get() = ShelfApp.repository(compose.activity)
    private lateinit var savedSettings: ReaderSettings
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        check(name.matches(Regex("[a-z0-9.-]+\\.png")))
        instrumentation.uiAutomation.executeShellCommand("screencap -p /sdcard/Download/$name").use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
    }
    private fun control(command: String): String = compose.activity.contentResolver.query(
        DocumentsContract.buildDocumentUriUsingTree(tree, command), null, null, null, null,
    )!!.use { it.moveToFirst(); it.getString(0) }

    @Before fun seed() {
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.MANAGE_DOCUMENTS")
        try {
            compose.activity.grantUriPermission(compose.activity.packageName, tree,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        } finally { automation.dropShellPermissionIdentity() }
        control("__reset")
        runBlocking {
            repository.scanner.cancelAndJoin()
            savedSettings = repository.settings.current()
            repository.settings.update { it.copy(filter = "all", gridDensity = "list", navigationBarVisible = true) }
            repository.dao.addRoot(RootEntity(tree.toString(), "Folder fixture"))
        }
        repository.scanner.start()
        compose.waitUntil(30_000) { runBlocking { repository.dao.booksForRoot(tree.toString()).size == 4 } && !repository.scanner.status.value.running }
    }

    @After fun cleanup() {
        runBlocking {
            repository.scanner.cancelAndJoin()
            repository.dao.booksForRoot(tree.toString()).forEach { repository.dao.clearProgress(it.id) }
            repository.dao.removeRoot(tree.toString())
            if (::savedSettings.isInitialized) repository.settings.replace(savedSettings)
        }
        control("__reset")
        compose.activity.revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun browseNestedFoldersKeepsSameNamedBranchesSeparateAndCanGoBack() {
        compose.onNodeWithContentDescription("Browse folders").performClick()
        compose.onNodeWithContentDescription("Open folder Folder fixture").performClick()
        compose.onNodeWithText("Direct book").assertExists()
        screenshot("subfolders-browse-0.3.7.png")
        compose.onNodeWithText("Fantasy nested book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open folder Fantasy").performClick()
        compose.onNodeWithText("Fantasy book").assertExists()
        compose.onNodeWithContentDescription("Open folder Shared").performClick()
        compose.onNodeWithText("Fantasy nested book").assertExists()
        screenshot("subfolders-nested-0.3.7.png")
        compose.onNodeWithText("Modern nested book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Parent folder").performClick()
        compose.onNodeWithText("Fantasy book").assertExists()
        compose.onNodeWithContentDescription("Parent folder").performClick()
        compose.onNodeWithContentDescription("Open folder Modern").performClick()
        compose.onNodeWithContentDescription("Open folder Shared").performClick()
        compose.onNodeWithText("Modern nested book").assertExists()
        compose.onNodeWithText("Fantasy nested book").assertDoesNotExist()
    }

    @Test fun subfolderSwitchStopsRecursionHidesBooksAndPreservesReadingProgress() {
        val nested = runBlocking { repository.dao.booksForRoot(tree.toString()).first { it.title == "Fantasy nested book" } }
        runBlocking { repository.dao.saveProgress(ProgressEntity(nested.id, chapter = 2, offset = .45f, at = 1234)) }
        compose.onNodeWithContentDescription("Browse folders").performClick()
        compose.onNodeWithText("Scan options").performClick()
        screenshot("subfolders-options-0.3.7.png")
        compose.onNodeWithContentDescription("Include subfolders for new folder").assertIsOn().performClick().assertIsOff()
        control("__reset")
        compose.onNodeWithContentDescription("Include subfolders in Folder fixture").assertIsOn().performClick()
        compose.waitUntil(15_000) { runBlocking { repository.dao.roots().first { it.treeUri == tree.toString() }.includeSubfolders.not() } && !repository.scanner.status.value.running && control("__queries") == "root" }
        assertEquals("root", control("__queries"))
        assertEquals(4, runBlocking { repository.dao.booksForRoot(tree.toString()).size })
        assertEquals(.45f, runBlocking { repository.dao.progress(nested.id)!!.offset })
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Open folder Folder fixture").performClick()
        compose.onNodeWithText("Direct book").assertExists()
        compose.onNodeWithText("Fantasy").assertDoesNotExist()
        compose.onNodeWithText("Scan options").performClick()
        compose.onNodeWithContentDescription("Include subfolders in Folder fixture").assertIsOff().performClick()
        compose.waitUntil(15_000) { runBlocking { repository.dao.roots().first { it.treeUri == tree.toString() }.includeSubfolders } && !repository.scanner.status.value.running && control("__queries").split(",").contains("aa") }
        assertTrue(control("__queries").split(",").contains("aa"))
        assertEquals(2, runBlocking { repository.dao.progress(nested.id)!!.chapter })
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Fantasy").assertExists()
    }

    @Test fun failedFolderListingDoesNotDeletePreviouslyIndexedBooks() {
        control("__fail_a")
        repository.scanner.start(force = true)
        compose.waitUntil(20_000) { repository.scanner.status.value.error.isNotEmpty() && !repository.scanner.status.value.running }
        assertEquals(4, runBlocking { repository.dao.booksForRoot(tree.toString()).size })
        assertTrue(repository.scanner.status.value.error.contains("Folder fixture"))
    }

    @Test fun refreshBackfillsLegacyFolderPathsAndKeepsReadingState() {
        val before = runBlocking { repository.dao.booksForRoot(tree.toString()) }
        val nested = before.first { it.title == "Fantasy nested book" }
        runBlocking {
            repository.dao.upsertBooks(before.map { it.copy(folderPath = null) })
            repository.dao.saveProgress(ProgressEntity(nested.id, chapter = 1, offset = .7f, at = 555))
        }
        repository.scanner.start()
        compose.waitUntil(20_000) {
            runBlocking { repository.dao.booksForRoot(tree.toString()).all { it.folderPath != null } } && !repository.scanner.status.value.running
        }
        val refreshed = runBlocking { repository.dao.book(nested.id)!! }
        assertEquals(listOf("Fantasy", "Shared"), BookFolders.decode(refreshed.folderPath))
        assertEquals(nested.stamp, refreshed.stamp)
        assertEquals(.7f, runBlocking { repository.dao.progress(nested.id)!!.offset })
    }
}
