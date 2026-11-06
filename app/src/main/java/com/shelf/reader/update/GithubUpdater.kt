package com.shelf.reader.update

import android.content.Context
import com.shelf.reader.BuildConfig
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class UpdateRelease(val version: String, val notes: String, val url: String, val size: Long, val sha256: String,
    val versionCode: Long = 0, val source: String = "GitHub API")

interface UpdateClient {
    fun latest(): UpdateRelease
}

class UpdateHttpException(val status: Int, val retryAt: Long, message: String) : java.io.IOException(message)

/** Public release manifest first, legacy API second. No GitHub credentials are embedded. */
class GithubUpdater(private val context: Context) : UpdateClient {
    private val repository = BuildConfig.UPDATE_REPOSITORY
    private val networkPrefs = context.getSharedPreferences("update_network", 0)
    private fun connection(url: String, api: Boolean = false): HttpURLConnection {
        var target = url
        repeat(6) {
            require(if (api) URI(target).host == "api.github.com" && target.startsWith("https://api.github.com/") else UpdatePolicy.trustedDownload(target)) { "Địa chỉ cập nhật không đáng tin cậy" }
            val connection = (URL(target).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000; readTimeout = 20_000; instanceFollowRedirects = false
                setRequestProperty("User-Agent", "Shelf/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", if (api) "application/vnd.github+json" else "application/octet-stream")
                setRequestProperty("Cache-Control", "no-cache")
                useCaches = false
                if (api) setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            }
            val code = try { connection.responseCode } catch (error: Exception) { connection.disconnect(); throw error }
            if (code in listOf(301, 302, 303, 307, 308)) {
                val next = connection.getHeaderField("Location")
                connection.disconnect()
                require(!next.isNullOrBlank()) { "Redirect cập nhật không hợp lệ" }
                target = URI(target).resolve(next).toString()
            } else {
                if (code !in 200..299) {
                    val now = System.currentTimeMillis()
                    val reset = connection.getHeaderField("X-RateLimit-Reset")?.toLongOrNull()?.times(1000) ?: 0
                    val retry = connection.getHeaderField("Retry-After")?.toLongOrNull()?.let { now + it * 1000 } ?: 0
                    val retryAt = if (code in listOf(403, 429)) maxOf(now + 60_000, reset, retry) else now + 60_000
                    connection.disconnect()
                    if (api && code in listOf(403, 429)) networkPrefs.edit().putLong("api_retry_at", retryAt).apply()
                    throw UpdateHttpException(code, retryAt, if (code in listOf(403, 429)) "GitHub đang giới hạn truy cập (HTTP $code). Thử lại sau hoặc mở trang release." else "Không đọc được bản cập nhật (HTTP $code).")
                }
                return connection
            }
        }
        error("Quá nhiều redirect cập nhật")
    }

    private fun readJson(url: String, api: Boolean = false, limit: Int = 64_000): JSONObject {
        val connection = connection(url, api)
        val bytes = try { connection.inputStream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= limit) { "Thông tin cập nhật quá lớn" }; output.write(buffer, 0, n) }
            output.toByteArray()
        } } finally { connection.disconnect() }
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    override fun latest(): UpdateRelease {
        try {
            // Public release assets do not consume the anonymous REST API quota shared by an IP.
            return decodeManifest(readJson(UpdatePolicy.manifestUrl(repository)), repository, context.packageName)
        } catch (missing: UpdateHttpException) { if (missing.status != 404) throw missing }
        val now = System.currentTimeMillis()
        val retryAt = networkPrefs.getLong("api_retry_at", 0)
        if (now < retryAt) throw UpdateHttpException(429, retryAt, "GitHub API đang giới hạn truy cập. Bản release cũ chưa có update.json; thử lại sau hoặc tải APK từ trang release.")
        val json = readJson("https://api.github.com/repos/$repository/releases/latest", api = true, limit = 1_000_000)
        require(!json.optBoolean("draft") && !json.optBoolean("prerelease")) { "Release chưa được phát hành chính thức" }
        val version = UpdatePolicy.stableVersion(json.optString("tag_name")) ?: error("Phiên bản release không hợp lệ")
        val assets = json.optJSONArray("assets") ?: error("Release chưa có APK")
        for (i in 0 until assets.length()) {
            val item = assets.getJSONObject(i)
            if (item.optString("name") != "Shelf-$version.apk") continue
            val url = item.optString("browser_download_url")
            val sha = UpdatePolicy.checksum(item.optString("digest")) ?: error("Release thiếu SHA-256; không tải bản chưa xác minh")
            val size = item.optLong("size")
            require(UpdatePolicy.releaseAsset(url, repository) && size in 1..UpdatePolicy.MAX_APK_BYTES)
            return UpdateRelease(version, json.optString("body").take(5000), url, size, sha)
        }
        error("Release $version thiếu APK Shelf-$version.apk; không thể xác nhận cập nhật")
    }

    companion object {
        internal fun decodeManifest(json: JSONObject, repository: String, packageName: String): UpdateRelease {
            require(json.getInt("schemaVersion") == 1 && json.getString("packageName") == packageName) { "Thông tin cập nhật không dành cho ứng dụng này" }
            val version = json.getString("versionName")
            require(UpdatePolicy.stableVersion(version) == version)
            val url = json.getString("apkUrl")
            val size = json.getLong("apkSize")
            val code = json.getLong("versionCode")
            val sha = UpdatePolicy.checksum(json.getString("sha256"))
            require(sha != null) { "Release thiếu SHA-256 hợp lệ" }
            require(url == UpdatePolicy.apkUrl(repository, version) && UpdatePolicy.releaseAsset(url, repository) && size in 1..UpdatePolicy.MAX_APK_BYTES && code > 0) { "Thông tin APK không hợp lệ" }
            return UpdateRelease(version, json.optString("releaseNotes").take(5000), url, size, sha, code, "GitHub release · update.json")
        }
    }
}
