package com.oneryl.music.ui.home

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneryl.music.R
import com.oneryl.music.data.LibraryStore
import com.oneryl.music.data.MusicCatalog
import com.oneryl.music.data.MusicSource
import com.oneryl.music.ui.components.LoadState
import com.oneryl.music.ui.components.LoadingBlock
import com.oneryl.music.ui.components.MessageBlock
import com.oneryl.music.ui.components.ScreenTitle
import com.oneryl.music.ui.components.SectionTitle
import com.oneryl.music.ui.components.TrackCarousel
import com.oneryl.music.ui.update.UpdateCard
import com.oneryl.music.update.AppUpdater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class HomeViewModel(private val catalog: MusicCatalog) : ViewModel() {

    private val charts = MusicSource.entries.associateWith { MutableStateFlow<LoadState>(LoadState.Loading) }

    init {
        MusicSource.entries.forEach(::load)
    }

    fun charts(source: MusicSource): StateFlow<LoadState> = charts.getValue(source).asStateFlow()

    fun load(source: MusicSource) {
        val state = charts.getValue(source)
        state.value = LoadState.Loading
        viewModelScope.launch {
            state.value = try {
                LoadState.Loaded(catalog.charts(source))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LoadState.Failed
            }
        }
    }
}

@Composable
fun HomeScreen(viewModel: HomeViewModel, library: LibraryStore, updater: AppUpdater, contentPadding: PaddingValues) {
    val data by library.state.collectAsStateWithLifecycle()
    val youtube by viewModel.charts(MusicSource.YOUTUBE_MUSIC).collectAsStateWithLifecycle()
    val soundCloud by viewModel.charts(MusicSource.SOUNDCLOUD).collectAsStateWithLifecycle()

    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        item { ScreenTitle(stringResource(R.string.app_name)) }
        item { UpdateCard(updater) }
        if (data.recent.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.home_recent)) }
            item { TrackCarousel(data.recent.take(20)) }
        }
        chartSection(R.string.home_youtube_charts, youtube) { viewModel.load(MusicSource.YOUTUBE_MUSIC) }
        chartSection(R.string.home_soundcloud_charts, soundCloud) { viewModel.load(MusicSource.SOUNDCLOUD) }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

private fun LazyListScope.chartSection(
    title: Int,
    state: LoadState,
    onRetry: () -> Unit,
) {
    item { SectionTitle(stringResource(title)) }
    item {
        when (state) {
            LoadState.Idle, LoadState.Loading -> LoadingBlock()
            LoadState.Failed -> MessageBlock(stringResource(R.string.load_failed), onRetry = onRetry)
            is LoadState.Loaded -> TrackCarousel(state.tracks)
        }
    }
}
