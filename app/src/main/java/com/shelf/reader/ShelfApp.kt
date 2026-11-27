package com.shelf.reader

import android.app.Application
import android.content.Context
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import com.shelf.reader.data.ShelfDatabase
import com.shelf.reader.data.ShelfRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor

class ShelfApp : Application() {

    val scope = CoroutineScope(SupervisorJob())

    val repository: ShelfRepository by lazy {
        ShelfRepository(this, ShelfDatabase.get(this).dao(), scope)
    }
    internal val readerWebViewStartup: Deferred<Unit> by lazy {
        CompletableDeferred<Unit>().also { ready ->
            try {
                WebViewCompat.startUpWebView(this, WebViewStartUpConfig.Builder(Dispatchers.IO.asExecutor()).build(),
                    object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                        override fun onResult(result: WebViewStartUpResult) { ready.complete(Unit) }
                        override fun onError(error: WebViewStartupException) { ready.complete(Unit) }
                    },
                )
            } catch (_: IllegalStateException) {
                ready.complete(Unit)
            }
        }
    }

    companion object {
        fun repository(context: Context): ShelfRepository =
            (context.applicationContext as ShelfApp).repository
    }
}
