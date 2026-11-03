package com.shelf.reader.update

/** Numeric version ordering shared by the release updater and policy tests. */
internal object AppVersions {
    fun isNewer(candidate: String, current: String): Boolean {
        val next = parts(candidate) ?: return false
        val installed = parts(current) ?: return false
        for (index in 0 until maxOf(next.size, installed.size)) {
            val left = next.getOrElse(index) { 0 }
            val right = installed.getOrElse(index) { 0 }
            if (left != right) return left > right
        }
        return false
    }

    private fun parts(value: String): List<Int>? {
        val clean = value.trim().removePrefix("v").removePrefix("V").substringBefore('-')
        if (clean.isBlank()) return null
        return clean.split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }
    }
}
