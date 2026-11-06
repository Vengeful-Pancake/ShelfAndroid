package com.shelf.reader.update

import android.app.Application
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.shelf.reader.BuildConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class UpdateIdentity(val version: String, val code: Long, val debug: Boolean)
data class UpdateStatus(val checking: Boolean = false, val lastAttempt: Long = 0, val lastSuccess: Long = 0,
    val latestVersion: String = "", val source: String = "", val error: String = "")

class UpdateViewModel internal constructor(app: Application, private val client: UpdateClient,
    private val identity: UpdateIdentity, private val prefs: SharedPreferences, private val clock: () -> Long) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, GithubUpdater(app), UpdateIdentity(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.DEBUG),
        app.getSharedPreferences("release_updates", 0), System::currentTimeMillis)
    private val mutable = MutableStateFlow<UpdateUi>(UpdateUi.Idle)
    val state = mutable.asStateFlow()
    private val statusMutable = MutableStateFlow(UpdateStatus(lastAttempt = prefs.getLong("last_attempt", 0), lastSuccess = prefs.getLong("last_success", 0),
        latestVersion = prefs.getString("latest_version", "").orEmpty(), source = prefs.getString("source", "").orEmpty(), error = prefs.getString("error", "").orEmpty()))
    val status = statusMutable.asStateFlow()
    private var work: Job? = null
    private var generation = 0
    private var lastAttempt: Long? = null // A new process/build always performs its own first check.
    private var succeeded = false
    private var retryAt = 0L
    private var notifyResult = false

    fun check(force: Boolean = false, foreground: Boolean = false) {
        if (identity.debug) { if (force) mutable.value = UpdateUi.Message("Bản debug (${identity.version}) không tự cài APK release. Hãy dùng APK Shelf từ mục Releases."); return }
        if (work?.isActive == true) { if (statusMutable.value.checking && force) { notifyResult = true; mutable.value = UpdateUi.Checking }; return }
        if (mutable.value !is UpdateUi.Idle) return
        val now = clock()
        if (!UpdateSchedule.due(now, lastAttempt, succeeded, retryAt, force, foreground)) return
        lastAttempt = now; notifyResult = force
        if (force) mutable.value = UpdateUi.Checking
        val token = ++generation
        statusMutable.value = statusMutable.value.copy(checking = true, lastAttempt = now)
        prefs.edit().putLong("last_attempt", now).apply()
        work = viewModelScope.launch(Dispatchers.Main.immediate) {
            try {
                val release = withContext(Dispatchers.IO) { client.latest() }
                if (token != generation) return@launch
                val newer = AppVersions.isNewer(release.version, identity.version)
                require(!newer || release.versionCode == 0L || release.versionCode > identity.code) { "Release có số phiên bản mới nhưng mã build không tăng; chưa thể cài cập nhật." }
                val completed = clock()
                succeeded = true; retryAt = 0
                prefs.edit().putLong("last_success", completed).putString("latest_version", release.version).putString("source", release.source).remove("error").apply()
                statusMutable.value = UpdateStatus(false, now, completed, release.version, release.source)
                mutable.value = when {
                    newer -> UpdateUi.Available(release)
                    notifyResult -> UpdateUi.Message("Không có bản mới hơn.\nĐang cài: ${identity.version} (${identity.code})\nGitHub: ${release.version}")
                    else -> UpdateUi.Idle
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (token != generation) return@launch
                succeeded = false; retryAt = (error as? UpdateHttpException)?.retryAt ?: 0
                val message = error.message ?: "Không kiểm tra được cập nhật"
                prefs.edit().putString("error", message).apply()
                statusMutable.value = statusMutable.value.copy(checking = false, error = message)
                if (notifyResult) mutable.value = UpdateUi.Message(message)
            }
        }
    }
    fun dismiss() { generation++; work?.cancel(); statusMutable.value = statusMutable.value.copy(checking = false); mutable.value = UpdateUi.Idle }
    fun error(text: String) { mutable.value = UpdateUi.Message(text) }
}
