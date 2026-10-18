package com.oneryl.music.data

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.SoundCloudGoPlusContentException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import java.io.IOException
import java.net.URI

/**
 * A stream ready to load. YouTube streams are single files fetched in chunks
 * (see [GoogleVideo]); SoundCloud streams are HLS playlists.
 */
data class ResolvedStream(
    val url: String,
    val isHls: Boolean,
    val contentLength: Long,
    val headers: Map<String, String>,
    val expiresAtMillis: Long,
)

/** Why a track cannot be played, so the app can say it in the user's language. */
enum class UnavailableReason {
    NOT_AVAILABLE,
    AGE_RESTRICTED,
    PREMIUM_ONLY,
    REGION_LOCKED,
    BLOCKED_BY_SERVICE,
    NO_AUDIO,
    UNKNOWN,
}

class StreamUnavailableException(
    val reason: UnavailableReason,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/** Turns a [Track.streamUri] into a stream URL, remembering it until it expires. */
class StreamResolver(private val now: () -> Long = System::currentTimeMillis) {

    private val cache = object : LinkedHashMap<String, ResolvedStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>) =
            size > MAX_CACHED
    }

    /** Blocks on the network; call it from a loader or IO thread. */
    fun resolve(streamUri: String): ResolvedStream {
        synchronized(cache) {
            cache[streamUri]?.takeIf { it.expiresAtMillis > now() }?.let { return it }
        }
        val uri = URI(streamUri)
        val source = musicSourceForHost(uri.host.orEmpty())
            ?: throw StreamUnavailableException(UnavailableReason.UNKNOWN, "Unknown stream $streamUri")
        val track = Track(source, uri.path.trim('/'), "", "", 0, null)
        val resolved = try {
            when (source) {
                MusicSource.YOUTUBE_MUSIC -> resolveYoutube(track.pageUrl)
                MusicSource.SOUNDCLOUD -> resolveSoundCloud(track.pageUrl)
            }
        } catch (e: StreamUnavailableException) {
            throw e
        } catch (e: IOException) {
            throw e
        } catch (e: Exception) {
            throw StreamUnavailableException(reasonFor(e), "Cannot play ${track.pageUrl}: ${e.message}", e)
        }
        synchronized(cache) { cache[streamUri] = resolved }
        return resolved
    }

    fun invalidate(streamUri: String) {
        synchronized(cache) { cache.remove(streamUri) }
    }

    private fun resolveYoutube(pageUrl: String): ResolvedStream {
        val extractor = ServiceList.YouTube.getStreamExtractor(pageUrl)
        extractor.fetchPage()
        val stream = pickYoutubeStream(extractor.audioStreams)
            ?: throw StreamUnavailableException(UnavailableReason.NO_AUDIO, "No audio stream for $pageUrl")
        val url = stream.content
        return ResolvedStream(
            url = url,
            isHls = false,
            contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 }
                ?: queryParameter(url, "clen")?.toLongOrNull()
                ?: -1,
            headers = GoogleVideo.headersFor(url),
            expiresAtMillis = queryParameter(url, "expire")?.toLongOrNull()
                ?.let { it * 1000 - EXPIRY_MARGIN_MS }
                ?: (now() + YOUTUBE_FALLBACK_TTL_MS),
        )
    }

    private fun resolveSoundCloud(pageUrl: String): ResolvedStream {
        val extractor = ServiceList.SoundCloud.getStreamExtractor(pageUrl)
        extractor.fetchPage()
        val stream = pickSoundCloudStream(extractor.audioStreams)
            ?: throw StreamUnavailableException(UnavailableReason.NO_AUDIO, "No audio stream for $pageUrl")
        return ResolvedStream(
            url = stream.content,
            isHls = true,
            contentLength = -1,
            headers = emptyMap(),
            expiresAtMillis = now() + SOUNDCLOUD_TTL_MS,
        )
    }

    companion object {
        private const val MAX_CACHED = 200
        private const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L
        private const val YOUTUBE_FALLBACK_TTL_MS = 3 * 60 * 60 * 1000L
        private const val SOUNDCLOUD_TTL_MS = 3 * 60 * 1000L

        /** Opus in WebM sounds best for its size; AAC in M4A is the fallback. */
        internal fun pickYoutubeStream(streams: List<AudioStream>): AudioStream? =
            streams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
                .filter { it.format == MediaFormat.WEBMA_OPUS || it.format == MediaFormat.M4A }
                .sortedWith(
                    compareByDescending<AudioStream> { it.format == MediaFormat.WEBMA_OPUS }
                        .thenByDescending { it.averageBitrate },
                )
                .firstOrNull()

        /** The player reads SoundCloud as HLS; its Opus playlists are not supported there. */
        internal fun pickSoundCloudStream(streams: List<AudioStream>): AudioStream? =
            streams
                .filter { it.isUrl && it.deliveryMethod == DeliveryMethod.HLS }
                .filter { it.format == MediaFormat.M4A || it.format == MediaFormat.MP3 }
                .minByOrNull { if (it.format == MediaFormat.M4A) 0 else 1 }

        internal fun reasonFor(error: Exception): UnavailableReason = when (error) {
            is AgeRestrictedContentException -> UnavailableReason.AGE_RESTRICTED
            is YoutubeMusicPremiumContentException, is PaidContentException, is SoundCloudGoPlusContentException ->
                UnavailableReason.PREMIUM_ONLY
            is GeographicRestrictionException -> UnavailableReason.REGION_LOCKED
            is SignInConfirmNotBotException, is ReCaptchaException -> UnavailableReason.BLOCKED_BY_SERVICE
            is ContentNotAvailableException -> UnavailableReason.NOT_AVAILABLE
            else -> UnavailableReason.UNKNOWN
        }

        fun queryParameter(url: String, name: String): String? =
            runCatching { URI(url).rawQuery }.getOrNull()
                ?.split('&')
                ?.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
    }
}

/**
 * How googlevideo.com expects audio to be fetched: NewPipe's player requests
 * ranges through the `range` query parameter in POST requests, numbered with
 * `rn`, and so does this app.
 */
object GoogleVideo {
    const val CHUNK_BYTES = 1024 * 1024L
    val POST_BODY = byteArrayOf(0x78, 0)

    fun headersFor(url: String): Map<String, String> = buildMap {
        put(
            "User-Agent",
            if (YoutubeParsingHelper.isVisionOsStreamingUrl(url)) {
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            } else {
                NewPipeDownloader.USER_AGENT
            },
        )
        if (YoutubeParsingHelper.isWebStreamingUrl(url)) {
            put("Origin", "https://www.youtube.com")
            put("Referer", "https://www.youtube.com")
            put("Sec-Fetch-Dest", "empty")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Site", "cross-site")
        }
    }

    /** [endInclusive] is -1 for "to the end of the file". */
    fun chunkUrl(url: String, requestNumber: Int, start: Long, endInclusive: Long): String {
        val range = if (endInclusive >= 0) "$start-$endInclusive" else "$start-"
        return "$url&rn=$requestNumber&range=$range"
    }
}
