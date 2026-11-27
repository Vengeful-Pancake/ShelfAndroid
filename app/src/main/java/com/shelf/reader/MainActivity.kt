package com.shelf.reader

import android.net.Uri
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.shelf.reader.data.ReaderSettings
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material3.Scaffold
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.shelf.reader.ui.folders.FoldersScreen
import com.shelf.reader.ui.browser.BrowserScreen
import com.shelf.reader.ui.library.LibraryScreen
import com.shelf.reader.ui.opds.OpdsScreen
import com.shelf.reader.ui.reader.ReaderScreen
import com.shelf.reader.ui.theme.ShelfTheme
import com.shelf.reader.ui.dictionary.DictionaryScreen

object Routes {
    const val LIBRARY = "library"
    const val FOLDERS = "folders"
    const val READER = "reader/{bookId}"
    const val READER_CHAPTER = "reader/{bookId}/{chapter}?fragment={fragment}"
    const val OPDS = "opds"
    const val BROWSER = "browser"
    const val DICTIONARY = "dictionary"
    fun reader(bookId: String, chapter: Int?, fragment: String) = if (chapter == null) "reader/$bookId" else {
        "reader/$bookId/$chapter" + if (fragment.isEmpty()) "" else "?fragment=${Uri.encode(fragment)}"
    }
}

class MainActivity : ComponentActivity() {
    private var incomingEpub by mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(0x801B1B1B.toInt()),
        )
        super.onCreate(savedInstanceState)
        incomingEpub = intent.epubUri()
        setContent { ShelfTheme { ShelfNavigation(incomingEpub) { incomingEpub = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incomingEpub = intent.epubUri()
    }

    private fun Intent.epubUri(): Uri? = data?.takeIf {
        action == Intent.ACTION_VIEW && type == "application/epub+zip" && it.scheme == "content"
    }
}

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun ShelfNavigation(incomingEpub: Uri?, onImportHandled: () -> Unit) {
    val nav = rememberNavController()
    val slide = tween<Float>(durationMillis = 180)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val benchmarkBuild = BuildConfig.APPLICATION_ID.endsWith(".benchmark")
    val inReader = route == Routes.READER || route == Routes.READER_CHAPTER
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val repository = remember { ShelfApp.repository(context) }
    val settingsStore = repository.settings
    val settings by settingsStore.flow.collectAsStateWithLifecycle(initialValue = ReaderSettings())
    val scope = rememberCoroutineScope()
    var navigationSettings by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }
    val changeSettings: ((ReaderSettings) -> ReaderSettings) -> Unit = { change -> scope.launch { settingsStore.update(change) }; Unit }

    LaunchedEffect(incomingEpub) {
        if (incomingEpub == null) return@LaunchedEffect
        try {
            val id = repository.importEpub(incomingEpub)
            nav.currentBackStackEntryFlow.first()
            lifecycle.withResumed {
                if (nav.currentBackStackEntry?.destination?.route == Routes.LIBRARY) {
                    nav.navigate(Routes.reader(id, null, ""))
                } else {
                    nav.navigate(Routes.reader(id, null, "")) { popUpTo(Routes.LIBRARY) }
                }
            }
        } catch (error: Exception) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            importError = error.message ?: "Could not import the EPUB."
        }
        onImportHandled()
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = benchmarkBuild }
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(Modifier.fillMaxSize()) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
            if (!inReader && settings.navigationBarVisible) Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer)
                    .windowInsetsPadding(WindowInsets.navigationBars).height((64 * settings.navigationBarScale).coerceAtLeast(48f).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listOf(
                    Triple(Routes.LIBRARY, "Books", Icons.AutoMirrored.Outlined.MenuBook),
                    Triple(Routes.BROWSER, "Internet", Icons.Outlined.Language),
                    Triple(Routes.DICTIONARY, "Dictionary", Icons.Outlined.Translate),
                ).forEach { (destination, label, icon) ->
                    val selected = if (destination == Routes.LIBRARY) route !in listOf(Routes.BROWSER, Routes.DICTIONARY) else route == destination
                    Column(
                        modifier = Modifier.weight(1f).fillMaxHeight().selectable(selected, role = Role.Tab, onClick = {
                            if (!selected) nav.navigate(destination) {
                                popUpTo(Routes.LIBRARY) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, contentDescription = label, modifier = Modifier.size((24 * settings.navigationBarScale).dp),
                            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(label, fontSize = (12 * settings.navigationBarScale).sp, maxLines = 1)
                    }
                }
                IconButton(onClick = { navigationSettings = true }) { Icon(Icons.Outlined.Settings, "Navigation settings") }
            }
        }) { padding ->
        NavHost(
            modifier = Modifier.padding(padding),
            navController = nav,
            startDestination = Routes.LIBRARY,
            enterTransition = {
                slideInHorizontally(tween(220)) { it / 6 } + fadeIn(slide)
            },
            exitTransition = { fadeOut(slide) },
            popEnterTransition = { fadeIn(slide) },
            popExitTransition = {
                slideOutHorizontally(tween(220)) { it / 6 } + fadeOut(slide)
            },
        ) {
            composable(Routes.LIBRARY) {
                LibraryScreen(
                    onOpenBook = { id, chapter, fragment -> nav.navigate(Routes.reader(id, chapter, fragment)) },
                    onManageFolders = { nav.navigate(Routes.FOLDERS) },
                    onOpenOpds = { nav.navigate(Routes.OPDS) },
                    onOpenBrowser = { nav.navigate(Routes.BROWSER) },
                )
            }
            composable(Routes.FOLDERS) {
                FoldersScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.OPDS) {
                OpdsScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.BROWSER) {
                BrowserScreen(onBack = { nav.popBackStack() }, onNavigationSettings = { navigationSettings = true })
            }
            composable(Routes.DICTIONARY) { DictionaryScreen() }
            for (readerRoute in listOf(Routes.READER, Routes.READER_CHAPTER)) {
                val arguments = buildList {
                    add(navArgument("bookId") { type = NavType.StringType })
                    if (readerRoute == Routes.READER_CHAPTER) {
                        add(navArgument("chapter") { type = NavType.IntType })
                        add(navArgument("fragment") {
                            type = NavType.StringType
                            defaultValue = ""
                        })
                    }
                }
                composable(readerRoute, arguments = arguments) { entry ->
                    ReaderScreen(
                        bookId = entry.arguments?.getString("bookId").orEmpty(),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
        }
        }
        if (!inReader && !settings.navigationBarVisible) FilledTonalIconButton(
            onClick = { changeSettings { it.copy(navigationBarVisible = true) } },
            modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(4.dp).size(40.dp),
        ) { Icon(Icons.Outlined.KeyboardArrowUp, "Show bottom navigation") }
        }
    }

    if (navigationSettings) com.shelf.reader.ui.NavigationSettingsDialog(settings, changeSettings) { navigationSettings = false }

    importError?.let { message ->
        AlertDialog(onDismissRequest = { importError = null }, title = { Text("Could not import EPUB") },
            text = { Text(message) }, confirmButton = { TextButton(onClick = { importError = null }) { Text("Close") } })
    }

    if (!benchmarkBuild) com.shelf.reader.update.AppUpdateCoordinator()
}
