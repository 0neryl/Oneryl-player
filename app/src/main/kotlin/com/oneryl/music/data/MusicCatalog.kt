package com.oneryl.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingMusicLinkHandlerFactory
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URI

/** Search and charts for both sources, on top of NewPipe Extractor. */
class MusicCatalog(private val io: CoroutineDispatcher = Dispatchers.IO) {

    /**
     * YouTube Music sometimes answers the "Songs" filter with nothing (it does
     * for requests from data centres), so music videos are the fallback.
     */
    suspend fun search(source: MusicSource, query: String): List<Track> = withContext(io) {
        val filters = when (source) {
            MusicSource.YOUTUBE_MUSIC -> listOf(
                YoutubeSearchQueryHandlerFactory.MUSIC_SONGS,
                YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS,
            )
            MusicSource.SOUNDCLOUD -> listOf(SoundcloudSearchQueryHandlerFactory.TRACKS)
        }
        filters.asSequence()
            .map { source.service.getSearchExtractor(query, listOf(it), "").tracks(source) }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
    }

    suspend fun charts(source: MusicSource): List<Track> = withContext(io) {
        val kiosks = source.service.kioskList
        when (source) {
            MusicSource.YOUTUBE_MUSIC -> kiosks.getExtractorById(YoutubeTrendingMusicLinkHandlerFactory.KIOSK_ID, null)
            MusicSource.SOUNDCLOUD -> kiosks.defaultKioskExtractor
        }.tracks(source)
    }

    private fun ListExtractor<out InfoItem>.tracks(source: MusicSource): List<Track> {
        fetchPage()
        return initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrack(source) }
            .distinctBy { it.id }
    }
}

val MusicSource.service: StreamingService
    get() = when (this) {
        MusicSource.YOUTUBE_MUSIC -> ServiceList.YouTube
        MusicSource.SOUNDCLOUD -> ServiceList.SoundCloud
    }

internal fun StreamInfoItem.toTrack(source: MusicSource): Track? {
    if (streamType == StreamType.LIVE_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
        return null
    }
    val id = when (source) {
        MusicSource.YOUTUBE_MUSIC -> runCatching { source.service.streamLHFactory.getId(url) }.getOrNull()
        MusicSource.SOUNDCLOUD -> soundCloudPath(url)
    } ?: return null
    return Track(
        source = source,
        id = id,
        title = name,
        artist = uploaderName.orEmpty(),
        durationSeconds = duration.coerceAtLeast(0),
        artworkUrl = bestArtwork(thumbnails),
    )
}

internal fun soundCloudPath(url: String): String? =
    runCatching { URI(url).path.trim('/') }.getOrNull()?.takeIf { it.count { c -> c == '/' } == 1 }

private val GOOGLE_SIZE = Regex("=w\\d+-h\\d+")
private val GOOGLE_SQUARE_SIZE = Regex("=s\\d+")

/** Picks the largest thumbnail and asks the CDN for a bigger version where it allows it. */
internal fun bestArtwork(images: List<Image>): String? {
    val url = images.maxByOrNull { it.height }?.url ?: return null
    return when {
        "googleusercontent.com" in url || "ggpht.com" in url ->
            url.replace(GOOGLE_SIZE, "=w544-h544").replace(GOOGLE_SQUARE_SIZE, "=s544")
        "sndcdn.com" in url -> url.replace("-large.", "-t500x500.")
        else -> url
    }
}
