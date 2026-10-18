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
)

/**
 * Likes, listening history and the last queue, kept in one JSON file on the
 * phone. Newest entries come first.
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
