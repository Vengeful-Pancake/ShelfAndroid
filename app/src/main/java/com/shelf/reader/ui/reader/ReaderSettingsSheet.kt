package com.shelf.reader.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shelf.reader.data.ReaderFont
import com.shelf.reader.data.ReaderSettings
import com.shelf.reader.ui.ShelfButton
import com.shelf.reader.ui.ShelfGhostButton
import com.shelf.reader.ui.library.GridDensity
import com.shelf.reader.ui.theme.ShelfColors
import kotlinx.coroutines.delay
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.provider.OpenableColumns
import androidx.compose.ui.platform.LocalContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onDismiss: () -> Unit,
    onChange: ((ReaderSettings) -> ReaderSettings) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val context = LocalContext.current

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            // Take persistable permission if possible
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            onChange { it.copy(wordReplacementsUri = uri.toString()) }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = ShelfColors.Panel,
        contentColor = ShelfColors.Text,
    ) {
        SheetHeader("Reading", onDismiss)

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            SettingRow("Page") {
                SwatchRow(
                    colors = ReaderSettings.PAGE_COLORS,
                    selected = settings.background,
                ) { picked ->
                    onChange { current ->
                        // Keep text readable when the page flips between light and dark.
                        val dark = ReaderSettings.isDark(picked)
                        val foreground = when {
                            dark && ReaderSettings.isDark(current.foreground) -> "#D8D4CC"
                            !dark && !ReaderSettings.isDark(current.foreground) -> "#1F1F1F"
                            else -> current.foreground
                        }
                        current.copy(background = picked, foreground = foreground)
                    }
                }
            }

            SettingRow("Text") {
                SwatchRow(
                    colors = ReaderSettings.TEXT_COLORS,
                    selected = settings.foreground,
                ) { picked -> onChange { it.copy(foreground = picked) } }
            }

            SettingRow("Typeface") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ReaderFont.entries.forEach { font ->
                        Chip(
                            label = font.label,
                            selected = font == settings.font,
                            onClick = { onChange { it.copy(font = font) } },
                        )
                    }
                }
            }

            Stepper(
                label = "Text size",
                value = "${settings.textSize} px",
                onDecrease = { onChange { it.copy(textSize = (it.textSize - 1).coerceAtLeast(12)) } },
                onIncrease = { onChange { it.copy(textSize = (it.textSize + 1).coerceAtMost(40)) } },
            )

            Stepper(
                label = "Line spacing",
                value = "%.2f".format(settings.lineHeight),
                onDecrease = {
                    onChange { it.copy(lineHeight = (it.lineHeight - 0.05f).coerceAtLeast(1.1f)) }
                },
                onIncrease = {
                    onChange { it.copy(lineHeight = (it.lineHeight + 0.05f).coerceAtMost(2.6f)) }
                },
            )

            SettingRow("Side margin · ${settings.margin} dp") {
                Slider(
                    value = settings.margin.toFloat(),
                    onValueChange = { onChange { s -> s.copy(margin = it.toInt()) } },
                    valueRange = 8f..48f,
                    steps = 7,
                    colors = SliderDefaults.colors(
                        thumbColor = ShelfColors.Rose,
                        activeTrackColor = ShelfColors.Rose,
                        inactiveTrackColor = ShelfColors.Hair,
                    ),
                )
            }

            SettingRow("Paragraphs") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip(
                        label = "Left",
                        selected = !settings.justify,
                        onClick = { onChange { it.copy(justify = false) } },
                    )
                    Chip(
                        label = "Justify",
                        selected = settings.justify,
                        onClick = { onChange { it.copy(justify = true) } },
                    )
                    Chip(
                        label = "Indent",
                        selected = settings.indent,
                        onClick = { onChange { it.copy(indent = !it.indent) } },
                    )
                }
            }

            ToggleRow(
                label = "Tap the edges to turn pages",
                help = "Tap the middle to show or hide the bars. Swipe sideways for the next section.",
                checked = settings.tapToTurn,
                onChange = { value -> onChange { it.copy(tapToTurn = value) } },
            )

            ToggleRow(
                label = "Continuous scrolling",
                help = "Keep scrolling past the end of a section and the next one follows right below.",
                checked = settings.continuousScroll,
                onChange = { value -> onChange { it.copy(continuousScroll = value) } },
            )

            ToggleRow(
                label = "Keep the screen on",
                help = "The display stays awake while a book is open.",
                checked = settings.keepScreenOn,
                onChange = { value -> onChange { it.copy(keepScreenOn = value) } },
            )

            ToggleRow(
                label = "Full screen",
                help = "Hide the status and navigation bars once the reading bars slide away.",
                checked = settings.fullscreen,
                onChange = { value -> onChange { it.copy(fullscreen = value) } },
            )

            ToggleRow(
                label = "Tablet Mode",
                help = "Split the text into two columns on wide screens.",
                checked = settings.twoColumns,
                onChange = { value -> onChange { it.copy(twoColumns = value) } },
            )

            ToggleRow(
                label = "Show filename as title",
                help = "Use the file name instead of the EPUB's internal title in the library.",
                checked = settings.useFilenameAsTitle,
                onChange = { value -> onChange { it.copy(useFilenameAsTitle = value) } },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "Library Appearance",
                style = MaterialTheme.typography.titleSmall,
                color = ShelfColors.Rose,
            )

            ToggleRow(
                label = "Hide all thumbnails",
                help = "Show books as text-only cards, with no cover images in the library or details.",
                checked = settings.hideThumbnails,
                onChange = { value -> onChange { it.copy(hideThumbnails = value) } },
            )

            SettingRow("View Mode") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(GridDensity.ADAPTIVE, GridDensity.TWO, GridDensity.THREE).forEach { density ->
                            Chip(
                                label = density.label.split(" ").first(),
                                selected = settings.gridDensity == density.key,
                                onClick = { onChange { it.copy(gridDensity = density.key) } }
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(GridDensity.FOUR, GridDensity.TINY, GridDensity.LIST, GridDensity.DETAILED).forEach { density ->
                            Chip(
                                label = density.label.split(" ").first(),
                                selected = settings.gridDensity == density.key,
                                onClick = { onChange { it.copy(gridDensity = density.key) } }
                            )
                        }
                    }
                }
            }

            Stepper(
                label = "Title max lines",
                value = "${settings.titleMaxLines}",
                onDecrease = { onChange { it.copy(titleMaxLines = (it.titleMaxLines - 1).coerceAtLeast(1)) } },
                onIncrease = { onChange { it.copy(titleMaxLines = (it.titleMaxLines + 1).coerceAtMost(10)) } },
            )

            Stepper(
                label = "Title font size",
                value = "${settings.titleFontSize} sp",
                onDecrease = { onChange { it.copy(titleFontSize = (it.titleFontSize - 1).coerceAtLeast(10)) } },
                onIncrease = { onChange { it.copy(titleFontSize = (it.titleFontSize + 1).coerceAtMost(24)) } },
            )

            Spacer(Modifier.height(8.dp))
            SettingRow("Word Replacements") {
                Text(
                    "Rules can be linked from a .txt file (old=new per line) or edited below.",
                    style = MaterialTheme.typography.bodySmall,
                    color = ShelfColors.Muted,
                )
                Spacer(Modifier.height(10.dp))

                if (settings.wordReplacementsUri != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(ShelfColors.Raised)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.AutoMirrored.Outlined.InsertDriveFile,
                            contentDescription = null,
                            tint = ShelfColors.Rose,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            fileName(context, settings.wordReplacementsUri),
                            style = MaterialTheme.typography.bodyMedium,
                            color = ShelfColors.Text,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        IconButton(onClick = { onChange { it.copy(wordReplacementsUri = null) } }) {
                            Icon(Icons.Outlined.Close, null, tint = ShelfColors.Muted)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                }

                ShelfGhostButton(
                    text = if (settings.wordReplacementsUri == null) "Link a .txt file" else "Change rules file",
                    onClick = { picker.launch(arrayOf("text/plain")) },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(16.dp))
                Text(
                    "Quick edits (internal rules):",
                    style = MaterialTheme.typography.labelSmall,
                    color = ShelfColors.Muted,
                )
                Spacer(Modifier.height(6.dp))

                var localText by remember(settings.wordReplacements) {
                    mutableStateOf(settings.wordReplacements)
                }

                LaunchedEffect(localText) {
                    if (localText != settings.wordReplacements) {
                        delay(600)
                        onChange { it.copy(wordReplacements = localText) }
                    }
                }

                TextField(
                    value = localText,
                    onValueChange = { localText = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 10,
                    placeholder = { Text("e.g. abc=def") },
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ShelfColors.Raised,
                        unfocusedContainerColor = ShelfColors.Raised,
                        focusedIndicatorColor = ShelfColors.Rose,
                        unfocusedIndicatorColor = ShelfColors.Hair,
                        focusedTextColor = ShelfColors.Text,
                        unfocusedTextColor = ShelfColors.Text,
                    )
                )
            }
        }
    }
}

private fun fileName(context: android.content.Context, uriString: String): String {
    val uri = android.net.Uri.parse(uriString)
    if (uri.scheme != "content") return uri.path?.substringAfterLast('/') ?: "rules.txt"
    return runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index != -1 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull() ?: "rules.txt"
}

@Composable
private fun SettingRow(label: String, content: @Composable () -> Unit) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ShelfColors.Muted,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun SwatchRow(
    colors: List<String>,
    selected: String,
    onPick: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        colors.forEach { hex ->
            val color = Color(android.graphics.Color.parseColor(hex))
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(color)
                    .border(
                        width = if (hex.equals(selected, true)) 2.dp else 1.dp,
                        color = if (hex.equals(selected, true)) ShelfColors.Rose else ShelfColors.Hair,
                        shape = CircleShape,
                    )
                    .clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (hex.equals(selected, true)) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = null,
                        tint = if (ReaderSettings.isDark(hex)) Color.White else Color.Black,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) ShelfColors.OnAccent else ShelfColors.Muted,
        modifier = Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) ShelfColors.Rose else ShelfColors.Raised)
            .border(
                1.dp,
                if (selected) ShelfColors.Rose else ShelfColors.Hair,
                RoundedCornerShape(7.dp),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Stepper(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = ShelfColors.Muted,
            modifier = Modifier.weight(1f),
        )
        StepButton("−", onDecrease)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = ShelfColors.Text,
            modifier = Modifier.width(64.dp).padding(horizontal = 8.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        StepButton("+", onIncrease)
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ShelfColors.Raised)
            .border(1.dp, ShelfColors.Hair, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            style = MaterialTheme.typography.titleMedium,
            color = ShelfColors.Text,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    help: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = ShelfColors.Text)
            Spacer(Modifier.height(2.dp))
            Text(help, style = MaterialTheme.typography.bodySmall, color = ShelfColors.Muted)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = ShelfColors.OnAccent,
                checkedTrackColor = ShelfColors.Rose,
                uncheckedTrackColor = ShelfColors.Raised,
                uncheckedBorderColor = ShelfColors.Hair,
            ),
        )
    }
}
