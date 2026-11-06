package com.shelf.reader.update

import java.net.URI

internal object UpdatePolicy {
    const val MAX_APK_BYTES = 100L * 1024 * 1024
    fun manifestUrl(repository: String) = "https://github.com/$repository/releases/latest/download/update.json"
    fun apkUrl(repository: String, version: String) = "https://github.com/$repository/releases/download/v$version/Shelf-$version.apk"
    fun releasePageUrl(repository: String, version: String): String {
        require(repository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")))
        require(stableVersion(version) == version)
        return "https://github.com/$repository/releases/tag/v$version"
    }
    fun stableVersion(tag: String): String? = tag.removePrefix("v").removePrefix("V")
        .takeIf { it.matches(Regex("[0-9]+(\\.[0-9]+){1,3}")) }
    fun checksum(digest: String): String? = digest.removePrefix("sha256:").lowercase()
        .takeIf { it.matches(Regex("[0-9a-f]{64}")) }
    fun trustedDownload(url: String): Boolean = runCatching {
        val uri = URI(url)
        val host = uri.host.orEmpty().lowercase()
        uri.scheme == "https" && uri.userInfo == null && uri.port in listOf(-1, 443) &&
            (host == "github.com" || host.endsWith(".githubusercontent.com"))
    }.getOrDefault(false)
    fun releaseAsset(url: String, repository: String): Boolean = runCatching {
        val uri = URI(url)
        trustedDownload(url) && uri.host == "github.com" &&
            uri.path.startsWith("/$repository/releases/download/") && uri.path.endsWith(".apk") && uri.query == null
    }.getOrDefault(false)
}
