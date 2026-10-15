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
import com.shelf.reader.update.AppUpdateCoordinator

object Routes {
    const val LIBRARY = "library"
    const val FOLDERS = "folders"
    const val READER = "reader/{bookId}"
    const val OPDS = "opds"
    const val BROWSER = "browser"
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

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        color = MaterialTheme.colorScheme.background,
    ) {
        NavHost(
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

    AppUpdateCoordinator()
}
