package com.shelf.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shelf.reader.data.BookCard
import com.shelf.reader.ui.ShelfButton
import com.shelf.reader.ui.ShelfGhostButton
import com.shelf.reader.ui.theme.ShelfColors
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailSheet(
    book: BookCard,
    coverPath: String?,
    showCover: Boolean = true,
    onDismiss: () -> Unit,
    onRead: () -> Unit,
    onFavorite: () -> Unit,
    onResetProgress: () -> Unit,
    onManageLibraries: () -> Unit,
    onDelete: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .navigationBarsPadding()
                .padding(bottom = 20.dp),
        ) {
            Row {
                if (showCover) {
                    Box(
                        Modifier
                            .width(104.dp)
                            .height(150.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(ShelfColors.Raised),
                    ) { CoverImage(coverPath, book.title) }

                    Spacer(Modifier.width(16.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        book.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = ShelfColors.Text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        book.author.ifBlank { "Unknown author" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = ShelfColors.Muted,
                    )
                    Spacer(Modifier.height(10.dp))

                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (book.series.isNotBlank()) Tag(book.series)
                        if (book.chapters > 0) Tag("${book.chapters} chapters")
                        if (book.language.isNotBlank()) Tag(book.language.uppercase())
                        if (book.size > 0) Tag(formatSize(book.size))
                        if (book.folder.isNotBlank()) Tag(book.folder)
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            if (book.description.isNotBlank()) {
                Text(
                    book.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShelfColors.Text,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (book.publisher.isNotBlank()) {
                Text(
                    "Published by ${book.publisher}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShelfColors.Muted,
                )
                Spacer(Modifier.height(12.dp))
            }

            if (book.started) {
                Text(
                    if (book.lastChapter.isNullOrBlank()) "${book.percent}% read"
                    else "${book.percent}% read · ${book.lastChapter}",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShelfColors.Rose,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { book.percent / 100f },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = ShelfColors.Rose,
                    trackColor = ShelfColors.Hair,
                )
                book.lastRead?.takeIf { it > 0 }?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Last opened ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it))}",
                        style = MaterialTheme.typography.bodySmall,
                        color = ShelfColors.Muted,
                    )
                }
                Spacer(Modifier.height(16.dp))
            }

            if (book.broken) {
                Text(
                    "This file will not open. It may be damaged, or protected by DRM.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = ShelfColors.Danger,
                )
                Spacer(Modifier.height(16.dp))
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShelfButton(
                    text = if (book.started) "Continue reading" else "Start reading",
                    onClick = onRead,
                    icon = Icons.AutoMirrored.Outlined.MenuBook,
                    enabled = !book.broken,
                    modifier = Modifier.weight(1f),
                )
                ShelfGhostButton(
                    text = if (book.favorite) "Pinned" else "Pin",
                    onClick = onFavorite,
                    icon = if (book.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                )
            }

            Spacer(Modifier.height(10.dp))
            ShelfGhostButton(
                text = "Add to library",
                onClick = onManageLibraries,
                icon = Icons.AutoMirrored.Outlined.Label,
                modifier = Modifier.fillMaxWidth(),
            )

            if (book.started) {
                Spacer(Modifier.height(10.dp))
                ShelfGhostButton(
                    text = "Forget my place",
                    onClick = onResetProgress,
                    icon = Icons.Outlined.RestartAlt,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(10.dp))
            ShelfGhostButton(
                text = "Delete from device",
                onClick = onDelete,
                icon = Icons.Outlined.DeleteOutline,
                danger = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Permanently removes the EPUB from its source folder.",
                style = MaterialTheme.typography.bodySmall,
                color = ShelfColors.Muted,
            )
        }
    }
}

@Composable
private fun Tag(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = ShelfColors.Muted,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(ShelfColors.Raised)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
