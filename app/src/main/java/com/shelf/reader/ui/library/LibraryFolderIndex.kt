package com.shelf.reader.ui.library

import com.shelf.reader.data.BookCard
import com.shelf.reader.data.BookFolders
import com.shelf.reader.data.RootEntity

data class LibraryFolderEntry(val uri: String, val rootName: String, val path: List<String>, val books: Int) {
    val name: String get() = path.lastOrNull() ?: rootName
    val category get() = LibraryCategory.Folder(uri, rootName, path)
}

internal object LibraryFolderIndex {
    fun includes(bookPath: List<String>?, currentPath: List<String>, recursive: Boolean): Boolean {
        if (bookPath == null) return currentPath.isEmpty()
        return if (recursive) bookPath.take(currentPath.size) == currentPath else bookPath == currentPath
    }

    fun children(roots: List<RootEntity>, books: List<BookCard>, category: LibraryCategory): List<LibraryFolderEntry> {
        if (category == LibraryCategory.Folders) return roots.filter { it.enabled }.map { root ->
            LibraryFolderEntry(root.treeUri, root.name, emptyList(), books.count { it.treeUri == root.treeUri })
        }.sortedBy { it.name.lowercase() }
        if (category !is LibraryCategory.Folder) return emptyList()
        val paths = books.filter { it.treeUri == category.uri }.mapNotNull { BookFolders.decode(it.folderPath) }
            .filter { it.size > category.path.size && it.take(category.path.size) == category.path }
        return paths.groupingBy { it.take(category.path.size + 1) }.eachCount().map { (path, count) ->
            LibraryFolderEntry(category.uri, category.name, path, count)
        }.sortedBy { it.name.lowercase() }
    }
}
