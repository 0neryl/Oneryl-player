package com.oneryl.music.playback

import androidx.core.net.toUri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.oneryl.music.data.MusicSource
import com.oneryl.music.data.Track
import com.oneryl.music.data.musicSourceForHost

private const val EXTRA_DURATION_SECONDS = "oneryl.durationSeconds"

/** SoundCloud plays as HLS, which the player and the downloader must know before they load anything. */
val Track.mimeType: String?
    get() = if (source == MusicSource.SOUNDCLOUD) MimeTypes.APPLICATION_M3U8 else null

fun Track.toMediaItem(): MediaItem = MediaItem.Builder()
    .setMediaId(key)
    .setUri(streamUri)
    .setMimeType(mimeType)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setArtworkUri(artworkUrl?.toUri())
            .setDurationMs(durationSeconds.takeIf { it > 0 }?.times(1000))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putLong(EXTRA_DURATION_SECONDS, durationSeconds) })
            .build(),
    )
    .build()

fun MediaItem.toTrack(): Track? {
    val host = mediaId.substringBefore('/', "")
    val id = mediaId.substringAfter('/', "")
    val source = musicSourceForHost(host) ?: return null
    if (id.isEmpty()) return null
    return Track(
        source = source,
        id = id,
        title = mediaMetadata.title?.toString().orEmpty(),
        artist = mediaMetadata.artist?.toString().orEmpty(),
        durationSeconds = mediaMetadata.extras?.getLong(EXTRA_DURATION_SECONDS)
            ?: mediaMetadata.durationMs?.div(1000)
            ?: 0,
        artworkUrl = mediaMetadata.artworkUri?.toString(),
    )
}

/** Controllers outside the app may send items without a URI; rebuild them from the id. */
fun MediaItem.withPlayableUri(): MediaItem = toTrack()?.toMediaItem() ?: this
