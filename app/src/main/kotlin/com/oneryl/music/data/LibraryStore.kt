package com.oneryl.music.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class SavedQueue(
    val tracks: List<Track> = emptyList(),
    val index: Int = 0,
    val positionMs: Long = 0,
)

@Serializable
data class LibraryData(
    val liked: List<Track> = emptyList(),
    val recent: List<Track> = emptyList(),
    val queue: SavedQueue = SavedQueue(),
    val playlists: List<Playlist> = emptyList(),
    /** Tracks downloaded on request, besides the liked ones and the offline playlists. */
    val pinned: List<Track> = emptyList(),
) {
    /** Everything to keep downloaded: liked, pinned and offline playlists' tracks, each once. */
    val offlineTracks: List<Track>
        get() = (liked + pinned + playlists.filter { it.offline }.flatMap { it.tracks }).distinctBy { it.key }
}

/** A playlist line still to be looked for on YouTube Music. */
data class PendingEntry(val playlistId: String, val index: Int, val source: ImportedTrack)

/**
 * Likes, listening history, imported playlists and the last queue, kept in
 * one JSON file on the phone. Newest entries come first.
 */
@OptIn(FlowPreview::class)
class LibraryStore(
    private val file: File,
    scope: CoroutineScope,
    saveDelayMs: Long = 500,
) {
    private val _state = MutableStateFlow(LibraryData())
    val state: StateFlow<LibraryData> = _state.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()

    init {
        scope.launch(Dispatchers.IO) {
            val stored = runCatching { json.decodeFromString<LibraryData>(file.readText()) }.getOrNull()
            val initial = stored ?: LibraryData()
            _state.value = initial
            loaded.complete(Unit)
            // Every change makes a new object, so anything but the loaded one needs saving,
            // including changes made before this collector started.
            _state.filter { it !== initial }.debounce(saveDelayMs).collect(::write)
        }
    }

    suspend fun awaitLoaded() = loaded.await()

    fun isLiked(key: String): Boolean = state.value.liked.any { it.key == key }

    fun toggleLike(track: Track) = _state.update { data ->
        if (data.liked.any { it.key == track.key }) {
            data.copy(liked = data.liked.filterNot { it.key == track.key })
        } else {
            data.copy(liked = listOf(track) + data.liked)
        }
    }

    fun addRecent(track: Track) = _state.update { data ->
        data.copy(recent = (listOf(track) + data.recent.filterNot { it.key == track.key }).take(MAX_RECENT))
    }

    fun saveQueue(queue: SavedQueue) = _state.update { it.copy(queue = queue) }

    /** Likes every track in [tracks] that is not liked yet, keeping their order. */
    fun likeAll(tracks: List<Track>) = _state.update { data ->
        val liked = data.liked.mapTo(HashSet()) { it.key }
        data.copy(liked = tracks.filter { it.key !in liked }.distinctBy { it.key } + data.liked)
    }

    /**
     * Adds an imported playlist on top, or replaces the one with its id. Lines
     * already found or given up on in the earlier import keep their result,
     * so importing again only looks for what is new, and an offline playlist
     * stays offline.
     */
    fun putPlaylist(playlist: Playlist) = _state.update { data ->
        val previous = data.playlists.firstOrNull { it.id == playlist.id }
        val earlier = previous?.entries?.filterNot { it.pending }?.associateBy { it.source }.orEmpty()
        val merged = playlist.copy(
            entries = playlist.entries.map { earlier[it.source] ?: it },
            offline = previous?.offline ?: playlist.offline,
        )
        data.copy(playlists = listOf(merged) + data.playlists.filterNot { it.id == playlist.id })
    }

    /** Downloads [track] on its own, or forgets that request. */
    fun togglePinned(track: Track) = _state.update { data ->
        if (data.pinned.any { it.key == track.key }) {
            data.copy(pinned = data.pinned.filterNot { it.key == track.key })
        } else {
            data.copy(pinned = listOf(track) + data.pinned)
        }
    }

    fun setPlaylistOffline(id: String, offline: Boolean) = _state.update { data ->
        data.copy(playlists = data.playlists.map { if (it.id == id) it.copy(offline = offline) else it })
    }

    fun removePlaylist(id: String) = _state.update { data -> data.copy(playlists = data.playlists.filterNot { it.id == id }) }

    /** The first playlist line nobody has looked for yet, oldest playlist first. */
    fun nextPending(): PendingEntry? {
        for (playlist in state.value.playlists.asReversed()) {
            val index = playlist.entries.indexOfFirst { it.pending }
            if (index >= 0) return PendingEntry(playlist.id, index, playlist.entries[index].source)
        }
        return null
    }

    /** Records what the search found for [entry], or that it found nothing. */
    fun settle(entry: PendingEntry, track: Track?) = _state.update { data ->
        data.copy(
            playlists = data.playlists.map { playlist ->
                val current = playlist.entries.getOrNull(entry.index)
                if (playlist.id != entry.playlistId || current?.source != entry.source) return@map playlist
                playlist.copy(
                    entries = playlist.entries.toMutableList().also {
                        it[entry.index] = PlaylistEntry(entry.source, track, pending = false)
                    },
                )
            },
        )
    }

    private fun write(data: LibraryData) {
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(json.encodeToString(LibraryData.serializer(), data))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    companion object {
        const val MAX_RECENT = 100
        private val json = Json { ignoreUnknownKeys = true }
    }
}
