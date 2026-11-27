package com.shelf.reader.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.shelf.reader.data.tagKey
import com.shelf.reader.ui.ShelfButton
import com.shelf.reader.ui.theme.ShelfColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TagFilterSheet(
    tags: List<LibraryTag>,
    initial: LibraryTagFilter,
    onDismiss: () -> Unit,
    onApply: (LibraryTagFilter) -> Unit,
    onSaveShelf: ((LibraryTagFilter) -> Unit)? = null,
) {
    var draft by remember(initial) { mutableStateOf(initial) }
    var query by rememberSaveable { mutableStateOf("") }
    val keyboard = LocalSoftwareKeyboardController.current
    val options = remember(tags, draft.included, draft.excluded, query) {
        val needle = tagKey(query)
        val knownKeys = tags.mapTo(HashSet()) { tagKey(it.name) }
        val unavailable = (draft.included + draft.excluded - knownKeys).sorted().map { LibraryTag(it, 0) }
        (tags + unavailable).filter { tagKey(it.name).contains(needle) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        Column(
            Modifier.fillMaxWidth().fillMaxHeight(0.86f).navigationBarsPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filter tags", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (onSaveShelf != null) TextButton(onClick = { onSaveShelf(draft) }) { Text("Save shelf") }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close tag filters") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Find a tag") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = draft.match == TagMatch.ALL,
                    onClick = { draft = draft.copy(match = TagMatch.ALL) },
                    label = { Text("All included") },
                )
                FilterChip(
                    selected = draft.match == TagMatch.ANY,
                    onClick = { draft = draft.copy(match = TagMatch.ANY) },
                    label = { Text("Any included") },
                )
            }
            Text("Books with any excluded tag are hidden.", style = MaterialTheme.typography.bodySmall,
                color = ShelfColors.Muted)
            if (options.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(if (query.isBlank()) "No tags available" else "No matching tags", color = ShelfColors.Muted)
                }
            } else {
                LazyColumn(Modifier.weight(1f)) {
                    items(options, key = { tagKey(it.name) }) { tag ->
                        TagRuleRow(tag, draft) { rule -> draft = draft.toggle(tag.name, rule) }
                        HorizontalDivider(color = ShelfColors.Hair)
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = { draft = LibraryTagFilter() },
                    enabled = draft.isActive,
                ) { Text("Clear all") }
                ShelfButton(
                    text = if (draft.isActive) "Apply filters (${draft.size})" else "Apply filters",
                    onClick = {
                        keyboard?.hide()
                        onApply(draft)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun TagRuleRow(tag: LibraryTag, filter: LibraryTagFilter, onToggle: (TagRule) -> Unit) {
    val key = tagKey(tag.name)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(tag.name, style = MaterialTheme.typography.bodyLarge)
            Text(if (tag.count == 1) "1 book" else "${tag.count} books", style = MaterialTheme.typography.bodySmall,
                color = ShelfColors.Muted)
        }
        FilterChip(
            selected = key in filter.included,
            onClick = { onToggle(TagRule.INCLUDE) },
            label = { Text(TagRule.INCLUDE.label) },
            modifier = Modifier.semantics { contentDescription = "Include ${tag.name}" },
        )
        FilterChip(
            selected = key in filter.excluded,
            onClick = { onToggle(TagRule.EXCLUDE) },
            label = { Text(TagRule.EXCLUDE.label) },
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = ShelfColors.Danger.copy(alpha = 0.16f),
                selectedLabelColor = ShelfColors.Danger,
            ),
            modifier = Modifier.semantics { contentDescription = "Exclude ${tag.name}" },
        )
    }
}
