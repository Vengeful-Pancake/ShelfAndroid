package com.shelf.reader.update

import android.content.Intent
import android.net.Uri
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
    data class Message(val text: String) : UpdateUi
}

@Composable
fun AppUpdateCoordinator(vm: UpdateViewModel = viewModel(), openRelease: ((String) -> Unit)? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val info by UpdateRequests.info.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.check(foreground = true)
            while (isActive) { delay(60_000L); vm.check() }
        }
    }
    LaunchedEffect(vm) { UpdateRequests.checks.collect { vm.check(force = true) } }

    when (val value = state) {
        UpdateUi.Idle -> Unit
        UpdateUi.Checking -> AlertDialog(onDismissRequest = vm::dismiss, title = { Text("Đang kiểm tra cập nhật") },
            text = { Column { Text("Shelf ${BuildConfig.VERSION_NAME}"); LinearProgressIndicator() } },
            confirmButton = {}, dismissButton = { TextButton(onClick = vm::dismiss) { Text("Hủy") } })
        is UpdateUi.Available -> AlertDialog(
            onDismissRequest = vm::dismiss,
            title = { Text("Shelf ${value.release.version} đã có bản mới") },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("Bản đang dùng: ${BuildConfig.VERSION_NAME}\nTải khoảng ${value.release.size / 1024 / 1024} MB. Mở trang GitHub trong trình duyệt để tải APK và xác nhận cài bằng Android. Shelf không yêu cầu quyền cài ứng dụng. Cài đè để giữ sách và Name đã lưu.")
                Spacer(Modifier.height(12.dp)); Text(value.release.notes)
            } },
            confirmButton = { TextButton(onClick = {
                try {
                    val url = UpdatePolicy.releasePageUrl(BuildConfig.UPDATE_REPOSITORY, value.release.version)
                    if (openRelease != null) openRelease(url)
                    else context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
                    vm.dismiss()
                } catch (_: Exception) { vm.error("Không mở được trình duyệt. Bạn có thể tải APK tại github.com/${BuildConfig.UPDATE_REPOSITORY}/releases.") }
            }) { Text("Tải qua trình duyệt") } },
            dismissButton = { TextButton(onClick = vm::dismiss) { Text("Để sau") } },
        )
        is UpdateUi.Message -> AlertDialog(onDismissRequest = vm::dismiss,
            title = { Text("Cập nhật Shelf") }, text = { Text(value.text) },
            confirmButton = { TextButton(onClick = vm::dismiss) { Text("Đóng") } })
    }
    if (info) AppVersionDialog(status, checking = status.checking,
        onDismiss = { UpdateRequests.info.value = false }, onCheck = {
            UpdateRequests.info.value = false; vm.check(force = true)
        })
}
