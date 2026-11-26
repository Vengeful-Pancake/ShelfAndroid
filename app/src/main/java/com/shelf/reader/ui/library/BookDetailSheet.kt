package com.shelf.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.Toc
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.shelf.reader.data.BookCard
import com.shelf.reader.ui.ShelfButton
import com.shelf.reader.ui.ShelfGhostButton
import com.shelf.reader.ui.theme.ShelfColors

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailSheet(
    book: BookCard,
    coverPath: String?,
    onDismiss: () -> Unit,
    onStart: () -> Unit,
    onContinue: () -> Unit,
    onContents: () -> Unit,
    onTag: (String) -> Unit,
    onFavorite: () -> Unit,
    onResetProgress: () -> Unit,
    onManageLibraries: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var expandedCover by remember(book.id) { mutableStateOf(false) }
    var showMenu by remember(book.id) { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)
                .navigationBarsPadding().padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                val coverModifier = Modifier.width(112.dp).aspectRatio(0.68f)
                    .clip(RoundedCornerShape(8.dp)).background(ShelfColors.Raised)
                Box(
                    if (coverPath == null) coverModifier
                    else coverModifier.semantics { contentDescription = "Cover of ${book.title}" }
                        .clickable(onClickLabel = "View cover") { expandedCover = true },
                ) {
                    CoverImage(coverPath, contentScale = ContentScale.Fit)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f).padding(top = 4.dp),
                        )
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = "Book menu")
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (book.favorite) "Unpin book" else "Pin book") },
                                    leadingIcon = { Icon(if (book.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, null) },
                                    onClick = { showMenu = false; onFavorite() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Add to library") },
                                    leadingIcon = { Icon(Icons.AutoMirrored.Outlined.Label, null) },
                                    onClick = { showMenu = false; onManageLibraries() },
                                )
                                if (book.started) DropdownMenuItem(
                                    text = { Text("Forget my place") },
                                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, null) },
                                    onClick = { showMenu = false; onResetProgress() },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete from device", color = ShelfColors.Danger) },
                                    leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null, tint = ShelfColors.Danger) },
                                    onClick = { showMenu = false; onDelete() },
                                )
                            }
                        }
                    }
                    Text(book.author.ifBlank { "Unknown author" }, color = ShelfColors.Muted)
                    if (book.series.isNotBlank()) Text(book.series, color = ShelfColors.Muted)
                    val facts = listOfNotNull(
                        "${book.chapters} chapters".takeIf { book.chapters > 0 },
                        book.language.takeIf(String::isNotBlank)?.uppercase(),
                    )
                    if (facts.isNotEmpty()) Text(
                        facts.joinToString(" · "),
                        color = ShelfColors.Muted,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (book.started && !book.broken) {
                    Text(
                        listOfNotNull("${book.percent}% read", book.lastChapter?.takeIf(String::isNotBlank)).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = ShelfColors.Rose,
                    )
                    LinearProgressIndicator(
                        progress = { book.percent / 100f },
                        modifier = Modifier.fillMaxWidth().height(3.dp),
                        color = ShelfColors.Rose,
                        trackColor = ShelfColors.Hair,
                    )
                }
                ShelfButton(
                    text = if (book.started) "Continue reading" else "Start reading",
                    onClick = if (book.started) onContinue else onStart,
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    enabled = !book.broken,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (!book.broken) ShelfGhostButton(
                    text = "Contents",
                    onClick = onContents,
                    icon = Icons.AutoMirrored.Outlined.Toc,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (book.started && !book.broken) {
                    ShelfGhostButton(
                        text = "Start from beginning",
                        onClick = onStart,
                        icon = Icons.Outlined.RestartAlt,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (book.broken) Text("Shelf could not open this EPUB.", color = ShelfColors.Danger)
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Description", style = MaterialTheme.typography.titleMedium)
                Text(
                    book.description.ifBlank { "No description available." },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (book.description.isBlank()) ShelfColors.Muted else ShelfColors.Text,
                )
            }

            val tags = remember(book.subjects, book.catalogTags) { book.tags }
            if (tags.isNotEmpty()) {
                Column {
                    Text("Tags", style = MaterialTheme.typography.titleMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (tag in tags) SuggestionChip(onClick = { onTag(tag) }, label = { Text(tag) })
                    }
                }
            }

            val details = listOfNotNull(
                book.publisher.takeIf(String::isNotBlank)?.let { "Published by $it" },
                book.folder.takeIf(String::isNotBlank),
                formatSize(book.size).takeIf { book.size > 0 },
            )
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = ShelfColors.Muted)
            }
        }
    }

    if (expandedCover) Dialog(onDismissRequest = { expandedCover = false }) {
        Box(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).aspectRatio(0.68f)
                .clip(RoundedCornerShape(10.dp)).background(ShelfColors.Ink),
        ) {
            CoverImage(coverPath, contentScale = ContentScale.Fit)
            IconButton(
                onClick = { expandedCover = false },
                modifier = Modifier.align(Alignment.TopEnd).background(ShelfColors.Panel, RoundedCornerShape(100)),
            ) {
                Icon(Icons.Outlined.Close, "Close cover", tint = ShelfColors.Text)
            }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
