package com.oneryl.music.ui.artist

import android.icu.text.CompactDecimalFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneryl.music.R
import com.oneryl.music.data.ArtistPage
import com.oneryl.music.data.ArtistRequest
import com.oneryl.music.data.MusicCatalog
import com.oneryl.music.data.MusicSource
import com.oneryl.music.data.Track
import com.oneryl.music.ui.components.Artwork
import com.oneryl.music.ui.components.LoadingBlock
import com.oneryl.music.ui.components.MessageBlock
import com.oneryl.music.ui.components.TrackRow
import com.oneryl.music.ui.components.label
import com.oneryl.music.ui.library.TracksHeader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

sealed interface ArtistState {
    data object Loading : ArtistState
    data object NotFound : ArtistState
    data object Failed : ArtistState
    data class Loaded(val page: ArtistPage) : ArtistState
}

/** The artist on screen and how far loading them got. */
data class ArtistLoad(val request: ArtistRequest, val state: ArtistState)

/** Loads one artist at a time: opening another drops the one before. */
class ArtistViewModel(private val catalog: MusicCatalog) : ViewModel() {

    private val _load = MutableStateFlow<ArtistLoad?>(null)
    val load: StateFlow<ArtistLoad?> = _load.asStateFlow()
    private var job: Job? = null

    /** Loads [request], unless it is loaded or loading already. */
    fun open(request: ArtistRequest) {
        val current = _load.value
        if (current?.request == request && current.state != ArtistState.Failed) return
        fetch(request)
    }

    fun retry() {
        _load.value?.request?.let(::fetch)
    }

    private fun fetch(request: ArtistRequest) {
        job?.cancel()
        _load.value = ArtistLoad(request, ArtistState.Loading)
        job = viewModelScope.launch {
            val state = try {
                catalog.artist(request)?.let(ArtistState::Loaded) ?: ArtistState.NotFound
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ArtistState.Failed
            }
            _load.value = ArtistLoad(request, state)
        }
    }
}

/** Keeps the open artist across rotation and the app being killed in the background. */
val ArtistRequestSaver: Saver<ArtistRequest?, Any> = Saver(
    save = { request -> request?.let { arrayListOf(it.source.name, it.name, it.soundCloudUser) } },
    restore = { saved ->
        (saved as? List<*>)?.let { ArtistRequest(MusicSource.valueOf(it[0] as String), it[1] as String, it[2] as String?) }
    },
)

/** An artist's page on one service: picture, name, followers and their tracks. */
@Composable
fun ArtistScreen(
    viewModel: ArtistViewModel,
    request: ArtistRequest,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPlay: (List<Track>, Int) -> Unit,
    onShuffle: (List<Track>) -> Unit,
) {
    LaunchedEffect(request) { viewModel.open(request) }
    val load by viewModel.load.collectAsStateWithLifecycle()
    // Until the view model catches up, the artist before this one is still in it.
    val state = load?.takeIf { it.request == request }?.state ?: ArtistState.Loading
    val page = (state as? ArtistState.Loaded)?.page

    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = contentPadding,
        modifier = Modifier.testTag("artist_screen"),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        }
        item {
            ArtistHeader(
                name = page?.name ?: request.name,
                avatarUrl = page?.avatarUrl,
                source = request.source,
                followers = page?.followers ?: -1,
            )
        }
        when (state) {
            ArtistState.Loading -> item { LoadingBlock() }
            ArtistState.NotFound -> item { MessageBlock(stringResource(R.string.artist_not_found, request.source.label)) }
            ArtistState.Failed -> item { MessageBlock(stringResource(R.string.load_failed), onRetry = viewModel::retry) }
            is ArtistState.Loaded -> {
                val tracks = state.page.tracks
                if (tracks.isEmpty()) {
                    item { MessageBlock(stringResource(R.string.artist_no_tracks)) }
                } else {
                    item {
                        TracksHeader(
                            summary = pluralStringResource(R.plurals.tracks_count, tracks.size, tracks.size),
                            onPlay = { onPlay(tracks, 0) },
                            onShuffle = { onShuffle(tracks) },
                        )
                    }
                    itemsIndexed(tracks, key = { _, track -> track.key }) { index, _ -> TrackRow(tracks, index) }
                }
            }
        }
    }
}

@Composable
private fun ArtistHeader(name: String, avatarUrl: String?, source: MusicSource, followers: Long) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Artwork(avatarUrl, Modifier.size(160.dp), corner = 80.dp)
        Spacer(Modifier.height(16.dp))
        Text(
            name,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("artist_name"),
        )
        val about = listOfNotNull(
            source.label,
            followers.takeIf { it >= 0 }?.let { stringResource(R.string.artist_followers, compactNumber(it)) },
        )
        Text(
            about.joinToString(" • "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }
}

/** 1 234 567 as "1.2M", or "1,2 млн" in Russian. */
private fun compactNumber(value: Long): String =
    CompactDecimalFormat.getInstance(Locale.getDefault(), CompactDecimalFormat.CompactStyle.SHORT).format(value)

/** Asks which service to open the artist [name] on. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ArtistSourceDialog(name: String, onPick: (MusicSource) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            // A dialog is a window of its own, so the emulator test needs the tags exposed here too.
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.semantics { testTagsAsResourceId = true },
            ) {
                Text(stringResource(R.string.artist_where))
                Spacer(Modifier.height(4.dp))
                MusicSource.entries.forEach { source ->
                    Text(
                        source.label,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(source) }
                            .padding(vertical = 12.dp)
                            .testTag("artist_on_${source.name.lowercase()}"),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}
