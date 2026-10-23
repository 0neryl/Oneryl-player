package com.oneryl.music.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownloadOff
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.oneryl.music.R
import com.oneryl.music.data.ImportedTrack
import com.oneryl.music.data.Playlist
import com.oneryl.music.data.PlaylistImporter
import com.oneryl.music.data.Track
import com.oneryl.music.data.YandexMusicException
import com.oneryl.music.playback.OfflineState
import com.oneryl.music.ui.components.Artwork
import com.oneryl.music.ui.components.LocalTrackUi
import com.oneryl.music.ui.components.TrackRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** One imported playlist in the Library's list. */
@Composable
internal fun PlaylistRow(playlist: Playlist, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(playlist.coverUrl, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(playlist.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playlistSummary(playlist),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun playlistSummary(playlist: Playlist): String {
    val total = playlist.entries.size
    if (playlist.pendingCount > 0) {
        return stringResource(R.string.playlist_searching, total - playlist.pendingCount, total)
    }
    val found = playlist.tracks.size
    return listOfNotNull(
        stringResource(R.string.playlist_from_yandex),
        pluralStringResource(R.plurals.tracks_count, found, found),
        playlist.missing.size.takeIf { it > 0 }?.let { stringResource(R.string.playlist_missing_short, it) },
    ).joinToString(" • ")
}

/** An imported playlist: its tracks, the search still running, and what was not found. */
@Composable
internal fun PlaylistScreen(
    playlist: Playlist,
    waitingForNetwork: Boolean,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onLikeAll: (List<Track>) -> Unit,
    onOffline: (Boolean) -> Unit,
    onRetryDownloads: () -> Unit,
    onSearchAgain: () -> Unit,
    onDelete: () -> Unit,
) {
    val tracks = remember(playlist) { playlist.tracks }
    val missing = remember(playlist) { playlist.missing }
    val offline = LocalTrackUi.current.offline
    val readyOffline = tracks.count { offline[it.key] == OfflineState.READY }
    val failedOffline = tracks.count { offline[it.key] == OfflineState.FAILED }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var confirmDownload by rememberSaveable { mutableStateOf(false) }

    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.playlist_like_all)) },
                            leadingIcon = { Icon(Icons.Filled.Favorite, null) },
                            enabled = tracks.isNotEmpty(),
                            onClick = {
                                menuOpen = false
                                onLikeAll(tracks)
                            },
                        )
                        // Downloading starts from the button over the tracks; stopping it lives here.
                        if (playlist.offline) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.playlist_download_stop)) },
                                leadingIcon = { Icon(Icons.Filled.FileDownloadOff, null) },
                                onClick = {
                                    menuOpen = false
                                    onOffline(false)
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.playlist_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                            onClick = {
                                menuOpen = false
                                confirmDelete = true
                            },
                        )
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Artwork(playlist.coverUrl, Modifier.size(180.dp), corner = 8.dp)
                Spacer(Modifier.height(16.dp))
                Text(playlist.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.playlist_from_yandex),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (playlist.pendingCount > 0) {
            item { SearchProgress(playlist, waitingForNetwork) }
        }
        if (tracks.isNotEmpty()) {
            item {
                TracksHeader(
                    summary = pluralStringResource(R.plurals.tracks_count, tracks.size, tracks.size),
                    onPlay = { onPlay(tracks, 0) },
                    onShuffle = { onShuffle(tracks) },
                    leading = {
                        // The first tap keeps the playlist downloaded; later ones fetch what failed.
                        OfflineButton(readyOffline, tracks.size) {
                            if (playlist.offline) onRetryDownloads() else confirmDownload = true
                        }
                    },
                )
            }
            if (playlist.offline && failedOffline > 0) {
                item { DownloadsFailed(failedOffline, onRetryDownloads) }
            }
            // A playlist may hold the same track twice, so the position is part of the key.
            itemsIndexed(tracks, key = { index, track -> "$index/${track.key}" }) { index, _ -> TrackRow(tracks, index) }
        }
        if (missing.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.playlist_missing, missing.size),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    // While the search runs it gets to these lines anyway.
                    if (playlist.pendingCount == 0) {
                        TextButton(onClick = onSearchAgain, modifier = Modifier.testTag("playlist_search_again")) {
                            Text(stringResource(R.string.playlist_search_again))
                        }
                    }
                }
                Text(
                    stringResource(R.string.playlist_missing_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            items(missing) { MissingRow(it) }
        }
    }

    if (confirmDownload) {
        val context = LocalContext.current
        // About 160 kbit/s, what YouTube Music and SoundCloud streams come at.
        val bytes = tracks.sumOf { it.durationSeconds.coerceAtLeast(180) } * BYTES_PER_SECOND
        AlertDialog(
            onDismissRequest = { confirmDownload = false },
            title = { Text(stringResource(R.string.playlist_download_title, playlist.title)) },
            text = {
                Text(
                    stringResource(
                        R.string.playlist_download_text,
                        pluralStringResource(R.plurals.tracks_count, tracks.size, tracks.size),
                        Formatter.formatShortFileSize(context, bytes),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDownload = false
                    onOffline(true)
                }) { Text(stringResource(R.string.download)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDownload = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.playlist_delete_title, playlist.title)) },
            text = { Text(stringResource(R.string.playlist_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun SearchProgress(playlist: Playlist, waitingForNetwork: Boolean) {
    val total = playlist.entries.size
    val done = total - playlist.pendingCount
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(stringResource(R.string.playlist_searching, done, total), style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { done.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        if (waitingForNetwork) {
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.playlist_waiting),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("playlist_waiting"),
            )
        }
    }
}

@Composable
private fun MissingRow(track: ImportedTrack) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(track.displayTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            track.artist,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Asks for a Yandex Music link and brings the playlist over; [onImported] gets its id. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun ImportDialog(importer: PlaylistImporter, onImported: (String) -> Unit, onDismiss: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<YandexMusicException?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.import_yandex)) },
        text = {
            // A dialog is a window of its own, so the emulator test needs the tags exposed here too.
            Column(Modifier.semantics { testTagsAsResourceId = true }) {
                Text(stringResource(R.string.import_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        failure = null
                    },
                    label = { Text(stringResource(R.string.import_link)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("import_link"),
                )
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        importError(it),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("import_error"),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && link.isNotBlank(),
                onClick = {
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            onImported(importer.import(link.trim()))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: YandexMusicException) {
                            failure = e
                        } catch (e: Exception) {
                            failure = YandexMusicException(YandexMusicException.Reason.UNEXPECTED, e.javaClass.simpleName, e)
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.import_action)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

@Composable
private fun importError(error: YandexMusicException): String = when (error.reason) {
    YandexMusicException.Reason.BAD_LINK -> stringResource(R.string.import_error_link)
    YandexMusicException.Reason.REGION -> stringResource(R.string.import_error_region)
    YandexMusicException.Reason.NOT_FOUND -> stringResource(R.string.import_error_not_found)
    YandexMusicException.Reason.NETWORK -> stringResource(R.string.import_error_network)
    YandexMusicException.Reason.UNEXPECTED -> stringResource(R.string.import_error_unexpected, error.message.orEmpty())
}

private const val BYTES_PER_SECOND = 20_000L
