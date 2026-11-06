package com.shelf.reader.update

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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shelf.reader.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

object UpdateRequests {
    private val pending = Channel<Unit>(Channel.CONFLATED)
    val checks = pending.receiveAsFlow()
    val info = MutableStateFlow(false)
    fun checkNow() { pending.trySend(Unit) }
    fun showInfo() { info.value = true }
}
sealed interface UpdateUi {
    data object Idle : UpdateUi
    data object Checking : UpdateUi
    data class Available(val release: UpdateRelease) : UpdateUi
    data class Downloading(val release: UpdateRelease, val progress: Float) : UpdateUi
    data class Ready(val release: UpdateRelease, val file: File) : UpdateUi
    data class Message(val text: String) : UpdateUi
}

@Composable
fun AppUpdateCoordinator(vm: UpdateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val info by UpdateRequests.info.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val scope = rememberCoroutineScope()
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.check(foreground = true)
            while (isActive) { delay(60_000L); vm.check() }
        }
    }
    LaunchedEffect(vm) { UpdateRequests.checks.collect { vm.check(force = true) } }

    fun install(ready: UpdateUi.Ready) { scope.launch(Dispatchers.Main.immediate) {
        try {
            withContext(Dispatchers.IO) { GithubUpdater(context).verifyPackage(ready.file, ready.release.version, ready.release.versionCode) }
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
        UpdateUi.Checking -> AlertDialog(onDismissRequest = vm::dismiss, title = { Text("Đang kiểm tra cập nhật") },
            text = { Column { Text("Shelf ${BuildConfig.VERSION_NAME}"); LinearProgressIndicator() } },
            confirmButton = {}, dismissButton = { TextButton(onClick = vm::dismiss) { Text("Hủy") } })
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
                else runCatching { permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))) }
                    .onFailure { vm.error(it.message ?: "Không mở được quyền cài cập nhật") }
            }) { Text("Cài đặt") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Để sau") } },
        )
        is UpdateUi.Message -> AlertDialog(onDismissRequest = vm::dismiss,
            title = { Text("Cập nhật Shelf") }, text = { Text(value.text) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Đóng") } })
    }
    if (info) AppVersionDialog(status, checking = status.checking || state is UpdateUi.Downloading,
        onDismiss = { UpdateRequests.info.value = false }, onCheck = {
            UpdateRequests.info.value = false; vm.check(force = true)
        })
}
