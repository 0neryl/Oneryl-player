package com.oneryl.music.playback

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.DatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadRequest
import com.oneryl.music.data.LibraryStore
import com.oneryl.music.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/** How far a track is from playing without a network. */
enum class OfflineState { WAITING, DOWNLOADING, READY, FAILED }

/**
 * Keeps tracks downloaded so they play without a network: the liked ones,
 * the ones downloaded on request and those of offline playlists
 * ([com.oneryl.music.data.LibraryData.offlineTracks]). A track joining them
 * queues its download and one leaving them deletes it. Downloads go to their
 * own cache, which nothing evicts, one at a time so YouTube does not see a
 * burst of requests; [OfflineDownloadService] keeps them going in the
 * background. A failed download is tried again on the next launch or
 * [retryFailed].
 *
 * Create it on the main thread: the download manager reports there.
 */
@UnstableApi
class OfflineTracks(
    context: Context,
    databaseProvider: DatabaseProvider,
    cache: Cache,
    upstream: DataSource.Factory,
    library: LibraryStore,
    scope: CoroutineScope,
) {
    internal val manager = DownloadManager(context, databaseProvider, cache, upstream, Executors.newSingleThreadExecutor())

    /** [Download.state] by [Track.key], as the manager last reported it. */
    private val downloads = mutableMapOf<String, Int>()
    private var wanted: Map<String, Track>? = null

    private val _states = MutableStateFlow<Map<String, OfflineState>>(emptyMap())
    val states: StateFlow<Map<String, OfflineState>> = _states.asStateFlow()

    private val _pending = MutableStateFlow(false)

    /** True while downloads wait or run, which is when the download service should be up. */
    val pending: StateFlow<Boolean> = _pending.asStateFlow()

    init {
        manager.maxParallelDownloads = 1
        manager.minRetryCount = 3
        manager.addListener(Listener())
        manager.resumeDownloads()
        scope.launch {
            library.awaitLoaded()
            library.state.map { data -> data.offlineTracks.associateBy(Track::key) }.distinctUntilChanged().collect {
                wanted = it
                sync()
            }
        }
    }

    /** Queues the failed downloads again; the reason they failed may have passed. */
    fun retryFailed() = sync(retryFailed = true)

    /**
     * Queues what should be downloaded and is not, and removes what no longer
     * should be. Failed downloads wait for [retryFailed] or the next launch, so
     * a track YouTube refuses is not asked for again on every library change.
     */
    private fun sync(retryFailed: Boolean = false) {
        val wanted = wanted ?: return
        if (!manager.isInitialized) return
        wanted.forEach { (key, track) ->
            val state = downloads[key]
            if (state == null || (retryFailed && state == Download.STATE_FAILED)) {
                manager.addDownload(DownloadRequest.Builder(key, track.streamUri.toUri()).setMimeType(track.mimeType).build())
                downloads[key] = Download.STATE_QUEUED
            }
        }
        (downloads.keys - wanted.keys).forEach { key ->
            manager.removeDownload(key)
            downloads.remove(key)
        }
        publish()
    }

    private fun publish() {
        _states.value = downloads.mapNotNull { (key, state) -> offlineState(state)?.let { key to it } }.toMap()
        _pending.value = downloads.values.any { it != Download.STATE_COMPLETED && it != Download.STATE_FAILED }
    }

    private fun offlineState(state: Int): OfflineState? = when (state) {
        Download.STATE_COMPLETED -> OfflineState.READY
        Download.STATE_DOWNLOADING -> OfflineState.DOWNLOADING
        Download.STATE_FAILED -> OfflineState.FAILED
        Download.STATE_REMOVING -> null
        else -> OfflineState.WAITING
    }

    private inner class Listener : DownloadManager.Listener {

        override fun onInitialized(downloadManager: DownloadManager) {
            downloadManager.downloadIndex.getDownloads().use { cursor ->
                while (cursor.moveToNext()) downloads[cursor.download.request.id] = cursor.download.state
            }
            publish()
            sync(retryFailed = true)
        }

        override fun onDownloadChanged(downloadManager: DownloadManager, download: Download, finalException: Exception?) {
            val key = download.request.id
            if (download.state == Download.STATE_REMOVING) downloads.remove(key) else downloads[key] = download.state
            publish()
            when (download.state) {
                Download.STATE_COMPLETED -> Log.i(TAG, "Ready offline: $key")
                Download.STATE_FAILED -> Log.w(TAG, "Could not download $key: ${finalException?.javaClass?.simpleName}")
            }
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            downloads.remove(download.request.id)
            publish()
        }
    }

    private companion object {
        const val TAG = "OfflineTracks"
    }
}
