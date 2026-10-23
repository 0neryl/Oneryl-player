package com.oneryl.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistRequestTest {

    @Test
    fun aCreditIsLookedUpWholeThenByItsFirstArtist() {
        assertEquals(listOf("Imagine Dragons"), artistNames("Imagine Dragons"))
        assertEquals(listOf("Simon & Garfunkel", "Simon"), artistNames("Simon & Garfunkel"))
        assertEquals(listOf("Miyagi, Andy Panda", "Miyagi"), artistNames("Miyagi, Andy Panda"))
        assertEquals(listOf("Calvin Harris feat. Dua Lipa", "Calvin Harris"), artistNames("Calvin Harris feat. Dua Lipa"))
        assertEquals(listOf("Lil Nas X"), artistNames("Lil Nas X"))
    }

    @Test
    fun youtubeChannelDecorationsAreDropped() {
        assertEquals(listOf("Rammstein"), artistNames("Rammstein - Topic"))
        assertEquals(listOf("ImagineDragons"), artistNames("ImagineDragonsVEVO"))
    }

    @Test
    fun aSoundCloudTrackPointsAtItsUploaderOnlyOnSoundCloud() {
        val track = Track(MusicSource.SOUNDCLOUD, "madk1d9/track-name", "Track", "madk1d", 120, null)
        val request = ArtistRequest.of(track)
        assertEquals(ArtistRequest(MusicSource.SOUNDCLOUD, "madk1d", "madk1d9"), request)
        assertEquals("madk1d9", request.on(MusicSource.SOUNDCLOUD).soundCloudUser)
        assertNull(request.on(MusicSource.YOUTUBE_MUSIC).soundCloudUser)

        val youtube = Track(MusicSource.YOUTUBE_MUSIC, "abc", "Song", "Artist", 120, null)
        assertNull(ArtistRequest.of(youtube).on(MusicSource.SOUNDCLOUD).soundCloudUser)
    }
}
