package com.oneryl.music

import android.app.Application
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.oneryl.music.data.LibraryStore
import com.oneryl.music.data.MusicCatalog
import com.oneryl.music.data.MusicSource
import com.oneryl.music.data.NewPipeDownloader
import com.oneryl.music.data.PlaylistImporter
import com.oneryl.music.data.StreamResolver
import com.oneryl.music.data.TrackMatcher
import com.oneryl.music.data.YandexMusic
import com.oneryl.music.data.YoutubeAccount
import com.oneryl.music.data.YoutubeIpv4Dns
import com.oneryl.music.net.ServerTunnel
import com.oneryl.music.playback.OfflineTracks
import com.oneryl.music.playback.PlayerDataSourceFactory
import com.oneryl.music.playback.youtube.InnerTubeXSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.io.File
import java.net.Proxy
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Holds the app-wide objects; there are few enough of them to wire by hand. */
@UnstableApi
class OnerylApplication : Application(), SingletonImageLoader.Factory {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val baseClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .dns(YoutubeIpv4Dns())
            .build()
    }

    /** The user's own server, when set up; everything below reaches the internet through it. */
    val tunnel: ServerTunnel by lazy {
        ServerTunnel(this, baseClient.newBuilder().proxy(Proxy.NO_PROXY).build(), scope) {
            httpClient.connectionPool.evictAll()
        }
    }

    val httpClient: OkHttpClient by lazy { baseClient.newBuilder().proxySelector(tunnel.proxySelector).build() }

    val catalog = MusicCatalog()
    val account: YoutubeAccount by lazy { YoutubeAccount(this) { youtube.describe(it) } }
    private val youtube by lazy { InnerTubeXSource(this, httpClient, account) }
    val resolver: StreamResolver by lazy { StreamResolver(youtube) }
    val library: LibraryStore by lazy { LibraryStore(File(filesDir, "library.json"), scope) }
    val importer: PlaylistImporter by lazy {
        PlaylistImporter(
            YandexMusic(httpClient),
            TrackMatcher { catalog.search(MusicSource.YOUTUBE_MUSIC, it) },
            library,
            scope,
        )
    }

    private val database by lazy { StandaloneDatabaseProvider(this) }
    private val streamCache by lazy {
        SimpleCache(File(cacheDir, "audio"), LeastRecentlyUsedCacheEvictor(AUDIO_CACHE_BYTES), database)
    }

    /** Downloads of the liked tracks; kept out of backups, which have no room for audio. */
    private val offlineCache by lazy { SimpleCache(File(noBackupFilesDir, "offline"), NoOpCacheEvictor(), database) }

    val playerDataSourceFactory: PlayerDataSourceFactory by lazy {
        PlayerDataSourceFactory(httpClient, resolver, streamCache, offlineCache)
    }

    val offline: OfflineTracks by lazy {
        val upstream = PlayerDataSourceFactory(httpClient, resolver, streamCache, offlineCache = null, writeStreamCache = false)
        OfflineTracks(this, database, offlineCache, upstream, library, scope)
    }

    override fun onCreate() {
        super.onCreate()
        tunnel.start()
        val locale = Locale.getDefault()
        NewPipe.init(
            NewPipeDownloader(httpClient),
            Localization(locale.language, locale.country.ifEmpty { null }),
            ContentCountry(locale.country.ifEmpty { "US" }),
        )
        offline // starts downloading liked tracks that are not downloaded yet
        importer.resume()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { httpClient })) }
            .build()

    private companion object {
        const val AUDIO_CACHE_BYTES = 512L * 1024 * 1024
    }
}

val android.content.Context.app: OnerylApplication
    @androidx.annotation.OptIn(UnstableApi::class)
    get() = applicationContext as OnerylApplication
