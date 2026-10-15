package com.shelf.reader.ui.library

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.CollectionsBookmark
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import com.shelf.reader.data.BookCard
import com.shelf.reader.data.CustomLibraryEntity
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.ui.ShelfButton
import com.shelf.reader.ui.theme.ShelfColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onOpenBook: (String) -> Unit,
    onManageFolders: () -> Unit,
    onOpenOpds: () -> Unit,
    onOpenBrowser: () -> Unit,
    viewModel: LibraryViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val folderWriteRequest by viewModel.folderWriteRequest.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var searching by remember { mutableStateOf(false) }
    var detailBook by remember { mutableStateOf<BookCard?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var showStats by remember { mutableStateOf(false) }
    var confirmRestore by remember { mutableStateOf(false) }
    var confirmDeleteBook by remember { mutableStateOf<BookCard?>(null) }
    var showCreateLibrary by remember { mutableStateOf(false) }
    var manageLibrariesBook by remember { mutableStateOf<BookCard?>(null) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val exportBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri -> if (uri != null) viewModel.exportBackup(uri) }
    val importBackup = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) viewModel.restoreBackup(uri) }
    val folderWritePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> viewModel.answerFolderWriteRequest(uri) }

    LaunchedEffect(searching) {
        if (searching) runCatching { focusRequester.requestFocus() }
    }
    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            LibraryDrawer(
                state = state,
                onSelect = {
                    viewModel.setCategory(it)
                    scope.launch { drawerState.close() }
                },
                onCreateLibrary = { showCreateLibrary = true }
            )
        }
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = ShelfColors.Panel,
                        titleContentColor = ShelfColors.Text,
                    ),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.AutoMirrored.Outlined.Toc, contentDescription = "Menu")
                        }
                    },
                    title = {
                        if (searching) {
                            TextField(
                                value = query,
                                onValueChange = viewModel::setQuery,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .focusRequester(focusRequester),
                                placeholder = { Text("Search title, author or folder") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Transparent,
                                    unfocusedContainerColor = Color.Transparent,
                                    focusedIndicatorColor = ShelfColors.Rose,
                                    unfocusedIndicatorColor = ShelfColors.Hair,
                                ),
                            )
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(state.activeCategory.label, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "${state.counts[LibraryFilter.ALL] ?: 0} books",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ShelfColors.Muted,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            if (searching) viewModel.setQuery("")
                            searching = !searching
                        }) {
                            Icon(
                                if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                contentDescription = if (searching) "Close search" else "Search",
                            )
                        }
                        LibraryMenu(
                            state = state,
                            onSort = viewModel::setSort,
                            onDensity = viewModel::setDensity,
                            onRescan = { viewModel.rescan(false) },
                            onFolders = onManageFolders,
                            onSettings = { showSettings = true },
                            onStats = { showStats = true },
                            onBackup = { exportBackup.launch("shelf-backup.json") },
                            onRestore = { confirmRestore = true },
                            onOpds = onOpenOpds,
                            onBrowser = onOpenBrowser,
                        )
                    },
                )
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {

                FilterRow(
                    selected = state.filter,
                    counts = state.counts,
                    onSelect = viewModel::setFilter,
                )

                AnimatedVisibility(
                    visible = (state.scan.running || state.scan.error.isNotEmpty()) && !state.loading,
                    enter = expandVertically(),
                    exit = shrinkVertically(),
                ) {
                    ScanBanner(state)
                }

                PullToRefreshBox(
                    isRefreshing = state.scan.running,
                    onRefresh = { viewModel.rescan(false) },
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        state.loading -> CenteredBox { CircularProgressIndicator(color = ShelfColors.Rose) }

                        state.books.isEmpty() && state.roots.isEmpty() -> EmptyState(
                            heading = "Your shelf is empty",
                            body = "Add a folder of .epub files to get started. Sub-folders come along too.",
                            actionLabel = "Choose a folder",
                            onAction = onManageFolders,
                        )

                        state.books.isEmpty() && query.isNotBlank() -> EmptyState(
                            heading = "Nothing matches",
                            body = "Try a different search, or switch the filter back to All.",
                        )

                        state.books.isEmpty() -> EmptyState(
                            heading = "No books here yet",
                            body = "Shelf found no .epub files in this view. Rescan or change view.",
                            actionLabel = "Rescan folders",
                            onAction = { viewModel.rescan(true) },
                        )

                        else -> BookGrid(
                            books = state.books,
                            density = state.density,
                            settings = state.settings,
                            coverPath = viewModel::coverPath,
                            onOpen = onOpenBook,
                            onDetails = { detailBook = it },
                            onFavorite = viewModel::toggleFavorite,
                        )
                    }
                }
            }
        }
    }

    detailBook?.let { book ->
        BookDetailSheet(
            book = book,
            coverPath = viewModel.coverPath(book.coverFile),
            showCover = !state.settings.hideThumbnails,
            onDismiss = { detailBook = null },
            onRead = {
                detailBook = null
                onOpenBook(book.id)
            },
            onFavorite = { viewModel.toggleFavorite(book.id) },
            onResetProgress = {
                viewModel.clearProgress(book.id)
                detailBook = null
            },
            onManageLibraries = {
                manageLibrariesBook = book
                detailBook = null
            },
            onDelete = {
                detailBook = null
                confirmDeleteBook = book
            },
        )
    }

    confirmDeleteBook?.let { book ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDeleteBook = null },
            containerColor = ShelfColors.Panel,
            title = { Text("Delete “${book.title}”?", color = ShelfColors.Text) },
            text = {
                Column {
                    Text(
                        "This permanently deletes ${book.filename} from ${book.folder.ifBlank { book.source }}.",
                        color = ShelfColors.Text,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Reading progress, bookmarks, highlights, statistics, and collection membership for this book will also be removed. This cannot be undone.",
                        color = ShelfColors.Muted,
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDeleteBook = null
                    viewModel.deleteBookFromSource(book.id)
                }) { Text("Delete EPUB", color = ShelfColors.Danger) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDeleteBook = null }) {
                    Text("Cancel", color = ShelfColors.Muted)
                }
            },
        )
    }

    folderWriteRequest?.let { request ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.answerFolderWriteRequest(null) },
            containerColor = ShelfColors.Panel,
            title = { Text("Allow folder changes?", color = ShelfColors.Text) },
            text = {
                Text(
                    "Android currently lets Shelf read “${request.sourceName}” but not delete from it. " +
                        "Continue, select that same source folder, then tap Use this folder. " +
                        "Shelf will retry the pending deletion automatically.",
                    color = ShelfColors.Muted,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    folderWritePicker.launch(Uri.parse(request.treeUri))
                }) { Text("Choose folder", color = ShelfColors.Rose) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    viewModel.answerFolderWriteRequest(null)
                }) { Text("Cancel", color = ShelfColors.Muted) }
            },
        )
    }

    manageLibrariesBook?.let { book ->
        LibrarySelectionSheet(
            book = book,
            customLibraries = state.customLibraries,
            onDismiss = { manageLibrariesBook = null },
            onToggle = { libId, member ->
                if (member) viewModel.addToLibrary(libId, book.id)
                else viewModel.removeFromLibrary(libId, book.id)
            },
            observeMembership = { viewModel.observeLibrariesForBook(book.id) }
        )
    }

    if (showSettings) {
        com.shelf.reader.ui.reader.ReaderSettingsSheet(
            settings = state.settings,
            onDismiss = { showSettings = false },
            onChange = viewModel::updateSettings,
        )
    }
    if (showStats) {
        ReadingStatsSheet(stats = state.stats, onDismiss = { showStats = false })
    }
    if (confirmRestore) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmRestore = false },
            containerColor = ShelfColors.Panel,
            title = { Text("Restore Shelf backup?", color = ShelfColors.Text) },
            text = {
                Text(
                    "This replaces current progress, bookmarks, highlights, favourites, custom libraries, and reading statistics. Books and folders are not changed.",
                    color = ShelfColors.Muted,
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmRestore = false
                    importBackup.launch(arrayOf("application/json", "text/json", "text/plain"))
                }) { Text("Choose backup", color = ShelfColors.Rose) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmRestore = false }) {
                    Text("Cancel", color = ShelfColors.Muted)
                }
            },
        )
    }
}

@Composable
private fun LibraryDrawer(
    state: LibraryUiState,
    onSelect: (LibraryCategory) -> Unit,
    onCreateLibrary: () -> Unit,
) {
    ModalDrawerSheet(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        drawerContainerColor = ShelfColors.Panel,
    ) {
        Spacer(Modifier.height(12.dp))
        Text(
            "Shelf",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 16.dp),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = ShelfColors.Text
        )

        NavigationDrawerItem(
            label = { Text("All Books") },
            selected = state.activeCategory == LibraryCategory.All,
            onClick = { onSelect(LibraryCategory.All) },
            icon = { Icon(Icons.Outlined.GridView, null) },
            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
            colors = drawerItemColors()
        )

        if (state.duplicateCount > 0) {
            NavigationDrawerItem(
                label = { Text("Duplicates (${state.duplicateCount})") },
                selected = state.activeCategory == LibraryCategory.Duplicates,
                onClick = { onSelect(LibraryCategory.Duplicates) },
                icon = { Icon(Icons.Outlined.ContentCopy, null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = drawerItemColors(),
            )
        }

        if (state.seriesNames.isNotEmpty()) {
            Text(
                "Series",
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelSmall,
                color = ShelfColors.Muted,
            )
            state.seriesNames.forEach { series ->
                NavigationDrawerItem(
                    label = { Text(series, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = (state.activeCategory as? LibraryCategory.Series)?.name == series,
                    onClick = { onSelect(LibraryCategory.Series(series)) },
                    icon = { Icon(Icons.Outlined.CollectionsBookmark, null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                    colors = drawerItemColors(),
                )
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp), thickness = 0.5.dp, color = ShelfColors.Hair)

        Text(
            "Folders",
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelSmall,
            color = ShelfColors.Muted
        )

        state.roots.forEach { root ->
            NavigationDrawerItem(
                label = { Text(root.name) },
                selected = (state.activeCategory as? LibraryCategory.Folder)?.uri == root.treeUri,
                onClick = { onSelect(LibraryCategory.Folder(root.treeUri, root.name)) },
                icon = { Icon(Icons.Outlined.FolderOpen, null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = drawerItemColors()
            )
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp, horizontal = 28.dp), thickness = 0.5.dp, color = ShelfColors.Hair)

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Custom Libraries",
                style = MaterialTheme.typography.labelSmall,
                color = ShelfColors.Muted
            )
            IconButton(onClick = onCreateLibrary, modifier = Modifier.size(24.dp)) {
                Icon(Icons.Outlined.Add, null, tint = ShelfColors.Rose, modifier = Modifier.size(16.dp))
            }
        }

        state.customLibraries.forEach { lib ->
            NavigationDrawerItem(
                label = { Text(lib.name) },
                selected = (state.activeCategory as? LibraryCategory.Custom)?.id == lib.id,
                onClick = { onSelect(LibraryCategory.Custom(lib.id, lib.name)) },
                icon = { Icon(Icons.AutoMirrored.Outlined.Label, null) },
                modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
                colors = drawerItemColors()
            )
        }
    }
}

@Composable
private fun drawerItemColors() = NavigationDrawerItemDefaults.colors(
    selectedContainerColor = ShelfColors.Rose.copy(alpha = 0.12f),
    selectedIconColor = ShelfColors.Rose,
    selectedTextColor = ShelfColors.Rose,
    unselectedContainerColor = Color.Transparent,
    unselectedIconColor = ShelfColors.Muted,
    unselectedTextColor = ShelfColors.Text
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibrarySelectionSheet(
    book: BookCard,
    customLibraries: List<CustomLibraryEntity>,
    onDismiss: () -> Unit,
    onToggle: (Long, Boolean) -> Unit,
    observeMembership: () -> kotlinx.coroutines.flow.Flow<List<Long>>
) {
    val sheetState = rememberModalBottomSheetState()
    val membership by observeMembership().collectAsStateWithLifecycle(emptyList())

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        Column(
            Modifier
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 32.dp)
        ) {
            Text(
                "Add to library",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                book.title,
                style = MaterialTheme.typography.bodySmall,
                color = ShelfColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(Modifier.height(20.dp))

            if (customLibraries.isEmpty()) {
                Text(
                    "You haven't created any custom libraries yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShelfColors.Muted,
                    modifier = Modifier.padding(vertical = 20.dp).fillMaxWidth(),
                    textAlign = TextAlign.Center
                )
            } else {
                customLibraries.forEach { lib ->
                    val isMember = lib.id in membership
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onToggle(lib.id, !isMember) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(lib.name, style = MaterialTheme.typography.bodyLarge)
                        androidx.compose.material3.Checkbox(
                            checked = isMember,
                            onCheckedChange = { onToggle(lib.id, it) },
                            colors = androidx.compose.material3.CheckboxDefaults.colors(
                                checkedColor = ShelfColors.Rose,
                                uncheckedColor = ShelfColors.Hair
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreateLibraryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ShelfColors.Panel,
        title = { Text("New Library", color = ShelfColors.Text) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Library name") },
                singleLine = true,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = ShelfColors.Raised,
                    unfocusedContainerColor = ShelfColors.Raised,
                    focusedIndicatorColor = ShelfColors.Rose,
                    unfocusedIndicatorColor = ShelfColors.Hair,
                )
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
                enabled = name.isNotBlank()
            ) { Text("Create", color = ShelfColors.Rose) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("Cancel", color = ShelfColors.Muted)
            }
        }
    )
}

// ------------------------------------------------------------------ app bar

@Composable
private fun LibraryMenu(
    state: LibraryUiState,
    onSort: (LibrarySort) -> Unit,
    onDensity: (GridDensity) -> Unit,
    onRescan: () -> Unit,
    onFolders: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onBackup: () -> Unit,
    onRestore: () -> Unit,
    onOpds: () -> Unit,
    onBrowser: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var sortOpen by remember { mutableStateOf(false) }
    var densityOpen by remember { mutableStateOf(false) }

    IconButton(onClick = { open = true }) {
        Icon(Icons.Outlined.MoreVert, contentDescription = "More")
    }

    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(
            text = { Text("Sort: ${state.sort.label}") },
            leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Sort, null) },
            onClick = { open = false; sortOpen = true },
        )
        DropdownMenuItem(
            text = { Text("View: ${state.density.label}") },
            leadingIcon = { Icon(Icons.Outlined.GridView, null) },
            onClick = { open = false; densityOpen = true },
        )
        DropdownMenuItem(
            text = { Text("Reading statistics") },
            leadingIcon = { Icon(Icons.Outlined.BarChart, null) },
            onClick = { open = false; onStats() },
        )
        DropdownMenuItem(
            text = { Text("Back up reading data") },
            leadingIcon = { Icon(Icons.Outlined.Upload, null) },
            onClick = { open = false; onBackup() },
        )
        DropdownMenuItem(
            text = { Text("Restore reading data") },
            leadingIcon = { Icon(Icons.Outlined.Download, null) },
            onClick = { open = false; onRestore() },
        )
        DropdownMenuItem(
            text = { Text("Library Settings") },
            leadingIcon = { Icon(Icons.Outlined.FormatSize, null) },
            onClick = { open = false; onSettings() },
        )
        DropdownMenuItem(
            text = { Text("Web browser") },
            leadingIcon = { Icon(Icons.Outlined.Language, null) },
            onClick = { open = false; onBrowser() },
        )
        DropdownMenuItem(
            text = { Text("OPDS catalog") },
            leadingIcon = { Icon(Icons.Outlined.Public, null) },
            onClick = { open = false; onOpds() },
        )
        DropdownMenuItem(
            text = { Text("Folders") },
            leadingIcon = { Icon(Icons.Outlined.FolderOpen, null) },
            onClick = { open = false; onFolders() },
        )
        DropdownMenuItem(
            text = { Text("Rescan folders") },
            leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
            onClick = { open = false; onRescan() },
        )
    }

    DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
        LibrarySort.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = { sortOpen = false; onSort(option) },
                trailingIcon = {
                    if (option == state.sort) Icon(Icons.Filled.Star, null, tint = ShelfColors.Rose)
                },
            )
        }
    }

    DropdownMenu(expanded = densityOpen, onDismissRequest = { densityOpen = false }) {
        GridDensity.entries.forEach { option ->
            DropdownMenuItem(
                text = { Text(option.label) },
                onClick = { densityOpen = false; onDensity(option) },
                trailingIcon = {
                    if (option == state.density) Icon(Icons.Filled.Star, null, tint = ShelfColors.Rose)
                },
            )
        }
    }
}

@Composable
private fun FilterRow(
    selected: LibraryFilter,
    counts: Map<LibraryFilter, Int>,
    onSelect: (LibraryFilter) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LibraryFilter.entries.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(option) },
                label = {
                    Text(
                        "${option.label} ${counts[option] ?: 0}",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    containerColor = ShelfColors.Raised,
                    labelColor = ShelfColors.Muted,
                    selectedContainerColor = ShelfColors.Rose,
                    selectedLabelColor = ShelfColors.OnAccent,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = option == selected,
                    borderColor = ShelfColors.Hair,
                    selectedBorderColor = ShelfColors.Rose,
                ),
            )
        }
    }
}

@Composable
private fun ScanBanner(state: LibraryUiState) {
    val scan = state.scan
    Column(
        Modifier
            .fillMaxWidth()
            .background(ShelfColors.Panel)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            when {
                scan.error.isNotEmpty() -> "Scan problem: ${scan.error}"
                scan.found > 0 -> "Reading ${scan.done} of ${scan.found} — ${scan.current}"
                else -> "Looking for books…"
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (scan.error.isNotEmpty()) ShelfColors.Danger else ShelfColors.Muted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (scan.running) {
            Spacer(Modifier.height(6.dp))
            if (scan.found > 0) {
                LinearProgressIndicator(
                    progress = { scan.fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ShelfColors.Rose,
                    trackColor = ShelfColors.Hair,
                )
            } else {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ShelfColors.Rose,
                    trackColor = ShelfColors.Hair,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- grid

@Composable
private fun BookGrid(
    books: List<BookCard>,
    density: GridDensity,
    settings: ReaderSettings,
    coverPath: (String?) -> String?,
    onOpen: (String) -> Unit,
    onDetails: (BookCard) -> Unit,
    onFavorite: (String) -> Unit,
) {
    val cells = when (density) {
        GridDensity.ADAPTIVE -> GridCells.Adaptive(minSize = 116.dp)
        GridDensity.LIST, GridDensity.DETAILED -> GridCells.Fixed(1)
        else -> GridCells.Fixed(density.columns)
    }

    LazyVerticalGrid(
        columns = cells,
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 32.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(if (density == GridDensity.LIST || density == GridDensity.DETAILED) 8.dp else 18.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(books, key = { it.id }) { book ->
            when (density) {
                GridDensity.LIST -> BookRow(book, settings, coverPath(book.coverFile), onOpen, onDetails, onFavorite)
                GridDensity.DETAILED -> DetailedBookRow(book, settings, coverPath(book.coverFile), onOpen, onDetails, onFavorite)
                else -> BookTile(book, settings, coverPath(book.coverFile), onOpen, onDetails, onFavorite)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    book: BookCard,
    settings: ReaderSettings,
    cover: String?,
    onOpen: (String) -> Unit,
    onDetails: (BookCard) -> Unit,
    onFavorite: (String) -> Unit,
) {
    if (settings.hideThumbnails) {
        TextOnlyTile(book, settings, onOpen, onDetails, onFavorite)
        return
    }
    Column(
        Modifier.combinedClickable(
            onClick = { onOpen(book.id) },
            onLongClick = { onDetails(book) },
        )
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.66f)
                .clip(RoundedCornerShape(8.dp))
                .background(ShelfColors.Raised)
                .border(1.dp, ShelfColors.Hair, RoundedCornerShape(8.dp)),
        ) {
            CoverImage(cover, book.title)

            IconButton(
                onClick = { onFavorite(book.id) },
                modifier = Modifier.align(Alignment.TopEnd).size(34.dp),
            ) {
                Icon(
                    if (book.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (book.favorite) "Remove favourite" else "Mark favourite",
                    tint = if (book.favorite) ShelfColors.Amber else Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(18.dp),
                )
            }

            if (book.percent > 0) {
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.Black.copy(alpha = 0.35f))
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(book.percent / 100f)
                            .height(3.dp)
                            .background(ShelfColors.Rose)
                    )
                }
            }

            if (book.broken) {
                Text(
                    "Won't open",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShelfColors.OnAccent,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ShelfColors.Danger)
                        .padding(horizontal = 5.dp, vertical = 2.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = TextStyle(
                fontSize = settings.titleFontSize.sp,
                fontWeight = FontWeight.Medium,
                lineHeight = (settings.titleFontSize * 1.3).sp
            ),
            color = ShelfColors.Text,
            maxLines = settings.titleMaxLines,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            book.author.ifBlank { book.folder.ifBlank { book.source } },
            style = MaterialTheme.typography.bodySmall,
            color = ShelfColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Grid card used when thumbnails are hidden: title, author, progress, no cover. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TextOnlyTile(
    book: BookCard,
    settings: ReaderSettings,
    onOpen: (String) -> Unit,
    onDetails: (BookCard) -> Unit,
    onFavorite: (String) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(ShelfColors.Panel)
            .border(1.dp, ShelfColors.Hair, RoundedCornerShape(8.dp))
            .combinedClickable(
                onClick = { onOpen(book.id) },
                onLongClick = { onDetails(book) },
            )
            .padding(start = 10.dp, top = 10.dp, bottom = 10.dp, end = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                book.title,
                style = TextStyle(
                    fontSize = settings.titleFontSize.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = (settings.titleFontSize * 1.3).sp
                ),
                color = ShelfColors.Text,
                maxLines = settings.titleMaxLines,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(
                onClick = { onFavorite(book.id) },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    if (book.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = if (book.favorite) "Remove favourite" else "Mark favourite",
                    tint = if (book.favorite) ShelfColors.Amber else ShelfColors.Muted,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Text(
            book.author.ifBlank { book.folder.ifBlank { book.source } },
            style = MaterialTheme.typography.bodySmall,
            color = ShelfColors.Muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(end = 8.dp),
        )
        if (book.broken) {
            Spacer(Modifier.height(4.dp))
            Text("Won't open", style = MaterialTheme.typography.labelSmall, color = ShelfColors.Danger)
        }
        if (book.percent > 0) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { book.percent / 100f },
                modifier = Modifier.fillMaxWidth().padding(end = 8.dp).height(2.dp),
                color = ShelfColors.Rose,
                trackColor = ShelfColors.Hair,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    book: BookCard,
    settings: ReaderSettings,
    cover: String?,
    onOpen: (String) -> Unit,
    onDetails: (BookCard) -> Unit,
    onFavorite: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ShelfColors.Panel)
            .combinedClickable(
                onClick = { onOpen(book.id) },
                onLongClick = { onDetails(book) },
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!settings.hideThumbnails) {
            Box(
                Modifier
                    .width(46.dp)
                    .height(66.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(ShelfColors.Raised),
            ) { CoverImage(cover, book.title) }

            Spacer(Modifier.width(12.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = TextStyle(
                    fontSize = settings.titleFontSize.sp,
                    fontWeight = FontWeight.SemiBold
                ),
                color = ShelfColors.Text,
                maxLines = settings.titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author.ifBlank { book.folder },
                style = MaterialTheme.typography.bodySmall,
                color = ShelfColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.percent > 0) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "${book.percent}% read",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShelfColors.Rose,
                )
            }
        }

        IconButton(onClick = { onFavorite(book.id) }) {
            Icon(
                if (book.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = if (book.favorite) ShelfColors.Amber else ShelfColors.Muted,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DetailedBookRow(
    book: BookCard,
    settings: ReaderSettings,
    cover: String?,
    onOpen: (String) -> Unit,
    onDetails: (BookCard) -> Unit,
    onFavorite: (String) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ShelfColors.Panel)
            .combinedClickable(
                onClick = { onOpen(book.id) },
                onLongClick = { onDetails(book) },
            )
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!settings.hideThumbnails) {
            Box(
                Modifier
                    .width(60.dp)
                    .height(86.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(ShelfColors.Raised),
            ) { CoverImage(cover, book.title) }

            Spacer(Modifier.width(14.dp))
        }

        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = TextStyle(
                    fontSize = settings.titleFontSize.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = ShelfColors.Text,
                maxLines = settings.titleMaxLines,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author.ifBlank { "Unknown author" },
                style = MaterialTheme.typography.bodyMedium,
                color = ShelfColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (book.series.isNotBlank()) {
                    Text(
                        book.series,
                        style = MaterialTheme.typography.labelSmall,
                        color = ShelfColors.Rose,
                        maxLines = 1,
                    )
                }
                Text(
                    "${book.chapters} chapters · ${formatSize(book.size)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShelfColors.Muted,
                )
            }

            if (book.percent > 0) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { book.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(2.dp),
                    color = ShelfColors.Rose,
                    trackColor = ShelfColors.Hair,
                )
            }
        }

        IconButton(onClick = { onFavorite(book.id) }) {
            Icon(
                if (book.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                contentDescription = null,
                tint = if (book.favorite) ShelfColors.Amber else ShelfColors.Muted,
            )
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}

@Composable
internal fun CoverImage(path: String?, title: String) {
    if (path != null) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(java.io.File(path))
                .crossfade(140)
                .build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(Modifier.fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
            Text(
                title,
                style = MaterialTheme.typography.bodySmall,
                color = ShelfColors.Muted,
                textAlign = TextAlign.Center,
                maxLines = 5,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// ------------------------------------------------------------------- states

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
}

@Composable
private fun EmptyState(
    heading: String,
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(heading, style = MaterialTheme.typography.titleMedium, color = ShelfColors.Text)
            Spacer(Modifier.height(8.dp))
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = ShelfColors.Muted,
                textAlign = TextAlign.Center,
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                ShelfButton(text = actionLabel, onClick = onAction)
            }
        }
    }
}
