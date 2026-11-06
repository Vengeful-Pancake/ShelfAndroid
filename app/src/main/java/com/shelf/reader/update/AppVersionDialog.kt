package com.shelf.reader.update

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import com.shelf.reader.BuildConfig
import java.text.DateFormat
import java.util.Date

@Composable
fun AppVersionLabel() {
    Text("Shelf ${BuildConfig.VERSION_NAME} · ${if (BuildConfig.DEBUG) "debug" else "release"}",
        modifier = Modifier.clickable(role = Role.Button, onClick = UpdateRequests::showInfo).padding(vertical = 4.dp),
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
}

@Composable
internal fun AppVersionDialog(status: UpdateStatus, checking: Boolean, onDismiss: () -> Unit, onCheck: () -> Unit) {
    val context = LocalContext.current
    fun time(value: Long) = if (value == 0L) "Chưa kiểm tra" else DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Phiên bản và cập nhật") }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Shelf ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.titleMedium)
            Text("${context.packageName} · ${if (BuildConfig.DEBUG) "debug — không tự cập nhật APK release" else "release"}")
            Text("Nguồn: ${BuildConfig.UPDATE_REPOSITORY}")
            Text("Đã thử: ${time(status.lastAttempt)}")
            Text("Kiểm tra thành công: ${time(status.lastSuccess)}")
            Text("Release gần nhất: ${status.latestVersion.ifBlank { "Chưa xác định" }}")
            if (status.source.isNotBlank()) Text(status.source)
            if (checking) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("Đang kiểm tra…") }
            if (status.error.isNotBlank()) Text("Lỗi gần nhất: ${status.error}", color = MaterialTheme.colorScheme.error)
            Text("Shelf không có quyền cài ứng dụng. Tải APK qua trình duyệt và xác nhận cài bằng Android.")
            Text("Tự kiểm tra khi mở app và mỗi 15 phút khi đang dùng. Trang release có APK, source và SHA-256. Giữ Play Protect bật; cài đè bằng APK cùng chữ ký để giữ dữ liệu.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/latest"))) } }) { Text("Mở trang release") }
        }
    }, confirmButton = { TextButton(onClick = onCheck, enabled = !checking) { Text("Kiểm tra cập nhật") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } })
}
