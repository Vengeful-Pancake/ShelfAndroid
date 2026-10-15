package com.shelf.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shelf.reader.data.AnnotationEntity
import com.shelf.reader.data.BookmarkEntity
import com.shelf.reader.epub.TocEntry
import com.shelf.reader.ui.ShelfGhostButton
import com.shelf.reader.ui.theme.ShelfColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentsSheet(
    toc: List<TocEntry>,
    currentChapter: Int,
    onDismiss: () -> Unit,
    onSelect: (TocEntry) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState()

    // Duplicate labels pointing at the same section only add noise.
    val entries = remember(toc) {
        val seen = HashSet<String>()
        toc.filter { seen.add("${it.spineIndex}:${it.label}") }
    }
    val currentIndex = entries.indexOfLast { it.spineIndex <= currentChapter }

    LaunchedEffect(currentIndex) {
        if (currentIndex > 2) listState.scrollToItem(currentIndex - 2)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        SheetHeader("Contents", onDismiss)

        if (entries.isEmpty()) {
            EmptySheetNote("This book has no contents list.")
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.heightIn(max = 520.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(entries.size, key = { entries[it].spineIndex * 1000 + it }) { i ->
                    val entry = entries[i]
                    val current = i == currentIndex
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(entry) }
                            .padding(
                                start = (20 + entry.depth.coerceAtMost(2) * 16).dp,
                                end = 20.dp,
                                top = 11.dp,
                                bottom = 11.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (current) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(ShelfColors.Rose)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            entry.label,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) ShelfColors.Rose else ShelfColors.Text,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onDismiss: () -> Unit,
    onOpen: (BookmarkEntity) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        SheetHeader("Bookmarks", onDismiss)

        if (bookmarks.isEmpty()) {
            EmptySheetNote("No bookmarks yet. Tap the bookmark button while reading to save your place.")
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(bookmarks, key = { it.id }) { mark ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ShelfColors.Raised)
                            .clickable { onOpen(mark) }
                            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                mark.title.ifBlank { "Section ${mark.chapter + 1}" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = ShelfColors.Text,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${(mark.offset * 100).toInt()}% in · " +
                                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(mark.at)),
                                style = MaterialTheme.typography.bodySmall,
                                color = ShelfColors.Muted,
                            )
                        }
                        IconButton(onClick = { onDelete(mark.id) }) {
                            Icon(
                                Icons.Outlined.Close,
                                contentDescription = "Remove bookmark",
                                tint = ShelfColors.Muted,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchSheet(
    query: String,
    results: List<ReaderSearchResult>,
    searching: Boolean,
    onQueryChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onOpen: (ReaderSearchResult) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        SheetHeader("Search this book", onDismiss)
        TextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("Enter at least two characters") },
            singleLine = true,
            colors = TextFieldDefaults.colors(
                focusedContainerColor = ShelfColors.Raised,
                unfocusedContainerColor = ShelfColors.Raised,
                focusedIndicatorColor = ShelfColors.Rose,
                unfocusedIndicatorColor = ShelfColors.Hair,
            ),
        )
        when {
            searching -> Box(
                Modifier.fillMaxWidth().height(160.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = ShelfColors.Rose) }

            query.trim().length < 2 -> EmptySheetNote("Searches every section in the open EPUB.")
            results.isEmpty() -> EmptySheetNote("No matches in this book.")
            else -> LazyColumn(
                modifier = Modifier.heightIn(max = 500.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(results, key = { "${it.chapter}:${it.snippet}" }) { result ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ShelfColors.Raised)
                            .clickable { onOpen(result) }
                            .padding(14.dp)
                    ) {
                        Text(
                            result.chapterTitle,
                            style = MaterialTheme.typography.labelLarge,
                            color = ShelfColors.Rose,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            result.snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShelfColors.Text,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnnotationsSheet(
    annotations: List<AnnotationEntity>,
    onDismiss: () -> Unit,
    onOpen: (AnnotationEntity) -> Unit,
    onDelete: (Long) -> Unit,
    onExport: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        SheetHeader("Highlights & notes", onDismiss)
        ShelfGhostButton(
            text = "Export Markdown",
            onClick = onExport,
            icon = Icons.Outlined.FileDownload,
            modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        if (annotations.isEmpty()) {
            EmptySheetNote("Select text in the reader, then tap the highlight button in the top bar.")
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(annotations, key = { it.id }) { mark ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ShelfColors.Raised)
                            .clickable { onOpen(mark) }
                            .padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                mark.chapterTitle.ifBlank { "Section ${mark.chapter + 1}" },
                                style = MaterialTheme.typography.labelLarge,
                                color = ShelfColors.Rose,
                                maxLines = 1,
                            )
                            Text(
                                "“${mark.quote}”",
                                style = MaterialTheme.typography.bodyMedium,
                                color = ShelfColors.Text,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (mark.note.isNotBlank()) {
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    mark.note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = ShelfColors.Muted,
                                    maxLines = 3,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        IconButton(onClick = { onDelete(mark.id) }) {
                            Icon(Icons.Outlined.Close, "Delete highlight", tint = ShelfColors.Muted)
                        }
                    }
                }
                item { Spacer(Modifier.height(20.dp)) }
            }
        }
    }
}

@Composable
fun AddAnnotationDialog(
    quote: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ShelfColors.Panel,
        title = { Text("Save highlight", color = ShelfColors.Text) },
        text = {
            Column {
                Text(
                    "“$quote”",
                    color = ShelfColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 5,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(12.dp))
                TextField(
                    value = note,
                    onValueChange = { note = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Optional note") },
                    minLines = 2,
                    maxLines = 5,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ShelfColors.Raised,
                        unfocusedContainerColor = ShelfColors.Raised,
                        focusedIndicatorColor = ShelfColors.Rose,
                        unfocusedIndicatorColor = ShelfColors.Hair,
                    ),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(note) }) { Text("Save", color = ShelfColors.Rose) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = ShelfColors.Muted) }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesSheet(
    voices: List<String>,
    selected: String?,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        SheetHeader("Voice Selection", onDismiss)

        if (voices.isEmpty()) {
            EmptySheetNote("No extra voices found on this device.")
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp).navigationBarsPadding(),
                contentPadding = PaddingValues(bottom = 24.dp),
            ) {
                items(voices, key = { it }) { voice ->
                    val current = voice == selected
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(voice) }
                            .padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (current) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(18.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(ShelfColors.Rose)
                            )
                            Spacer(Modifier.width(10.dp))
                        }
                        Text(
                            voice,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (current) ShelfColors.Rose else ShelfColors.Text,
                            fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, onClose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = ShelfColors.Text,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClose) {
            Icon(Icons.Outlined.Close, contentDescription = "Close", tint = ShelfColors.Muted)
        }
    }
}

@Composable
private fun EmptySheetNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = ShelfColors.Muted,
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
    )
}
