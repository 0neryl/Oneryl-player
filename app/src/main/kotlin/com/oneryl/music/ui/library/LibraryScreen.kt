package com.oneryl.music.ui.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneryl.music.R
import com.oneryl.music.data.LibraryStore
import com.oneryl.music.data.PlaylistImporter
import com.oneryl.music.data.Track
import com.oneryl.music.data.YoutubeAccount
import com.oneryl.music.data.YoutubeSession
import com.oneryl.music.net.ServerTunnel
import com.oneryl.music.playback.OfflineState
import com.oneryl.music.ui.components.LocalTrackUi
import com.oneryl.music.ui.components.MessageBlock
import com.oneryl.music.ui.components.ScreenTitle
import com.oneryl.music.ui.components.TrackRow

private enum class LibrarySection { LIKED, PLAYLISTS, HISTORY }

@Composable
fun LibraryScreen(
    library: LibraryStore,
    account: YoutubeAccount,
    importer: PlaylistImporter,
    tunnel: ServerTunnel,
    onRetryDownloads: () -> Unit,
    contentPadding: PaddingValues,
    openPlaylistId: String?,
    onOpenPlaylist: (String?) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onSignIn: () -> Unit,
) {
    val data by library.state.collectAsStateWithLifecycle()
    val waitingForNetwork by importer.waitingForNetwork.collectAsStateWithLifecycle()
    val openPlaylist = openPlaylistId?.let { id -> data.playlists.firstOrNull { it.id == id } }
    if (openPlaylist != null) {
        PlaylistScreen(
            playlist = openPlaylist,
            waitingForNetwork = waitingForNetwork,
            contentPadding = contentPadding,
            onBack = { onOpenPlaylist(null) },
            onPlay = onPlay,
            onShuffle = onShuffle,
            onLikeAll = library::likeAll,
            onOffline = { library.setPlaylistOffline(openPlaylist.id, it) },
            onRetryDownloads = onRetryDownloads,
            onSearchAgain = { importer.searchMissingAgain(openPlaylist.id) },
            onDelete = {
                onOpenPlaylist(null)
                library.removePlaylist(openPlaylist.id)
            },
        )
        return
    }

    val session by account.session.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }
    var importing by rememberSaveable { mutableStateOf(false) }
    var section by rememberSaveable { mutableStateOf(LibrarySection.LIKED) }
    val tracks = if (section == LibrarySection.HISTORY) data.recent else data.liked
    val offline = LocalTrackUi.current.offline
    val readyOffline = tracks.count { offline[it.key] == OfflineState.READY }
    val failedOffline = tracks.count { offline[it.key] == OfflineState.FAILED }

    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        item { ScreenTitle(stringResource(R.string.tab_library)) }
        item { AccountRow(session, onSignIn = onSignIn, onSignOut = { confirmSignOut = true }) }
        item { ServerRow(tunnel) }
        item {
            Row(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    LibrarySection.LIKED to R.string.library_liked,
                    LibrarySection.PLAYLISTS to R.string.library_playlists,
                    LibrarySection.HISTORY to R.string.library_recent,
                ).forEach { (shown, label) ->
                    FilterChip(
                        selected = section == shown,
                        onClick = { section = shown },
                        label = { Text(stringResource(label)) },
                    )
                }
            }
        }
        when {
            section == LibrarySection.PLAYLISTS -> {
                item {
                    OutlinedButton(
                        onClick = { importing = true },
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("import_yandex"),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.import_yandex))
                    }
                }
                if (data.playlists.isEmpty()) {
                    item { MessageBlock(stringResource(R.string.library_empty_playlists)) }
                }
                items(data.playlists, key = { it.id }) { playlist ->
                    PlaylistRow(playlist, onClick = { onOpenPlaylist(playlist.id) })
                }
            }
            tracks.isEmpty() -> item {
                MessageBlock(
                    stringResource(
                        if (section == LibrarySection.HISTORY) R.string.library_empty_recent else R.string.library_empty_liked,
                    ),
                )
            }
            else -> {
                item {
                    TracksHeader(
                        summary = listOfNotNull(
                            pluralStringResource(R.plurals.tracks_count, tracks.size, tracks.size),
                            if (section == LibrarySection.LIKED) stringResource(R.string.offline_count, readyOffline) else null,
                        ).joinToString(" • "),
                        onPlay = { onPlay(tracks, 0) },
                        onShuffle = { onShuffle(tracks) },
                    )
                }
                if (section == LibrarySection.LIKED && failedOffline > 0) {
                    item { DownloadsFailed(failedOffline, onRetryDownloads) }
                }
                itemsIndexed(tracks, key = { _, track -> track.key }) { index, _ -> TrackRow(tracks, index) }
            }
        }
    }

    if (importing) {
        ImportDialog(
            importer = importer,
            onImported = { id ->
                importing = false
                onOpenPlaylist(id)
            },
            onDismiss = { importing = false },
        )
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = { Text(stringResource(R.string.account_sign_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    account.signOut()
                }) { Text(stringResource(R.string.account_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

/** Downloads that failed, and a way to try them again. */
@Composable
internal fun DownloadsFailed(count: Int, onRetry: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.downloads_failed, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

/** How many tracks a list holds, and buttons to play it in order or shuffled. */
@Composable
internal fun TracksHeader(summary: String, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.shuffle))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play_all))
        }
    }
}

/** The optional YouTube account: why to sign in, or whose account is in use. */
@Composable
private fun AccountRow(session: YoutubeSession?, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = if (session != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    session == null -> stringResource(R.string.account_youtube)
                    else -> session.name ?: stringResource(R.string.account_signed_in)
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (session == null) stringResource(R.string.account_why) else stringResource(R.string.account_in_use),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (session == null) {
            FilledTonalButton(onClick = onSignIn) { Text(stringResource(R.string.account_sign_in)) }
        } else {
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.account_sign_out)) }
        }
    }
}
