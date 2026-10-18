package com.oneryl.music.playback

import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.oneryl.music.data.MusicSource
import com.oneryl.music.data.NewPipeDownloader
import com.oneryl.music.data.StreamResolver
import com.oneryl.music.data.Track
import com.oneryl.music.data.musicSourceForHost
import okhttp3.OkHttpClient

/**
 * Loads `oneryl://` track URIs: downloaded tracks from [offlineCache], the
 * rest from the network, YouTube through [streamCache] and the chunked
 * googlevideo reader and SoundCloud HLS playlists and segments directly
 * (their signed URLs change, so caching them is pointless).
 */
@UnstableApi
class PlayerDataSourceFactory(
    client: OkHttpClient,
    private val resolver: StreamResolver,
    streamCache: Cache,
    offlineCache: Cache?,
    /** False for downloads: they take what playback cached but keep their own copy. */
    writeStreamCache: Boolean = true,
) : DataSource.Factory {

    private val http = OkHttpDataSource.Factory(client).setUserAgent(NewPipeDownloader.USER_AGENT)

    private val resolve = ResolvingDataSource.Resolver { dataSpec ->
        if (dataSpec.uri.scheme != Track.STREAM_SCHEME) return@Resolver dataSpec
        val stream = resolver.resolve(dataSpec.uri.toString())
        dataSpec.withUri(stream.url.toUri()).withAdditionalHeaders(
            stream.headers + GoogleVideoDataSource.instructions(stream),
        )
    }

    private val youtube = CacheDataSource.Factory()
        .setCache(streamCache)
        .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(GoogleVideoDataSource.Factory(client), resolve))
        .apply { if (!writeStreamCache) setCacheWriteDataSinkFactory(null) }
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val soundCloud = ResolvingDataSource.Factory(http, resolve)

    private val network = DataSource.Factory {
        RoutingDataSource(
            youtube = youtube.createDataSource(),
            soundCloud = soundCloud.createDataSource(),
            http = http.createDataSource(),
        )
    }

    /** Reads downloads only; [OfflineTracks] is the one writing them. */
    private val offline = offlineCache?.let {
        CacheDataSource.Factory()
            .setCache(it)
            .setUpstreamDataSourceFactory(network)
            .setCacheWriteDataSinkFactory(null)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    override fun createDataSource(): DataSource = (offline ?: network).createDataSource()
}

@UnstableApi
private class RoutingDataSource(
    private val youtube: DataSource,
    private val soundCloud: DataSource,
    private val http: DataSource,
) : DataSource {

    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        youtube.addTransferListener(transferListener)
        soundCloud.addTransferListener(transferListener)
        http.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val source = when (dataSpec.uri.trackSource()) {
            MusicSource.YOUTUBE_MUSIC -> youtube
            MusicSource.SOUNDCLOUD -> soundCloud
            null -> http
        }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        checkNotNull(current).read(buffer, offset, length)

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders.orEmpty()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }
}

fun Uri.trackSource(): MusicSource? =
    if (scheme == Track.STREAM_SCHEME) musicSourceForHost(host.orEmpty()) else null
