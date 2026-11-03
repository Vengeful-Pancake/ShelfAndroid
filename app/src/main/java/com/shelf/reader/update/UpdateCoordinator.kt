package com.shelf.reader.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

object UpdateRequests { val check = MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data class Available(val release: UpdateRelease) : UpdateUi
    data class Downloading(val release: UpdateRelease, val progress: Float) : UpdateUi
    data class Ready(val release: UpdateRelease, val file: File) : UpdateUi
    data class Message(val text: String) : UpdateUi
}

class UpdateViewModel(app: Application) : AndroidViewModel(app) {
    private val client = GithubUpdater(app)
    private val prefs = app.getSharedPreferences("release_updates", 0)
    private val mutable = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state = mutable.asStateFlow()
    private var work: Job? = null
    fun check(force: Boolean = false) {
        if (work?.isActive == true || mutable.value !is UpdateUi.Idle) return
        if (BuildConfig.DEBUG) { if (force) mutable.value = UpdateUi.Message("Bản debug không tự cài APK release."); return }
        val now = System.currentTimeMillis()
        val previous = prefs.getLong("last_attempt", 0)
        if (!force && now >= previous && now - previous < 6 * 60 * 60 * 1000L) return
        prefs.edit().putLong("last_attempt", now).apply()
        work = viewModelScope.launch {
            try {
                val release = withContext(Dispatchers.IO) { client.latest() }
                mutable.value = when {
                    release != null -> UpdateUi.Available(release)
                    force -> UpdateUi.Message("Bạn đang dùng bản mới nhất.")
                    else -> UpdateUi.Idle
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = if (force) UpdateUi.Message(error.message ?: "Không kiểm tra được cập nhật") else UpdateUi.Idle }
        }
    }
    fun download(release: UpdateRelease) {
        if (work?.isActive == true) return
        mutable.value = UpdateUi.Downloading(release, 0f)
        work = viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) { client.download(release) { mutable.value = UpdateUi.Downloading(release, it) } }
                mutable.value = UpdateUi.Ready(release, file)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { mutable.value = UpdateUi.Message(error.message ?: "Không tải được cập nhật") }
        }
    }
    fun dismiss() { work?.cancel(); mutable.value = UpdateUi.Idle }
    fun error(text: String) { mutable.value = UpdateUi.Message(text) }
}

@Composable
fun AppUpdateCoordinator(vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) { vm.check(); delay(6 * 60 * 60 * 1000L) }
        }
    }
    LaunchedEffect(vm) { UpdateRequests.check.collect { vm.check(force = true) } }

    fun install(ready: UpdateUi.Ready) { scope.launch(Dispatchers.Main.immediate) {
        try {
            withContext(Dispatchers.IO) { GithubUpdater(context).verifyPackage(ready.file, ready.release.version) }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", ready.file)
            context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        } catch (error: Exception) { vm.error(error.message ?: "Không mở được trình cài đặt") }
    } }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val ready = vm.state.value as? UpdateUi.Ready
        if (ready != null && context.packageManager.canRequestPackageInstalls()) install(ready)
    }
    when (val value = state) {
        UpdateUi.Idle -> Unit
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Shelf ${value.release.version} đã có bản mới") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Bản đang dùng: ${BuildConfig.VERSION_NAME}\nTải khoảng ${value.release.size / 1024 / 1024} MB. Giữ nguyên sách và Name đã lưu.")
                Spacer(Modifier.height(12.dp)); Text(value.release.notes)
            } },
            confirmButton = { TextButton(onClick = { vm.download(value.release) }) { Text("Tải và cập nhật") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Để sau") } },
        )
        is UpdateUi.Downloading -> AlertDialog(
            onDismissRequest = vm::dismiss, title = { Text("Đang tải bản cập nhật") },
            text = { Column { LinearProgressIndicator(progress = { value.progress }); Text("${(value.progress * 100).toInt()}% · Kiểm tra SHA-256 và chữ ký trước khi cài") } },
            confirmButton = {}, dismissButton = { TextButton(onClick = vm::dismiss) { Text("Hủy") } },
        )
        is UpdateUi.Ready -> AlertDialog(
            onDismissRequest = vm::dismiss, title = { Text("Bản cập nhật đã xác minh") },
            text = { Text("SHA-256, mã ứng dụng, phiên bản và chứng chỉ ký khớp. Android sẽ yêu cầu bạn xác nhận cài đặt. Không cần gỡ bản đang dùng.") },
            confirmButton = { TextButton(onClick = {
                if (context.packageManager.canRequestPackageInstalls()) install(value)
                else permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            }) { Text("Cài đặt") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Để sau") } },
        )
        is UpdateUi.Message -> AlertDialog(onDismissRequest = vm::dismiss,
            title = { Text("Cập nhật Shelf") }, text = { Text(value.text) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Đóng") } })
    }
}
