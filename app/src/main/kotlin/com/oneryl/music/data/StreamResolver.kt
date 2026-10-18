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
    val fetch: FetchStyle = FetchStyle.NEWPIPE,
    val chunkBytes: Long = GoogleVideo.CHUNK_BYTES,
    val client: String = StreamResolver.NEWPIPE_CLIENT,
)

/** How googlevideo.com wants the chunks of a YouTube stream requested. */
enum class FetchStyle {
    /** POST with the range in the URL, as NewPipe's player does. */
    NEWPIPE,

    /** Plain GET with a Range header, as InnerTubeX streams expect. */
    RANGED_GET,
}

/** A second way to get YouTube audio, tried when YouTube refuses NewPipe's client. */
fun interface YoutubeFallback {
    /**
     * Blocks on the network. [refusedClients] names the clients whose streams
     * googlevideo.com refused for this track a moment ago, so another one is
     * worth a try; [ageRestricted] says YouTube wants a signed-in adult.
     */
    fun resolve(videoId: String, refusedClients: Set<String>, ageRestricted: Boolean): ResolvedStream
}

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
    /** A short technical hint for the error message, such as the refusing client. */
    val detail: String? = null,
) : IOException(message, cause)

/**
 * Turns a [Track.streamUri] into a stream URL, remembering it until it expires.
 * YouTube goes through NewPipe first and [youtubeFallback] when YouTube refuses
 * it; after such a refusal the fallback goes first for a while, since the same
 * network keeps getting refused. A stream the server refused is not asked for
 * from the same client again for a few minutes.
 */
class StreamResolver(
    private val youtubeFallback: YoutubeFallback? = null,
    private val now: () -> Long = System::currentTimeMillis,
    private val newPipeYoutube: (pageUrl: String) -> ResolvedStream = ::resolveYoutube,
) {

    @Volatile
    private var preferFallbackUntil = 0L

    private val cache = object : LinkedHashMap<String, ResolvedStream>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedStream>) =
            size > MAX_CACHED
    }

    /** Clients whose streams were refused, by stream URI; guarded by [cache]. */
    private val refusals = object : LinkedHashMap<String, Refusal>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Refusal>) = size > MAX_CACHED
    }

    private class Refusal(val clients: Set<String>, val atMillis: Long)

    /** Blocks on the network; call it from a loader or IO thread. */
    fun resolve(streamUri: String): ResolvedStream {
        synchronized(cache) {
            cache[streamUri]?.takeIf { it.expiresAtMillis > now() }?.let { return it }
        }
        val uri = URI(streamUri)
        val source = musicSourceForHost(uri.host.orEmpty())
            ?: throw StreamUnavailableException(UnavailableReason.UNKNOWN, "Unknown stream $streamUri")
        val track = Track(source, uri.path.trim('/'), "", "", 0, null)
        val resolved = when (source) {
            MusicSource.YOUTUBE_MUSIC -> resolveYoutubeWithFallback(track, refusedClients(streamUri))
            MusicSource.SOUNDCLOUD -> wrapped(track) { resolveSoundCloud(track.pageUrl) }
        }
        synchronized(cache) { cache[streamUri] = resolved }
        return resolved
    }

    /**
     * Forgets a stream the server refused and the client that found it. If it
     * came from NewPipe, the fallback goes first for a while: the refusal is
     * likely to repeat.
     */
    fun invalidate(streamUri: String) {
        val refused = synchronized(cache) {
            cache.remove(streamUri)?.also { stream ->
                refusals[streamUri] = Refusal(refusedClients(streamUri) + stream.client, now())
            }
        }
        if (refused?.fetch == FetchStyle.NEWPIPE && youtubeFallback != null) {
            preferFallbackUntil = now() + PREFER_FALLBACK_MS
        }
    }

    private fun refusedClients(streamUri: String): Set<String> = synchronized(cache) {
        val refusal = refusals[streamUri] ?: return emptySet()
        if (now() - refusal.atMillis < REFUSAL_MEMORY_MS) return refusal.clients
        refusals.remove(streamUri)
        emptySet()
    }

    private fun resolveYoutubeWithFallback(track: Track, refused: Set<String>): ResolvedStream {
        val fallback = youtubeFallback ?: return wrapped(track) { newPipeYoutube(track.pageUrl) }
        val refusedElsewhere = refused - NEWPIPE_CLIENT
        var earlierFallbackError: Exception? = null
        if (now() < preferFallbackUntil || NEWPIPE_CLIENT in refused) {
            try {
                return fallback.resolve(track.id, refusedElsewhere, ageRestricted = false)
            } catch (e: Exception) {
                earlierFallbackError = e
                // It is refused here too, so it stops costing every track a detour.
                preferFallbackUntil = 0
            }
        }
        return try {
            wrapped(track) { newPipeYoutube(track.pageUrl) }
        } catch (newPipeError: StreamUnavailableException) {
            if (newPipeError.reason !in RETRY_ELSEWHERE) throw newPipeError
            try {
                if (earlierFallbackError != null) throw earlierFallbackError
                val ageRestricted = newPipeError.reason == UnavailableReason.AGE_RESTRICTED
                fallback.resolve(track.id, refusedElsewhere, ageRestricted).also {
                    // An age check is about the track, not the network: NewPipe keeps leading.
                    if (!ageRestricted) preferFallbackUntil = now() + PREFER_FALLBACK_MS
                }
            } catch (fallbackError: Exception) {
                throw StreamUnavailableException(
                    newPipeError.reason,
                    "${newPipeError.message}; fallback: ${fallbackError.message}",
                    newPipeError,
                    listOfNotNull(
                        newPipeError.detail,
                        (fallbackError as? StreamUnavailableException)?.detail ?: fallbackError.javaClass.simpleName,
                    ).joinToString(", "),
                )
            }
        }
    }

    private inline fun wrapped(track: Track, block: () -> ResolvedStream): ResolvedStream = try {
        block()
    } catch (e: StreamUnavailableException) {
        throw e
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw StreamUnavailableException(reasonFor(e), "Cannot play ${track.pageUrl}: ${e.message}", e, describe(e))
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
        /** [ResolvedStream.client] of the streams NewPipe finds. */
        const val NEWPIPE_CLIENT = "NewPipe"

        private const val MAX_CACHED = 200
        private const val PREFER_FALLBACK_MS = 30 * 60 * 1000L
        private const val REFUSAL_MEMORY_MS = 5 * 60 * 1000L
        private const val MAX_SAID_CHARS = 60
        /** How YouTube words "sign in to prove you are an adult", in English and Russian. */
        private val AGE_CHECKS = listOf("confirm your age", "inappropriate for some users", "подтвердить свой возраст")
        private val QUOTED = Regex("\"([^\"]+)\"\\s*$")

        /** Refusals another YouTube client may get past; premium or region locks it will not. */
        private val RETRY_ELSEWHERE = setOf(
            UnavailableReason.BLOCKED_BY_SERVICE,
            UnavailableReason.NOT_AVAILABLE,
            UnavailableReason.AGE_RESTRICTED,
            UnavailableReason.NO_AUDIO,
            UnavailableReason.UNKNOWN,
        )
        internal const val EXPIRY_MARGIN_MS = 10 * 60 * 1000L
        internal const val YOUTUBE_FALLBACK_TTL_MS = 3 * 60 * 60 * 1000L
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

        internal fun reasonFor(error: Exception): UnavailableReason = when {
            AGE_CHECKS.any { error.message.orEmpty().contains(it, ignoreCase = true) } -> UnavailableReason.AGE_RESTRICTED
            else -> reasonForType(error)
        }

        private fun reasonForType(error: Exception): UnavailableReason = when (error) {
            is AgeRestrictedContentException -> UnavailableReason.AGE_RESTRICTED
            is YoutubeMusicPremiumContentException, is PaidContentException, is SoundCloudGoPlusContentException ->
                UnavailableReason.PREMIUM_ONLY
            is GeographicRestrictionException -> UnavailableReason.REGION_LOCKED
            is SignInConfirmNotBotException, is ReCaptchaException -> UnavailableReason.BLOCKED_BY_SERVICE
            is ContentNotAvailableException -> UnavailableReason.NOT_AVAILABLE
            else -> UnavailableReason.UNKNOWN
        }

        /**
         * The error's name and, for a plain "not available", what YouTube said:
         * it tells a removed video from a refused address.
         */
        internal fun describe(error: Exception): String {
            val name = error.javaClass.simpleName.removeSuffix("Exception")
            if (error.javaClass != ContentNotAvailableException::class.java) return name
            val said = error.message?.let { QUOTED.find(it)?.groupValues?.get(1) }?.take(MAX_SAID_CHARS)
            return if (said.isNullOrBlank()) name else "$name «$said»"
        }

        fun queryParameter(url: String, name: String): String? =
            runCatching { URI(url).rawQuery }.getOrNull()
                ?.split('&')
                ?.firstOrNull { it.startsWith("$name=") }
                ?.substringAfter('=')
    }
}

/** YouTube through NewPipe Extractor, whose visionOS client usually needs no PO token. */
private fun resolveYoutube(pageUrl: String): ResolvedStream {
    val extractor = ServiceList.YouTube.getStreamExtractor(pageUrl)
    extractor.fetchPage()
    val stream = StreamResolver.pickYoutubeStream(extractor.audioStreams)
        ?: throw StreamUnavailableException(UnavailableReason.NO_AUDIO, "No audio stream for $pageUrl")
    val url = stream.content
    return ResolvedStream(
        url = url,
        isHls = false,
        contentLength = stream.itagItem?.contentLength?.takeIf { it > 0 }
            ?: StreamResolver.queryParameter(url, "clen")?.toLongOrNull()
            ?: -1,
        headers = GoogleVideo.headersFor(url),
        expiresAtMillis = StreamResolver.queryParameter(url, "expire")?.toLongOrNull()
            ?.let { it * 1000 - StreamResolver.EXPIRY_MARGIN_MS }
            ?: (System.currentTimeMillis() + StreamResolver.YOUTUBE_FALLBACK_TTL_MS),
    )
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
