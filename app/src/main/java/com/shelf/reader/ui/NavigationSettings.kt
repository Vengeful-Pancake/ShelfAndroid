package com.shelf.reader.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.shelf.reader.data.ReaderSettings

@Composable
fun NavigationSettingsDialog(settings: ReaderSettings, onChange: ((ReaderSettings) -> ReaderSettings) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Bottom navigation") }, text = {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Show navigation bar", Modifier.weight(1f))
                Switch(settings.navigationBarVisible, { value -> onChange { it.copy(navigationBarVisible = value) } },
                    Modifier.semantics { contentDescription = "Show navigation bar" })
            }
            Text("Size: ${(settings.navigationBarScale * 100).toInt()}%")
            Slider(value = settings.navigationBarScale, onValueChange = { value -> onChange { it.copy(navigationBarScale = value) } },
                valueRange = .65f..1.4f, steps = 14, modifier = Modifier.semantics { contentDescription = "Navigation bar size" })
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Smaller", style = MaterialTheme.typography.labelSmall)
                Text("Bigger", style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(8.dp))
            Text("When hidden, tap the small arrow at the bottom to show it again.", style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } })
}
