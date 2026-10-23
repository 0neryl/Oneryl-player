package com.oneryl.music.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackMatcherTest {

    private fun yt(id: String, title: String, artist: String, seconds: Long) =
        Track(MusicSource.YOUTUBE_MUSIC, id, title, artist, seconds, null)

    private fun sc(id: String, title: String, artist: String, seconds: Long) =
        Track(MusicSource.SOUNDCLOUD, id, title, artist, seconds, null)

    private fun yandex(title: String, vararg artists: String, seconds: Long = 0, version: String? = null) =
        ImportedTrack(title, artists.toList(), version, seconds)

    @Test
    fun theSameSongWinsOverOthersByTheArtist() {
        val match = TrackMatcher.best(
            yandex("Believer", "Imagine Dragons", seconds = 204),
            listOf(
                yt("a", "Thunder", "Imagine Dragons", 187),
                yt("b", "Believer", "Imagine Dragons", 204),
                yt("c", "Believer (Live)", "Imagine Dragons", 260),
            ),
        )
        assertEquals("b", match?.id)
    }

    @Test
    fun cyrillicAndLatinSpellingsOfAnArtistMatch() {
        val match = TrackMatcher.best(
            yandex("Положение", "Скриптонит", seconds = 230),
            listOf(yt("a", "Положение", "Scriptonite", 231)),
        )
        assertEquals("a", match?.id)
        assertTrue(TrackMatcher.similarity(TrackMatcher.normalize("Скриптонит"), TrackMatcher.normalize("Scriptonite")) > 0.6)
    }

    @Test
    fun musicVideosWithTheArtistInTheTitleMatch() {
        val match = TrackMatcher.best(
            yandex("Sweater Weather", "The Neighbourhood", seconds = 240),
            listOf(yt("v", "The Neighbourhood - Sweater Weather (Official Video)", "TheNeighbourhoodVEVO", 252)),
        )
        assertEquals("v", match?.id)
    }

    @Test
    fun anUnrelatedResultIsNoMatch() {
        val match = TrackMatcher.best(
            yandex("Город золотой", "Аквариум", seconds = 200),
            listOf(yt("x", "Golden Hour", "JVKE", 209), yt("y", "Gold", "Imagine Dragons", 300)),
        )
        assertNull(match)
    }

    @Test
    fun theSearchAsksForArtistsAndTitle() = runBlocking {
        var asked = ""
        val matcher = TrackMatcher { query ->
            asked = query
            listOf(yt("a", "Притяжения больше нет", "Валерий Меладзе", 180))
        }
        val match = matcher.find(yandex("Притяжения больше нет", "Валерий Меладзе", "ВИА Гра", seconds = 180))
        assertEquals("Валерий Меладзе ВИА Гра Притяжения больше нет", asked)
        assertEquals("a", match?.id)
    }

    @Test
    fun aLiveOrRemixedVersionLosesToTheOriginal() {
        val match = TrackMatcher.best(
            yandex("Группа крови", "Кино", seconds = 267),
            listOf(
                yt("live", "Кино - Группа крови (Live, 03.06.87)", "КИНО Цоя", 267),
                yt("studio", "Группа крови", "Кино", 270),
            ),
        )
        assertEquals("studio", match?.id)

        val remix = TrackMatcher.best(
            yandex("Believer", "Imagine Dragons", seconds = 204, version = "Remix"),
            listOf(yt("r", "Believer (Remix)", "Imagine Dragons", 210), yt("o", "Believer", "Imagine Dragons", 204)),
        )
        assertEquals("a remix asked for is a remix found", "r", remix?.id)
    }

    @Test
    fun bothServicesAreSearchedAndTheClearlyBetterMatchWins() = runBlocking {
        val track = yandex("Группа крови", "Кино", seconds = 267)
        val youtubeOnlyLive = TrackMatcher(
            listOf(
                { _ -> listOf(yt("live", "Кино - Группа крови (Live)", "Fan channel", 300)) },
                { _ -> listOf(sc("kino/gruppa-krovi", "Группа крови", "Кино", 267)) },
            ),
        )
        assertEquals("kino/gruppa-krovi", youtubeOnlyLive.find(track)?.id)

        val both = TrackMatcher(
            listOf(
                { _ -> listOf(yt("yt", "Группа крови", "Кино", 268)) },
                { _ -> listOf(sc("kino/gruppa-krovi", "Группа крови", "Кино", 267)) },
            ),
        )
        assertEquals("a song both have comes from the first service", "yt", both.find(track)?.id)
    }

    @Test
    fun aServiceThatFailedOnlyMattersWhenNoOtherFoundTheTrack() = runBlocking {
        val track = yandex("Believer", "Imagine Dragons", seconds = 204)
        val youtubeDown = TrackMatcher(
            listOf(
                { _ -> throw java.io.IOException("no route") },
                { _ -> listOf(sc("id/believer", "Believer", "Imagine Dragons", 204)) },
            ),
        )
        assertEquals("id/believer", youtubeDown.find(track)?.id)

        val nothingElse = TrackMatcher(listOf({ _ -> throw java.io.IOException("no route") }, { _ -> emptyList() }))
        val failure = runCatching { nothingElse.find(track) }.exceptionOrNull()
        assertTrue("the search is tried again later", failure is java.io.IOException)
    }

    @Test
    fun aSoundCloudPreviewIsNoMatch() {
        val match = TrackMatcher.best(
            yandex("Believer", "Imagine Dragons", seconds = 204),
            listOf(sc("id/believer", "Believer", "Imagine Dragons", 30)),
        )
        assertNull(match)
    }
}
