package com.shelf.reader.ui.dictionary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.shelf.reader.ShelfApp
import com.shelf.reader.translation.NameStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DictionaryScreen() {
    val context = LocalContext.current
    val store = remember { NameStore.get(context) }
    val revision by store.revision.collectAsState()
    val scope = rememberCoroutineScope()
    var global by rememberSaveable { mutableStateOf(true) }
    var globalDraft by rememberSaveable { mutableStateOf("") }
    var storyDraft by rememberSaveable { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var savedGlobal by rememberSaveable { mutableStateOf("") }
    var savedStory by rememberSaveable { mutableStateOf("") }
    var savedStoryKey by rememberSaveable { mutableStateOf("") }
    val story = store.activeKey()
    LaunchedEffect(revision, story) {
        val settings = ShelfApp.repository(context).settings.current()
        val latestGlobal = store.global(settings.globalNames)
        val latestStory = store.story(story)
        if (!loaded || globalDraft == savedGlobal) globalDraft = latestGlobal
        if (!loaded || savedStoryKey != story || storyDraft == savedStory) storyDraft = latestStory
        savedGlobal = latestGlobal; savedStory = latestStory; savedStoryKey = story; loaded = true
    }
    fun save() { scope.launch {
        runCatching { withContext(Dispatchers.IO) { store.save(story, global, if (global) globalDraft else storyDraft) } }
            .onSuccess { status = "Đã lưu và chạy" }.onFailure { status = it.message ?: "Không thể lưu" }
    } }
    Column(Modifier.fillMaxSize().background(Color(0xff191919)).statusBarsPadding().padding(8.dp)) {
        Text("Name", color = Color.LightGray, style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth().background(Color(0xff303b3b))) {
            TextButton(onClick = ::save, enabled = loaded) { Text("Lưu lại", color = Color.LightGray) }
            TextButton(onClick = ::save, enabled = loaded) { Text("Chạy", color = Color.LightGray) }
            Spacer(Modifier.weight(1f))
            Text("JSON local", color = Color.Gray, modifier = Modifier.padding(12.dp))
        }
        Row {
            TextButton(onClick = { global = true }) { Text("Global", color = if (global) Color(0xffdf79df) else Color.LightGray) }
            TextButton(onClick = { global = false }, enabled = story.isNotBlank()) {
                Text("Riêng truyện", color = if (!global) Color(0xffdf79df) else Color.LightGray)
            }
        }
        Text(if (global) "Áp dụng cho tất cả truyện" else store.activeTitle(), color = Color.Gray, modifier = Modifier.padding(4.dp))
        OutlinedTextField(
            value = if (global) globalDraft else storyDraft,
            onValueChange = { if (global) globalDraft = it else storyDraft = it; status = "Chưa lưu" },
            modifier = Modifier.fillMaxWidth().weight(1f),
            textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, color = Color.LightGray),
            placeholder = { Text("\$[张三]=Trương Tam", color = Color.Gray) },
        )
        Text(status.ifBlank { "\$[cụm từ]=bản dịch · Chạy theo thứ tự dòng · Riêng ưu tiên hơn global" }, color = Color.LightGray, modifier = Modifier.padding(8.dp))
    }
}
