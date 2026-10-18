package com.oneryl.music.data

import kotlinx.serialization.Serializable

@Serializable
enum class MusicSource {
    YOUTUBE_MUSIC,
    SOUNDCLOUD,
}

/**
 * A playable track. [id] is the YouTube video id or the SoundCloud page path
 * ("artist/track"), which is all the player needs to find the stream again.
 */
@Serializable
data class Track(
    val source: MusicSource,
    val id: String,
    val title: String,
    val artist: String,
    val durationSeconds: Long,
    val artworkUrl: String?,
) {
    val key: String get() = "${source.uriHost}/$id"

    /** The URI the player loads; [StreamResolver] turns it into a real stream. */
    val streamUri: String get() = "$STREAM_SCHEME://$key"

    val pageUrl: String
        get() = when (source) {
            MusicSource.YOUTUBE_MUSIC -> "https://www.youtube.com/watch?v=$id"
            MusicSource.SOUNDCLOUD -> "https://soundcloud.com/$id"
        }

    companion object {
        const val STREAM_SCHEME = "oneryl"
    }
}

val MusicSource.uriHost: String
    get() = when (this) {
        MusicSource.YOUTUBE_MUSIC -> "youtube"
        MusicSource.SOUNDCLOUD -> "soundcloud"
    }

fun musicSourceForHost(host: String): MusicSource? =
    MusicSource.entries.firstOrNull { it.uriHost == host }
