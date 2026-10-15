package com.shelf.reader.ui.opds

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.BuildConfig
import com.shelf.reader.ui.theme.ShelfColors
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.w3c.dom.Element

data class OpdsEntry(
    val title: String,
    val author: String,
    val summary: String,
    val downloadUrl: String,
)

data class OpdsUiState(
    val url: String = "",
    val loading: Boolean = false,
    val entries: List<OpdsEntry> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val downloading: String? = null,
)

class OpdsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(OpdsUiState())
    val state = _state.asStateFlow()

    fun setUrl(value: String) { _state.value = _state.value.copy(url = value) }

    fun load() {
        val address = _state.value.url.trim()
        if (!isWebUrl(address)) {
            _state.value = _state.value.copy(error = "Enter a complete http:// or https:// catalog address")
            return
        }
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null, entries = emptyList())
            val result = runCatching { withContext(Dispatchers.IO) { fetch(address) } }
            _state.value = result.fold(
                onSuccess = { _state.value.copy(loading = false, entries = it, error = null) },
                onFailure = { _state.value.copy(loading = false, error = it.message ?: "Catalog could not be loaded") },
            )
        }
    }

    fun download(entry: OpdsEntry, destination: Uri) = viewModelScope.launch {
        _state.value = _state.value.copy(downloading = entry.title, message = null)
        val result = runCatching {
            withContext(Dispatchers.IO) {
                val connection = open(entry.downloadUrl)
                try {
                    getApplication<Application>().contentResolver.openOutputStream(destination, "w")?.use { output ->
                        connection.inputStream.use { input -> input.copyTo(output, 64 * 1024) }
                    } ?: error("Could not open the selected destination")
                } finally {
                    connection.disconnect()
                }
            }
        }
        _state.value = _state.value.copy(
            downloading = null,
            message = if (result.isSuccess) "${entry.title} saved. Rescan its folder to add it to Shelf."
                else result.exceptionOrNull()?.message ?: "Download failed",
        )
    }

    fun consumeMessage() { _state.value = _state.value.copy(message = null) }

    private fun fetch(address: String): List<OpdsEntry> {
        val connection = open(
            address,
            "application/atom+xml;profile=opds-catalog, application/xml;q=0.9",
        )
        val bytes = try {
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(32 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= 8 * 1024 * 1024) { "Catalog response is too large" }
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }

        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val nodes = document.getElementsByTagNameNS("*", "entry")
        return buildList {
            for (index in 0 until nodes.length) {
                val element = nodes.item(index) as? Element ?: continue
                val links = element.getElementsByTagNameNS("*", "link")
                var acquisition = ""
                for (linkIndex in 0 until links.length) {
                    val link = links.item(linkIndex) as? Element ?: continue
                    val rel = link.getAttribute("rel")
                    val type = link.getAttribute("type")
                    if (rel.contains("acquisition") || type == "application/epub+zip") {
                        acquisition = link.getAttribute("href")
                        if (acquisition.isNotBlank()) break
                    }
                }
                if (acquisition.isBlank()) continue
                val authorElement = element.getElementsByTagNameNS("*", "author").item(0) as? Element
                add(
                    OpdsEntry(
                        title = element.textOf("title").ifBlank { "Untitled" },
                        author = authorElement?.textOf("name").orEmpty(),
                        summary = element.textOf("summary").ifBlank { element.textOf("content") },
                        downloadUrl = URI(address).resolve(acquisition).toString(),
                    )
                )
            }
        }
    }

    private fun open(address: String, accept: String? = null): HttpURLConnection {
        require(isWebUrl(address)) { "Only http and https addresses are supported" }
        return (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Shelf-Android/${BuildConfig.VERSION_NAME}")
            if (accept != null) setRequestProperty("Accept", accept)
            connect()
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                error("Server returned HTTP $code")
            }
        }
    }

    private fun isWebUrl(value: String): Boolean = runCatching {
        URI(value).let { (it.scheme == "http" || it.scheme == "https") && !it.host.isNullOrBlank() }
    }.getOrDefault(false)

    private fun Element.textOf(localName: String): String =
        getElementsByTagNameNS("*", localName).item(0)?.textContent?.trim().orEmpty()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpdsScreen(onBack: () -> Unit, viewModel: OpdsViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbars = remember { SnackbarHostState() }
    var pending by remember { mutableStateOf<OpdsEntry?>(null) }
    val saveBook = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/epub+zip")
    ) { uri ->
        val entry = pending
        pending = null
        if (uri != null && entry != null) viewModel.download(entry, uri)
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbars.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        containerColor = ShelfColors.Ink,
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            TopAppBar(
                title = { Text("OPDS catalog") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ShelfColors.Panel,
                    titleContentColor = ShelfColors.Text,
                    navigationIconContentColor = ShelfColors.Text,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextField(
                    value = state.url,
                    onValueChange = viewModel::setUrl,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("https://example.com/opds") },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = ShelfColors.Raised,
                        unfocusedContainerColor = ShelfColors.Raised,
                        focusedIndicatorColor = ShelfColors.Rose,
                        unfocusedIndicatorColor = ShelfColors.Hair,
                    ),
                )
                Button(
                    onClick = viewModel::load,
                    enabled = !state.loading,
                    colors = ButtonDefaults.buttonColors(containerColor = ShelfColors.Rose),
                ) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "Load catalog")
                }
            }

            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ShelfColors.Rose)
                }
                state.error != null -> Text(
                    state.error.orEmpty(),
                    color = ShelfColors.Danger,
                    modifier = Modifier.padding(20.dp),
                )
                state.entries.isEmpty() -> Text(
                    "Enter an OPDS 1 catalog address to browse EPUB downloads. Catalog access only happens when you tap Load.",
                    color = ShelfColors.Muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(20.dp),
                )
                else -> LazyColumn(
                    contentPadding = PaddingValues(12.dp, 4.dp, 12.dp, 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.entries, key = { it.downloadUrl }) { entry ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(ShelfColors.Panel)
                                .clickable(enabled = state.downloading == null) {
                                    pending = entry
                                    saveBook.launch(safeFilename(entry.title) + ".epub")
                                }
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    entry.title,
                                    color = ShelfColors.Text,
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                if (entry.author.isNotBlank()) Text(
                                    entry.author,
                                    color = ShelfColors.Rose,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (entry.summary.isNotBlank()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        entry.summary,
                                        color = ShelfColors.Muted,
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            if (state.downloading == entry.title) {
                                CircularProgressIndicator(Modifier.padding(8.dp), color = ShelfColors.Rose)
                            } else {
                                Icon(Icons.Outlined.Download, "Download EPUB", tint = ShelfColors.Rose)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun safeFilename(title: String): String = title
    .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    .trim()
    .take(80)
    .ifBlank { "book" }
