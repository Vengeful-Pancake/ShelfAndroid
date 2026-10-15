# Shelf for Android

Shelf is a native, local-first EPUB reader for Android. Point it at one or more
folders, let it index the `.epub` files, and read without uploading the library
to a server.

[Download the latest APK](https://github.com/Vengeful-Pancake/ShelfAndroid/releases/latest/download/Shelf-v0.1.apk)

Current release: **0.1** (`versionCode 1`) · Android 8.0+ (API 26) · Kotlin ·
Jetpack Compose · Material 3

## Highlights

- Storage Access Framework folder scanning for internal storage, SD cards, USB
  storage, and document-provider mounts.
- EPUB 2 and EPUB 3 metadata, NCX/navigation contents, covers, and ZIP64 archives.
- Random-access EPUB reading: Shelf seeks directly to the requested chapter
  instead of inflating the whole book.
- Continuous section scrolling, tap/swipe navigation, full-book search, saved
  position, bookmarks, highlights, notes, and Markdown note export.
- Configurable typography, colours, margins, paragraph layout, two-column mode,
  title display, grid density, and word-replacement rules.
- Device text-to-speech with language, voice, speed, and position controls.
- Favourites, series shelves, duplicate detection, custom libraries, reading
  statistics, and portable JSON backup/restore.
- OPDS 1 catalogue downloads plus an optional in-app browser/article reader.
- Automatic GitHub Release checks and an in-app verified APK update flow.

## Install and update

Download `Shelf-v0.1.apk` from the release link above and open it on the Android
device. Android may ask for permission to install apps from the browser or file
manager used to open it.

On each app launch, Shelf silently requests the latest stable release metadata
from `Vengeful-Pancake/ShelfAndroid`. If its numeric version is newer than the
installed `BuildConfig.VERSION_NAME`, Shelf shows the release notes and offers
the APK. The download:

1. must start at an HTTPS `github.com` release URL;
2. may redirect only to GitHub or `*.githubusercontent.com`;
3. is limited to 300 MB and must have an APK/ZIP signature;
4. is checked against GitHub's SHA-256 asset digest when GitHub supplies one;
5. is shared with Android through a non-exported `FileProvider`.

Android 8+ requires per-app permission to install unknown packages. Shelf opens
that settings page when necessary, then hands the verified APK to Android's
system package installer. Android always owns the final confirmation; ordinary
apps cannot silently replace themselves. Future APKs must use a larger
`versionCode` and the same signing key as 0.1.

Update checks are silent when the device is offline. Drafts, prereleases,
releases without a non-debug APK, invalid versions, and non-GitHub downloads are
ignored or rejected.

## Add and manage books

Open **More → Folders → Add a folder**, then choose a folder in Android's system
picker. Subfolders are scanned recursively to a depth of 12. Shelf persists the
permission and incrementally rescans: unchanged files keep their cached metadata
and only changed/new EPUBs are parsed again.

Android does not expose arbitrary UNC/SMB paths. A network share must be made
available by an Android document-provider app, or synced locally, before Shelf
can select it.

Tapping a book opens it at the saved location. Long-pressing opens details,
metadata, favourite/collection controls, progress reset, and permanent deletion.
Deletion is transactional from the user's perspective: Shelf keeps local book
data if the provider refuses to delete the source, and can request write access
to the exact original folder before retrying.

## Reader controls

| Gesture or control | Action |
|---|---|
| Tap the centre | Show or hide reader bars |
| Tap left/right edge | Page within the section; cross the boundary at the end |
| Swipe sideways | Confirm movement to the previous/next section |
| Scroll down | Hide reader bars |
| Progress slider | Move within the current section |
| Contents | Jump through the EPUB navigation document/NCX |
| Search | Search the text of every reading section |
| Bookmark | Save the current section and offset |
| Highlight | Save selected text with an optional note |
| Read aloud | Speak the current section and highlight the active passage |

Front matter such as cover, title, description, contents, copyright, and
colophon pages is classified separately and does not inflate reading progress.
If classification would otherwise leave an unusual EPUB empty, Shelf safely
falls back to its complete linear spine.

Publisher scripts, remote frames, event handlers, and CSS are removed from book
chapters. Images and other EPUB resources are served from a per-book local HTTPS
namespace. The reader WebView returns an empty response for every request that is
not a resource in the currently open book; external links open only after a tap.

## Backup, OPDS, and browser

**More → Back up reading data** exports settings, progress, bookmarks,
highlights, favourites, custom libraries, membership, and reading sessions as
JSON. Restore matches books by stable ID, filename/size, then normalized
title/author; source books and watched folders are never replaced.

**More → OPDS catalog** fetches only after **Load** is tapped. EPUB downloads go
through Android's save picker; save into a watched folder and rescan.

**More → Web browser** is intentionally networked and separate from the isolated
book reader. It disables file/content access, third-party cookies, mixed content,
automatic popups, and autoplay. Reader mode extracts the largest article-like
region locally and can speak it with Android text-to-speech. Because HTTP OPDS
catalogues and sites are supported, the application currently permits cleartext
traffic; HTTPS is strongly preferred.

## Build the project

Requirements:

- JDK 17
- Android SDK Platform 35 and normal Android build tools
- Android Studio Ladybug or newer, or the included Gradle wrapper

```powershell
.\gradlew.bat testDebugUnitTest assembleDebug
.\gradlew.bat installDebug
```

Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.

The release build uses `keystore.properties` when present. Both that file and
the keystore are deliberately ignored. A local file has this shape:

```properties
storeFile=release-key.jks
storePassword=replace-with-a-secret
keyAlias=shelf
keyPassword=replace-with-a-secret
```

```powershell
.\gradlew.bat clean testDebugUnitTest lintRelease assembleRelease
```

Preserve the 0.1 signing keystore and credentials offline. Losing the key makes
it impossible to publish an APK that Android accepts as an update to existing
installations. Never commit the key or `keystore.properties`.

## Release process

1. Increase `versionCode` and set `versionName` in `app/build.gradle.kts`.
2. Update the README's current release/download filename when the asset name
   changes.
3. Run unit tests, lint, and `assembleRelease` with the release keystore.
4. Verify the APK signature and SHA-256 digest.
5. Commit, tag `v<version>`, push, and create a non-draft, non-prerelease GitHub
   Release containing one signed, non-debug `.apk` asset.

The updater reads GitHub's `/releases/latest` API endpoint, so prereleases do not
replace the stable update channel.

## Architecture and data flow

```text
Android document tree
    → LibraryScanner → EpubOpener/ZipReader/EpubBook
    → Room (books + reading state) → ShelfRepository
    → LibraryViewModel / ReaderViewModel → Compose UI + isolated reader WebView

GitHub Releases API → UpdateViewModel → verified cache APK → Android installer
```

- `epub/` owns archive access, path resolution, package parsing, classification,
  navigation, chapter sanitization, and resource lookup.
- `data/` owns Room entities/queries, settings, backup, scanning, cover cache,
  deletion, and the parsed-book LRU cache.
- `ui/library/`, `ui/folders/`, `ui/reader/`, `ui/opds/`, and `ui/browser/`
  contain state holders and Compose screens.
- `update/` owns release discovery, version comparison, APK validation/download,
  permission handling, and installer hand-off.

Room is schema version 3. Migration 2→3 adds annotations and reading sessions.
DataStore holds reader/library preferences. Cover images are downsampled near
520 px and stored in private app files. Non-seekable EPUB providers use a bounded
private cache; parsed books use a four-entry access-ordered cache.

## Data model

| Type | Responsibility |
|---|---|
| `RootEntity` | Persisted document-tree folder and enabled state |
| `BookEntity` | Source identity, file stamp, metadata, chapter count, cover, parse status |
| `ProgressEntity` | Current reading section, fraction, label, and timestamp |
| `BookmarkEntity` | Saved section/fraction with title and note |
| `AnnotationEntity` | Highlight quote, optional note, and reading location |
| `ReadingSessionEntity` | Timed reader session used by statistics |
| `FavoriteEntity` | Pinned-book relation |
| `CustomLibraryEntity` | Named user collection |
| `LibraryBookJoinEntity` | Many-to-many custom-library membership |
| `BookCard` | Room projection combining book, progress, and favourite state |
| `ReaderSettings` | Persisted reader and library appearance/behaviour preferences |
| `EpubMeta`, `ManifestItem`, `EpubSection`, `TocEntry`, `Chapter` | Parsed EPUB package model |
| `AppRelease`, `UpdateState` | Latest-release metadata and updater UI state |

Kotlin data classes also have compiler-generated `copy`, component, equality,
hash, and string functions; the catalogue below documents every function and
custom computed property written in the project rather than those generated
methods.

## Function catalogue

### Application entry points

`ShelfApp.kt`

- `repository(context)` returns the process-wide lazily initialized
  `ShelfRepository` from the application object.

`MainActivity.kt`

- `Routes.reader(bookId)` builds a reader navigation route.
- `MainActivity.onCreate(savedInstanceState)` installs the splash screen,
  enables edge-to-edge drawing, applies the theme, and starts Compose.
- `ShelfNavigation()` owns the navigation graph for library, folders, reader,
  OPDS, and browser, then mounts the update coordinator above it.

### Data layer

`Entities.kt`

- `BookCard.percent` computes clamped whole-book completion from section plus
  fractional section progress.
- `BookCard.started` reports whether a positive last-read timestamp exists.

`ReaderSettings.kt`

- `ReaderFont.from(key)` safely resolves a persisted enum name.
- `ReaderSettings.align` maps paragraph justification to a CSS alignment value.
- `ReaderSettings.isDarkPage` classifies the selected page colour.
- `ReaderSettings.luminance(hex)` computes a simple normalized RGB luminance.
- `ReaderSettings.isDark(hex)` applies the reader's dark-colour threshold.
- `SettingsStore.current()` returns the first current settings value.
- `SettingsStore.replace(settings)` atomically replaces all persisted settings.
- `Preferences.toSettings()` maps DataStore keys, defaults, and legacy values to
  `ReaderSettings`.
- `SettingsStore.update(transform)` applies a settings transform in one DataStore
  edit, including nullable URI/voice removal.
- `migrateColumns(cols)` converts the legacy numeric grid column value to a
  `GridDensity` key.

`WordTransformer.kt`

- `transform(text)` parses an HTML fragment and replaces text-node content only,
  leaving markup and resource URLs intact.
- `transformPlain(text)` applies the same case-insensitive rules to plain text.
- `parseRules(input, out)` reads newline-separated replacement rules.
- `parseLine(line, out)` validates and appends one `old=new` rule.

`LibraryScanner.kt`

- `ScanStatus.fraction` returns completed/located scan progress.
- `coverFile(name)` resolves an existing private cached cover.
- `start(force)` starts one IO scan unless another is active.
- `cancel()` requests cancellation of the active scan.
- `cancelAndJoin()` stops and awaits the scan before a destructive operation.
- `scan(force)` walks enabled roots, parses changed books in bounded batches,
  upserts metadata, updates status, and removes vanished books/covers.
- `walk(root, treeUri, documentId, folderName, out, depth)` queries one document
  directory, records EPUBs, skips hidden/system-like folders, and recurses after
  closing its cursor.
- `readBook(id, item, stamp, existing)` parses metadata/cover into a `BookEntity`
  or records a non-fatal broken-book error.
- `writeCover(id, bytes)` decodes, downsamples, compresses, and caches a JPEG.
- `bookId(uri)` derives a stable 16-hex-character SHA-1 URI identifier.

`ShelfBackup.kt`

- `export(context, dao, settingsStore, uri)` snapshots portable state and settings
  as versioned JSON.
- `restore(context, dao, settingsStore, uri)` validates a backup, matches current
  books, remaps relations, transactionally replaces portable data, and restores
  settings.
- `bookToJson(book)`, `progressToJson(item)`, `bookmarkToJson(item)`, and
  `annotationToJson(item)` serialize their respective records.
- `settingsToJson(value)` serializes every reader/library preference.
- `settingsFromJson(json)` restores settings with per-field defaults.
- `JSONObject.array(name)` returns an array or an empty compatibility value.
- `JSONArray.objects()` extracts only object entries.
- `normalize(value)` creates a punctuation-insensitive book matching key.

`ShelfDatabase.kt` — Room implements the abstract query functions; transaction
functions are written in Kotlin.

- Roots: `observeRoots()` streams roots; `roots()` snapshots them; `addRoot()`
  upserts one; `deleteRoot()` removes one; `deleteBooksForRoot()` removes its
  books; `setRootEnabled()` toggles it; `removeRoot()` transactionally removes
  both root and books.
- Books: `observeCards()` streams the book/progress/favourite projection;
  `book()` and `observeBook()` read one; `allBookIds()`, `booksForRoot()`, and
  `bookCount()` query indexes/counts; `upsertBooks()`, `deleteBooks()`, and
  `deleteBook()` mutate book rows.
- Progress: `progress()`, `saveProgress()`, `remapProgressChapter()`, and
  `clearProgress()` read, save, migrate, and remove locations.
- Bookmarks: `observeBookmarks()`, `bookmarksForBook()`, `addBookmark()`,
  `deleteBookmark()`, `deleteBookmarksForBook()`, `remapBookmarkChapter()`,
  `allBookmarks()`, and `addBookmarks()` provide live, per-book, migration,
  backup, and bulk-insert operations.
- Annotations: `observeAnnotations()`, `annotationsForBook()`, `addAnnotation()`,
  `deleteAnnotation()`, `deleteAnnotationsForBook()`,
  `remapAnnotationChapter()`, `allAnnotations()`, and `addAnnotations()` mirror
  the bookmark operations for highlights/notes.
- Sessions: `observeReadingSessions()`, `addReadingSession()`,
  `addReadingSessions()`, `allReadingSessions()`, and
  `deleteReadingSessionsForBook()` maintain reading-time events.
- Favourites: `addFavorite()`, `removeFavorite()`, `isFavorite()`,
  `allFavorites()`, and `addFavorites()` maintain pinned-book state.
- Collections: `observeCustomLibraries()`, `insertCustomLibrary()`,
  `deleteCustomLibrary()`, `addBookToLibrary()`, `removeBookFromLibrary()`,
  `removeBookFromAllLibraries()`, `observeLibrariesForBook()`,
  `observeBookIdsInLibrary()`, `clearLibrary()`, `allCustomLibraries()`,
  `allLibraryJoins()`, and `addLibraryJoins()` implement named many-to-many
  shelves.
- Cleanup/backup: `deleteBookData()` transactionally removes all state owned by a
  deleted book; `allBooks()`, `allProgress()`, and `saveProgressItems()` support
  backup; `clearAllProgress()`, `clearAllBookmarks()`, `clearAllAnnotations()`,
  `clearAllFavorites()`, `clearAllLibraryJoins()`,
  `clearAllCustomLibraries()`, and `clearAllReadingSessions()` clear portable
  tables; `replacePortableData()` performs the complete restore transaction.
- Database lifecycle: `dao()` exposes the generated DAO; migration `migrate()`
  creates schema-3 annotation/session tables and indexes; `get(context)` returns
  the synchronized singleton database.

`ShelfRepository.kt`

- `removeEldestEntry(eldest)` closes and evicts parsed books after the LRU exceeds
  four entries.
- `observeCards()`, `observeRoots()`, `observeBookmarks()`,
  `observeAnnotations()`, `observeReadingSessions()`,
  `observeCustomLibraries()`, `observeLibrariesForBook()`, and
  `observeBookIdsInLibrary()` expose DAO flows.
- `bookEntity(id)` reads one scanned book.
- `syncBookMetadata(id, meta)` refreshes a row when parser classification changes.
- `openBook(id)` validates the document and returns a cached or newly parsed EPUB.
- `deleteBookFromSource(id)` stops scanning, validates the source, deletes it,
  then transactionally removes local state only after source deletion succeeds.
- `grantFolderWriteAccess(expectedTreeUri, selectedTreeUri)` accepts a persisted
  write grant only for the original tree.
- `hasPersistedWriteAccess(treeUri)` tests current persisted grants.
- `sameDocumentTree(first, second)` compares authority and tree document ID.
- `releaseBook(id)` and `releaseAll()` close cached archive handles.
- `documentExists(uri)` checks a SAF document without treating provider errors as
  proof of disappearance.
- `coverFile(name)` delegates cached-cover lookup.
- `exportBackup(uri)` and `restoreBackup(uri)` delegate portable backup work.
- `addRoot(treeUri)` persists read/write (or read-only) access, rejects duplicates,
  names the root, and starts scanning.
- `removeRoot(treeUri)` removes covers/data, releases the persisted read grant,
  and closes parsed books.
- `setRootEnabled(treeUri, enabled)` updates the flag and rescans.
- `rescan(force)` closes cached books and starts incremental/forced scanning.
- `progress(bookId)`, `remapProgressChapter(bookId, chapter)`,
  `remapSavedLocations(bookId, book)`, `saveProgress(...)`, and
  `clearProgress(bookId)` manage current and legacy-filtered locations.
- `addBookmark(...)`/`deleteBookmark(id)` and
  `addAnnotation(annotation)`/`deleteAnnotation(id)` mutate saved reader marks.
- `recordReadingSession(...)` stores a timed session.
- `toggleFavorite(bookId)` inserts or removes a favourite.
- `addCustomLibrary(name)`, `removeCustomLibrary(id)`,
  `addBookToLibrary(libraryId, bookId)`, and
  `removeBookFromLibrary(libraryId, bookId)` mutate custom shelves.

### EPUB layer

`EpubModels.kt`

- `EpubSection.countsTowardProgress` is true only for reading content.
- `EpubPaths.percentDecode(input)` decodes percent bytes without form-style `+`
  conversion.
- `EpubPaths.normalize(path)` collapses slash, dot, and parent segments.
- `EpubPaths.resolve(baseDir, href)` decodes and resolves archive-relative links.
- `EpubPaths.dirName(path)` returns an archive path's parent directory.

`ZipReader.kt`

- `open(channel, onClose)` constructs a reader and indexes its central directory.
- `names` exposes central-directory entry names in archive order.
- `readAt(position, length)` fills a little-endian buffer from an absolute offset.
- `readCentralDirectory()` locates EOCD/ZIP64 metadata and builds exact and
  case-insensitive entry indexes.
- `resolve(path)` tolerates case and backslash mistakes in EPUB paths.
- `has(path)` tests tolerant resolution.
- `read(path)` locates the local header and returns stored or deflated bytes.
- `readText(path)` decodes UTF-8 and removes an optional BOM.
- `inflate(dataOffset, compressedSize, expectedSize)` streams raw DEFLATE data
  with stall/truncation protection.
- `close()` closes the channel and provider descriptor/owner.

`EpubOpener.kt`

- `open(context, uri)` prefers a seekable provider descriptor, otherwise opens a
  private cached copy.
- `cacheCopy(context, uri)` reuses or creates a URI-keyed EPUB cache file.
- `trimCache(dir)` removes oldest copies until the 400 MB budget is met.
- `clearCache(context)` removes all fallback EPUB copies.

`EpubBook.kt`

- `guessMime(path)` maps known EPUB resource extensions to MIME types.
- `chapterCount` returns the filtered reading-spine size; `close()` closes the ZIP.
- `parseXml(bytes, what)` parses namespace-aware XML with external entities/DTDs
  disabled.
- `DomElement.childrenNamed()`, `DomElement.firstNamed()`, and
  `DomElement.descendantsNamed()` provide namespace-tolerant DOM traversal.
- `findOpf()` reads `container.xml` or falls back to the first OPF entry.
- `readMeta(root)` extracts/cleans Dublin Core metadata and Calibre/EPUB series;
  its local `dc(tag)` reads one DC field.
- `readManifest(root)` resolves manifest hrefs; `readSpine(root)` builds the linear
  reading order with an HTML fallback for damaged packages.
- `classifySection(...)` combines semantics, names, filenames, text, metadata, and
  link density to separate content from front/back matter.
- `semanticKind(tokens)` and `namedAuxiliaryKind(key)` map EPUB semantic/name
  hints to section roles.
- `semanticKey(value)` strips accents/punctuation for tolerant classification.
- `cleanMetadataTitle()`, `cleanMetadataDescription()`, and
  `cleanMetadataText()` normalize metadata and drop generator-placeholder text.
- `samePath()` compares archive paths case-insensitively.
- `findCover(root)` checks EPUB 3 properties, EPUB 2 metadata, then image names.
- `spineIndexOf(path)` resolves a content path to the filtered reading spine.
- `readNavigation(root)` prefers EPUB 3 nav and falls back to NCX.
- `readToc(navigation)` maps entries to readable sections or creates defaults.
- `tocFromNav()` parses nested HTML navigation; its local `walk(ol, depth)`
  flattens entries while retaining depth.
- `tocFromNcx(root)` parses EPUB 2 NCX; its local `walk(parent, depth)` recursively
  flattens nav points.
- `resource(path)` returns archive bytes with declared/guessed MIME type.
- `cover()` safely returns the selected cover resource when present.
- `chapter(index, resourceUrl)` sanitizes XHTML, removes publisher scripting/CSS,
  rewrites local media and internal links, derives a title, and counts words.
- `tocLabelFor(index)` finds the first label for a reading section.
- `readingIndexForPackageIndex(index)` migrates locations from an older complete
  package spine to the filtered reading spine.
- `hasScheme(value)` and `schemeOf(value)` safely classify link schemes.

### Library, folders, and shared UI

`LibraryViewModel.kt`

- `LibraryCategory.label` renders the active shelf name.
- `LibraryFilter.from()`, `LibrarySort.from()`, and `GridDensity.from()` safely
  decode persisted keys.
- `LibraryUiState.filter`, `sort`, and `density` expose decoded settings;
  `continueReading` selects the first active unfinished book.
- `computeState(base, cards)` applies title replacements, counts, category/filter,
  search, and sorting off the main thread.
- `bucket(card)` classifies unread/reading/finished state.
- `duplicateIds(cards)` groups normalized title/author or filename/size keys; its
  local `clean(value)` builds those keys.
- `readingStats(cards, sessions)` calculates totals, seven-day time, and streak.
- `comparator(sort)` builds locale-aware stable book ordering.
- `setQuery()`, `setCategory()`, `setFilter()`, `setSort()`, and `setDensity()`
  update live/persisted library controls.
- `updateSettings(transform)`, `toggleFavorite(bookId)`, `rescan(force)`,
  `coverPath(name)`, and `clearProgress(bookId)` delegate common actions.
- `deleteBookFromSource(bookId)`, `answerFolderWriteRequest(selectedTreeUri)`, and
  `performDelete(bookId)` drive the safe two-stage deletion flow.
- `exportBackup(uri)`, `restoreBackup(uri)`, and `consumeMessage()` run operations
  and manage snackbar text.
- `createLibrary(name)`, `removeLibrary(id)`, `addToLibrary(...)`,
  `removeFromLibrary(...)`, and `observeLibrariesForBook(bookId)` manage custom
  shelf membership.

`LibraryScreen.kt`

- `LibraryScreen(...)` coordinates launch scanning, pickers, drawer/menu state,
  book actions, confirmations, filters, and the active book layout.
- `LibraryDrawer(...)` renders all/duplicate/series/folder/custom shelves.
- `drawerItemColors()` centralizes drawer selection colours.
- `LibrarySelectionSheet(...)` toggles one book's custom-library membership.
- `CreateLibraryDialog(...)` validates and submits a new shelf name.
- `LibraryMenu(...)` renders sort, view, statistics, backup/restore, settings,
  browser, OPDS, folders, and rescan actions.
- `FilterRow(...)` displays reading-state counts and filter chips.
- `ScanBanner(state)` reports scan location/progress/errors.
- `BookGrid(...)` selects adaptive/fixed grid or list rendering.
- `BookTile(...)`, `TextOnlyTile(...)`, `BookRow(...)`, and
  `DetailedBookRow(...)` render the supported library card modes.
- `formatSize(bytes)` formats book sizes; `CoverImage(path, title)` loads a cached
  cover or initial placeholder.
- `CenteredBox(content)` centers full-screen status content.
- `EmptyState(...)` renders an empty-library/filter message and optional action.

`BookDetailSheet.kt`

- `BookDetailSheet(...)` renders metadata, tags, progress, favourite, collection,
  reset, read, and delete actions.
- `Tag(text)` renders a metadata pill; `formatSize(bytes)` formats source size.

`LibraryExtras.kt`

- `ReadingStatsSheet(stats, onDismiss)` lays out streak, time, started, and
  finished metrics.
- `StatCard(...)` renders one metric; `formatDuration(seconds)` formats tracked
  time.

`FoldersScreen.kt`

- `FoldersViewModel.addFolder(uri)`, `remove(treeUri)`,
  `setEnabled(treeUri, enabled)`, `rescan(force)`, and `consumeMessage()` delegate
  root actions and notifications.
- `FoldersScreen(...)` owns the tree picker, removal confirmation, scan status,
  and root list.
- `FolderRow(...)` renders root name/URI, enabled switch, and removal action.

`Components.kt`

- `ShelfButton(...)` renders the filled primary action.
- `ShelfGhostButton(...)` renders a bordered secondary/danger action.

`Theme.kt`

- `ShelfTheme(content)` applies the dark Shelf palette, typography, and shapes.

### Reader UI and state

`ReaderHtml.kt`

- `resourceUrl(bookId, version, path)` creates a per-book/version local HTTPS URL.
- `resourceRequest(url)` validates and decodes a local WebView resource request.
- `section(...)` wraps one transformed chapter with section navigation metadata.
- `page(...)` builds the complete styled reader document and interaction script.
- `frontPart(index, html)` wraps legacy merged front matter for fragment targets.
- `escape(text)` HTML-escapes generated labels.
- `applySettingsScript(...)` updates CSS variables/behaviour without reloading or
  losing scroll position.
- The embedded JavaScript functions `sections`, `topOf`, `current`, `span`,
  `fraction`, `checkMore`, `report`, `shelfAppend`, `prune`, `scrollToSection`,
  `shelfScrollTo`, `shelfScrollToId`, `shelfPage`, `shelfAtEnd`, `shelfAtStart`,
  `shelfMark`, `shelfHighlight`, `shelfHighlights`, `shelfAddHighlights`,
  `shelfSelection`, `shelfClearHighlight`, and `ready` handle section tracking,
  continuous append/pruning, gestures, navigation, progress, and highlights.

`ReaderViewModel.kt`

- `ReaderUiState.wholeBookProgress`, `canGoBack`, and `canGoForward` derive reader
  navigation/progress state.
- `load(id)` saves/releases the old book, opens and migrates the requested EPUB,
  synchronizes metadata, restores progress, and opens its section.
- `observeSettings()` keeps UI state synchronized with DataStore.
- `sectionStart(index)` returns the canonical filtered index.
- `nextSection(epub, index)` calculates the next valid section.
- `readSection(...)` loads/sanitizes a chapter and plain text on IO.
- `titleFor(content)` selects its label; `clearSections()` resets continuous-scroll
  caches/jobs.
- `openChapter(...)` validates, asynchronously loads, restores location/highlight,
  publishes page state, and saves progress.
- `requestSection(index)` deduplicates/fetches continuous-scroll appends.
- `onSection(index)` updates the active section as the stacked page scrolls.
- `next()` and `previous()` stop speech and move through sections.
- `searchBook(query)` searches up to 200 section matches with context snippets;
  `openSearchResult(result)` opens/highlights one.
- `onScroll(fraction)`, `onScrollDirection(scrollingDown)`, `toggleBars()`, and
  `setBars(visible)` process WebView UI feedback.
- `scheduleSave()` debounces progress; `saveProgressNow()` launches a final-safe
  application-scope save.
- `addBookmark()`, `deleteBookmark(id)`, `openBookmark(bookmark)`, and
  `consumeEvent()` manage bookmark UI actions.
- `addAnnotation(quote, note)`, `deleteAnnotation(id)`,
  `openAnnotation(annotation)`, and `exportAnnotations(uri)` validate, persist,
  open, remove, and export highlights/notes.
- `setReadingActive(active)` and `finishReadingSession()` record reader sessions
  of at least five seconds.
- `updateSettings(transform)` persists reader preferences.
- `toggleSpeaking()`, `startSpeaking()`, `speakNow(fromIndex)`,
  `seekTts(progress)`, `setTtsSpeed(speed)`, `setTtsVoice(voice)`,
  `stopSpeaking()`, and `chunk(text, limit)` own text-to-speech setup, queuing,
  seek, voice/rate persistence, shutdown, and engine-sized chunks.
- TTS callbacks `onStart`, `onDone`, and `onError` update progress/highlight and
  completion state.
- `onCleared()` saves, records time, shuts down TTS, and releases the EPUB.
- `resourceFor(requestedBookId, requestedVersion, path)` returns a resource only
  for the currently open exact book version.

`ReaderScreen.kt`

- `ReaderScreen(...)` connects lifecycle, WebView, JavaScript bridge, settings,
  continuous appends, highlights, sheets, bars, progress, and navigation.
- WebView `shouldInterceptRequest(...)` serves only current-book resources;
  `shouldOverrideUrlLoading(...)` blocks local navigation and hands explicit
  external links to Android.
- `pageForward(...)` and `pageBack(...)` page-scroll or cross section boundaries.
- `String.escapeJs()` escapes speech/search text for JavaScript.
- `blank()` creates the empty blocked-resource response.
- `ReaderBridge.onScroll`, `onScrollDirection`, `onTap`, `onSwipe`,
  `onInternalLink`, `onReady`, `onSection`, and `onNeedNext` expose the minimal
  JavaScript-to-Kotlin callback surface.
- `highlightPairs(...)` serializes visible saved highlights for the page script.
- `ReaderTopBar(...)` and `ReaderBottomBar(...)` render navigation, speech,
  bookmark/highlight, tools, and sliders.

`ReaderSheets.kt`

- `ContentsSheet(...)`, `BookmarksSheet(...)`, `SearchSheet(...)`,
  `AnnotationsSheet(...)`, and `VoicesSheet(...)` render the corresponding reader
  tools and actions.
- `AddAnnotationDialog(...)` captures an optional note for selected text.
- `SheetHeader(title, onClose)` provides a shared sheet heading.
- `EmptySheetNote(text)` provides shared empty-state content.

`ReaderSettingsSheet.kt`

- `ReaderSettingsSheet(...)` edits page/text colours, font, size, spacing, margin,
  paragraph/touch/fullscreen/continuous/tablet settings, library appearance, and
  internal/external replacement rules.
- `fileName(context, uriString)` resolves a document display name.
- `SettingRow(...)`, `SwatchRow(...)`, `Chip(...)`, `Stepper(...)`,
  `StepButton(...)`, and `ToggleRow(...)` are reusable settings controls.

### Network tools

`OpdsScreen.kt`

- `setUrl(value)` updates the catalogue address.
- `load()` validates and asynchronously fetches the current URL.
- `download(entry, destination)` streams an acquisition into a user-selected
  document and reports completion.
- `consumeMessage()` clears one-shot UI text.
- `fetch(address)` size-limits and securely parses Atom/OPDS XML into EPUB entries.
- `open(address, accept)` validates HTTP(S), applies timeouts/headers, connects,
  and rejects non-success responses.
- `isWebUrl(value)` requires HTTP(S) with a host.
- `Element.textOf(localName)` reads a namespace-agnostic child value.
- `OpdsScreen(...)` renders URL entry, status, catalogue list, and save picker.
- `safeFilename(title)` removes invalid filename characters and limits length.

`BrowserScreen.kt`

- `setArticle(value)` changes the extracted article and stops stale speech.
- `toggleSpeaking()`, `setSpeed(value)`, `seek(progress)`, `consumeMessage()`,
  `stopSpeaking()`, `startSpeaking()`, `speak(fromIndex)`, and
  `chunk(text, limit)` manage article text-to-speech.
- Speech callbacks `onStart`, `onDone`, and `onError` publish progress/completion.
- `onCleared()` stops and shuts down the article speech engine.
- `BrowserScreen(...)` owns browser state, WebView hardening/lifecycle, reader-mode
  extraction, back handling, and its local `navigate()` address/search action.
- Browser callbacks `onReceivedTitle`, `onPageStarted`, `onPageFinished`, and
  `shouldOverrideUrlLoading` synchronize UI, extract articles, and delegate
  non-web schemes.
- `BrowserScaffold(...)` renders address and browser controls around WebView.
- `ArticleReader(...)` renders extracted text plus speech seek/rate controls.
- `normalizeAddress(input)` converts domains/search terms into HTTPS URLs.
- `decodeArticle(raw, url)` validates the JavaScript extraction result and minimum
  useful article length.
- Embedded `ARTICLE_SCRIPT` selects the largest article-like node, removes page
  chrome/executable media, and returns bounded plain text and metadata.

### Updater

`AppUpdater.kt`

- `AppVersions.isNewer(candidate, current)` compares numeric version segments;
  private `parts(value)` normalizes `v` prefixes and stable suffixes.
- `UpdateViewModel.checkForUpdate(silent)` requests the latest stable release and
  publishes only a newer compatible version.
- `download(release)` downloads on IO and exposes progress/verified readiness.
- `dismiss()` cancels updater work; `installFailed(message)` and
  `installLaunched()` finalize UI state.
- `fetchLatestRelease()` validates repository/tag/assets and parses the preferred
  non-debug APK plus optional SHA-256 digest.
- `downloadApk(release, onProgress)` enforces trusted HTTPS hosts, size/APK magic,
  checksum, private cache cleanup, and atomic `.part` completion.
- `openGithubConnection(url, accept)` applies HTTPS, timeouts, GitHub headers, and
  success checks.
- `isGithubDownloadHost(host)` restricts redirect targets.
- `safeName(value)` sanitizes the private cached asset filename.
- `AppUpdateCoordinator(...)` runs the launch-time UI, unknown-source permission,
  `FileProvider`, and system installer flow; its local `launchInstaller(ready)`
  grants one-file read access and starts Android's installer.
- `formatBytes(bytes)` formats release asset size.

### Tests

`EpubBookTest.kt`

- `calibreExportCountsOnlyTheRealChapter()` verifies metadata cleanup, auxiliary
  classification, filtered progress, navigation, and legacy remapping.
- `aShortUnlabelledDocumentIsNotDiscarded()` protects short genuine chapters from
  over-aggressive classification.
- `withBook(entries, block)` builds a temporary EPUB fixture and guarantees
  cleanup; `sampleEntries()` provides a Calibre-like EPUB 2 package.

`AppVersionsTest.kt`

- `newerVersionsAreDetectedAcrossSegmentLengths()` covers prefixes and uneven
  numeric segments.
- `equalOlderAndInvalidVersionsAreRejected()` covers equality, downgrade, and
  malformed tags.

## Formats and limitations

- EPUB 2/3 without DRM is supported. `.mobi`, `.azw3`, `.pdf`, and DRM removal are
  out of scope.
- Publisher CSS is intentionally removed so reader settings consistently win;
  structural HTML such as headings, lists, tables, images, and quotes remains.
- Text search returns the first match per section and caps the result list at 200.
- Word replacements are literal, case-insensitive `old=new` rules, not regexes.
- The release updater is for the GitHub-distributed APK. Store-distributed builds
  should use their store's update mechanism and policy requirements.
- No open-source licence has been granted by this repository unless a licence file
  is added later.

## Verification performed for 0.1

- `testDebugUnitTest` — EPUB classification and updater version tests.
- `assembleDebug` — full debug compilation, resources, manifest, Room/KSP, DEX,
  and APK packaging.
- Release verification is completed from the signed release artifact before it is
  attached to GitHub.
