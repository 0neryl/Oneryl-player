package com.oneryl.music.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.oneryl.music.R
import com.oneryl.music.data.MusicCatalog
import com.oneryl.music.data.MusicSource
import com.oneryl.music.ui.components.LoadState
import com.oneryl.music.ui.components.LoadingBlock
import com.oneryl.music.ui.components.MessageBlock
import com.oneryl.music.ui.components.ScreenTitle
import com.oneryl.music.ui.components.TrackRow
import com.oneryl.music.ui.components.label
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class SearchViewModel(private val catalog: MusicCatalog) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _source = MutableStateFlow(MusicSource.YOUTUBE_MUSIC)
    val source: StateFlow<MusicSource> = _source.asStateFlow()

    private val submitted = MutableStateFlow(0)

    val results: StateFlow<LoadState> =
        combine(_query.debounce(SEARCH_DELAY_MS), _source, submitted) { query, source, _ -> query.trim() to source }
            .flatMapLatest { (query, source) ->
                flow {
                    if (query.isEmpty()) {
                        emit(LoadState.Idle)
                        return@flow
                    }
                    emit(LoadState.Loading)
                    emit(
                        try {
                            LoadState.Loaded(catalog.search(source, query))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            LoadState.Failed
                        },
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LoadState.Idle)

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSource(value: MusicSource) {
        _source.value = value
    }

    fun retry() {
        submitted.value++
    }

    private companion object {
        const val SEARCH_DELAY_MS = 450L
    }
}

@Composable
fun SearchScreen(viewModel: SearchViewModel, contentPadding: PaddingValues) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val source by viewModel.source.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = LocalFocusManager.current

    Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
        ScreenTitle(stringResource(R.string.tab_search))
        TextField(
            value = query,
            onValueChange = viewModel::setQuery,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .testTag("search_field"),
            placeholder = { Text(stringResource(R.string.search_hint)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { viewModel.setQuery("") }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_clear))
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(8.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        )
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MusicSource.entries.forEach { option ->
                FilterChip(
                    selected = option == source,
                    onClick = { viewModel.setSource(option) },
                    label = { Text(option.label) },
                )
            }
        }

        when (val state = results) {
            LoadState.Idle -> MessageBlock(stringResource(R.string.search_prompt))
            LoadState.Loading -> LoadingBlock()
            LoadState.Failed -> MessageBlock(stringResource(R.string.load_failed), onRetry = viewModel::retry)
            is LoadState.Loaded -> if (state.tracks.isEmpty()) {
                MessageBlock(stringResource(R.string.search_nothing_found))
            } else {
                LazyColumn(
                    state = rememberLazyListState(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                ) {
                    itemsIndexed(state.tracks, key = { _, track -> track.key }) { index, _ ->
                        TrackRow(state.tracks, index)
                    }
                }
            }
        }
    }
}
