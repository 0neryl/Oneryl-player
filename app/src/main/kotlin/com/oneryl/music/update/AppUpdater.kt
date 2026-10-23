package com.oneryl.music.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.edit
import com.oneryl.music.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** A published version of the app and its APK. */
@Serializable
data class Release(
    val version: String,
    val apkUrl: String,
    val sizeBytes: Long,
    /** Hex SHA-256 of the APK, as GitHub reports it. */
    val sha256: String? = null,
)

sealed interface UpdateState {
    /** Not checked yet. */
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data object CheckFailed : UpdateState

    /** A state about one newer [release]. */
    sealed interface ForRelease : UpdateState {
        val release: Release
    }

    data class Available(override val release: Release) : ForRelease
    data class Downloading(override val release: Release, val progress: Float) : ForRelease
    data class Installing(override val release: Release) : ForRelease

    /** Android wants the user to confirm the installation with [confirm]. */
    data class NeedsConfirmation(override val release: Release, val confirm: Intent) : ForRelease
    data class Failed(override val release: Release, val step: Step, val detail: String? = null) : ForRelease

    enum class Step { DOWNLOAD, INSTALL }
}

/**
 * Keeps the app up to date from its GitHub releases. The latest release is
 * checked every few hours while the app is open; an update is downloaded
 * when the user asks, checked against the SHA-256 GitHub publishes, and
 * installed by Android's package installer. From Android 12 the app updates
 * itself without a confirmation; before that Android asks. Android installs
 * only an APK signed with the same key as the installed app, so no one but
 * the releases' author can replace it.
 */
class AppUpdater(
    private val context: Context,
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
    val currentVersion: String = BuildConfig.VERSION_NAME,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val downloads = File(context.cacheDir, "updates")
    private var job: Job? = null

    private val _state = MutableStateFlow(savedState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _dismissed = MutableStateFlow(preferences.getString(KEY_DISMISSED, null))

    /** The version the user put off; Home stops offering it, Library still does. */
    val dismissed: StateFlow<String?> = _dismissed.asStateFlow()

    init {
        // Whatever was downloaded before is installed or given up on by now.
        scope.launch(io) { downloads.deleteRecursively() }
    }

    /** Checks for a new version unless that was done a little while ago. */
    fun checkIfDue() {
        if (System.currentTimeMillis() - preferences.getLong(KEY_CHECKED_AT, 0) >= CHECK_INTERVAL_MS) check(quiet = true)
    }

    /** Asks GitHub for the latest release. A [quiet] check shows nothing while it runs or when it fails. */
    fun check(quiet: Boolean = false) {
        // An update being installed or waiting for the user's word stays on screen.
        val current = state.value
        if (job?.isActive == true || current is UpdateState.Installing || current is UpdateState.NeedsConfirmation) return
        if (!quiet) _state.value = UpdateState.Checking
        job = scope.launch {
            val release = try {
                withContext(io) { fetchLatest() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not check for updates: ${e.javaClass.simpleName}")
                if (!quiet) _state.value = UpdateState.CheckFailed
                return@launch
            }
            preferences.edit {
                putLong(KEY_CHECKED_AT, System.currentTimeMillis())
                putString(KEY_LATEST, release?.let { json.encodeToString(Release.serializer(), it) })
            }
            _state.value = stateFor(release)
        }
    }

    /** Stops offering the new version on Home. */
    fun dismiss() {
        val version = (state.value as? UpdateState.ForRelease)?.release?.version ?: return
        preferences.edit { putString(KEY_DISMISSED, version) }
        _dismissed.value = version
    }

    /** Downloads the new version and has Android install it over this one. */
    fun update() {
        val release = (state.value as? UpdateState.ForRelease)?.release ?: return
        if (job?.isActive == true) return
        job = scope.launch {
            _state.value = UpdateState.Downloading(release, 0f)
            val apk = try {
                download(release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not download ${release.version}: ${e.javaClass.simpleName}")
                _state.value = UpdateState.Failed(release, UpdateState.Step.DOWNLOAD)
                return@launch
            }
            _state.value = UpdateState.Installing(release)
            try {
                install(apk, release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Could not start installing ${release.version}", e)
                _state.value = UpdateState.Failed(release, UpdateState.Step.INSTALL, e.message)
            }
        }
    }

    internal fun onConfirmationNeeded(confirm: Intent) {
        releaseInProgress()?.let { _state.value = UpdateState.NeedsConfirmation(it, confirm) }
    }

    internal fun onInstallCancelled() {
        preferences.edit { remove(KEY_INSTALLING) }
        releaseInProgress()?.let { _state.value = UpdateState.Available(it) }
    }

    internal fun onInstallFailed(detail: String?) {
        preferences.edit { remove(KEY_INSTALLING) }
        releaseInProgress()?.let { _state.value = UpdateState.Failed(it, UpdateState.Step.INSTALL, detail) }
    }

    private fun releaseInProgress(): Release? =
        (state.value as? UpdateState.ForRelease)?.release ?: savedRelease()?.takeIf { isNewer(it.version, currentVersion) }

    private fun fetchLatest(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$REPOSITORY/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        return client.newCall(request).execute().use { response ->
            // A repository with no releases yet answers 404.
            if (response.code == 404) return null
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            parseRelease(response.body.string())
        }
    }

    private suspend fun download(release: Release): File = withContext(io) {
        downloads.deleteRecursively()
        downloads.mkdirs()
        val file = File(downloads, "Oneryl-${release.version}.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            response.body.byteStream().use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    var done = 0L
                    while (true) {
                        ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        digest.update(buffer, 0, read)
                        done += read
                        if (total > 0) _state.value = UpdateState.Downloading(release, (done.toFloat() / total).coerceAtMost(1f))
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (release.sha256 != null && !actual.equals(release.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("The APK does not match its SHA-256")
        }
        file
    }

    private suspend fun install(apk: File, release: Release) = withContext(io) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            // An app updating itself may skip the confirmation from Android 12.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        try {
            installer.openSession(sessionId).use { session ->
                session.openWrite("base.apk", 0, apk.length()).use { output ->
                    apk.inputStream().use { it.copyTo(output) }
                    session.fsync(output)
                }
                preferences.edit(commit = true) { putString(KEY_INSTALLING, release.version) }
                val status = Intent(context, UpdateInstallReceiver::class.java)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                session.commit(PendingIntent.getBroadcast(context, sessionId, status, flags).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(sessionId)
            throw e
        }
    }

    private fun stateFor(release: Release?): UpdateState =
        if (release != null && isNewer(release.version, currentVersion)) UpdateState.Available(release) else UpdateState.UpToDate

    private fun savedRelease(): Release? =
        preferences.getString(KEY_LATEST, null)?.let { runCatching { json.decodeFromString(Release.serializer(), it) }.getOrNull() }

    private fun savedState(): UpdateState =
        if (preferences.getLong(KEY_CHECKED_AT, 0) == 0L) UpdateState.Idle else stateFor(savedRelease())

    companion object {
        /** Where the releases are published. */
        const val REPOSITORY = "0neryl/Oneryl-player"

        private const val TAG = "AppUpdater"
        private const val PREFERENCES = "updates"
        private const val KEY_CHECKED_AT = "checked_at"
        private const val KEY_LATEST = "latest"
        private const val KEY_DISMISSED = "dismissed"
        private const val KEY_INSTALLING = "installing"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
        private const val BUFFER_BYTES = 64 * 1024

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * The version Android just installed, if this app installed it: forgets
         * it, so asking again answers null.
         */
        internal fun takeInstalledVersion(context: Context): String? {
            val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            val version = preferences.getString(KEY_INSTALLING, null) ?: return null
            preferences.edit { remove(KEY_INSTALLING) }
            return version.takeIf { it == BuildConfig.VERSION_NAME }
        }

        /** The release in a GitHub "latest release" answer, or null if it has no APK. */
        internal fun parseRelease(body: String): Release? {
            val release = json.decodeFromString(GithubRelease.serializer(), body)
            if (release.draft || release.prerelease) return null
            val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
            return Release(
                version = release.tagName.removePrefix("v"),
                apkUrl = apk.url,
                sizeBytes = apk.size,
                sha256 = apk.digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            )
        }

        /** Whether [version] comes after [than]: 0.10.0 after 0.9.1. */
        internal fun isNewer(version: String, than: String): Boolean {
            val a = numbers(version)
            val b = numbers(than)
            for (i in 0 until maxOf(a.size, b.size)) {
                val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (diff != 0) return diff > 0
            }
            return false
        }

        private fun numbers(version: String): List<Int> =
            version.removePrefix("v").split('.').map { part -> part.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
    }
}

@Serializable
private class GithubRelease(
    @SerialName("tag_name") val tagName: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

@Serializable
private class GithubAsset(
    val name: String,
    @SerialName("browser_download_url") val url: String,
    val size: Long = 0,
    val digest: String? = null,
)
