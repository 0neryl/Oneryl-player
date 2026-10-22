package com.oneryl.music.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YandexMusicTest {

    @Test
    fun linksAreFoundInWhatTheShareButtonCopies() {
        assertEquals(
            YandexPlaylistLink.ByUuid("lk.a1b2c3d4-e5f6-7890-abcd-ef1234567890"),
            YandexMusic.parseLink(
                "Слушайте «Вечер» в Яндекс Музыке https://music.yandex.ru/playlists/lk.a1b2c3d4-e5f6-7890-abcd-ef1234567890?utm_source=web&utm_medium=copy_link",
            ),
        )
        assertEquals(
            YandexPlaylistLink.ByOwner("ivan.petrov", "1003"),
            YandexMusic.parseLink("https://music.yandex.com/users/ivan.petrov/playlists/1003"),
        )
        assertEquals(
            YandexPlaylistLink.ByOwner("ivan petrov", "3"),
            YandexMusic.parseLink("music.yandex.kz/users/ivan%20petrov/playlists/3?from=x"),
        )
        assertNull(YandexMusic.parseLink("https://music.yandex.ru/album/123"))
        assertNull(YandexMusic.parseLink("https://open.spotify.com/playlist/abc"))
    }

    @Test
    fun aPlaylistKeepsItsOrderAndFetchesTheTracksThatCameAsIds() {
        val result = Json.parseToJsonElement(
            """
            {
              "uid": 1, "kind": 1003, "title": "Вечер", "trackCount": 3,
              "cover": {"type": "pic", "uri": "avatars.yandex.net/get-music-user-playlist/1/cover/%%"},
              "tracks": [
                {"id": 11, "timestamp": "t", "track": {
                  "id": "11", "title": "Believer", "durationMs": 204000,
                  "artists": [{"id": 1, "name": "Imagine Dragons"}],
                  "albums": [{"id": 5, "title": "Evolve", "coverUri": "avatars.yandex.net/get-music-content/5/a/%%"}]
                }},
                {"id": 22, "albumId": 7, "timestamp": "t"},
                {"id": 33, "timestamp": "t", "track": {
                  "id": "33", "title": "Притяжения больше нет", "version": "Remix", "durationMs": 180500,
                  "artists": [{"name": "Валерий Меладзе"}, {"name": "ВИА Гра"}],
                  "coverUri": "avatars.yandex.net/get-music-content/9/b/%%"
                }}
              ]
            }
            """,
        ).jsonObject
        var asked: List<String>? = null
        val playlist = YandexMusic.parsePlaylist(YandexPlaylistLink.ByOwner("ivan", "1003"), result) { ids ->
            asked = ids
            listOf(Json.parseToJsonElement("""{"id": "22", "title": "Sweater Weather", "durationMs": 240000, "artists": [{"name": "The Neighbourhood"}]}""").jsonObject)
        }

        assertEquals(listOf("22:7"), asked)
        assertEquals("yandex:ivan:1003", playlist.id)
        assertEquals("Вечер", playlist.title)
        assertEquals("https://avatars.yandex.net/get-music-user-playlist/1/cover/400x400", playlist.coverUrl)
        assertEquals(listOf("Believer", "Sweater Weather", "Притяжения больше нет"), playlist.entries.map { it.source.title })
        val third = playlist.entries[2].source
        assertEquals(listOf("Валерий Меладзе", "ВИА Гра"), third.artists)
        assertEquals("Притяжения больше нет (Remix)", third.displayTitle)
        assertEquals(180, third.durationSeconds)
        assertEquals("https://avatars.yandex.net/get-music-content/5/a/400x400", playlist.entries[0].source.coverUrl)
        assertEquals(true, playlist.entries.all { it.pending && it.track == null })
    }

    @Test
    fun tracksWithoutATitleAreSkipped() {
        val track = Json.parseToJsonElement("""{"id": "1", "title": "", "artists": []}""") as JsonObject
        assertNull(YandexMusic.parseTrack(track))
        assertNull(YandexMusic.coverUrl(null))
        assertEquals("https://x/1/400x400", YandexMusic.coverUrl("https://x/1/%%"))
    }
}
