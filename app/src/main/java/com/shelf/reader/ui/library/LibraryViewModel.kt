package com.shelf.reader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.ShelfApp
import com.shelf.reader.data.BookCard
import com.shelf.reader.data.CustomLibraryEntity
import com.shelf.reader.data.FolderWritePermissionRequest
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.data.ReadingSessionEntity
import com.shelf.reader.data.RootEntity
import com.shelf.reader.data.ScanStatus
import com.shelf.reader.data.WordTransformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface LibraryCategory {
    object All : LibraryCategory
    data class Folder(val uri: String, val name: String) : LibraryCategory
    data class Custom(val id: Long, val name: String) : LibraryCategory
    data class Series(val name: String) : LibraryCategory
    object Duplicates : LibraryCategory

    val label: String get() = when (this) {
        is All -> "All Books"
        is Folder -> name
        is Custom -> name
        is Series -> name
        is Duplicates -> "Duplicates"
    }
}

data class ReadingStats(
    val startedBooks: Int = 0,
    val finishedBooks: Int = 0,
    val totalSeconds: Long = 0,
    val weekSeconds: Long = 0,
    val streakDays: Int = 0,
)

enum class LibraryFilter(val key: String, val label: String) {
    ALL("all", "All"),
    READING("reading", "Reading"),
    UNREAD("unread", "Not started"),
    FINISHED("finished", "Finished");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ALL
    }
}

enum class LibrarySort(val key: String, val label: String) {
    RECENT("recent", "Recently read"),
    TITLE("title", "Title A–Z"),
    AUTHOR("author", "Author A–Z"),
    ADDED("added", "Newest file"),
    PRIORITY("priority", "Favourites first");

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: RECENT
    }
}

/** How dense the shelf grid is. */
enum class GridDensity(val key: String, val label: String, val columns: Int = 0) {
    ADAPTIVE("adaptive", "Fit to screen"),
    TWO("two", "Large covers", 2),
    THREE("three", "Medium covers", 3),
    FOUR("four", "Small covers", 4),
    TINY("tiny", "Tiny covers", 5),
    LIST("list", "List", 1),
    DETAILED("detailed", "Detailed List", 1);

    companion object {
        fun from(key: String?) = entries.firstOrNull { it.key == key } ?: ADAPTIVE
    }
}

data class LibraryUiState(
    val books: List<BookCard> = emptyList(),
    val counts: Map<LibraryFilter, Int> = emptyMap(),
    val roots: List<RootEntity> = emptyList(),
    val customLibraries: List<CustomLibraryEntity> = emptyList(),
    val seriesNames: List<String> = emptyList(),
    val duplicateCount: Int = 0,
    val stats: ReadingStats = ReadingStats(),
    val activeCategory: LibraryCategory = LibraryCategory.All,
    val scan: ScanStatus = ScanStatus(),
    val settings: ReaderSettings = ReaderSettings(),
    val query: String = "",
    val loading: Boolean = true,
) {
    val filter: LibraryFilter get() = LibraryFilter.from(settings.filter)
    val sort: LibrarySort get() = LibrarySort.from(settings.sort)
    val density: GridDensity get() = GridDensity.from(settings.gridDensity)
    val continueReading: BookCard?
        get() = books.firstOrNull { it.started && it.percent < 98 }
}

private data class LibrarySources(
    val cards: List<BookCard>,
    val roots: List<RootEntity>,
    val customLibraries: List<CustomLibraryEntity>,
    val scan: ScanStatus,
    val settings: ReaderSettings,
)

private data class LibraryControls(
    val sessions: List<ReadingSessionEntity>,
    val query: String,
    val category: LibraryCategory,
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ShelfApp.repository(app)
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow<LibraryCategory>(LibraryCategory.All)
    val category: StateFlow<LibraryCategory> = _category.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _folderWriteRequest = MutableStateFlow<FolderWritePermissionRequest?>(null)
    val folderWriteRequest: StateFlow<FolderWritePermissionRequest?> = _folderWriteRequest.asStateFlow()

    private val collator: Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val state: StateFlow<LibraryUiState> = combine(
        combine(
            repository.observeCards(),
            repository.observeRoots(),
            repository.observeCustomLibraries(),
            repository.scanner.status,
            repository.settings.flow,
        ) { cards, roots, customLibraries, scan, settings ->
            LibrarySources(cards, roots, customLibraries, scan, settings)
        },
        combine(
            repository.observeReadingSessions(),
            _query,
            _category,
        ) { sessions, query, category ->
            LibraryControls(sessions, query, category)
        },
    ) { sources, controls -> sources to controls }
        .flatMapLatest { (sources, controls) ->
            val cards = sources.cards
            val activeCategory = controls.category

            val duplicateIds = duplicateIds(cards)

            val base = LibraryUiState(
                roots = sources.roots,
                customLibraries = sources.customLibraries,
                seriesNames = cards.map { it.series.trim() }.filter { it.isNotEmpty() }.distinct()
                    .sortedWith { a, b -> collator.compare(a, b) },
                duplicateCount = duplicateIds.size,
                stats = readingStats(cards, controls.sessions),
                activeCategory = activeCategory,
                scan = sources.scan,
                settings = sources.settings,
                query = controls.query,
                loading = false,
            )

            if (activeCategory is LibraryCategory.Custom) {
                repository.dao.observeBookIdsInLibrary(activeCategory.id).map { ids ->
                    val idSet = ids.toSet()
                    withContext(Dispatchers.Default) {
                        computeState(base, cards.filter { it.id in idSet })
                    }
                }
            } else {
                val filteredCards = when (activeCategory) {
                    LibraryCategory.All -> cards
                    is LibraryCategory.Folder -> cards.filter { it.treeUri == activeCategory.uri }
                    is LibraryCategory.Series -> cards.filter { it.series.equals(activeCategory.name, true) }
                    LibraryCategory.Duplicates -> cards.filter { it.id in duplicateIds }
                    else -> cards
                }
                flowOf(withContext(Dispatchers.Default) { computeState(base, filteredCards) })
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private fun computeState(base: LibraryUiState, cards: List<BookCard>): LibraryUiState {
        val transformer = WordTransformer(
            getApplication<Application>().applicationContext,
            base.settings.wordReplacements,
            base.settings.wordReplacementsUri,
        )
        val filterObj = LibraryFilter.from(base.settings.filter)
        val needle = base.query.trim().lowercase()

        val counts = mapOf(
            LibraryFilter.ALL to cards.size,
            LibraryFilter.READING to cards.count { bucket(it) == LibraryFilter.READING },
            LibraryFilter.UNREAD to cards.count { bucket(it) == LibraryFilter.UNREAD },
            LibraryFilter.FINISHED to cards.count { bucket(it) == LibraryFilter.FINISHED },
        )

        val visible = cards
            .map {
                val title = if (base.settings.useFilenameAsTitle) it.filename.substringBeforeLast('.') else it.title
                val tTitle = transformer.transformPlain(title)
                val tAuthor = transformer.transformPlain(it.author)
                it.copy(title = tTitle, author = tAuthor)
            }
            .filter { filterObj == LibraryFilter.ALL || bucket(it) == filterObj }
            .filter { card ->
                needle.isEmpty() || listOf(card.title, card.author, card.filename, card.folder, card.series)
                    .any { it.lowercase().contains(needle) }
            }
            .sortedWith(comparator(LibrarySort.from(base.settings.sort)))

        return base.copy(books = visible, counts = counts)
    }

    private fun bucket(card: BookCard): LibraryFilter = when {
        card.percent >= 98 -> LibraryFilter.FINISHED
        card.started -> LibraryFilter.READING
        else -> LibraryFilter.UNREAD
    }

    private fun duplicateIds(cards: List<BookCard>): Set<String> {
        fun clean(value: String) = value.lowercase().replace(Regex("[^\\p{L}\\p{N}]+"), "").trim()
        return cards.groupBy { card ->
            val title = clean(card.title.ifBlank { card.filename.substringBeforeLast('.') })
            val author = clean(card.author)
            if (title.isNotEmpty()) "title:$title|$author" else "file:${clean(card.filename)}|${card.size}"
        }.values.filter { it.size > 1 }.flatten().mapTo(LinkedHashSet()) { it.id }
    }

    private fun readingStats(cards: List<BookCard>, sessions: List<ReadingSessionEntity>): ReadingStats {
        val now = System.currentTimeMillis()
        val weekStart = now - 7L * 24 * 60 * 60 * 1_000
        val zone = ZoneId.systemDefault()
        val activeDays = buildSet {
            sessions.forEach { add(Instant.ofEpochMilli(it.startedAt).atZone(zone).toLocalDate()) }
            cards.mapNotNull { it.lastRead }.filter { it > 0 }.forEach {
                add(Instant.ofEpochMilli(it).atZone(zone).toLocalDate())
            }
        }
        var streak = 0
        var day = LocalDate.now(zone)
        if (day !in activeDays) day = day.minusDays(1)
        while (day in activeDays) {
            streak++
            day = day.minusDays(1)
        }
        return ReadingStats(
            startedBooks = cards.count { it.started },
            finishedBooks = cards.count { it.percent >= 98 },
            totalSeconds = sessions.sumOf { it.durationSeconds },
            weekSeconds = sessions.filter { it.startedAt >= weekStart }.sumOf { it.durationSeconds },
            streakDays = streak,
        )
    }

    private fun comparator(sort: LibrarySort): Comparator<BookCard> {
        val byTitle = Comparator<BookCard> { a, b -> collator.compare(a.title, b.title) }
        return when (sort) {
            LibrarySort.RECENT ->
                compareByDescending<BookCard> { it.lastRead ?: 0L }.then(byTitle)
            LibrarySort.TITLE ->
                compareByDescending<BookCard> { it.favorite }.then(byTitle)
            LibrarySort.AUTHOR ->
                Comparator<BookCard> { a, b ->
                    collator.compare(a.author.ifBlank { "\uFFFF" }, b.author.ifBlank { "\uFFFF" })
                }.then(byTitle)
            LibrarySort.ADDED ->
                compareByDescending<BookCard> { it.added }.then(byTitle)
            LibrarySort.PRIORITY ->
                compareByDescending<BookCard> { it.favorite }
                    .thenByDescending { it.started }
                    .then(byTitle)
        }
    }

    // ---------------------------------------------------------------- actions

    fun setQuery(value: String) { _query.value = value }

    fun setCategory(category: LibraryCategory) { _category.value = category }

    fun setFilter(filter: LibraryFilter) = viewModelScope.launch {
        repository.settings.update { it.copy(filter = filter.key) }
    }

    fun setSort(sort: LibrarySort) = viewModelScope.launch {
        repository.settings.update { it.copy(sort = sort.key) }
    }

    fun setDensity(density: GridDensity) = viewModelScope.launch {
        repository.settings.update { it.copy(gridDensity = density.key) }
    }

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = viewModelScope.launch {
        repository.settings.update(transform)
    }

    fun toggleFavorite(bookId: String) = viewModelScope.launch {
        repository.toggleFavorite(bookId)
    }

    fun rescan(force: Boolean = false) = repository.rescan(force)

    fun coverPath(name: String?) = repository.coverFile(name)?.absolutePath

    fun clearProgress(bookId: String) = viewModelScope.launch {
        repository.clearProgress(bookId)
    }

    fun deleteBookFromSource(bookId: String) = viewModelScope.launch {
        performDelete(bookId)
    }

    fun answerFolderWriteRequest(selectedTreeUri: Uri?) {
        val request = _folderWriteRequest.value ?: return
        _folderWriteRequest.value = null
        if (selectedTreeUri == null) {
            _message.value = "Deletion cancelled. No files were changed."
            return
        }
        viewModelScope.launch {
            val error = repository.grantFolderWriteAccess(request.treeUri, selectedTreeUri)
            if (error != null) _message.value = error else performDelete(request.bookId)
        }
    }

    private suspend fun performDelete(bookId: String) {
        val result = repository.deleteBookFromSource(bookId)
        val permissionRequest = result.writePermissionRequest
        if (permissionRequest != null) {
            _folderWriteRequest.value = permissionRequest
        } else {
            _message.value = result.message
        }
    }

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        _message.value = runCatching { repository.exportBackup(uri) }.fold(
            onSuccess = { "Backup saved: ${it.progressItems} reading positions and ${it.annotations} highlights" },
            onFailure = { it.message ?: "Backup failed" },
        )
    }

    fun restoreBackup(uri: Uri) = viewModelScope.launch {
        _message.value = runCatching { repository.restoreBackup(uri) }.fold(
            onSuccess = {
                "Backup restored for ${it.booksMatched} matching books (${it.bookmarks} bookmarks, ${it.annotations} highlights)"
            },
            onFailure = { it.message ?: "Restore failed" },
        )
    }

    fun consumeMessage() { _message.value = null }

    // -------------------------------------------------------- custom libraries

    fun createLibrary(name: String) = viewModelScope.launch {
        repository.addCustomLibrary(name)
    }

    fun removeLibrary(id: Long) = viewModelScope.launch {
        repository.removeCustomLibrary(id)
        if ((_category.value as? LibraryCategory.Custom)?.id == id) {
            _category.value = LibraryCategory.All
        }
    }

    fun addToLibrary(libraryId: Long, bookId: String) = viewModelScope.launch {
        repository.addBookToLibrary(libraryId, bookId)
    }

    fun removeFromLibrary(libraryId: Long, bookId: String) = viewModelScope.launch {
        repository.removeBookFromLibrary(libraryId, bookId)
    }

    fun observeLibrariesForBook(bookId: String) = repository.observeLibrariesForBook(bookId)
}
