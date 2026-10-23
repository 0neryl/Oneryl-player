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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
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
import com.oneryl.music.net.ServerTunnel
import com.oneryl.music.playback.OfflineState
import com.oneryl.music.ui.components.LocalTrackUi
import com.oneryl.music.ui.components.MessageBlock
import com.oneryl.music.ui.components.ScreenTitle
import com.oneryl.music.ui.components.TrackRow
import com.oneryl.music.ui.settings.SettingsScreen
import com.oneryl.music.update.AppUpdater
import com.oneryl.music.update.UpdateState

private enum class LibrarySection { LIKED, PLAYLISTS, HISTORY }

@Composable
fun LibraryScreen(
    library: LibraryStore,
    account: YoutubeAccount,
    importer: PlaylistImporter,
    tunnel: ServerTunnel,
    updater: AppUpdater,
    onRetryDownloads: () -> Unit,
    contentPadding: PaddingValues,
    settingsOpen: Boolean,
    onOpenSettings: (Boolean) -> Unit,
    openPlaylistId: String?,
    onOpenPlaylist: (String?) -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
    onSignIn: () -> Unit,
) {
    // Kept above the screens opened from here, so closing them comes back to the same section.
    var section by rememberSaveable { mutableStateOf(LibrarySection.LIKED) }
    if (settingsOpen) {
        SettingsScreen(account, tunnel, updater, contentPadding, onBack = { onOpenSettings(false) }, onSignIn = onSignIn)
        return
    }
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

    val update by updater.state.collectAsStateWithLifecycle()
    var importing by rememberSaveable { mutableStateOf(false) }
    val tracks = if (section == LibrarySection.HISTORY) data.recent else data.liked
    val offline = LocalTrackUi.current.offline
    val readyOffline = tracks.count { offline[it.key] == OfflineState.READY }
    val failedOffline = tracks.count { offline[it.key] == OfflineState.FAILED }

    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScreenTitle(stringResource(R.string.tab_library), Modifier.weight(1f))
                IconButton(onClick = { onOpenSettings(true) }, modifier = Modifier.padding(end = 8.dp).testTag("settings")) {
                    // A dot while a new version waits in the settings.
                    BadgedBox(badge = { if (update is UpdateState.ForRelease) Badge(containerColor = MaterialTheme.colorScheme.primary) }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            }
        }
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
                        summary = pluralStringResource(R.plurals.tracks_count, tracks.size, tracks.size),
                        onPlay = { onPlay(tracks, 0) },
                        onShuffle = { onShuffle(tracks) },
                        // Liked tracks download by themselves; the button fetches what failed.
                        leading = if (section == LibrarySection.LIKED) {
                            { OfflineButton(readyOffline, tracks.size, onDownload = onRetryDownloads) }
                        } else {
                            null
                        },
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

/**
 * How many tracks a list holds, and buttons to play it in order or shuffled;
 * [leading] goes before the count.
 */
@Composable
internal fun TracksHeader(
    summary: String,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (leading != null) 4.dp else 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        OutlinedIconButton(onClick = onShuffle) {
            Icon(Icons.Filled.Shuffle, contentDescription = stringResource(R.string.shuffle))
        }
        Spacer(Modifier.width(8.dp))
        Button(onClick = onPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.play_all))
        }
    }
}
