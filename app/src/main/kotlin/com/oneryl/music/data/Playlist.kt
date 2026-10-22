package com.oneryl.music.data

import kotlinx.serialization.Serializable

/** A track as another service lists it, before it is found on YouTube Music. */
@Serializable
data class ImportedTrack(
    val title: String,
    val artists: List<String>,
    val version: String? = null,
    val durationSeconds: Long = 0,
    val coverUrl: String? = null,
) {
    val displayTitle: String get() = if (version.isNullOrBlank()) title else "$title ($version)"
    val artist: String get() = artists.joinToString(", ")
    val query: String get() = (artists + displayTitle).joinToString(" ")
}

/** One line of an imported playlist: still being looked for, found, or not found. */
@Serializable
data class PlaylistEntry(
    val source: ImportedTrack,
    val track: Track? = null,
    val pending: Boolean = true,
)

/** A playlist brought over from another service, in its original order. */
@Serializable
data class Playlist(
    val id: String,
    val title: String,
    val coverUrl: String? = null,
    val sourceUrl: String? = null,
    val entries: List<PlaylistEntry> = emptyList(),
    /** Kept downloaded, so it plays without a network. */
    val offline: Boolean = false,
) {
    val tracks: List<Track> get() = entries.mapNotNull { it.track }
    val pendingCount: Int get() = entries.count { it.pending }
    val missing: List<ImportedTrack> get() = entries.filter { !it.pending && it.track == null }.map { it.source }
}
