package com.shelf.reader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.ShelfApp
import com.shelf.reader.data.BookCard
import com.shelf.reader.data.BookFolders
import com.shelf.reader.data.CustomLibraryEntity
import com.shelf.reader.data.FolderWritePermissionRequest
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.data.ReadingSessionEntity
import com.shelf.reader.data.RootEntity
import com.shelf.reader.data.ScanStatus
import com.shelf.reader.data.WordTransformer
import com.shelf.reader.data.tagKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

sealed interface LibraryCategory {
    object All : LibraryCategory
    object Folders : LibraryCategory
    data class Folder(val uri: String, val name: String, val path: List<String> = emptyList()) : LibraryCategory
    data class Custom(val id: Long, val name: String) : LibraryCategory
    data class Series(val name: String) : LibraryCategory
    object Tags : LibraryCategory
    data class Tag(val name: String) : LibraryCategory
    object Duplicates : LibraryCategory

    val label: String get() = when (this) {
        is All -> "All Books"
        is Folders -> "Browse folders"
        is Folder -> path.lastOrNull() ?: name
        is Custom -> name
        is Series -> name
        is Tags -> "Tags"
        is Tag -> name
        is Duplicates -> "Duplicates"
    }
}

data class LibraryTag(val name: String, val count: Int)

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
    PRIORITY("priority", "Favourites first"),
    TAG("tag", "Tags A–Z");

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
    val tags: List<LibraryTag> = emptyList(),
    val duplicateCount: Int = 0,
    val stats: ReadingStats = ReadingStats(),
    val activeCategory: LibraryCategory = LibraryCategory.All,
    val scan: ScanStatus = ScanStatus(),
    val settings: ReaderSettings = ReaderSettings(),
    val query: String = "",
    val loading: Boolean = true,
    val folders: List<LibraryFolderEntry> = emptyList(),
    val unindexedFolders: Boolean = false,
    val continueReading: BookCard? = null,
) {
    val filter: LibraryFilter get() = LibraryFilter.from(settings.filter)
    val sort: LibrarySort get() = LibrarySort.from(settings.sort)
    val density: GridDensity get() = GridDensity.from(settings.gridDensity)
}

private data class LibraryCatalog(
    val cards: List<BookCard>,
    val roots: List<RootEntity>,
    val seriesNames: List<String>,
    val tags: List<LibraryTag>,
    val duplicateIds: Set<String>,
    val unindexedFolders: Boolean,
)

private data class LibrarySelection(val category: LibraryCategory, val members: Set<String> = emptySet())

private data class LibraryDisplayOptions(
    val sort: LibrarySort,
    val useFilenameAsTitle: Boolean,
    val wordReplacements: String,
    val wordReplacementsUri: String?,
)

private data class PreparedLibrary(
    val catalog: LibraryCatalog,
    val category: LibraryCategory,
    val books: List<BookCard>,
    val folders: List<LibraryFolderEntry>,
    val counts: Map<LibraryFilter, Int>,
    val folderSearchBooks: List<BookCard> = emptyList(),
    val folderSearchCounts: Map<LibraryFilter, Int> = emptyMap(),
    val continueReading: BookCard? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ShelfApp.repository(app)

    init {
        viewModelScope.launch { repository.scanner.refreshOutdatedBooks() }
    }
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _category = MutableStateFlow<LibraryCategory>(LibraryCategory.All)
    val category: StateFlow<LibraryCategory> = _category.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _folderWriteRequest = MutableStateFlow<FolderWritePermissionRequest?>(null)
    val folderWriteRequest: StateFlow<FolderWritePermissionRequest?> = _folderWriteRequest.asStateFlow()

    private val catalog = combine(repository.observeCards(), repository.observeRoots()) { allCards, roots ->
        val enabledRoots = roots.filter { it.enabled }.associateBy { it.treeUri }
        val cards = allCards.filter { card ->
            val root = enabledRoots[card.treeUri]
            root != null && (root.includeSubfolders || BookFolders.decode(card.folderPath)?.isEmpty() == true ||
                (card.folderPath == null && card.folder == root.name))
        }
        LibraryCatalog(
            cards = cards,
            roots = roots,
            seriesNames = cards.map { it.series.trim() }.filter { it.isNotEmpty() }.distinct()
                .sortedWith(libraryCollator()),
            tags = libraryTags(cards),
            duplicateIds = duplicateIds(cards),
            unindexedFolders = cards.any { it.folderPath == null },
        )
    }.flowOn(Dispatchers.Default).shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    private val selection = _category.flatMapLatest { category ->
        if (category is LibraryCategory.Custom) {
            repository.dao.observeBookIdsInLibrary(category.id).map { LibrarySelection(category, it.toSet()) }
        } else {
            flowOf(LibrarySelection(category))
        }
    }

    private val displayOptions = repository.settings.flow.map {
        LibraryDisplayOptions(LibrarySort.from(it.sort), it.useFilenameAsTitle, it.wordReplacements, it.wordReplacementsUri)
    }.distinctUntilChanged()

    private val prepared = combine(catalog, selection, displayOptions, ::Triple)
        .mapLatest { (catalog, selection, options) -> prepareLibrary(catalog, selection, options) }

    private val listing = combine(prepared, _query, repository.settings.flow.map { LibraryFilter.from(it.filter) }
        .distinctUntilChanged(), ::Triple)
        .mapLatest { (prepared, query, filter) -> filterLibrary(prepared, query, filter) }

    private val stats = combine(catalog, repository.observeReadingSessions()) { catalog, sessions ->
        readingStats(catalog.cards, sessions)
    }.distinctUntilChanged()

    val state: StateFlow<LibraryUiState> = combine(
        listing, stats, repository.observeCustomLibraries(), repository.scanner.status, repository.settings.flow,
    ) { listing, stats, customLibraries, scan, settings ->
        listing.copy(stats = stats, customLibraries = customLibraries, scan = scan, settings = settings)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private suspend fun prepareLibrary(
        catalog: LibraryCatalog,
        selection: LibrarySelection,
        options: LibraryDisplayOptions,
    ): PreparedLibrary {
        val category = selection.category
        val cards = when (category) {
            LibraryCategory.All -> catalog.cards
            LibraryCategory.Folders, LibraryCategory.Tags -> emptyList()
            is LibraryCategory.Folder -> catalog.cards.filter {
                it.treeUri == category.uri && LibraryFolderIndex.includes(BookFolders.decode(it.folderPath), category.path, true)
            }
            is LibraryCategory.Series -> catalog.cards.filter { it.series.equals(category.name, true) }
            is LibraryCategory.Tag -> {
                val key = tagKey(category.name)
                catalog.cards.filter { card -> card.tags.any { tagKey(it) == key } }
            }
            LibraryCategory.Duplicates -> catalog.cards.filter { it.id in catalog.duplicateIds }
            is LibraryCategory.Custom -> catalog.cards.filter { it.id in selection.members }
        }

        val transformer = WordTransformer(
            getApplication<Application>().applicationContext,
            options.wordReplacements,
            options.wordReplacementsUri,
        )
        val coroutine = currentCoroutineContext()
        val displayCards = cards.map { card ->
            coroutine.ensureActive()
            val title = if (options.useFilenameAsTitle) card.filename.substringBeforeLast('.') else card.title
            val displayTitle = transformer.transformPlain(title)
            val displayAuthor = transformer.transformPlain(card.author)
            if (displayTitle == card.title && displayAuthor == card.author) card
            else card.copy(title = displayTitle, author = displayAuthor)
        }
        val sorted = displayCards.sortedWith(comparator(options.sort, displayCards))
        val currentFolder = if (category is LibraryCategory.Folder) sorted.filter {
            LibraryFolderIndex.includes(BookFolders.decode(it.folderPath), category.path, false)
        } else sorted

        return PreparedLibrary(
            catalog, category, currentFolder,
            LibraryFolderIndex.children(catalog.roots, catalog.cards, category),
            filterCounts(currentFolder),
            folderSearchBooks = if (category is LibraryCategory.Folder) sorted else emptyList(),
            folderSearchCounts = if (category is LibraryCategory.Folder) filterCounts(sorted) else emptyMap(),
            continueReading = if (category == LibraryCategory.All) sorted.asSequence()
                .filter { it.started && it.percent < 98 && !it.broken }
                .maxByOrNull { it.lastRead ?: 0L } else null,
        )
    }

    private suspend fun filterLibrary(prepared: PreparedLibrary, query: String, filter: LibraryFilter): LibraryUiState {
        val needle = query.trim().lowercase()
        val searchSubfolders = prepared.category is LibraryCategory.Folder && needle.isNotEmpty()
        val candidates = if (searchSubfolders) prepared.folderSearchBooks else prepared.books
        val coroutine = currentCoroutineContext()
        val books = if (needle.isEmpty() && filter == LibraryFilter.ALL) candidates else candidates.filter { card ->
            coroutine.ensureActive()
            (filter == LibraryFilter.ALL || bucket(card) == filter) &&
                (needle.isEmpty() || card.title.lowercase().contains(needle) || card.author.lowercase().contains(needle) ||
                    card.filename.lowercase().contains(needle) || card.folder.lowercase().contains(needle) ||
                    card.series.lowercase().contains(needle) || card.tags.any { tagKey(it).contains(needle) })
        }
        val folders = if (prepared.category == LibraryCategory.Folders && needle.isNotEmpty()) {
            prepared.folders.filter { it.name.contains(query.trim(), ignoreCase = true) }
        } else prepared.folders
        val catalog = prepared.catalog
        return LibraryUiState(
            books = books,
            counts = if (searchSubfolders) prepared.folderSearchCounts else prepared.counts,
            roots = catalog.roots,
            seriesNames = catalog.seriesNames,
            tags = if (prepared.category == LibraryCategory.Tags && needle.isNotEmpty()) {
                catalog.tags.filter { tagKey(it.name).contains(needle) }
            } else catalog.tags,
            duplicateCount = catalog.duplicateIds.size,
            activeCategory = prepared.category,
            query = query,
            loading = false,
            folders = folders,
            unindexedFolders = catalog.unindexedFolders,
            continueReading = prepared.continueReading.takeIf { needle.isEmpty() && filter == LibraryFilter.ALL },
        )
    }

    private fun filterCounts(cards: List<BookCard>): Map<LibraryFilter, Int> {
        val counts = IntArray(LibraryFilter.entries.size)
        counts[LibraryFilter.ALL.ordinal] = cards.size
        for (card in cards) {
            counts[bucket(card).ordinal]++
        }
        return LibraryFilter.entries.associateWith { counts[it.ordinal] }
    }

    private fun bucket(card: BookCard): LibraryFilter = when {
        card.percent >= 98 -> LibraryFilter.FINISHED
        card.started -> LibraryFilter.READING
        else -> LibraryFilter.UNREAD
    }

    private fun duplicateIds(cards: List<BookCard>): Set<String> {
        fun clean(value: String) = duplicatePunctuation.replace(value.lowercase(), "")
        return cards.groupBy { card ->
            val title = clean(card.title.ifBlank { card.filename.substringBeforeLast('.') })
            val author = clean(card.author)
            if (title.isNotEmpty()) "title:$title|$author" else "file:${clean(card.filename)}|${card.size}"
        }.values.filter { it.size > 1 }.flatten().mapTo(LinkedHashSet()) { it.id }
    }

    private fun libraryTags(cards: List<BookCard>): List<LibraryTag> {
        val tags = mutableMapOf<String, LibraryTag>()
        for (card in cards) {
            for (name in card.tags) {
                val key = tagKey(name)
                tags[key] = LibraryTag(tags[key]?.name ?: name, (tags[key]?.count ?: 0) + 1)
            }
        }
        val collator = libraryCollator()
        return tags.values.sortedWith { a, b -> collator.compare(a.name, b.name) }
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

    private fun comparator(sort: LibrarySort, cards: List<BookCard>): Comparator<BookCard> {
        val collator = libraryCollator()
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
            LibrarySort.TAG -> {
                val byTag = Comparator<String> { a, b -> collator.compare(a, b) }
                val firstTags = cards.associate { it.id to it.catalogTags.ifEmpty { it.subjects }.minWithOrNull(byTag) }
                compareBy<BookCard, String?>(nullsLast(byTag)) { firstTags[it.id] }.then(byTitle)
            }
        }
    }

    private fun libraryCollator(): Collator = Collator.getInstance().apply { strength = Collator.PRIMARY }

    private companion object {
        val duplicatePunctuation = Regex("[^\\p{L}\\p{N}]+")
    }

    // ---------------------------------------------------------------- actions

    fun setQuery(value: String) { _query.value = value }

    fun setCategory(category: LibraryCategory) {
        _category.value = category
        _query.value = ""
        if ((category is LibraryCategory.Folder || category == LibraryCategory.Folders) && state.value.unindexedFolders) {
            repository.rescan(false)
        }
    }

    fun parentFolder() {
        val current = _category.value as? LibraryCategory.Folder ?: return
        setCategory(if (current.path.isEmpty()) LibraryCategory.Folders else current.copy(path = current.path.dropLast(1)))
    }

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

    suspend fun bookContents(id: String) = repository.openBook(id).toc

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
