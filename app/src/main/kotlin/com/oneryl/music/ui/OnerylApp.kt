package com.oneryl.music.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.oneryl.music.R
import com.oneryl.music.data.LibraryStore
import com.oneryl.music.data.MusicCatalog
import com.oneryl.music.data.PlaylistImporter
import com.oneryl.music.data.Track
import com.oneryl.music.data.YoutubeAccount
import com.oneryl.music.net.ServerTunnel
import com.oneryl.music.playback.OfflineTracks
import com.oneryl.music.playback.PlayerConnection
import com.oneryl.music.ui.account.YoutubeLoginScreen
import com.oneryl.music.ui.components.LocalTrackUi
import com.oneryl.music.ui.components.TrackActions
import com.oneryl.music.ui.components.TrackUi
import com.oneryl.music.ui.home.HomeScreen
import com.oneryl.music.ui.home.HomeViewModel
import com.oneryl.music.ui.library.LibraryScreen
import com.oneryl.music.ui.player.MiniPlayer
import com.oneryl.music.ui.player.PlayerScreen
import com.oneryl.music.ui.search.SearchScreen
import com.oneryl.music.ui.search.SearchViewModel
import kotlinx.coroutines.launch

enum class Tab(val title: Int, val icon: ImageVector, val selectedIcon: ImageVector) {
    HOME(R.string.tab_home, Icons.Outlined.Home, Icons.Filled.Home),
    SEARCH(R.string.tab_search, Icons.Filled.Search, Icons.Filled.Search),
    LIBRARY(R.string.tab_library, Icons.Outlined.LibraryMusic, Icons.Filled.LibraryMusic),
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun OnerylApp(
    catalog: MusicCatalog,
    library: LibraryStore,
    offline: OfflineTracks,
    account: YoutubeAccount,
    importer: PlaylistImporter,
    tunnel: ServerTunnel,
    connection: PlayerConnection,
) {
    val player by connection.state.collectAsStateWithLifecycle()
    val libraryData by library.state.collectAsStateWithLifecycle()
    val offlineStates by offline.states.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var playerOpen by rememberSaveable { mutableStateOf(false) }
    var signingIn by rememberSaveable { mutableStateOf(false) }
    var openPlaylistId by rememberSaveable { mutableStateOf<String?>(null) }
    val signInFailed = stringResource(R.string.account_sign_in_failed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val tabStates = rememberSaveableStateHolder()

    val homeViewModel = viewModel { HomeViewModel(catalog) }
    val searchViewModel = viewModel { SearchViewModel(catalog) }

    val likedKeys = remember(libraryData.liked) { libraryData.liked.mapTo(HashSet()) { it.key } }
    val pinnedKeys = remember(libraryData.pinned) { libraryData.pinned.mapTo(HashSet()) { it.key } }
    val actions = remember(connection, library) {
        object : TrackActions {
            override fun play(tracks: List<Track>, index: Int) = connection.play(tracks, index)
            override fun playNext(track: Track) = connection.playNext(track)
            override fun addToQueue(track: Track) = connection.addToQueue(track)
            override fun toggleLike(track: Track) = library.toggleLike(track)
            override fun toggleDownload(track: Track) = library.togglePinned(track)
        }
    }
    val current = player.current
    val currentLiked = current?.key in likedKeys
    val toggleCurrentLike = { current?.let(library::toggleLike) ?: Unit }

    LaunchedEffect(connection) {
        connection.errors.collect { snackbar.showSnackbar(it) }
    }
    LaunchedEffect(current == null) {
        if (current == null) playerOpen = false
    }
    // Back closes the topmost thing first: sign-in, the player, an open playlist, then the tab.
    val playlistShown = tab == Tab.LIBRARY && openPlaylistId != null
    BackHandler(enabled = signingIn) { signingIn = false }
    BackHandler(enabled = !signingIn && playerOpen) { playerOpen = false }
    BackHandler(enabled = !signingIn && !playerOpen && playlistShown) { openPlaylistId = null }
    BackHandler(enabled = !signingIn && !playerOpen && !playlistShown && tab != Tab.HOME) { tab = Tab.HOME }

    CompositionLocalProvider(
        LocalTrackUi provides TrackUi(current?.key, player.isPlaying, likedKeys, actions, offlineStates, pinnedKeys),
    ) {
        // Test tags double as resource ids so the emulator test in CI can find the controls.
        Box(Modifier.fillMaxSize().semantics { testTagsAsResourceId = true }) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                bottomBar = {
                    Column {
                        MiniPlayer(
                            state = player,
                            connection = connection,
                            isLiked = currentLiked,
                            onToggleLike = toggleCurrentLike,
                            onExpand = { playerOpen = true },
                            modifier = Modifier.testTag("mini_player"),
                        )
                        NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                            Tab.entries.forEach { item ->
                                NavigationBarItem(
                                    selected = item == tab,
                                    onClick = { tab = item },
                                    icon = {
                                        Icon(if (item == tab) item.selectedIcon else item.icon, contentDescription = null)
                                    },
                                    label = { Text(stringResource(item.title)) },
                                    colors = NavigationBarItemDefaults.colors(
                                        indicatorColor = MaterialTheme.colorScheme.background,
                                        selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                        selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                    label = "tabs",
                ) { shown ->
                    tabStates.SaveableStateProvider(shown) {
                        when (shown) {
                            Tab.HOME -> HomeScreen(homeViewModel, library, padding)
                            Tab.SEARCH -> SearchScreen(searchViewModel, padding)
                            Tab.LIBRARY -> LibraryScreen(
                                library = library,
                                account = account,
                                importer = importer,
                                tunnel = tunnel,
                                onRetryDownloads = offline::retryFailed,
                                contentPadding = padding,
                                openPlaylistId = openPlaylistId,
                                onOpenPlaylist = { openPlaylistId = it },
                                onPlay = connection::play,
                                onShuffle = connection::shuffleAll,
                                onSignIn = { signingIn = true },
                            )
                        }
                    }
                }
            }

            AnimatedVisibility(
                visible = playerOpen && current != null,
                enter = slideInVertically(tween(260)) { it } + fadeIn(tween(160)),
                exit = slideOutVertically(tween(220)) { it } + fadeOut(tween(200)),
            ) {
                PlayerScreen(
                    state = player,
                    connection = connection,
                    isLiked = currentLiked,
                    onToggleLike = toggleCurrentLike,
                    onClose = { playerOpen = false },
                )
            }

            if (signingIn) {
                YoutubeLoginScreen(
                    onSignedIn = { session ->
                        account.signIn(session)
                        signingIn = false
                    },
                    onFailed = {
                        signingIn = false
                        scope.launch { snackbar.showSnackbar(signInFailed) }
                    },
                    onClose = { signingIn = false },
                )
            }
        }
    }
}
