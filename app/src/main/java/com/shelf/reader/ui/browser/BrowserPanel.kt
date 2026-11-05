package com.shelf.reader.ui.browser

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shelf.reader.data.ReaderSettings
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserPanel(kind: String, data: BrowserData, settings: ReaderSettings, onDismiss: () -> Unit,
    onSelectTab: (String) -> Unit, onCloseTab: (String) -> Unit, onNewTab: () -> Unit,
    onOpen: (String, Boolean) -> Unit, onFavoriteRemove: (String) -> Unit,
    onHistoryRemove: (String) -> Unit, onHistoryClear: () -> Unit, onExtension: (Boolean) -> Unit) {
    var search by remember(kind) { mutableStateOf("") }
    var clear by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().then(if (kind == "Extensions") Modifier.heightIn(min = 220.dp).verticalScroll(rememberScrollState()) else Modifier.fillMaxHeight(.85f)).navigationBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(kind, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                if (kind == "Tabs") TextButton(onClick = onNewTab) { Text("New tab") }
                if (kind == "History") TextButton(onClick = { clear = true }, enabled = data.history.isNotEmpty()) { Text("Clear history") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close browser panel") }
            }
            if (kind == "Extensions") {
                ListItem(headlineContent = { Text("Smooth Xianxia / QT") }, supportingContent = { Text("Translate Chinese web pages to Vietnamese on this device") },
                    leadingContent = { Icon(Icons.Outlined.Extension, null) }, trailingContent = {
                        Switch(settings.browserExtensionsEnabled, onExtension, Modifier.semantics { contentDescription = "Smooth translation extension" })
                    })
                Text("Turning this off restores original page text. Reader settings are separate.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
            } else {
                OutlinedTextField(search, { search = it }, modifier = Modifier.fillMaxWidth().padding(12.dp), singleLine = true,
                    label = { Text("Search $kind") }, leadingIcon = { Icon(Icons.Outlined.Search, null) })
                LazyColumn(Modifier.weight(1f)) {
                    when (kind) {
                        "Tabs" -> {
                            val tabs = data.tabs.filter { it.title.contains(search, true) || it.url.contains(search, true) }
                            items(tabs, key = { it.id }) { tab ->
                                ListItem(modifier = Modifier.clickable { onSelectTab(tab.id) },
                                    headlineContent = { Text(tab.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(tab.url, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    leadingContent = { Icon(if (tab.id == data.activeId) Icons.Outlined.RadioButtonChecked else Icons.Outlined.Tab, null) },
                                    trailingContent = { IconButton(onClick = { onCloseTab(tab.id) }) { Icon(Icons.Outlined.Close, "Close tab ${tab.title}") } })
                            }
                            if (tabs.isEmpty()) item { Text("No matching tabs", Modifier.padding(24.dp)) }
                        }
                        "Favorites" -> {
                            val entries = data.favorites.filter { it.title.contains(search, true) || it.url.contains(search, true) }
                            items(entries, key = { it.url }) { entry ->
                                LinkRow(entry.title, entry.url, { onOpen(entry.url, false) }, { onOpen(entry.url, true) }, { onFavoriteRemove(entry.url) })
                            }
                            if (entries.isEmpty()) item { Text(if (search.isBlank()) "No favorites yet. Tap the star on a page to save it." else "No matching favorites", Modifier.padding(24.dp)) }
                        }
                        "History" -> {
                            val entries = data.history.filter { it.title.contains(search, true) || it.url.contains(search, true) }
                            items(entries, key = { it.id }) { entry ->
                                LinkRow(entry.title, DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(entry.time)) + " · " + entry.url,
                                    { onOpen(entry.url, false) }, { onOpen(entry.url, true) }, { onHistoryRemove(entry.id) })
                            }
                            if (entries.isEmpty()) item { Text(if (search.isBlank()) "No browsing history" else "No matching history", Modifier.padding(24.dp)) }
                        }
                    }
                }
            }
        }
    }
    if (clear) AlertDialog(onDismissRequest = { clear = false }, title = { Text("Clear browsing history?") }, text = { Text("Favorites and open tabs will stay saved.") },
        confirmButton = { TextButton(onClick = { onHistoryClear(); clear = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { clear = false }) { Text("Cancel") } })
}

@Composable
private fun LinkRow(title: String, detail: String, onOpen: () -> Unit, onNewTab: () -> Unit, onRemove: () -> Unit) {
    ListItem(modifier = Modifier.clickable(onClick = onOpen),
        headlineContent = { Text(title.ifBlank { detail }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(detail, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        trailingContent = { Row {
            IconButton(onClick = onNewTab) { Icon(Icons.Outlined.OpenInNew, "Open in new tab: $title") }
            IconButton(onClick = onRemove) { Icon(Icons.Outlined.DeleteOutline, "Remove: $title") }
        } })
}
