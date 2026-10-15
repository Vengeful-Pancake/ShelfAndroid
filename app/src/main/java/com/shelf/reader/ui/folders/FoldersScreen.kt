package com.shelf.reader.ui.folders

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.ShelfApp
import com.shelf.reader.data.RootEntity
import com.shelf.reader.data.ScanStatus
import com.shelf.reader.ui.ShelfButton
import com.shelf.reader.ui.theme.ShelfColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FoldersViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ShelfApp.repository(app)

    val roots: StateFlow<List<RootEntity>> =
        repository.observeRoots().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scan: StateFlow<ScanStatus> = repository.scanner.status

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun addFolder(uri: Uri) = viewModelScope.launch {
        _message.value = repository.addRoot(uri) ?: "Folder added — scanning now"
    }

    fun remove(treeUri: String) = viewModelScope.launch { repository.removeRoot(treeUri) }

    fun setEnabled(treeUri: String, enabled: Boolean) = viewModelScope.launch {
        repository.setRootEnabled(treeUri, enabled)
    }

    fun rescan(force: Boolean) = repository.rescan(force)

    fun consumeMessage() { _message.value = null }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    onBack: () -> Unit,
    viewModel: FoldersViewModel = viewModel(),
) {
    val roots by viewModel.roots.collectAsStateWithLifecycle()
    val scan by viewModel.scan.collectAsStateWithLifecycle()
    val message by viewModel.message.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var confirmRemove by remember { mutableStateOf<RootEntity?>(null) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) viewModel.addFolder(uri) }

    LaunchedEffect(message) {
        message?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ShelfColors.Panel,
                    titleContentColor = ShelfColors.Text,
                ),
                title = { Text("Folders") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.rescan(true) }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = "Rescan everything")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {

            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                ShelfButton(
                    text = "Add a folder",
                    onClick = { picker.launch(null) },
                    icon = Icons.Outlined.CreateNewFolder,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    "Pick any folder holding .epub files — internal storage, an SD card, or a " +
                        "network share your file manager can reach. Sub-folders are included. " +
                        "Android remembers the permission, so Shelf can read it again after a restart " +
                        "and delete a book when you explicitly confirm that action.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShelfColors.Muted,
                )
            }

            if (scan.running) {
                Text(
                    "Reading ${scan.done} of ${scan.found} — ${scan.current}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShelfColors.Rose,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            if (roots.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "No folders yet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShelfColors.Muted,
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 16.dp, end = 16.dp, bottom = 32.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(roots, key = { it.treeUri }) { root ->
                        FolderRow(
                            root = root,
                            onToggle = { viewModel.setEnabled(root.treeUri, it) },
                            onRemove = { confirmRemove = root },
                        )
                    }
                }
            }
        }
    }

    confirmRemove?.let { root ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            containerColor = ShelfColors.Panel,
            title = { Text("Remove ${root.name}?") },
            text = {
                Text(
                    "Shelf forgets these books and their reading positions. The files themselves stay where they are.",
                    color = ShelfColors.Muted,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(root.treeUri)
                    confirmRemove = null
                }) { Text("Remove", color = ShelfColors.Danger) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Keep") }
            },
        )
    }
}

@Composable
private fun FolderRow(
    root: RootEntity,
    onToggle: (Boolean) -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(ShelfColors.Panel)
            .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                root.name,
                style = MaterialTheme.typography.bodyLarge,
                color = if (root.enabled) ShelfColors.Text else ShelfColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                Uri.decode(root.treeUri.substringAfterLast("/")),
                style = MaterialTheme.typography.bodySmall,
                color = ShelfColors.Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Switch(
            checked = root.enabled,
            onCheckedChange = onToggle,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ShelfColors.OnAccent,
                checkedTrackColor = ShelfColors.Rose,
                uncheckedTrackColor = ShelfColors.Raised,
                uncheckedBorderColor = ShelfColors.Hair,
            ),
        )
        Spacer(Modifier.width(4.dp))
        IconButton(onClick = onRemove) {
            Icon(Icons.Outlined.Delete, contentDescription = "Remove folder", tint = ShelfColors.Muted)
        }
    }
}
