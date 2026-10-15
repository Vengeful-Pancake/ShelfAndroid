package com.shelf.reader.update

import android.app.Application
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.BuildConfig
import com.shelf.reader.ui.theme.ShelfColors
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val GITHUB_API_VERSION = "2022-11-28"
private const val MAX_RELEASE_RESPONSE_BYTES = 2 * 1024 * 1024
private const val MAX_APK_BYTES = 300L * 1024 * 1024
private const val APK_MIME = "application/vnd.android.package-archive"

data class AppRelease(
    val version: String,
    val title: String,
    val notes: String,
    val assetName: String,
    val downloadUrl: String,
    val size: Long,
    val sha256: String?,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val release: AppRelease) : UpdateState
    data class Downloading(val release: AppRelease, val progress: Float?) : UpdateState
    data class Ready(val release: AppRelease, val apk: File) : UpdateState
    data class Error(val message: String) : UpdateState
}

/** Semantic numeric version comparison used for GitHub tags such as `v0.2.1`. */
internal object AppVersions {
    fun isNewer(candidate: String, current: String): Boolean {
        val next = parts(candidate) ?: return false
        val installed = parts(current) ?: return false
        val length = maxOf(next.size, installed.size)
        for (index in 0 until length) {
            val left = next.getOrElse(index) { 0 }
            val right = installed.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parts(value: String): List<Int>? {
        val clean = value.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (clean.isBlank()) return null
        val result = clean.split('.').map { it.toIntOrNull() ?: return null }
        return result.takeIf { it.isNotEmpty() }
    }
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var work: Job? = null

    init {
        checkForUpdate(silent = true)
    }

    fun checkForUpdate(silent: Boolean = false) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            _state.value = UpdateState.Checking
            val result = runCatching { withContext(Dispatchers.IO) { fetchLatestRelease() } }
            val release = result.getOrNull()
            _state.value = when {
                release != null && AppVersions.isNewer(release.version, BuildConfig.VERSION_NAME) ->
                    UpdateState.Available(release)
                result.isFailure && !silent ->
                    UpdateState.Error(result.exceptionOrNull()?.message ?: "Could not check for updates")
                else -> UpdateState.Idle
            }
        }
    }

    fun download(release: AppRelease) {
        work?.cancel()
        work = viewModelScope.launch {
            _state.value = UpdateState.Downloading(release, null)
            try {
                val apk = withContext(Dispatchers.IO) {
                    downloadApk(release) { progress ->
                        _state.value = UpdateState.Downloading(release, progress)
                    }
                }
                _state.value = UpdateState.Ready(release, apk)
            } catch (cancelled: CancellationException) {
                if (_state.value is UpdateState.Downloading) {
                    _state.value = UpdateState.Available(release)
                }
                throw cancelled
            } catch (error: Throwable) {
                _state.value = UpdateState.Error(error.message ?: "The update could not be downloaded")
            }
        }
    }

    fun dismiss() {
        work?.cancel()
        _state.value = UpdateState.Idle
    }

    fun installFailed(message: String) {
        _state.value = UpdateState.Error(message)
    }

    fun installLaunched() {
        _state.value = UpdateState.Idle
    }

    private fun fetchLatestRelease(): AppRelease {
        val repository = BuildConfig.GITHUB_REPOSITORY
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            "The update repository is not configured"
        }
        val connection = openGithubConnection(
            URL("https://api.github.com/repos/$repository/releases/latest"),
            "application/vnd.github+json",
        )
        val bytes = try {
            connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                    require(output.size() <= MAX_RELEASE_RESPONSE_BYTES) { "Release response is too large" }
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }

        val json = JSONObject(String(bytes, Charsets.UTF_8))
        require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) {
            "The latest release is not a stable release"
        }
        val tag = json.optString("tag_name").trim()
        require(tag.isNotEmpty()) { "The release has no version tag" }
        val assets = json.optJSONArray("assets") ?: error("The release has no APK")
        val candidates = buildList {
            for (index in 0 until assets.length()) {
                val asset = assets.optJSONObject(index) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                val size = asset.optLong("size", -1)
                if (name.endsWith(".apk", ignoreCase = true) &&
                    !name.contains("debug", ignoreCase = true) &&
                    url.isNotBlank() && size in 1..MAX_APK_BYTES
                ) add(asset)
            }
        }
        val asset = candidates.firstOrNull { it.optString("name").contains("release", true) }
            ?: candidates.firstOrNull()
            ?: error("The release does not contain an installable APK")
        val digest = asset.optString("digest")
            .removePrefix("sha256:")
            .lowercase()
            .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
        return AppRelease(
            version = tag.removePrefix("v").removePrefix("V"),
            title = json.optString("name").ifBlank { tag },
            notes = json.optString("body").trim().take(8_000),
            assetName = asset.getString("name"),
            downloadUrl = asset.getString("browser_download_url"),
            size = asset.getLong("size"),
            sha256 = digest,
        )
    }

    private fun downloadApk(release: AppRelease, onProgress: (Float?) -> Unit): File {
        val initial = URL(release.downloadUrl)
        require(initial.protocol == "https" && initial.host.equals("github.com", true)) {
            "The release APK is not hosted by GitHub"
        }
        val connection = openGithubConnection(initial, "application/octet-stream")
        try {
            val finalUrl = connection.url
            require(finalUrl.protocol == "https" && isGithubDownloadHost(finalUrl.host)) {
                "GitHub redirected the APK to an untrusted host"
            }
            val expectedLength = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
            require(expectedLength in 1..MAX_APK_BYTES) { "The release APK has an invalid size" }

            val directory = File(getApplication<Application>().cacheDir, "updates").apply { mkdirs() }
            directory.listFiles()?.filter { it.isFile }?.forEach { it.delete() }
            val partial = File(directory, "${safeName(release.assetName)}.part")
            val target = File(directory, safeName(release.assetName))
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            connection.inputStream.use { input ->
                partial.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_APK_BYTES) { "The release APK is too large" }
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        onProgress(expectedLength.takeIf { it > 0 }?.let { (total.toFloat() / it).coerceIn(0f, 1f) })
                    }
                }
            }
            require(total > 4 && partial.inputStream().use { input ->
                input.read() == 'P'.code && input.read() == 'K'.code
            }) { "The downloaded file is not an APK" }
            release.sha256?.let { expected ->
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(actual == expected) { "The APK checksum does not match the GitHub release" }
            }
            require(partial.renameTo(target)) { "Could not finish the APK download" }
            return target
        } finally {
            connection.disconnect()
        }
    }

    private fun openGithubConnection(url: URL, accept: String): HttpURLConnection {
        require(url.protocol == "https") { "Only HTTPS updates are supported" }
        return (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Shelf-Android/${BuildConfig.VERSION_NAME}")
            setRequestProperty("X-GitHub-Api-Version", GITHUB_API_VERSION)
            connect()
            if (responseCode !in 200..299) {
                val code = responseCode
                disconnect()
                error("GitHub returned HTTP $code")
            }
        }
    }

    private fun isGithubDownloadHost(host: String): Boolean {
        val value = host.lowercase()
        return value == "github.com" || value.endsWith(".githubusercontent.com")
    }

    private fun safeName(value: String): String = value.substringAfterLast('/').replace(
        Regex("[^A-Za-z0-9._-]"),
        "_",
    ).take(120).ifBlank { "Shelf-update.apk" }
}

@Composable
fun AppUpdateCoordinator(viewModel: UpdateViewModel = viewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current

    fun launchInstaller(ready: UpdateState.Ready) {
        runCatching {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                ready.apk,
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, APK_MIME)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }.onSuccess {
            viewModel.installLaunched()
        }.onFailure {
            viewModel.installFailed(it.message ?: "Android could not open the package installer")
        }
    }

    val unknownSources = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val ready = viewModel.state.value as? UpdateState.Ready ?: return@rememberLauncherForActivityResult
        if (context.packageManager.canRequestPackageInstalls()) {
            launchInstaller(ready)
        } else {
            viewModel.installFailed("Install permission was not granted. You can try again on the next update check.")
        }
    }

    LaunchedEffect(state) {
        val ready = state as? UpdateState.Ready ?: return@LaunchedEffect
        if (context.packageManager.canRequestPackageInstalls()) {
            launchInstaller(ready)
        } else {
            unknownSources.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    "package:${context.packageName}".toUri(),
                )
            )
        }
    }

    when (val current = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            containerColor = ShelfColors.Panel,
            title = { Text("Shelf ${current.release.version} is available", color = ShelfColors.Text) },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "Installed: ${BuildConfig.VERSION_NAME}  •  Download: ${formatBytes(current.release.size)}",
                        color = ShelfColors.Muted,
                    )
                    if (current.release.notes.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(current.release.notes, color = ShelfColors.Text)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.download(current.release) }) {
                    Text("Download update", color = ShelfColors.Rose)
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Later", color = ShelfColors.Muted) }
            },
        )

        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            containerColor = ShelfColors.Panel,
            title = { Text("Downloading Shelf ${current.release.version}", color = ShelfColors.Text) },
            text = {
                Column {
                    if (current.progress == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(), color = ShelfColors.Rose)
                    } else {
                        LinearProgressIndicator(
                            progress = { current.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = ShelfColors.Rose,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("The Android installer will open when the download is verified.", color = ShelfColors.Muted)
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = viewModel::dismiss) { Text("Cancel", color = ShelfColors.Muted) }
            },
        )

        is UpdateState.Error -> AlertDialog(
            onDismissRequest = viewModel::dismiss,
            containerColor = ShelfColors.Panel,
            title = { Text("Update could not continue", color = ShelfColors.Text) },
            text = { Text(current.message, color = ShelfColors.Muted) },
            confirmButton = {
                TextButton(onClick = viewModel::dismiss) { Text("OK", color = ShelfColors.Rose) }
            },
        )

        UpdateState.Checking,
        UpdateState.Idle,
        is UpdateState.Ready -> Unit
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "${bytes / 1024} KB"
    else -> "$bytes B"
}
