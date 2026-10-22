package com.oneryl.music.data

import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryStoreTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun track(id: String) = Track(MusicSource.YOUTUBE_MUSIC, id, "Song $id", "Artist", 180, null)

    private fun <T> withStore(file: File, block: suspend (LibraryStore) -> T): T = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val store = LibraryStore(file, scope, saveDelayMs = 10)
            store.awaitLoaded()
            block(store)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun likeTogglesAndKeepsNewestFirst() {
        withStore(File(folder.root, "library.json")) { store ->
            store.toggleLike(track("a"))
            store.toggleLike(track("b"))
            assertEquals(listOf("b", "a"), store.state.value.liked.map { it.id })
            assertTrue(store.isLiked(track("a").key))

            store.toggleLike(track("a"))
            assertFalse(store.isLiked(track("a").key))
            assertEquals(listOf("b"), store.state.value.liked.map { it.id })
        }
    }

    @Test
    fun recentMovesRepeatsToTheTopAndIsCapped() {
        withStore(File(folder.root, "library.json")) { store ->
            store.addRecent(track("a"))
            store.addRecent(track("b"))
            store.addRecent(track("a"))
            assertEquals(listOf("a", "b"), store.state.value.recent.map { it.id })

            repeat(LibraryStore.MAX_RECENT + 5) { store.addRecent(track("t$it")) }
            assertEquals(LibraryStore.MAX_RECENT, store.state.value.recent.size)
        }
    }

    @Test
    fun dataSurvivesARestart() {
        val file = File(folder.root, "library.json")
        withStore(file) { store ->
            store.toggleLike(track("a"))
            store.saveQueue(SavedQueue(listOf(track("a"), track("b")), index = 1, positionMs = 42_000))
            withTimeout(5_000) {
                while (!file.exists() || "positionMs" !in file.readText()) kotlinx.coroutines.delay(10)
            }
        }
        withStore(file) { store ->
            assertTrue(store.isLiked(track("a").key))
            assertEquals(1, store.state.value.queue.index)
            assertEquals(42_000, store.state.value.queue.positionMs)
            assertEquals(listOf("a", "b"), store.state.first().queue.tracks.map { it.id })
        }
    }

    private fun imported(title: String) = ImportedTrack(title, listOf("Artist"), durationSeconds = 180)

    @Test
    fun reimportingAPlaylistKeepsWhatWasAlreadyFound() {
        withStore(File(folder.root, "library.json")) { store ->
            store.putPlaylist(Playlist("yandex:a", "Mix", entries = listOf(PlaylistEntry(imported("one")), PlaylistEntry(imported("two")))))
            store.settle(store.nextPending()!!, track("1"))
            store.settle(store.nextPending()!!, null)
            assertEquals(null, store.nextPending())

            store.putPlaylist(
                Playlist(
                    "yandex:a",
                    "Mix (new)",
                    entries = listOf(PlaylistEntry(imported("zero")), PlaylistEntry(imported("one")), PlaylistEntry(imported("two"))),
                ),
            )
            val playlist = store.state.value.playlists.single()
            assertEquals("Mix (new)", playlist.title)
            assertEquals(listOf(true, false, false), playlist.entries.map { it.pending })
            assertEquals(listOf("1"), playlist.tracks.map { it.id })
            assertEquals(listOf("two"), playlist.missing.map { it.title })
            assertEquals("zero", store.nextPending()?.source?.title)
        }
    }

    @Test
    fun likeAllAddsOnlyWhatIsNotLikedYet() {
        withStore(File(folder.root, "library.json")) { store ->
            store.toggleLike(track("b"))
            store.likeAll(listOf(track("a"), track("b"), track("c"), track("a")))
            assertEquals(listOf("a", "c", "b"), store.state.value.liked.map { it.id })
        }
    }

    @Test
    fun oldLibraryFilesLoadWithoutPlaylists() {
        val file = File(folder.root, "library.json").apply { writeText("""{"liked":[],"recent":[]}""") }
        withStore(file) { store -> assertEquals(emptyList<Playlist>(), store.state.value.playlists) }
    }

    @Test
    fun offlineTracksAreLikedPinnedAndOfflinePlaylistTracksOnce() {
        withStore(File(folder.root, "library.json")) { store ->
            store.toggleLike(track("a"))
            store.togglePinned(track("b"))
            store.togglePinned(track("a"))
            store.putPlaylist(Playlist("yandex:p", "Mix", entries = listOf(PlaylistEntry(imported("c")))))
            store.settle(store.nextPending()!!, track("c"))
            assertEquals("a playlist is not downloaded until asked", listOf("a", "b"), store.state.value.offlineTracks.map { it.id })

            store.setPlaylistOffline("yandex:p", true)
            assertEquals(listOf("a", "b", "c"), store.state.value.offlineTracks.map { it.id })

            store.putPlaylist(Playlist("yandex:p", "Mix", entries = listOf(PlaylistEntry(imported("c")))))
            assertTrue("importing again keeps it offline", store.state.value.playlists.single().offline)

            store.togglePinned(track("b"))
            store.setPlaylistOffline("yandex:p", false)
            assertEquals(listOf("a"), store.state.value.offlineTracks.map { it.id })
        }
    }
}
