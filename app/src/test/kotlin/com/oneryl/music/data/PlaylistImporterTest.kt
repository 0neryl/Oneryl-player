package com.oneryl.music.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class PlaylistImporterTest {

    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun theSearchWaitsOutANetworkFailureAndSettlesEveryLine() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val library = LibraryStore(File(folder.root, "library.json"), scope, saveDelayMs = 10)
            library.awaitLoaded()
            library.putPlaylist(
                Playlist(
                    "yandex:a",
                    "Mix",
                    entries = listOf("Believer", "Nothing like it", "Thunder").map {
                        PlaylistEntry(ImportedTrack(it, listOf("Imagine Dragons"), durationSeconds = 200))
                    },
                ),
            )
            var calls = 0
            var sawWaiting = false
            lateinit var importer: PlaylistImporter
            val matcher = TrackMatcher { query ->
                calls++
                when {
                    calls == 1 -> throw IOException("no network")
                    "Nothing" in query -> emptyList()
                    else -> {
                        sawWaiting = sawWaiting || importer.waitingForYoutube.value
                        listOf(Track(MusicSource.YOUTUBE_MUSIC, query.substringAfterLast(' '), query.substringAfterLast(' '), "Imagine Dragons", 200, null))
                    }
                }
            }
            importer = PlaylistImporter(YandexMusic(OkHttpClient()), matcher, library, scope, Dispatchers.Default, retryDelayMs = 10, pauseMs = 0)
            importer.resume()
            withTimeout(5_000) {
                while (library.nextPending() != null || importer.waitingForYoutube.value) delay(10)
            }

            val playlist = library.state.value.playlists.single()
            assertTrue("the failure was reported while waiting", sawWaiting)
            assertEquals(listOf("Believer", "Thunder"), playlist.tracks.map { it.id })
            assertEquals(listOf("Nothing like it"), playlist.missing.map { it.title })
            assertEquals(4, calls)
        } finally {
            scope.cancel()
        }
    }
}
