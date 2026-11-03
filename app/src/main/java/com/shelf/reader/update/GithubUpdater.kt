package com.shelf.reader.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.shelf.reader.BuildConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest

data class UpdateRelease(val version: String, val notes: String, val url: String, val size: Long, val sha256: String)

/** Anonymous public-release API only. Never embeds GitHub credentials in the app. */
class GithubUpdater(private val context: Context) {
    private val repository = BuildConfig.UPDATE_REPOSITORY
    private fun connection(url: String, api: Boolean = false): HttpURLConnection {
        var target = url
        repeat(6) {
            require(if (api) URI(target).host == "api.github.com" && target.startsWith("https://api.github.com/") else UpdatePolicy.trustedDownload(target)) { "Địa chỉ cập nhật không đáng tin cậy" }
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Shelf/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                if (api) setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = connection.responseCode
            if (code in listOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                require(!next.isNullOrBlank()) { "Redirect cập nhật không hợp lệ" }
                target = URI(target).resolve(next).toString()
            } else {
                if (code !in 200..299) { connection.disconnect(); error("GitHub HTTP $code; chưa có release hoặc không truy cập được") }
                return connection
            }
        }
        error("Quá nhiều redirect cập nhật")
    }

    fun latest(): UpdateRelease? {
        val connection = connection("https://api.github.com/repos/$repository/releases/latest", api = true)
        val bytes = try { connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 1_000_000); output.write(buffer, 0, n) }
            output.toByteArray()
        } } finally { connection.disconnect() }
        val json = JSONObject(String(bytes, Charsets.UTF_8))
        if (json.optBoolean("draft") || json.optBoolean("prerelease")) return null
        val version = UpdatePolicy.stableVersion(json.optString("tag_name")) ?: return null
        if (!AppVersions.isNewer(version, BuildConfig.VERSION_NAME)) return null
        val assets = json.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val item = assets.getJSONObject(i)
            if (item.optString("name") != "Shelf-$version.apk") continue
            val url = item.optString("browser_download_url")
            val sha = UpdatePolicy.checksum(item.optString("digest")) ?: error("Release thiếu SHA-256; không tải bản chưa xác minh")
            val size = item.optLong("size")
            require(UpdatePolicy.releaseAsset(url, repository) && size in 1..UpdatePolicy.MAX_APK_BYTES)
            return UpdateRelease(version, json.optString("body").take(5000), url, size, sha)
        }
        return null
    }

    suspend fun download(release: UpdateRelease, progress: (Float) -> Unit): File {
        require(UpdatePolicy.releaseAsset(release.url, repository))
        require(UpdatePolicy.checksum(release.sha256) != null && release.size in 1..UpdatePolicy.MAX_APK_BYTES)
        require(UpdatePolicy.stableVersion(release.version) == release.version)
        val directory = File(context.cacheDir, "updates").apply { check(isDirectory || mkdirs()) }
        val target = File(directory, "Shelf-${release.version}.apk")
        val partial = File(directory, "Shelf-${release.version}.pending.apk")
        val connection = connection(release.url)
        try {
            val advertised = connection.contentLengthLong
            require(advertised < 0 || advertised == release.size) { "Kích thước APK không khớp" }
            var total = 0L
            val digest = MessageDigest.getInstance("SHA-256")
            connection.inputStream.use { input -> partial.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    currentCoroutineContext().ensureActive()
                    total += n
                    require(total <= release.size && total <= UpdatePolicy.MAX_APK_BYTES) { "APK vượt giới hạn" }
                    digest.update(buffer, 0, n); output.write(buffer, 0, n)
                    progress(total.toFloat() / release.size)
                }
            } }
            require(total == release.size) { "APK tải chưa đủ" }
            require(digest.digest().hex() == release.sha256) { "SHA-256 không khớp; đã từ chối APK" }
            verifyPackage(partial, release.version)
            currentCoroutineContext().ensureActive()
            check(partial.renameTo(target)) { "Không thể lưu APK" }
            return target
        } finally { connection.disconnect(); partial.delete() }
    }

    @Suppress("DEPRECATION")
    fun verifyPackage(file: File, version: String) {
        val manager = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val apk = manager.getPackageArchiveInfo(file.absolutePath, flags) ?: error("Không phải APK hợp lệ")
        val installed = manager.getPackageInfo(context.packageName, flags)
        require(apk.packageName == context.packageName && apk.versionName == version) { "APK không phải bản cập nhật Shelf này" }
        val nextCode = if (Build.VERSION.SDK_INT >= 28) apk.longVersionCode else apk.versionCode.toLong()
        val currentCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        require(nextCode > currentCode) { "Đã từ chối bản cũ hoặc trùng phiên bản" }
        require((apk.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) { "Không cài bản debug" }
        val nextSignatures = if (Build.VERSION.SDK_INT >= 28) apk.signingInfo?.apkContentsSigners else apk.signatures
        val ownSignatures = if (Build.VERSION.SDK_INT >= 28) installed.signingInfo?.apkContentsSigners else installed.signatures
        val next = nextSignatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        val own = ownSignatures.orEmpty().map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).hex() }.toSet()
        require(own.isNotEmpty() && next == own) { "Chữ ký khác bản đã cài; đã từ chối APK" }
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}
