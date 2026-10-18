package com.oneryl.music.playback.youtube

import android.content.Context
import android.util.Log
import com.metrolist.innertubex.InnerTube
import com.metrolist.innertubex.InnerTubeLogLevel
import com.metrolist.innertubex.InnerTubeLogger
import com.metrolist.innertubex.cipher.PlayerConfigRepository
import com.metrolist.innertubex.cipher.RemotePlayerConfigStore
import com.metrolist.innertubex.cipher.YouTubeCipherService
import com.metrolist.innertubex.extraction.AudioQuality
import com.metrolist.innertubex.extraction.ContentHints
import com.metrolist.innertubex.extraction.InnerTubeExtractor
import com.metrolist.innertubex.extraction.StreamResolveException
import com.metrolist.innertubex.extraction.TokenProvider
import com.metrolist.innertubex.extraction.TokenProviderCapabilities
import com.metrolist.innertubex.extraction.YtConfigParser
import com.metrolist.innertubex.extraction.YtConfigParserImpl
import com.metrolist.innertubex.extraction.generateClientPlaybackNonce
import com.metrolist.innertubex.extraction.strategy.PoTokenProviderKind
import com.oneryl.music.data.FetchStyle
import com.oneryl.music.data.GoogleVideo
import com.oneryl.music.data.ResolvedStream
import com.oneryl.music.data.StreamUnavailableException
import com.oneryl.music.data.UnavailableReason
import com.oneryl.music.data.YoutubeFallback
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.compression.ContentEncoding
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.header
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient

/**
 * YouTube playback through Metrolist's InnerTubeX: it tries several YouTube
 * clients in turn and, for the web ones, proves the request comes from a real
 * browser with a BotGuard PO token minted in a hidden WebView. Used when
 * YouTube refuses NewPipe's client, which happens from many VPN addresses.
 */
class InnerTubeXSource(context: Context, okHttp: OkHttpClient) : YoutubeFallback {

    private val httpClient = HttpClient(OkHttp) {
        expectSuccess = false
        engine { preconfigured = okHttp }
        install(ContentNegotiation) {
            json(
                Json {
                    ignoreUnknownKeys = true
                    explicitNulls = false
                    encodeDefaults = true
                },
            )
        }
        install(ContentEncoding) {
            gzip(0.9F)
            deflate(0.8F)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 30_000
        }
        defaultRequest {
            url("https://music.youtube.com/youtubei/v1/")
            header("Accept", "application/json")
            header("Cache-Control", "no-cache")
        }
    }

    private val logger = InnerTubeLogger { event ->
        if (event.level == InnerTubeLogLevel.WARN || event.level == InnerTubeLogLevel.ERROR) {
            Log.w(TAG, "${event.tag}: ${event.message} ${event.details}")
        }
    }

    private val poTokens = PoTokenGenerator(context, okHttp)

    private val tokenProvider = object : TokenProvider {
        override val capabilities = TokenProviderCapabilities(
            providers = setOf(PoTokenProviderKind.WEB_BOTGUARD),
            usesWebView = true,
        )

        override suspend fun getPoToken(videoId: String, visitorData: String, cookie: String?) =
            poTokens.getWebClientPoToken(videoId, visitorData)?.let { token ->
                com.metrolist.innertubex.extraction.PoTokenResult(
                    playerRequestToken = token.playerRequestPoToken,
                    streamingDataToken = token.streamingDataPoToken,
                    visitorData = visitorData,
                )
            }

        override suspend fun close() = poTokens.close()
    }

    private val extractor: InnerTubeExtractor by lazy {
        val innerTube = InnerTube(httpClient, logger = logger)
        val configStore = RemotePlayerConfigStore(httpClient, PlayerConfigCache(context), logger)
        val cipher = YouTubeCipherService(httpClient, configStore, logger)
        InnerTubeExtractor(
            configParser = YtConfigParserImpl(httpClient, innerTube, configStore, logger, cipher)
                .withEmbeddedConfigFallback(),
            cipherService = cipher,
            innerTube = innerTube,
            tokenProvider = tokenProvider,
            logger = logger,
        )
    }

    /** Blocks on the network; the player calls it from a loader thread. */
    override fun resolve(videoId: String): ResolvedStream = runBlocking(Dispatchers.IO) {
        val stream = try {
            extractor.extract(
                videoId = videoId,
                hints = ContentHints().withStreamCapabilities(
                    allowHls = false,
                    allowSabr = false,
                    allowBoundedRange = true,
                ),
                excludedClients = emptySet(),
                audioQuality = AudioQuality.HIGH,
                clientPlaybackNonce = generateClientPlaybackNonce(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: StreamResolveException) {
            Log.w(TAG, "Could not resolve $videoId: ${e.reason}")
            throw StreamUnavailableException(reasonFor(e.reason), "InnerTubeX: ${e.reason}", e, "ITX ${e.reason}")
        }
        if (stream == null || stream.sabrBootstrap != null) {
            throw StreamUnavailableException(UnavailableReason.NO_AUDIO, "InnerTubeX found no direct stream", detail = "ITX no stream")
        }
        Log.i(TAG, "Resolved $videoId with ${stream.clientName}, itag ${stream.itag}")
        ResolvedStream(
            url = stream.audioUrl,
            isHls = false,
            contentLength = stream.contentLengthBytes ?: -1,
            headers = stream.headers,
            expiresAtMillis = stream.expiresAt?.toEpochMilliseconds()?.minus(EXPIRY_MARGIN_MS)
                ?: (System.currentTimeMillis() + DEFAULT_TTL_MS),
            fetch = FetchStyle.RANGED_GET,
            chunkBytes = stream.rangeChunkSizeBytes.takeIf { it > 0 } ?: GoogleVideo.CHUNK_BYTES,
            client = stream.clientName,
        )
    }

    private fun reasonFor(reason: StreamResolveException.Reason) = when (reason) {
        StreamResolveException.Reason.AGE_RESTRICTED -> UnavailableReason.AGE_RESTRICTED
        StreamResolveException.Reason.UNAVAILABLE -> UnavailableReason.NOT_AVAILABLE
        StreamResolveException.Reason.NO_PLAYABLE_STREAM -> UnavailableReason.NO_AUDIO
        else -> UnavailableReason.UNKNOWN
    }

    /** Lets a broken remote player config fall back to the one embedded in the watch page. */
    private fun YtConfigParser.withEmbeddedConfigFallback(): YtConfigParser =
        object : YtConfigParser by this {
            override suspend fun fetchConfig(videoId: String, useLoginCookies: Boolean) =
                try {
                    this@withEmbeddedConfigFallback.fetchConfig(videoId, useLoginCookies)
                } catch (_: IllegalStateException) {
                    this@withEmbeddedConfigFallback.fetchEmbeddedConfig(videoId, useLoginCookies = false)
                }
        }

    /** Caches the shared cipher configs Metrolist publishes, so they survive restarts. */
    private class PlayerConfigCache(context: Context) : PlayerConfigRepository {
        private val preferences = context.getSharedPreferences("innertubex_player_config", Context.MODE_PRIVATE)

        override val enabled = true
        override val sourceUrl = PLAYER_CONFIG_URL
        override val defaultSourceUrl = PLAYER_CONFIG_URL
        override var cachedJson: String
            get() = preferences.getString("json", "").orEmpty()
            set(value) = preferences.edit().putString("json", value).apply()
        override var cachedAtMs: Long
            get() = preferences.getLong("cached_at_ms", 0L)
            set(value) = preferences.edit().putLong("cached_at_ms", value).apply()
        override var cachedSourceUrl: String
            get() = preferences.getString("source_url", "").orEmpty()
            set(value) = preferences.edit().putString("source_url", value).apply()
        override var cachedEtag: String
            get() = preferences.getString("etag", "").orEmpty()
            set(value) = preferences.edit().putString("etag", value).apply()
    }

    private companion object {
        const val TAG = "InnerTubeX"
        const val EXPIRY_MARGIN_MS = 5 * 60 * 1000L
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        const val PLAYER_CONFIG_URL =
            "https://raw.githubusercontent.com/ZemerTeam/zemer-cipher/master/library/src/main/assets/player_configs.json"
    }
}
