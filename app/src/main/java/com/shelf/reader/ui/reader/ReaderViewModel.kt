package com.shelf.reader.ui.reader

import android.app.Application
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.ShelfApp
import com.shelf.reader.data.AnnotationEntity
import com.shelf.reader.data.BookmarkEntity
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.epub.EpubBook
import com.shelf.reader.epub.TocEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.util.Locale

data class ReaderSearchResult(
    val chapter: Int,
    val chapterTitle: String,
    val snippet: String,
    val query: String,
)

/** A section fetched for continuous scrolling, ready to go under the current page. */
data class SectionAppend(
    val revision: Long,
    val index: Int,
    val next: Int,
    val label: String,
    val html: String,
)

data class ReaderUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val bookTitle: String = "",
    val chapterTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterCount: Int = 0,
    val html: String = "",
    val toc: List<TocEntry> = emptyList(),
    val settings: ReaderSettings = ReaderSettings(),
    val barsVisible: Boolean = true,
    val speaking: Boolean = false,
    val ttsProgress: Float = 0f,
    val ttsVoice: String? = null,
    /** Set when a chapter loads so the WebView knows where to jump. */
    val restoreOffset: Float = 0f,
    val restoreFragment: String = "",
    val restoreHighlight: String = "",
    /** Bumped on every chapter load so the WebView reloads even for a repeat index. */
    val revision: Long = 0,
    val scrollFraction: Float = 0f,
    val ttsHighlight: String? = null,
    val availableVoices: List<String> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<ReaderSearchResult> = emptyList(),
    val searchingBook: Boolean = false,
    /** Index of every reading chapter currently stacked in the page. */
    val loadedSections: List<Int> = emptyList(),
    /** Reading-spine index of the chapter that follows [chapterIndex]. */
    val nextChapterIndex: Int = -1,
    /** The section the page was built from, with its header label and follower. */
    val pageIndex: Int = 0,
    val pageLabel: String = "",
    val pageNext: Int = -1,
) {
    val wholeBookProgress: Float
        get() = if (chapterCount > 0) (chapterIndex + scrollFraction) / chapterCount else 0f

    val canGoBack: Boolean get() = chapterIndex > 0
    val canGoForward: Boolean get() = nextChapterIndex in 0 until chapterCount
}

class ReaderViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = ShelfApp.repository(app)

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state.asStateFlow()

    private val _events = MutableStateFlow<ReaderEvent?>(null)
    val events: StateFlow<ReaderEvent?> = _events.asStateFlow()

    @Volatile private var bookId: String = ""
    @Volatile private var bookVersion: String = ""
    @Volatile private var book: EpubBook? = null
    private var chapterText: String = ""
    private val sectionTexts = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val sectionLabels = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private val appendingSections = HashSet<Int>()
    private var appendJob: Job? = null

    private val _appends = MutableSharedFlow<SectionAppend>(extraBufferCapacity = 4)
    /** Sections to add under the page when continuous scrolling reaches the end. */
    val appends: SharedFlow<SectionAppend> = _appends.asSharedFlow()
    private var currentFraction: Float = 0f
    private var loadJob: Job? = null
    private var chapterJob: Job? = null
    private var saveJob: Job? = null
    private var searchJob: Job? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsPieces: List<String> = emptyList()
    private var readingStartedAt: Long? = null
    private var readerActive = false

    private val bookIdFlow = MutableStateFlow("")

    init {
        observeSettings()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val bookmarks: StateFlow<List<BookmarkEntity>> =
        bookIdFlow
            .flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else repository.observeBookmarks(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val annotations: StateFlow<List<AnnotationEntity>> =
        bookIdFlow
            .flatMapLatest { if (it.isEmpty()) flowOf(emptyList()) else repository.observeAnnotations(it) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // -------------------------------------------------------------------- load

    fun load(id: String) {
        if (bookId == id && book != null) return

        val previousBookId = bookId
        if (previousBookId.isNotEmpty()) saveProgressNow()
        if (previousBookId.isNotEmpty() && previousBookId != id) finishReadingSession()
        loadJob?.cancel()
        chapterJob?.cancel()
        searchJob?.cancel()
        saveJob?.cancel()
        stopSpeaking()
        book = null
        bookVersion = ""
        if (previousBookId.isNotEmpty() && previousBookId != id) {
            repository.releaseBook(previousBookId)
        }

        bookId = id
        if (readerActive && readingStartedAt == null) readingStartedAt = System.currentTimeMillis()
        bookIdFlow.value = id
        chapterText = ""
        clearSections()
        currentFraction = 0f
        _state.value = _state.value.copy(
            loading = true,
            error = null,
            bookTitle = "",
            chapterTitle = "",
            chapterIndex = 0,
            chapterCount = 0,
            html = "",
            toc = emptyList(),
            restoreOffset = 0f,
            restoreFragment = "",
            restoreHighlight = "",
            barsVisible = true,
            revision = _state.value.revision + 1,
            scrollFraction = 0f,
            loadedSections = emptyList(),
            nextChapterIndex = -1,
        )

        loadJob = viewModelScope.launch {
            val requestedBookId = id

            val entity = repository.bookEntity(id)
            if (bookId != requestedBookId) return@launch
            if (entity == null) {
                _state.value = _state.value.copy(loading = false, error = "That book is not in the library.")
                return@launch
            }

            val opened = runCatching { repository.openBook(id) }
            val epub = opened.getOrElse { error ->
                _state.value = _state.value.copy(
                    loading = false,
                    error = error.message ?: "This book would not open. It may have moved, or the file is damaged.",
                    bookTitle = entity.title,
                )
                return@launch
            }
            if (bookId != requestedBookId) {
                repository.releaseBook(requestedBookId)
                return@launch
            }
            book = epub
            bookVersion = entity.stamp.ifBlank { entity.id }

            val progress = repository.progress(id)
            val legacySpine = entity.chapters != epub.chapterCount && epub.auxiliarySections.isNotEmpty()
            val savedIndex = progress?.chapter ?: 0
            val startIndex = when {
                epub.chapterCount <= 0 -> 0
                legacySpine -> epub.readingIndexForPackageIndex(savedIndex)
                else -> savedIndex.coerceIn(0, epub.chapterCount - 1)
            }
            if (legacySpine && progress != null && progress.chapter != startIndex) {
                repository.remapProgressChapter(id, startIndex)
            }
            if (legacySpine) repository.remapSavedLocations(id, epub)
            repository.syncBookMetadata(id, epub.meta)
            _state.value = _state.value.copy(
                bookTitle = epub.meta.title.ifBlank { entity.title },
                chapterCount = epub.chapterCount,
                toc = epub.toc,
            )
            openChapter(
                index = startIndex,
                offset = progress?.offset ?: 0f,
            )
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            repository.settings.flow.collect { settings ->
                _state.value = _state.value.copy(settings = settings)
            }
        }
    }

    // ----------------------------------------------------------- sections

    /** Front matter is metadata/navigation now, so reader indices are already canonical. */
    fun sectionStart(index: Int): Int = index

    private fun nextSection(epub: EpubBook, index: Int): Int {
        val next = index + 1
        return if (next < epub.chapterCount) next else -1
    }

    private class SectionContent(
        val index: Int,
        val next: Int,
        val label: String,
        val html: String,
        val text: String,
    )

    /** Reads one section off the disk. Call on an IO dispatcher. */
    private fun readSection(epub: EpubBook, start: Int, forBook: String, version: String): SectionContent? {
        val resolver: (String) -> String = { path -> ReaderHtml.resourceUrl(forBook, version, path) }
        return runCatching {
            val chapter = epub.chapter(start, resolver)
            SectionContent(
                index = start,
                next = nextSection(epub, start),
                label = epub.tocLabelFor(start).ifBlank { chapter.title },
                html = chapter.html,
                text = Jsoup.parse(chapter.html).text(),
            )
        }.getOrNull()
    }

    private fun titleFor(content: SectionContent): String = content.label

    private fun clearSections() {
        appendJob?.cancel()
        sectionTexts.clear()
        sectionLabels.clear()
        synchronized(appendingSections) { appendingSections.clear() }
    }

    fun openChapter(
        index: Int,
        offset: Float = 0f,
        fragment: String = "",
        highlight: String = "",
    ) {
        val epub = book ?: return
        if (index !in 0 until epub.chapterCount) return
        val requestedBookId = bookId
        val requestedVersion = bookVersion
        val start = sectionStart(index)
        // Jumping to a file inside the merged front page lands on that file's part.
        val target = if (start != index && fragment.isBlank()) "shelf-part-$index" else fragment
        val restoreOffset = if (start != index) 0f else offset

        stopSpeaking()
        chapterJob?.cancel()
        clearSections()
        chapterJob = viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val content = withContext(Dispatchers.IO) {
                readSection(epub, start, requestedBookId, requestedVersion)
            }

            if (bookId != requestedBookId || book !== epub) return@launch

            if (content == null) {
                _state.value = _state.value.copy(
                    loading = false,
                    error = "That section would not load. Try the next one.",
                )
                return@launch
            }

            val title = titleFor(content)
            sectionTexts[content.index] = content.text
            sectionLabels[content.index] = title
            chapterText = content.text
            currentFraction = restoreOffset

            _state.value = _state.value.copy(
                loading = false,
                error = null,
                chapterIndex = content.index,
                chapterTitle = title,
                html = content.html,
                pageIndex = content.index,
                pageLabel = content.label,
                pageNext = content.next,
                restoreOffset = restoreOffset,
                restoreFragment = target,
                restoreHighlight = highlight,
                barsVisible = true,
                revision = _state.value.revision + 1,
                scrollFraction = restoreOffset,
                loadedSections = listOf(content.index),
                nextChapterIndex = content.next,
            )

            saveProgressNow()
        }
    }

    /** The page scrolled near its end and wants [index] appended below. */
    fun requestSection(index: Int) {
        val epub = book ?: return
        if (!_state.value.settings.continuousScroll) return
        if (index !in 0 until epub.chapterCount) return
        if (index in _state.value.loadedSections) return
        synchronized(appendingSections) { if (!appendingSections.add(index)) return }
        val requestedBookId = bookId
        val requestedVersion = bookVersion
        val revision = _state.value.revision

        appendJob = viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                readSection(epub, index, requestedBookId, requestedVersion)
            }
            synchronized(appendingSections) { appendingSections.remove(index) }
            if (content == null || bookId != requestedBookId || book !== epub) return@launch
            if (_state.value.revision != revision) return@launch

            sectionTexts[content.index] = content.text
            sectionLabels[content.index] = titleFor(content)
            _state.value = _state.value.copy(
                loadedSections = (_state.value.loadedSections + content.index).distinct(),
            )
            _appends.emit(
                SectionAppend(revision, content.index, content.next, content.label, content.html)
            )
        }
    }

    /** The reader scrolled into a different section of the stacked page. */
    fun onSection(index: Int) {
        val epub = book ?: return
        val current = _state.value
        if (current.chapterIndex == index) return
        // Read-aloud keeps going with what it was reading; the next start uses this section.
        chapterText = sectionTexts[index].orEmpty()
        _state.value = current.copy(
            chapterIndex = index,
            chapterTitle = sectionLabels[index] ?: epub.tocLabelFor(index),
            nextChapterIndex = nextSection(epub, index),
            // Progress for the new section arrives with the next scroll report.
            scrollFraction = 0f,
        )
        currentFraction = 0f
    }

    fun next() {
        stopSpeaking()
        val target = _state.value.nextChapterIndex
        if (target >= 0) openChapter(target)
    }

    fun previous() {
        stopSpeaking()
        val target = _state.value.chapterIndex - 1
        if (target >= 0) openChapter(sectionStart(target))
    }

    // ----------------------------------------------------------------- search

    fun searchBook(query: String) {
        searchJob?.cancel()
        val needle = query.trim()
        _state.value = _state.value.copy(searchQuery = query)
        val epub = book
        if (needle.length < 2 || epub == null) {
            _state.value = _state.value.copy(searchResults = emptyList(), searchingBook = false)
            return
        }

        val requestedBookId = bookId
        searchJob = viewModelScope.launch {
            _state.value = _state.value.copy(searchingBook = true, searchResults = emptyList())
            val results = withContext(Dispatchers.IO) {
                buildList {
                    for (index in 0 until epub.chapterCount) {
                        if (size >= 200) break
                        val chapter = runCatching { epub.chapter(index) { "" } }.getOrNull() ?: continue
                        val text = Jsoup.parse(chapter.html).text().replace(Regex("\\s+"), " ")
                        val match = text.indexOf(needle, ignoreCase = true)
                        if (match < 0) continue
                        val from = (match - 70).coerceAtLeast(0)
                        val to = (match + needle.length + 110).coerceAtMost(text.length)
                        val snippet = buildString {
                            if (from > 0) append("…")
                            append(text.substring(from, to).trim())
                            if (to < text.length) append("…")
                        }
                        add(
                            ReaderSearchResult(
                                chapter = index,
                                chapterTitle = epub.tocLabelFor(index).ifBlank { chapter.title },
                                snippet = snippet,
                                query = needle,
                            )
                        )
                    }
                }
            }
            if (bookId == requestedBookId && book === epub && _state.value.searchQuery.trim() == needle) {
                _state.value = _state.value.copy(searchResults = results, searchingBook = false)
            }
        }
    }

    fun openSearchResult(result: ReaderSearchResult) {
        openChapter(result.chapter, highlight = result.query)
    }

    // ---------------------------------------------------------------- progress

    fun onScroll(fraction: Float) {
        currentFraction = fraction
        _state.value = _state.value.copy(scrollFraction = fraction)
        scheduleSave()
    }

    fun onScrollDirection(scrollingDown: Boolean) {
        val visible = !scrollingDown
        if (_state.value.barsVisible != visible) {
            _state.value = _state.value.copy(barsVisible = visible)
        }
    }

    fun toggleBars() {
        _state.value = _state.value.copy(barsVisible = !_state.value.barsVisible)
    }

    fun setBars(visible: Boolean) {
        if (_state.value.barsVisible != visible) {
            _state.value = _state.value.copy(barsVisible = visible)
        }
    }

    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(900)
            saveProgressNow()
        }
    }

    fun saveProgressNow() {
        val state = _state.value
        val currentBookId = bookId
        val fraction = currentFraction
        if (currentBookId.isEmpty() || state.chapterCount == 0) return
        // Use the app scope for the final save, so it isn't cancelled when the VM is cleared.
        repository.scope.launch {
            repository.saveProgress(
                currentBookId,
                state.chapterIndex,
                fraction,
                state.chapterTitle,
            )
        }
    }

    // --------------------------------------------------------------- bookmarks

    fun addBookmark() {
        val state = _state.value
        val currentBookId = bookId
        val fraction = currentFraction
        if (currentBookId.isEmpty()) return
        viewModelScope.launch {
            repository.addBookmark(currentBookId, state.chapterIndex, fraction, state.chapterTitle)
            _events.value = ReaderEvent.Toast("Bookmark saved")
        }
    }

    fun deleteBookmark(id: Long) = viewModelScope.launch { repository.deleteBookmark(id) }

    fun openBookmark(bookmark: BookmarkEntity) =
        openChapter(bookmark.chapter, bookmark.offset)

    fun consumeEvent() { _events.value = null }

    // ------------------------------------------------------------ annotations

    fun addAnnotation(quote: String, note: String = "") {
        val cleanQuote = quote.trim().replace(Regex("\\s+"), " ").take(2_000)
        if (cleanQuote.isEmpty()) {
            _events.value = ReaderEvent.Toast("Select some text first")
            return
        }
        val state = _state.value
        val currentBookId = bookId
        if (currentBookId.isEmpty()) return
        viewModelScope.launch {
            repository.addAnnotation(
                AnnotationEntity(
                    bookId = currentBookId,
                    chapter = state.chapterIndex,
                    offset = currentFraction,
                    chapterTitle = state.chapterTitle,
                    quote = cleanQuote,
                    note = note.trim().take(4_000),
                )
            )
            _events.value = ReaderEvent.Toast("Highlight saved")
        }
    }

    fun deleteAnnotation(id: Long) = viewModelScope.launch { repository.deleteAnnotation(id) }

    fun openAnnotation(annotation: AnnotationEntity) =
        openChapter(annotation.chapter, annotation.offset, highlight = annotation.quote)

    fun exportAnnotations(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val state = _state.value
        val marks = annotations.value.sortedWith(compareBy({ it.chapter }, { it.offset }))
        val markdown = buildString {
            append("# ").append(state.bookTitle.ifBlank { "Book notes" }).append("\n\n")
            marks.forEach { mark ->
                append("## ").append(mark.chapterTitle.ifBlank { "Section ${mark.chapter + 1}" }).append("\n\n")
                append("> ").append(mark.quote.replace("\n", "\n> ")).append("\n\n")
                if (mark.note.isNotBlank()) append(mark.note).append("\n\n")
            }
        }
        val result = runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri, "w")?.bufferedWriter()?.use {
                it.write(markdown)
            } ?: error("Could not open the selected file")
        }
        _events.value = ReaderEvent.Toast(
            if (result.isSuccess) "Notes exported" else result.exceptionOrNull()?.message ?: "Export failed"
        )
    }

    // --------------------------------------------------------- reading time

    fun setReadingActive(active: Boolean) {
        readerActive = active
        if (active) {
            if (readingStartedAt == null && bookId.isNotEmpty()) readingStartedAt = System.currentTimeMillis()
            return
        }
        finishReadingSession()
    }

    private fun finishReadingSession() {
        val startedAt = readingStartedAt ?: return
        readingStartedAt = null
        val duration = ((System.currentTimeMillis() - startedAt) / 1_000).coerceAtLeast(0)
        val id = bookId
        if (duration < 5 || id.isEmpty()) return
        repository.scope.launch { repository.recordReadingSession(id, startedAt, duration) }
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (ReaderSettings) -> ReaderSettings) = viewModelScope.launch {
        repository.settings.update(transform)
    }

    // --------------------------------------------------------------------- tts

    /**
     * Reads the current section aloud. The book's own language wins when it declares
     * one, which matters for anything that isn't English.
     */
    fun toggleSpeaking() {
        if (_state.value.speaking) stopSpeaking() else startSpeaking()
    }

    private fun startSpeaking() {
        if (chapterText.isBlank()) {
            _events.value = ReaderEvent.Toast("There is no text to read here")
            return
        }
        if (tts == null) {
            tts = TextToSpeech(getApplication()) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    val voices = tts?.voices?.map { it.name }?.sorted() ?: emptyList()
                    _state.value = _state.value.copy(availableVoices = voices)
                    speakNow()
                } else {
                    _events.value = ReaderEvent.Toast("No speech engine is available on this device")
                }
            }.also { engine ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val index = utteranceId?.substringAfter("shelf-")?.toIntOrNull() ?: return
                        val total = ttsPieces.size
                        if (total > 0) {
                            val text = ttsPieces.getOrNull(index)
                            _state.value = _state.value.copy(
                                ttsProgress = index.toFloat() / total,
                            )
                            _events.value = ReaderEvent.TtsHighlight(text)
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith("shelf-") == true) {
                            val index = utteranceId.substringAfter("shelf-").toIntOrNull() ?: return
                            if (index == ttsPieces.lastIndex) {
                                _state.value = _state.value.copy(speaking = false, ttsProgress = 1f)
                                _events.value = ReaderEvent.TtsHighlight(null)
                            }
                        }
                    }

                    @Deprecated("Kept for API levels below 21")
                    override fun onError(utteranceId: String?) {
                        _state.value = _state.value.copy(speaking = false)
                    }
                })
            }
        } else if (ttsReady) {
            speakNow()
        }
    }

    private fun speakNow(fromIndex: Int = 0) {
        val engine = tts ?: return
        val language = book?.meta?.language.orEmpty()
        val locale = runCatching {
            if (language.isNotBlank()) Locale.forLanguageTag(language) else Locale.getDefault()
        }.getOrDefault(Locale.getDefault())

        val result = engine.setLanguage(locale)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
        engine.setSpeechRate(_state.value.settings.ttsSpeed)

        val voiceName = _state.value.settings.ttsVoice
        if (voiceName != null) {
            engine.voices?.find { it.name == voiceName }?.let { engine.voice = it }
        }

        // The engine caps a single utterance, so long sections go in sentence-sized pieces.
        if (fromIndex == 0) {
            ttsPieces = chunk(chapterText)
        }

        engine.stop()
        for (i in fromIndex until ttsPieces.size) {
            val piece = ttsPieces[i]
            val id = "shelf-$i"
            val mode = if (i == fromIndex) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            engine.speak(piece, mode, null, id)
        }
        _state.value = _state.value.copy(speaking = true, ttsProgress = fromIndex.toFloat() / ttsPieces.size.coerceAtLeast(1))
    }

    fun seekTts(progress: Float) {
        if (!_state.value.speaking || ttsPieces.isEmpty()) return
        val index = (progress * ttsPieces.size).toInt().coerceIn(0, ttsPieces.lastIndex)
        speakNow(index)
    }

    fun setTtsSpeed(speed: Float) {
        updateSettings { it.copy(ttsSpeed = speed) }
        if (_state.value.speaking) {
            // Restart from current progress to apply new speed
            val currentIndex = (_state.value.ttsProgress * ttsPieces.size).toInt().coerceIn(0, ttsPieces.lastIndex)
            speakNow(currentIndex)
        }
    }

    fun setTtsVoice(voice: String) {
        updateSettings { it.copy(ttsVoice = voice) }
        if (_state.value.speaking) {
            val currentIndex = (_state.value.ttsProgress * ttsPieces.size).toInt().coerceIn(0, ttsPieces.lastIndex)
            speakNow(currentIndex)
        }
    }

    fun stopSpeaking() {
        tts?.stop()
        if (_state.value.speaking) _state.value = _state.value.copy(speaking = false)
    }

    private fun chunk(text: String, limit: Int = 2_000): List<String> {
        val out = ArrayList<String>()
        val builder = StringBuilder()
        val sentences = text.split(Regex("(?<=[.!?。！？])\\s+"))

        for (sentence in sentences) {
            val s = sentence.trim()
            if (s.isEmpty()) continue

            if (builder.length + s.length > limit && builder.isNotEmpty()) {
                out.add(builder.toString().trim())
                builder.setLength(0)
            }

            if (s.length > limit) {
                if (builder.isNotEmpty()) {
                    out.add(builder.toString().trim())
                    builder.setLength(0)
                }
                s.chunked(limit).forEach { out.add(it.trim()) }
            } else {
                builder.append(s).append(' ')
            }
        }
        if (builder.isNotEmpty()) out.add(builder.toString().trim())
        return out
    }

    // ------------------------------------------------------------------- close

    override fun onCleared() {
        finishReadingSession()
        saveProgressNow()
        tts?.stop()
        tts?.shutdown()
        tts = null
        if (bookId.isNotEmpty()) repository.releaseBook(bookId)
        book = null
        super.onCleared()
    }

    fun resourceFor(
        requestedBookId: String,
        requestedVersion: String,
        path: String,
    ): Pair<ByteArray, String>? {
        if (requestedBookId != bookId || requestedVersion != bookVersion) return null
        return runCatching { book?.resource(path) }.getOrNull()
    }
}

sealed interface ReaderEvent {
    data class Toast(val message: String) : ReaderEvent
    data class TtsHighlight(val text: String?) : ReaderEvent
}
