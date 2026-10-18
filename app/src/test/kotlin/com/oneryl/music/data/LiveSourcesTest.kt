package com.oneryl.music.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.Localization

/** Talks to the real services; runs only with `./gradlew test -Ponline`. */
class LiveSourcesTest {

    private val client = OkHttpClient()

    @Before
    fun setUp() {
        assumeTrue(System.getProperty("oneryl.online") == "true")
        NewPipe.init(NewPipeDownloader(client), Localization("en", "US"))
    }

    @Test
    fun youtubeMusicSearchResolvesToFetchableAudio() = runBlocking {
        val tracks = MusicCatalog().search(MusicSource.YOUTUBE_MUSIC, "Imagine Dragons Believer")
        tracks.take(3).forEach(::println)
        assertTrue(tracks.isNotEmpty())

        val stream = StreamResolver().resolve(tracks.first().streamUri)
        println("stream: ${stream.url.take(120)}… length=${stream.contentLength}")
        assertTrue(stream.contentLength > GoogleVideo.CHUNK_BYTES)

        // googlevideo.com locks the URL to the IP that asked for it. Behind a
        // proxy whose exits differ, that check fails, so only report it here.
        val request = Request.Builder()
            .url(GoogleVideo.chunkUrl(stream.url, 0, 0, GoogleVideo.CHUNK_BYTES - 1))
            .post(GoogleVideo.POST_BODY.toRequestBody())
            .apply { stream.headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use { response ->
            println("first chunk: HTTP ${response.code}, ${response.body.bytes().size} bytes")
        }
    }

    @Test
    fun soundCloudSearchResolvesToHlsPlaylist() = runBlocking {
        val tracks = MusicCatalog().search(MusicSource.SOUNDCLOUD, "lofi hip hop")
        tracks.take(3).forEach(::println)
        assertTrue(tracks.isNotEmpty())

        val track = tracks.first { it.durationSeconds > 60 }
        val stream = StreamResolver().resolve(track.streamUri)
        println("stream: ${stream.url.take(120)}…")
        assertTrue(stream.isHls)

        val playlist = client.newCall(Request.Builder().url(stream.url).build()).execute()
            .use { it.body.string() }
        val firstSegment = playlist.lines().first { it.isNotBlank() && !it.startsWith("#") }
        println("first segment: ${firstSegment.take(120)}…")
        client.newCall(Request.Builder().url(firstSegment).build()).execute().use {
            assertEquals(200, it.code)
        }
    }

    @Test
    fun chartsReturnTracks() = runBlocking {
        for (source in MusicSource.entries) {
            val tracks = MusicCatalog().charts(source)
            println("$source charts: ${tracks.size}, first ${tracks.firstOrNull()}")
            assertTrue(tracks.isNotEmpty())
        }
    }
}
