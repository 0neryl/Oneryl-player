package com.oneryl.music.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.ListExtractor
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.channel.ChannelExtractor
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeTrendingMusicLinkHandlerFactory
import org.schabi.newpipe.extractor.services.soundcloud.linkHandler.SoundcloudSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.net.URI

/** An artist on one service: who they are and their tracks, most popular first. */
data class ArtistPage(
    val source: MusicSource,
    val name: String,
    val avatarUrl: String?,
    /** Subscribers or followers, or -1 when the service does not say. */
    val followers: Long,
    val tracks: List<Track>,
)

/**
 * An artist to open on [source], by the credit a track gave them.
 * [soundCloudUser] is the uploader of the SoundCloud track they were opened
 * from, which is their page there without a search.
 */
data class ArtistRequest(val source: MusicSource, val name: String, val soundCloudUser: String? = null) {

    /** The same artist on [other]; the SoundCloud user means nothing elsewhere. */
    fun on(other: MusicSource) = copy(source = other, soundCloudUser = soundCloudUser.takeIf { other == MusicSource.SOUNDCLOUD })

    companion object {
        fun of(track: Track) = ArtistRequest(
            source = track.source,
            name = track.artist,
            soundCloudUser = track.id.substringBefore('/').takeIf { track.source == MusicSource.SOUNDCLOUD },
        )
    }
}

/** Search, charts and artists for both sources, on top of NewPipe Extractor. */
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

    /**
     * The artist [request] names, or null if the service knows no one by that
     * name. A SoundCloud user in the request is opened straight away instead
     * of searched for.
     */
    suspend fun artist(request: ArtistRequest): ArtistPage? = withContext(io) {
        when (request.source) {
            MusicSource.YOUTUBE_MUSIC -> youtubeArtist(request.name)
            MusicSource.SOUNDCLOUD -> soundCloudArtist(request.name, request.soundCloudUser)
        }
    }

    /** YouTube Music: the artist channel for the picture, and their songs from a search. */
    private fun youtubeArtist(credit: String): ArtistPage? {
        val source = MusicSource.YOUTUBE_MUSIC
        val names = artistNames(credit)
        val channel = names.firstNotNullOfOrNull { findChannel(source, YoutubeSearchQueryHandlerFactory.MUSIC_ARTISTS, it) }
        val artistName = channel?.name ?: names.last()
        val songs = listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.MUSIC_VIDEOS)
            .asSequence()
            .map { filter -> source.service.getSearchExtractor(artistName, listOf(filter), "").tracks(source) }
            .map { tracks ->
                val wanted = TrackMatcher.normalize(artistName)
                // Their own uploads first, then fan channels and features.
                tracks.filter { isBy(it, artistName) }.sortedByDescending { TrackMatcher.normalize(it.artist) == wanted }
            }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()
        if (channel == null && songs.isEmpty()) return null
        return ArtistPage(source, artistName, channel?.let { bestArtwork(it.thumbnails) }, channel?.subscriberCount ?: -1, songs)
    }

    /** SoundCloud: the user's page and the tracks they uploaded. */
    private fun soundCloudArtist(credit: String, user: String?): ArtistPage? {
        val source = MusicSource.SOUNDCLOUD
        val url = user?.let { "https://soundcloud.com/$it" }
            ?: artistNames(credit).firstNotNullOfOrNull { findChannel(source, SoundcloudSearchQueryHandlerFactory.USERS, it) }?.url
            ?: return null
        val channel: ChannelExtractor = source.service.getChannelExtractor(url).apply { fetchPage() }
        val tracksTab = channel.tabs.firstOrNull { ChannelTabs.TRACKS in it.contentFilters }
        val tracks = tracksTab?.let { source.service.getChannelTabExtractor(it).tracks(source) }.orEmpty()
        return ArtistPage(source, channel.name, bestArtwork(channel.avatars), channel.subscriberCount, tracks)
    }

    /** The search result for [filter] whose name is closest to [name], if any is close. */
    private fun findChannel(source: MusicSource, filter: String, name: String): ChannelInfoItem? {
        val wanted = TrackMatcher.normalize(name)
        return source.service.getSearchExtractor(name, listOf(filter), "")
            .apply { fetchPage() }
            .initialPage.items
            .filterIsInstance<ChannelInfoItem>()
            .take(CHANNEL_CANDIDATES)
            .map { it to TrackMatcher.similarity(wanted, TrackMatcher.normalize(it.name)) }
            .filter { (_, similarity) -> similarity >= CHANNEL_SIMILARITY }
            .maxByOrNull { (_, similarity) -> similarity }
            ?.first
    }

    private fun isBy(track: Track, artist: String): Boolean {
        val wanted = TrackMatcher.normalize(artist)
        val by = TrackMatcher.normalize(track.artist)
        return wanted.isNotEmpty() && (wanted in by || TrackMatcher.similarity(wanted, by) >= CHANNEL_SIMILARITY)
    }

    private fun ListExtractor<out InfoItem>.tracks(source: MusicSource): List<Track> {
        fetchPage()
        return initialPage.items
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { it.toTrack(source) }
            .distinctBy { it.id }
    }

    private companion object {
        const val CHANNEL_CANDIDATES = 5
        const val CHANNEL_SIMILARITY = 0.7
    }
}

private val CREDIT_SEPARATORS = Regex("""\s*(?:,|&|;|\s(?:feat\.?|ft\.?|x)\s)\s*""", RegexOption.IGNORE_CASE)
private val CHANNEL_DECORATIONS = Regex("""(?:\s+-\s+Topic|VEVO)$""")

/**
 * The names to look an artist up by, from a credit such as "A, B feat. C":
 * the whole credit, since "Simon & Garfunkel" is one act, then its first
 * artist. Without the decorations YouTube channel names carry ("A - Topic",
 * "AVEVO").
 */
fun artistNames(credit: String): List<String> {
    val whole = credit.trim().replace(CHANNEL_DECORATIONS, "").trim()
    val first = whole.split(CREDIT_SEPARATORS).first().trim()
    return listOf(whole, first).filter { it.isNotEmpty() }.distinct().ifEmpty { listOf(credit) }
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
