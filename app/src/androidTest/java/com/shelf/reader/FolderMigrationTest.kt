package com.shelf.reader

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.shelf.reader.data.ShelfDatabase
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FolderMigrationTest {
    @Test fun upgradeFromPublishedSchemaKeepsBooksAndProgressAndDefaultsToRecursiveScanning() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val name = "folder-migration-test.db"
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(instrumentation.context.assets.open("com.shelf.reader.data.ShelfDatabase/3.json").bufferedReader().use { it.readText() }).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                db.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indexes = entity.getJSONArray("indices")
                for (j in 0 until indexes.length()) db.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO roots VALUES ('tree','My books',1,123)")
            db.execSQL("INSERT INTO books (id,docUri,treeUri,source,filename,folder,stamp,size,added,title,author,series,language,publisher,description,chapters,broken) VALUES ('book','doc','tree','My books','book.epub','Subfolder','stamp',100,123,'Saved book','','','','','',10,0)")
            db.execSQL("INSERT INTO progress VALUES ('book',3,0.25,'Saved chapter',999)")
            db.version = 3
        }
        val migrated = Room.databaseBuilder(context, ShelfDatabase::class.java, name).addMigrations(ShelfDatabase.MIGRATION_3_4).build()
        try {
            assertTrue(migrated.dao().roots().single().includeSubfolders)
            assertEquals("Saved book", migrated.dao().book("book")!!.title)
            assertNull(migrated.dao().book("book")!!.folderPath)
            assertEquals(3, migrated.dao().progress("book")!!.chapter)
            assertEquals(.25f, migrated.dao().progress("book")!!.offset)
        } finally { migrated.close(); context.deleteDatabase(name) }
    }
}
