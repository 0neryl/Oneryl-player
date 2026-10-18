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
 * Loads `oneryl://` track URIs: YouTube through the cache and the chunked
 * googlevideo reader, SoundCloud HLS playlists and segments straight from the
 * network (their signed URLs change, so caching them is pointless).
 */
@UnstableApi
class PlayerDataSourceFactory(
    client: OkHttpClient,
    private val resolver: StreamResolver,
    cache: Cache,
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
        .setCache(cache)
        .setUpstreamDataSourceFactory(ResolvingDataSource.Factory(GoogleVideoDataSource.Factory(client), resolve))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val soundCloud = ResolvingDataSource.Factory(http, resolve)

    override fun createDataSource(): DataSource = RoutingDataSource(
        youtube = youtube.createDataSource(),
        soundCloud = soundCloud.createDataSource(),
        http = http.createDataSource(),
    )
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
