package com.oneryl.music.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/** A Yandex Music playlist, as its share link names it. */
sealed interface YandexPlaylistLink {
    val id: String

    /** `music.yandex.ru/users/<owner>/playlists/<kind>`, the older links and "Мне нравится". */
    data class ByOwner(val owner: String, val kind: String) : YandexPlaylistLink {
        override val id get() = "yandex:$owner:$kind"
    }

    /** `music.yandex.ru/playlists/<uuid>`, what the share button gives now. */
    data class ByUuid(val uuid: String) : YandexPlaylistLink {
        override val id get() = "yandex:$uuid"
    }
}

class YandexMusicException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason {
        /** Not a link to a Yandex Music playlist. */
        BAD_LINK,

        /** Yandex Music serves only Russia and nearby countries, so a VPN elsewhere is turned away. */
        REGION,

        /** No such playlist, or a private one. */
        NOT_FOUND,
        NETWORK,
        UNEXPECTED,
    }
}

/**
 * Reads public playlists from the Yandex Music API, the one its apps use.
 * Only names, artists and durations come over; the music itself is found on
 * YouTube Music afterwards.
 */
class YandexMusic(
    private val client: OkHttpClient,
    private val baseUrl: String = "https://api.music.yandex.net",
) {

    /** Blocks on the network. */
    fun playlist(text: String): Playlist {
        val link = parseLink(text)
            ?: throw YandexMusicException(YandexMusicException.Reason.BAD_LINK, "Not a Yandex Music playlist link")
        val path = when (link) {
            is YandexPlaylistLink.ByOwner -> "/users/${link.owner.encoded()}/playlists/${link.kind}"
            is YandexPlaylistLink.ByUuid -> "/playlist/${link.uuid.encoded()}"
        }
        val result = call(Request.Builder().url(baseUrl + path).get()) as? JsonObject
            ?: throw YandexMusicException(YandexMusicException.Reason.UNEXPECTED, "No playlist in the answer")
        return parsePlaylist(link, result, ::fetchTracks)
    }

    /** Full track details for playlist items that came as ids only, in batches. */
    private fun fetchTracks(ids: List<String>): List<JsonObject> = ids.chunked(TRACK_BATCH).flatMap { batch ->
        val body = FormBody.Builder().add("track-ids", batch.joinToString(",")).build()
        (call(Request.Builder().url("$baseUrl/tracks").post(body)) as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
    }

    private fun call(request: Request.Builder): JsonElement? {
        val built = request
            .header("X-Yandex-Music-Client", CLIENT)
            .header("Accept", "application/json")
            .header("Accept-Language", "ru")
            .build()
        val (code, body) = try {
            client.newCall(built).execute().use { it.code to it.body.string() }
        } catch (e: IOException) {
            throw YandexMusicException(YandexMusicException.Reason.NETWORK, "Yandex Music is unreachable", e)
        }
        when {
            code == 451 -> throw YandexMusicException(YandexMusicException.Reason.REGION, "Not served in this country")
            code == 403 || code == 404 -> throw YandexMusicException(YandexMusicException.Reason.NOT_FOUND, "HTTP $code")
            code !in 200..299 -> throw YandexMusicException(YandexMusicException.Reason.UNEXPECTED, "HTTP $code")
        }
        val answer = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: throw YandexMusicException(YandexMusicException.Reason.UNEXPECTED, "Not JSON")
        return answer["result"]
    }

    companion object {
        private const val CLIENT = "YandexMusicAndroid/24023621"
        private const val TRACK_BATCH = 200
        private const val COVER_SIZE = "400x400"
        private val json = Json { ignoreUnknownKeys = true }

        private val BY_OWNER = Regex("""music\.yandex\.[a-z]+/users/([^/?#\s]+)/playlists/(\d+)""")
        private val BY_UUID = Regex("""music\.yandex\.[a-z]+/playlists/([A-Za-z0-9._-]+)""")

        /** Finds the playlist link in [text], which may be the whole message the share button makes. */
        fun parseLink(text: String): YandexPlaylistLink? {
            BY_OWNER.find(text)?.let { match ->
                val owner = URLDecoder.decode(match.groupValues[1], "UTF-8")
                return YandexPlaylistLink.ByOwner(owner, match.groupValues[2])
            }
            return BY_UUID.find(text)?.let { YandexPlaylistLink.ByUuid(it.groupValues[1]) }
        }

        /**
         * Builds the playlist from the API's `result`. Items that come without
         * their track get it from [fetchTracks], by "trackId:albumId".
         */
        internal fun parsePlaylist(
            link: YandexPlaylistLink,
            result: JsonObject,
            fetchTracks: (List<String>) -> List<JsonObject>,
        ): Playlist {
            val items = result.array("tracks").mapNotNull { it as? JsonObject }
            val bare = items.filter { it["track"] !is JsonObject }.mapNotNull { item ->
                val id = item.text("id") ?: return@mapNotNull null
                item.text("albumId")?.let { "$id:$it" } ?: id
            }
            val fetched = if (bare.isEmpty()) emptyMap() else fetchTracks(bare).associateBy { it.text("id") }
            val tracks = items.mapNotNull { item ->
                val track = item["track"] as? JsonObject ?: fetched[item.text("id")] ?: return@mapNotNull null
                parseTrack(track)
            }
            return Playlist(
                id = link.id,
                title = result.text("title") ?: "Яндекс Музыка",
                coverUrl = playlistCover(result),
                sourceUrl = when (link) {
                    is YandexPlaylistLink.ByOwner -> "https://music.yandex.ru/users/${link.owner.encoded()}/playlists/${link.kind}"
                    is YandexPlaylistLink.ByUuid -> "https://music.yandex.ru/playlists/${link.uuid}"
                },
                entries = tracks.map { PlaylistEntry(it) },
            )
        }

        internal fun parseTrack(track: JsonObject): ImportedTrack? {
            val title = track.text("title")?.takeIf { it.isNotBlank() } ?: return null
            val album = track.array("albums").firstOrNull() as? JsonObject
            return ImportedTrack(
                title = title,
                artists = track.array("artists").mapNotNull { (it as? JsonObject)?.text("name") },
                version = track.text("version")?.takeIf { it.isNotBlank() },
                durationSeconds = (track["durationMs"] as? JsonPrimitive)?.longOrNull?.div(1000) ?: 0,
                coverUrl = coverUrl(track.text("coverUri") ?: album?.text("coverUri")),
            )
        }

        private fun playlistCover(result: JsonObject): String? {
            val cover = result["cover"] as? JsonObject
            return coverUrl(
                cover?.text("uri")
                    ?: (cover?.array("itemsUri")?.firstOrNull() as? JsonPrimitive)?.contentOrNull
                    ?: result.text("ogImage"),
            )
        }

        /** Yandex covers come as "avatars.yandex.net/get-music-content/…/%%", with the size to fill in. */
        internal fun coverUrl(uri: String?): String? {
            if (uri.isNullOrBlank()) return null
            val sized = uri.replace("%%", COVER_SIZE)
            return if (sized.startsWith("http")) sized else "https://$sized"
        }

        private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.array(name: String): JsonArray = this[name] as? JsonArray ?: JsonArray(emptyList())

        private fun String.encoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    }
}
