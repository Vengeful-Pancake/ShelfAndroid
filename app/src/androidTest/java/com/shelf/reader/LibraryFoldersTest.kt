package com.shelf.reader

import android.net.Uri
import android.content.Intent
import android.provider.DocumentsContract
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.shelf.reader.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalTestApi::class)
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
            repository.dao.booksForRoot(tree.toString()).forEach { repository.dao.deleteBookData(it.id) }
            repository.dao.observeSmartShelves().first().filter { it.name == "Fixture smart shelf" }
                .forEach { repository.dao.deleteSmartShelf(it.id) }
            repository.dao.removeRoot(tree.toString())
            if (::savedSettings.isInitialized) repository.settings.replace(savedSettings)
        }
        control("__reset")
        compose.activity.revokeUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun browseNestedFoldersKeepsSameNamedBranchesSeparateAndCanGoBack() {
        compose.onNodeWithContentDescription("Browse folders").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Open folder Folder fixture"), 10_000)
        compose.onNodeWithContentDescription("Open folder Folder fixture").performClick()
        compose.waitUntilExactlyOneExists(hasText("Direct book"), 10_000)
        screenshot("subfolders-browse-0.3.7.png")
        compose.onNodeWithText("Fantasy nested book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Open folder Fantasy").performClick()
        compose.waitUntilExactlyOneExists(hasText("Fantasy book"), 10_000)
        compose.onNodeWithContentDescription("Open folder Shared").performClick()
        compose.waitUntilExactlyOneExists(hasText("Fantasy nested book"), 10_000)
        screenshot("subfolders-nested-0.3.7.png")
        compose.onNodeWithText("Modern nested book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Parent folder").performClick()
        compose.waitUntilExactlyOneExists(hasText("Fantasy book"), 10_000)
        compose.onNodeWithContentDescription("Parent folder").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Open folder Modern"), 10_000)
        compose.onNodeWithContentDescription("Open folder Modern").performClick()
        compose.waitUntilExactlyOneExists(hasContentDescription("Open folder Shared"), 10_000)
        compose.onNodeWithContentDescription("Open folder Shared").performClick()
        compose.waitUntilExactlyOneExists(hasText("Modern nested book"), 10_000)
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
        compose.waitUntilExactlyOneExists(hasText("Direct book"), 10_000)
        compose.onNodeWithText("Fantasy").assertDoesNotExist()
        compose.onNodeWithText("Scan options").performClick()
        compose.onNodeWithContentDescription("Include subfolders in Folder fixture").assertIsOff().performClick()
        compose.waitUntil(15_000) { runBlocking { repository.dao.roots().first { it.treeUri == tree.toString() }.includeSubfolders } && !repository.scanner.status.value.running && control("__queries").split(",").contains("aa") }
        assertTrue(control("__queries").split(",").contains("aa"))
        assertEquals(2, runBlocking { repository.dao.progress(nested.id)!!.chapter })
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Fantasy"), 10_000)
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

    @Test fun bookOverviewShowsCoverDescriptionAndReadingActions() {
        compose.onNodeWithContentDescription("Browse folders").performClick()
        val folderRow = hasContentDescription("Open folder Folder fixture")
        compose.waitUntilExactlyOneExists(folderRow, 10_000)
        compose.onNode(folderRow).performClick()
        val directBookRow = hasText("Direct book") and hasClickAction() and !hasText("Continue reading")
        compose.waitUntilExactlyOneExists(directBookRow, 10_000)
        compose.onNode(directBookRow).performClick()
        compose.onNodeWithText("Start reading").assertIsDisplayed()
        val description = hasText("A journey that begins at the library.", substring = true)
        compose.waitUntilExactlyOneExists(description, 10_000)
        compose.onNode(description).assertIsDisplayed()
        screenshot("shelf-book-overview.png")
        compose.onNodeWithContentDescription("Cover of Direct book").performClick()
        compose.onNodeWithContentDescription("Close cover").performClick()
        compose.onNodeWithContentDescription("Book menu").performClick()
        compose.onNodeWithText("Add to library").assertIsDisplayed()
        compose.onNodeWithText("Pin book").performClick()
        compose.waitUntil(10_000) {
            runBlocking { repository.dao.booksForRoot(tree.toString()).firstOrNull { it.title == "Direct book" }
                ?.let { repository.dao.isFavorite(it.id) } == true }
        }
        compose.onNodeWithContentDescription("Book menu").performClick()
        compose.waitUntilExactlyOneExists(hasText("Unpin book"), 10_000)
        compose.onNodeWithText("Unpin book").performClick()

        compose.onNodeWithText("Start reading").performClick()
        compose.waitUntilExactlyOneExists(hasText("Chapter 1 · 1 of 2"), 15_000)
        val back = hasContentDescription("Back to the library")
        compose.onNode(back).performClick()
        compose.waitUntilExactlyOneExists(directBookRow, 10_000)
        compose.onNode(directBookRow).performClick()
        compose.waitUntilExactlyOneExists(hasText("Start from beginning"), 10_000)
        compose.onNodeWithText("Contents").performClick()
        compose.waitUntilExactlyOneExists(hasText("Chapter 2"), 10_000)
        compose.onNodeWithText("Chapter 2").performClick()
        compose.waitUntilExactlyOneExists(hasText("Chapter 2 · 2 of 2"), 15_000)
        compose.onNode(back).performClick()

        compose.waitUntilExactlyOneExists(directBookRow, 10_000)
        compose.onNode(directBookRow).performClick()
        compose.onNode(hasText("Continue reading") and !hasText("Direct book")).assertIsDisplayed().performClick()
        compose.waitUntilExactlyOneExists(hasText("Chapter 2 · 2 of 2"), 15_000)
    }

    @Test fun tagsSortAndBrowseAndCatalogEditsReuseCachedEpubMetadata() {
        val direct = runBlocking { repository.dao.booksForRoot(tree.toString()).first { it.title == "Direct book" } }
        assertEquals(listOf("Travel"), direct.subjects)
        assertEquals(listOf("Zebra stories"), direct.catalogTags)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Sort:", substring = true).performClick()
        compose.onNodeWithText("Tags A–Z").performClick()
        compose.waitUntil(10_000) {
            runCatching {
                val fantasy = compose.onNodeWithText("Fantasy nested book").getUnclippedBoundsInRoot().top
                val tagged = compose.onNodeWithText("Direct book").getUnclippedBoundsInRoot().top
                val untagged = compose.onNodeWithText("Modern nested book").getUnclippedBoundsInRoot().top
                fantasy < tagged && tagged < untagged
            }.getOrDefault(false)
        }

        val opens = control("__book_opens")
        control("__edit_catalog")
        repository.scanner.start()
        compose.waitUntil(15_000) {
            !repository.scanner.status.value.running &&
                runBlocking { repository.dao.book(direct.id)?.catalogTags == listOf("New journeys") }
        }
        assertEquals(opens, control("__book_opens"))

        compose.onNodeWithContentDescription("Menu").performClick()
        val tagsDestination = hasText("Tags") and hasClickAction()
        compose.waitUntilExactlyOneExists(tagsDestination, 10_000)
        compose.onNode(tagsDestination).performSemanticsAction(SemanticsActions.OnClick)
        val refreshedTag = hasText("New journeys") and hasClickAction()
        compose.waitUntilExactlyOneExists(refreshedTag, 10_000)
        compose.onNode(refreshedTag).performClick()
        compose.waitUntilExactlyOneExists(hasText("Direct book"), 10_000)
        compose.onNodeWithText("Fantasy nested book").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back to tags").performClick()
        compose.waitUntilExactlyOneExists(hasText("Travel"), 10_000)
    }

    @Test fun catalogEditionKeepsProgressAndMarksAcrossAReplacementExport() {
        val original = runBlocking { repository.dao.booksForRoot(tree.toString()).first { it.filename == "direct.epub" } }
        val otherEdition = runBlocking { repository.dao.booksForRoot(tree.toString()).first { it.editionKey == "zh_epub:trip" } }
        runBlocking {
            repository.dao.saveProgress(ProgressEntity(original.id, chapter = 1, offset = .42f, at = 1234))
            repository.dao.addBookmark(BookmarkEntity(bookId = original.id, chapter = 0, offset = .3f))
            repository.dao.addAnnotation(AnnotationEntity(bookId = original.id, chapter = 1, offset = .4f, quote = "old library"))
            repository.dao.saveProgress(ProgressEntity(otherEdition.id, chapter = 0, offset = .8f, at = 1235))
        }
        control("__hide_edition")
        repository.rescan()
        compose.waitUntil(15_000) { runBlocking { repository.dao.book(original.id)?.available == false } && !repository.scanner.status.value.running }
        control("__replace_edition")
        repository.rescan()
        compose.waitUntil(15_000) { runBlocking { repository.dao.book(original.id)?.filename == "renamed.epub" } && !repository.scanner.status.value.running }
        runBlocking {
            assertEquals(3, repository.dao.book(original.id)?.chapters)
            assertEquals(2, repository.dao.progress(original.id)?.chapter)
            assertEquals(.42f, repository.dao.progress(original.id)?.offset)
            assertEquals(1, repository.dao.bookmarksForBook(original.id).single().chapter)
            assertEquals(2, repository.dao.annotationsForBook(original.id).single().chapter)
            assertEquals(.8f, repository.dao.progress(otherEdition.id)?.offset)
        }
    }

    @Test fun refreshRestoresDescriptionForAnAlreadyImportedBook() {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "file-root")
        val repo = repository
        val id = runBlocking { repo.importEpub(uri) }
        try {
            runBlocking {
                val book = repo.dao.book(id)!!
                repo.dao.upsertBooks(listOf(book.copy(stamp = "4:0:0", description = "")))
                repo.dao.saveProgress(ProgressEntity(id, chapter = 1, offset = .35f, at = 1234))
                repo.scanner.refreshOutdatedBooks()
            }
            compose.waitUntil(30_000) {
                isDescriptionRestored(repo, id) && !repo.scanner.status.value.running
            }
            runBlocking {
                assertEquals(.35f, repo.dao.progress(id)?.offset)
                assertEquals(1, repo.dao.progress(id)?.chapter)
            }
        } finally {
            runBlocking { repo.deleteBookFromSource(id) }
        }
    }

    private fun isDescriptionRestored(repo: ShelfRepository, id: String): Boolean = runBlocking {
        repo.dao.book(id)?.description == "A journey that begins at the library.\n\nA story told in two chapters."
    }

    @Test fun openWithShelfMakesOneReadablePrivateCopy() {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, "file-root")
        val existingIds = runBlocking { repository.dao.booksForRoot(LibraryScanner.IMPORTED_ROOT).map { it.id }.toSet() }
        try {
            compose.activity.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setPackage(compose.activity.packageName)
                setDataAndType(uri, "application/epub+zip")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
            compose.waitUntilExactlyOneExists(hasContentDescription("Back to the library"), 15_000)
            runBlocking {
                val imported = repository.dao.booksForRoot(LibraryScanner.IMPORTED_ROOT).single { it.id !in existingIds }
                assertEquals(imported.id, repository.importEpub(uri))
                assertEquals(existingIds.size + 1, repository.dao.booksForRoot(LibraryScanner.IMPORTED_ROOT).size)
                assertEquals(2, repository.openBook(imported.id).chapterCount)
                assertEquals("Direct book", imported.title)
            }
        } finally {
            runBlocking {
                repository.dao.booksForRoot(LibraryScanner.IMPORTED_ROOT).filter { it.id !in existingIds }.forEach {
                    assertTrue(repository.deleteBookFromSource(it.id).success)
                }
            }
        }
    }

    @Test fun smartShelfSavesTagRulesAndShowsLiveBookCount() {
        val filters = hasText("Filter tags") and hasClickAction()
        compose.waitUntilExactlyOneExists(filters, 10_000)
        compose.onNode(filters).performClick()
        compose.onNodeWithContentDescription("Include Travel").performSemanticsAction(SemanticsActions.OnClick)
        compose.onNodeWithText("Save shelf").performClick()
        compose.waitUntilExactlyOneExists(hasText("1 matching book"), 10_000)
        compose.onNode(hasSetTextAction()).performTextReplacement("Fixture smart shelf")
        compose.waitUntilExactlyOneExists(hasText("Save shelf") and isEnabled(), 10_000)
        compose.onNodeWithText("Save shelf").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilExactlyOneExists(hasText("Fixture smart shelf") and !hasClickAction(), 10_000)
        compose.waitUntilExactlyOneExists(hasText("Direct book"), 10_000)
        compose.onNodeWithText("Fantasy book").assertDoesNotExist()
        assertEquals(listOf("travel"), runBlocking { repository.dao.observeSmartShelves().first().single { it.name == "Fixture smart shelf" }.included })
    }

    @Test fun tagFilteringCombinesIncludesExcludesAndCanBeCleared() {
        fun selectRule(description: String) {
            val chip = compose.onNodeWithContentDescription(description)
            chip.performSemanticsAction(SemanticsActions.OnClick)
            chip.assertIsSelected()
        }

        val openFilters = hasText("Filter tags", substring = true) and hasClickAction()
        compose.waitUntilExactlyOneExists(openFilters, 10_000)
        compose.onNode(openFilters).performClick()
        val tagSearch = hasSetTextAction() and hasText("Find a tag")
        compose.onNode(tagSearch).performTextInput("Trav")
        compose.onNodeWithContentDescription("Include Adventure").assertDoesNotExist()
        compose.onNode(tagSearch).performImeAction()
        selectRule("Include Travel")
        compose.onNode(tagSearch).performTextClearance()
        compose.onNode(tagSearch).performImeAction()
        selectRule("Include Adventure")
        compose.waitUntilExactlyOneExists(hasText("Apply filters (2)"), 10_000)
        compose.onNodeWithText("Apply filters (2)").performClick()
        compose.waitUntilExactlyOneExists(hasText("No books match"), 10_000)

        compose.onNode(openFilters).performClick()
        compose.onNodeWithText("All included").assertIsSelected()
        compose.onNodeWithText("Any included").performClick()
        selectRule("Exclude Zebra stories")
        screenshot("shelf-tag-filters.png")
        compose.onNodeWithText("Apply filters (3)").performClick()
        compose.waitUntilExactlyOneExists(hasText("Fantasy book"), 10_000)
        compose.onNodeWithText("Direct book").assertDoesNotExist()
        compose.onNodeWithText("Modern nested book").assertDoesNotExist()

        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), 10_000)
        compose.onNode(hasSetTextAction()).performTextInput("Direct")
        compose.waitUntilExactlyOneExists(hasText("No books match"), 10_000)
        compose.onNodeWithContentDescription("Close search").performClick()
        compose.waitUntilExactlyOneExists(hasText("Fantasy book"), 10_000)

        compose.onNode(openFilters).performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithContentDescription("Close tag filters").performClick()
        compose.onNodeWithText("Fantasy book").assertIsDisplayed()
        compose.onNodeWithText("Direct book").assertDoesNotExist()

        compose.onNode(openFilters).performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("Apply filters").performClick()
        compose.waitUntilExactlyOneExists(hasText("Filter tags") and hasClickAction(), 10_000)
        compose.onNodeWithContentDescription("Search").performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), 10_000)
        compose.onNode(hasSetTextAction()).performTextInput("Direct")
        compose.waitUntilExactlyOneExists(hasText("Direct book"), 10_000)
        compose.onNodeWithContentDescription("Close search").performClick()
    }

    @Test fun resumeUsesMostRecentlyReadBookEvenWhenShelfIsSortedByTitle() {
        runBlocking {
            repository.settings.update { it.copy(sort = "title") }
            val books = repository.dao.booksForRoot(tree.toString())
            val older = books.first { it.title == "Direct book" }
            val latest = books.first { it.title == "Modern nested book" }
            val now = System.currentTimeMillis()
            repository.dao.saveProgress(ProgressEntity(older.id, offset = .2f, at = now - 1000))
            repository.dao.saveProgress(ProgressEntity(latest.id, offset = .4f, at = now))
        }
        val resume = hasText("Continue reading")
        compose.waitUntilExactlyOneExists(resume and hasText("Modern nested book"), 10_000)
        screenshot("shelf-continue-reading.png")
        compose.onNode(resume).performClick()
        val back = hasContentDescription("Back to the library")
        compose.waitUntilExactlyOneExists(back, 10_000)
        compose.waitUntilExactlyOneExists(hasText("Modern nested book"), 10_000)
        compose.onNodeWithText("Internet").assertDoesNotExist()
        compose.onNode(back).performClick()
        compose.waitUntilExactlyOneExists(resume, 10_000)
        compose.onNodeWithText("Internet").assertIsDisplayed()
    }
}
