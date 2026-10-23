package com.oneryl.music.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.IOException

/**
 * Brings playlists over from Yandex Music. The track list comes at once; the
 * search for each track on YouTube Music and SoundCloud runs in the
 * background, one track at a time, and survives restarts, since the lines
 * still to find are saved with the playlist. Yandex Music answers only without
 * a VPN and YouTube often only with one, so when the services cannot be
 * reached the search waits and tries again.
 */
class PlaylistImporter(
    private val yandex: YandexMusic,
    private val matcher: TrackMatcher,
    private val library: LibraryStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val retryDelayMs: Long = RETRY_DELAY_MS,
    private val pauseMs: Long = PAUSE_MS,
) {
    private var job: Job? = null

    private val _waitingForNetwork = MutableStateFlow(false)

    /** True while the search cannot reach the services and waits to try again. */
    val waitingForNetwork: StateFlow<Boolean> = _waitingForNetwork.asStateFlow()

    /** Reads the playlist [link] points to and starts looking for its tracks; returns the playlist id. */
    suspend fun import(link: String): String {
        val playlist = withContext(io) { yandex.playlist(link) }
        library.putPlaylist(playlist)
        resume()
        return playlist.id
    }

    /** Looks again for the tracks of playlist [id] that were not found: the services keep adding music. */
    fun searchMissingAgain(id: String) {
        library.searchMissingAgain(id)
        resume()
    }

    /** Looks for the tracks earlier imports did not get to, if any. */
    fun resume() {
        if (job?.isActive == true) return
        job = scope.launch(io) {
            library.awaitLoaded()
            searchPending()
        }
    }

    private suspend fun searchPending() {
        var failures = 0
        while (true) {
            val entry = library.nextPending() ?: break
            val track = try {
                matcher.find(entry.source)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No network or a service turning requests away: wait for it. A query that keeps
                // breaking the parser while the rest of the service works is given up on.
                _waitingForNetwork.value = true
                failures++
                Log.w(TAG, "Search failed (${e.javaClass.simpleName}), retrying")
                val waitable = e is IOException || e is ReCaptchaException
                if (!waitable && failures >= MAX_FAILURES) {
                    library.settle(entry, null)
                    failures = 0
                }
                delay(retryDelayMs)
                continue
            }
            failures = 0
            _waitingForNetwork.value = false
            library.settle(entry, track)
            delay(pauseMs)
        }
        _waitingForNetwork.value = false
    }

    private companion object {
        const val TAG = "PlaylistImporter"
        const val RETRY_DELAY_MS = 15_000L
        const val PAUSE_MS = 300L
        const val MAX_FAILURES = 3
    }
}
