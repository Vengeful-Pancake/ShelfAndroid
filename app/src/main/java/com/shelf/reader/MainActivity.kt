package com.shelf.reader

import android.os.Bundle
import androidx.activity.ComponentActivity
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
    const val OPDS = "opds"
    const val BROWSER = "browser"
    const val DICTIONARY = "dictionary"
    fun reader(bookId: String) = "reader/$bookId"
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { ShelfTheme { ShelfNavigation() } }
    }
}

@Composable
private fun ShelfNavigation() {
    val nav = rememberNavController()
    val slide = tween<Float>(durationMillis = 180)
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background,
    ) {
        Scaffold(contentWindowInsets = WindowInsets(0, 0, 0, 0), bottomBar = {
            NavigationBar {
                listOf(
                    Triple(Routes.LIBRARY, "Books", Icons.AutoMirrored.Outlined.MenuBook),
                    Triple(Routes.BROWSER, "Internet", Icons.Outlined.Language),
                    Triple(Routes.DICTIONARY, "Dictionary", Icons.Outlined.Translate),
                ).forEach { (destination, label, icon) ->
                    val selected = if (destination == Routes.LIBRARY) route !in listOf(Routes.BROWSER, Routes.DICTIONARY) else route == destination
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            if (!selected) nav.navigate(destination) {
                                popUpTo(Routes.LIBRARY) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(icon, contentDescription = label) },
                        label = { Text(label) },
                    )
                }
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
                    onOpenBook = { nav.navigate(Routes.reader(it)) },
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
                BrowserScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.DICTIONARY) { DictionaryScreen() }
            composable(
                route = Routes.READER,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
            ) { entry ->
                ReaderScreen(
                    bookId = entry.arguments?.getString("bookId").orEmpty(),
                    onBack = { nav.popBackStack() },
                )
            }
        }
        }
    }

    com.shelf.reader.update.AppUpdateCoordinator()
}
