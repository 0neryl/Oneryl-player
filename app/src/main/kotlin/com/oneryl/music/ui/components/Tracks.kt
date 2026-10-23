package com.oneryl.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadForOffline
import androidx.compose.material.icons.filled.Downloading
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.oneryl.music.R
import com.oneryl.music.data.MusicSource
import com.oneryl.music.data.Track
import com.oneryl.music.playback.OfflineState

/** What track lists need from the rest of the app, handed down once from the root. */
interface TrackActions {
    fun play(tracks: List<Track>, index: Int)
    fun playNext(track: Track)
    fun addToQueue(track: Track)
    fun toggleLike(track: Track)

    /** Downloads a track on its own, or drops that download. */
    fun toggleDownload(track: Track)

    /** Asks which service to open the artist of [track] on, then opens them there. */
    fun openArtist(track: Track)
}

data class TrackUi(
    val currentKey: String? = null,
    val isPlaying: Boolean = false,
    val likedKeys: Set<String> = emptySet(),
    val actions: TrackActions? = null,
    val offline: Map<String, OfflineState> = emptyMap(),
    /** Tracks downloaded on their own rather than through Liked or an offline playlist. */
    val pinnedKeys: Set<String> = emptySet(),
)

val LocalTrackUi = compositionLocalOf { TrackUi() }

@Composable
fun Artwork(url: String?, modifier: Modifier = Modifier, corner: Dp = 4.dp) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        SubcomposeAsyncImage(
            model = url,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            error = { ArtworkPlaceholder() },
            loading = { ArtworkPlaceholder() },
        )
    }
}

@Composable
private fun ArtworkPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Filled.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxSize(0.4f),
        )
    }
}

fun formatDuration(seconds: Long): String {
    if (seconds <= 0) return ""
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    val secs = seconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, secs) else "%d:%02d".format(minutes, secs)
}

val MusicSource.label: String
    get() = when (this) {
        MusicSource.YOUTUBE_MUSIC -> "YouTube Music"
        MusicSource.SOUNDCLOUD -> "SoundCloud"
    }

/**
 * One line of a track list: tap plays [tracks] from [index], tapping the
 * artist opens them, the menu holds the rest.
 */
@Composable
fun TrackRow(
    tracks: List<Track>,
    index: Int,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val track = tracks[index]
    val ui = LocalTrackUi.current
    val isCurrent = ui.currentKey == track.key
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("track_row")
            .clickable { onClick?.invoke() ?: ui.actions?.play(tracks, index) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.artworkUrl, Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCurrent && ui.isPlaying) {
                    Icon(
                        Icons.Filled.GraphicEq,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                OfflineMark(ui.offline[track.key])
                if (track.artist.isNotEmpty()) {
                    Text(
                        track.artist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable { ui.actions?.openArtist(track) }
                            .testTag("track_artist"),
                    )
                }
                val duration = formatDuration(track.durationSeconds)
                if (duration.isNotEmpty()) {
                    Text(
                        if (track.artist.isNotEmpty()) " • $duration" else duration,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (trailing != null) trailing() else TrackMenu(track)
    }
}

/** Marks a downloaded track, or one being downloaded, next to its artist. */
@Composable
private fun OfflineMark(state: OfflineState?) {
    val (icon, description, tint) = when (state) {
        OfflineState.READY -> Triple(Icons.Filled.DownloadForOffline, R.string.offline_ready, MaterialTheme.colorScheme.primary)
        OfflineState.WAITING, OfflineState.DOWNLOADING ->
            Triple(Icons.Filled.Downloading, R.string.offline_downloading, MaterialTheme.colorScheme.onSurfaceVariant)
        OfflineState.FAILED, null -> return
    }
    Icon(icon, contentDescription = stringResource(description), tint = tint, modifier = Modifier.size(14.dp))
    Spacer(Modifier.width(4.dp))
}

@Composable
fun TrackMenu(track: Track) {
    val ui = LocalTrackUi.current
    var open by remember { mutableStateOf(false) }
    val liked = track.key in ui.likedKeys
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(if (liked) R.string.unlike else R.string.like)) },
                leadingIcon = { Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, null) },
                onClick = {
                    open = false
                    ui.actions?.toggleLike(track)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.play_next)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistPlay, null) },
                onClick = {
                    open = false
                    ui.actions?.playNext(track)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.add_to_queue)) },
                leadingIcon = { Icon(Icons.AutoMirrored.Filled.PlaylistAdd, null) },
                onClick = {
                    open = false
                    ui.actions?.addToQueue(track)
                },
            )
            if (track.artist.isNotEmpty()) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.artist_open)) },
                    leadingIcon = { Icon(Icons.Filled.Person, null) },
                    onClick = {
                        open = false
                        ui.actions?.openArtist(track)
                    },
                    modifier = Modifier.testTag("menu_artist"),
                )
            }
            // A track kept through Liked or an offline playlist goes with them, not from here.
            val pinned = track.key in ui.pinnedKeys
            if (pinned || track.key !in ui.offline) {
                DropdownMenuItem(
                    text = { Text(stringResource(if (pinned) R.string.download_remove else R.string.download)) },
                    leadingIcon = { Icon(if (pinned) Icons.Filled.FileDownloadOff else Icons.Filled.Download, null) },
                    onClick = {
                        open = false
                        ui.actions?.toggleDownload(track)
                    },
                )
            }
        }
    }
}

@Composable
fun TrackCard(tracks: List<Track>, index: Int, modifier: Modifier = Modifier) {
    val track = tracks[index]
    val ui = LocalTrackUi.current
    Column(
        modifier = modifier
            .width(140.dp)
            .clip(RoundedCornerShape(6.dp))
            .clickable { ui.actions?.play(tracks, index) }
            .padding(bottom = 4.dp),
    ) {
        Artwork(track.artworkUrl, Modifier.size(140.dp), corner = 6.dp)
        Spacer(Modifier.height(8.dp))
        Text(
            track.title,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ui.currentKey == track.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            track.artist,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun TrackCarousel(tracks: List<Track>, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        itemsIndexed(tracks, key = { _, track -> track.key }) { index, _ -> TrackCard(tracks, index) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
    )
}

@Composable
fun ScreenTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        modifier = modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}
