package com.oneryl.music.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSession.ConnectionResult
import androidx.media3.session.MediaSession.MediaItemsWithStartPosition
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.oneryl.music.MainActivity
import com.oneryl.music.R
import com.oneryl.music.app
import com.oneryl.music.data.SavedQueue
import com.oneryl.music.data.StreamUnavailableException
import com.oneryl.music.data.UnavailableReason
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.guava.future
import kotlinx.coroutines.launch

/**
 * Plays the queue in the background and shows it in the notification shade,
 * on the lock screen and to headsets, with a like button next to the usual
 * controls.
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private val scope = MainScope()
    private lateinit var player: ExoPlayer
    private var session: MediaSession? = null

    private var failuresInARow = 0
    private val retries = mutableMapOf<String, Int>()
    private var retryJob: Job? = null
    private var recordedMediaId: String? = null

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app.playerDataSourceFactory))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(Events())

        session = MediaSession.Builder(this, player)
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                ),
            )
            .setCallback(Callback())
            .setMediaButtonPreferences(listOf(likeButton(liked = false)))
            .build()

        scope.launch {
            app.library.state
                .map { data -> player.currentMediaItem?.mediaId?.let { id -> data.liked.any { it.key == id } } ?: false }
                .distinctUntilChanged()
                .collect { session?.setMediaButtonPreferences(listOf(likeButton(it))) }
        }
        scope.launch { restoreQueue() }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        saveQueue()
        scope.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private suspend fun restoreQueue() {
        app.library.awaitLoaded()
        val saved = app.library.state.value.queue
        if (player.mediaItemCount > 0 || saved.tracks.isEmpty()) return
        player.setMediaItems(
            saved.tracks.map { it.toMediaItem() },
            saved.index.coerceIn(saved.tracks.indices),
            saved.positionMs,
        )
    }

    private fun saveQueue() {
        if (!::player.isInitialized) return
        val tracks = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toTrack() }
        app.library.saveQueue(
            SavedQueue(tracks, player.currentMediaItemIndex.coerceAtLeast(0), player.currentPosition),
        )
    }

    private fun toggleLike() {
        player.currentMediaItem?.toTrack()?.let(app.library::toggleLike)
    }

    private fun likeButton(liked: Boolean): CommandButton =
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(getString(if (liked) R.string.unlike else R.string.like))
            .setSessionCommand(LIKE_COMMAND)
            .build()

    private fun notifyError(message: String) {
        session?.broadcastCustomCommand(
            ERROR_EVENT,
            Bundle().apply { putString(KEY_MESSAGE, message) },
        )
    }

    private fun messageFor(error: PlaybackException): String {
        val unavailable = error.causes().filterIsInstance<StreamUnavailableException>().firstOrNull()
        val text = when (unavailable?.reason) {
            UnavailableReason.NOT_AVAILABLE -> R.string.error_not_available
            UnavailableReason.AGE_RESTRICTED -> R.string.error_age_restricted
            UnavailableReason.PREMIUM_ONLY -> R.string.error_premium
            UnavailableReason.REGION_LOCKED -> R.string.error_region
            UnavailableReason.BLOCKED_BY_SERVICE -> R.string.error_blocked
            UnavailableReason.NO_AUDIO -> R.string.error_no_audio
            UnavailableReason.UNKNOWN -> R.string.error_unknown
            null -> when (error.errorCode) {
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                -> R.string.error_network
                else -> R.string.error_unknown
            }
        }
        return "${getString(text)} (${technicalDetail(error)})"
    }

    /** A short hint at what failed, so a screenshot of the message is enough to debug it. */
    private fun technicalDetail(error: PlaybackException): String {
        val httpCode = error.causes()
            .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
            .firstOrNull()?.responseCode
        if (httpCode != null) return "HTTP $httpCode"
        error.causes().filterIsInstance<StreamUnavailableException>().firstNotNullOfOrNull { it.detail }
            ?.let { return it }
        val root = error.causes().last()
        return root.javaClass.simpleName.removeSuffix("Exception").ifEmpty { error.errorCodeName }
    }

    private inner class Events : Player.Listener {

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            recordedMediaId = null
            val liked = mediaItem?.mediaId?.let(app.library::isLiked) ?: false
            session?.setMediaButtonPreferences(listOf(likeButton(liked)))
            saveQueue()
        }

        override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) saveQueue()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) {
                saveQueue()
                return
            }
            failuresInARow = 0
            retries.clear()
            val item = player.currentMediaItem ?: return
            if (item.mediaId != recordedMediaId) {
                recordedMediaId = item.mediaId
                item.toTrack()?.let(app.library::addRecent)
            }
        }

        /**
         * A refused request means the stream URL expired or YouTube did not
         * trust the client that found it: fetch a new one, from another client,
         * a few times. YouTube also turns a busy VPN address away now and then,
         * so a refusal to give a link at all gets one more try. Anything else
         * skips to the next track, up to a few in a row so a lost connection
         * does not race through the whole queue.
         */
        override fun onPlayerError(error: PlaybackException) {
            val item = player.currentMediaItem ?: return
            val streamUri = item.localConfiguration?.uri?.toString()
            val httpCode = error.causes()
                .filterIsInstance<HttpDataSource.InvalidResponseCodeException>()
                .firstOrNull()?.responseCode
            val refused = httpCode != null && httpCode in 400..499
            val turnedAway = error.causes().filterIsInstance<StreamUnavailableException>().firstOrNull()
                ?.reason in RECHECKED_REASONS
            val tries = retries[item.mediaId] ?: 0
            if (streamUri != null && (refused && tries < MAX_REFRESHES || turnedAway && tries == 0)) {
                retries[item.mediaId] = tries + 1
                if (refused) app.resolver.invalidate(streamUri)
                retryJob?.cancel()
                retryJob = scope.launch {
                    delay(RETRY_DELAY_MS * (tries + 1))
                    if (player.currentMediaItem?.mediaId == item.mediaId && player.playerError != null) {
                        player.prepare()
                    }
                }
                return
            }

            val message = messageFor(error)
            failuresInARow++
            if (failuresInARow < MAX_FAILURES_IN_A_ROW && player.hasNextMediaItem()) {
                notifyError(getString(R.string.error_skipping, message))
                player.seekToNextMediaItem()
                player.prepare()
            } else {
                notifyError(message)
            }
        }
    }

    private inner class Callback : MediaSession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): ConnectionResult =
            ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE_COMMAND).build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            if (customCommand.customAction == LIKE_COMMAND.customAction) {
                toggleLike()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            return super.onCustomCommand(session, controller, customCommand, args)
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>,
        ): ListenableFuture<List<MediaItem>> = Futures.immediateFuture(mediaItems.map { it.withPlayableUri() })

        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean,
        ): ListenableFuture<MediaItemsWithStartPosition> = scope.future {
            app.library.awaitLoaded()
            val saved = app.library.state.value.queue
            check(saved.tracks.isNotEmpty()) { "Nothing to resume" }
            MediaItemsWithStartPosition(
                saved.tracks.map { it.toMediaItem() },
                saved.index.coerceIn(saved.tracks.indices),
                saved.positionMs,
            )
        }
    }

    companion object {
        const val KEY_MESSAGE = "message"
        val LIKE_COMMAND = SessionCommand("com.oneryl.music.LIKE", Bundle.EMPTY)
        val ERROR_EVENT = SessionCommand("com.oneryl.music.ERROR", Bundle.EMPTY)
        private const val MAX_FAILURES_IN_A_ROW = 3
        private const val MAX_REFRESHES = 3
        private const val RETRY_DELAY_MS = 1000L
        private val RECHECKED_REASONS = setOf(UnavailableReason.BLOCKED_BY_SERVICE, UnavailableReason.NOT_AVAILABLE)
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }
