package com.oneryl.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.oneryl.music.data.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

data class PlayerState(
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = C.INDEX_UNSET,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
) {
    val current: Track? get() = queue.getOrNull(currentIndex)
}

/** The UI's handle on [PlaybackService]: its state as flows, and the commands it accepts. */
class PlayerConnection(context: Context, scope: CoroutineScope) {

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val errors: SharedFlow<String> = _errors.asSharedFlow()

    private var controller: MediaController? = null

    init {
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val connected = runCatching {
                MediaController.Builder(context, token)
                    .setListener(ControllerListener())
                    .buildAsync()
                    .await()
            }.getOrNull() ?: return@launch
            connected.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) = refresh(player, events)
            })
            controller = connected
            refresh(connected, null)
        }
    }

    fun release() {
        controller?.release()
        controller = null
    }

    val positionMs: Long get() = controller?.currentPosition ?: 0
    val bufferedPositionMs: Long get() = controller?.bufferedPosition ?: 0

    fun play(tracks: List<Track>, startIndex: Int = 0) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        player.setMediaItems(tracks.map { it.toMediaItem() }, startIndex.coerceIn(tracks.indices), 0)
        player.prepare()
        player.play()
    }

    fun shuffleAll(tracks: List<Track>) {
        val player = controller ?: return
        if (tracks.isEmpty()) return
        player.shuffleModeEnabled = true
        play(tracks, tracks.indices.random())
    }

    fun playNext(track: Track) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return play(listOf(track))
        player.addMediaItem(player.currentMediaItemIndex + 1, track.toMediaItem())
    }

    fun addToQueue(track: Track) {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return play(listOf(track))
        player.addMediaItem(track.toMediaItem())
    }

    fun togglePlayPause() {
        val player = controller ?: return
        if (player.isPlaying) {
            player.pause()
        } else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            if (player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition()
            player.play()
        }
    }

    fun next() = controller?.seekToNext()
    fun previous() = controller?.seekToPrevious()
    fun seekTo(positionMs: Long) = controller?.seekTo(positionMs)
    fun playAt(index: Int) = controller?.run {
        seekToDefaultPosition(index)
        if (playbackState == Player.STATE_IDLE) prepare()
        play()
    }

    fun removeAt(index: Int) = controller?.removeMediaItem(index)

    fun toggleShuffle() = controller?.run { shuffleModeEnabled = !shuffleModeEnabled }

    fun cycleRepeat() = controller?.run {
        repeatMode = when (repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    private fun refresh(player: Player, events: Player.Events?) {
        val queueChanged = events == null || events.contains(Player.EVENT_TIMELINE_CHANGED)
        val queue = if (queueChanged) {
            (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toTrack() }
        } else {
            _state.value.queue
        }
        _state.value = PlayerState(
            queue = queue,
            currentIndex = if (player.mediaItemCount == 0) C.INDEX_UNSET else player.currentMediaItemIndex,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }

    private inner class ControllerListener : MediaController.Listener {
        override fun onCustomCommand(
            controller: MediaController,
            command: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (command.customAction == PlaybackService.ERROR_EVENT.customAction) {
                args.getString(PlaybackService.KEY_MESSAGE)?.let(_errors::tryEmit)
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }
}
