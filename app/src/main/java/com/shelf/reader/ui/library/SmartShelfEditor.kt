package com.shelf.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.shelf.reader.data.SmartShelfEntity
import com.shelf.reader.ui.ShelfButton

@Composable
internal fun SmartShelfEditor(
    shelf: SmartShelfEntity,
    viewModel: LibraryViewModel,
    onChange: (SmartShelfEntity) -> Unit,
    onEditTags: () -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
    onRemove: () -> Unit,
) {
    val matches by remember(shelf.included, shelf.excluded, shelf.matchAny, shelf.readingFilter) {
        viewModel.previewShelf(shelf)
    }.collectAsStateWithLifecycle(initialValue = null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (shelf.id == 0L) "New smart shelf" else "Edit smart shelf") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(shelf.name, { onChange(shelf.copy(name = it)) },
                    label = { Text("Shelf name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ShelfChoice("Reading status", LibraryFilter.from(shelf.readingFilter).label,
                    LibraryFilter.entries.map { it.key to it.label }) { onChange(shelf.copy(readingFilter = it)) }
                ShelfChoice("Sort", LibrarySort.from(shelf.sort).label,
                    LibrarySort.entries.map { it.key to it.label }) { onChange(shelf.copy(sort = it)) }
                TextButton(onClick = onEditTags) {
                    Text("Edit tags (${shelf.included.size} included · ${shelf.excluded.size} excluded)")
                }
                matches?.let { Text(if (it == 1) "1 matching book" else "$it matching books") }
                if (shelf.id != 0L) TextButton(onClick = onRemove) { Text("Remove smart shelf") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        confirmButton = { ShelfButton("Save shelf", onClick = onSave, enabled = shelf.name.isNotBlank()) },
    )
}

@Composable
private fun ShelfChoice(label: String, value: String, options: List<Pair<String, String>>, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text("$label · $value") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (key, name) ->
                DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(key) })
            }
        }
    }
}
