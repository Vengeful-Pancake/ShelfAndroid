package com.shelf.reader

import android.app.Application
import android.content.Context
import com.shelf.reader.data.ShelfDatabase
import com.shelf.reader.data.ShelfRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

class ShelfApp : Application() {

    val scope = CoroutineScope(SupervisorJob())

    val repository: ShelfRepository by lazy {
        ShelfRepository(this, ShelfDatabase.get(this).dao(), scope)
    }

    companion object {
        fun repository(context: Context): ShelfRepository =
            (context.applicationContext as ShelfApp).repository
    }
}
