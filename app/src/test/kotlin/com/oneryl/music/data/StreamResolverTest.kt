package com.oneryl.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException

class StreamResolverTest {

    private var clock = 1_000_000L
    private val newPipeCalls = mutableListOf<String>()
    private val fallbackCalls = mutableListOf<String>()
    private val refusedSeen = mutableListOf<Set<String>>()
    private val ageChecksSeen = mutableListOf<Boolean>()

    private fun stream(client: String, fetch: FetchStyle) =
        ResolvedStream("https://example.com/$client", false, 100, emptyMap(), clock + 60_000, fetch, client = client)

    private fun resolver(
        newPipe: (String) -> ResolvedStream = { stream("NewPipe", FetchStyle.NEWPIPE) },
        fallback: (String) -> ResolvedStream = { stream("ITX", FetchStyle.RANGED_GET) },
    ) = StreamResolver(
        youtubeFallback = { id, refused, ageRestricted ->
            fallbackCalls += id
            refusedSeen += refused
            ageChecksSeen += ageRestricted
            fallback(id)
        },
        now = { clock },
        newPipeYoutube = { url -> newPipeCalls += url; newPipe(url) },
    )

    private fun uri(id: String) = Track(MusicSource.YOUTUBE_MUSIC, id, "", "", 0, null).streamUri

    private val botCheck: (String) -> ResolvedStream = { throw SignInConfirmNotBotException("Sign in to confirm you're not a bot") }

    @Test
    fun newPipeAloneWhenItWorks() {
        val result = resolver().resolve(uri("a"))
        assertEquals("NewPipe", result.client)
        assertTrue(fallbackCalls.isEmpty())
    }

    @Test
    fun botCheckFallsBackAndTheFallbackLeadsAfterwards() {
        val resolver = resolver(newPipe = botCheck)
        assertEquals("ITX", resolver.resolve(uri("a")).client)
        assertEquals(listOf("a"), fallbackCalls)

        assertEquals("ITX", resolver.resolve(uri("b")).client)
        assertEquals("NewPipe went first only for the first track", 1, newPipeCalls.size)

        clock += 31 * 60 * 1000L
        resolver.resolve(uri("c"))
        assertEquals("after half an hour NewPipe gets another chance", 2, newPipeCalls.size)
    }

    @Test
    fun premiumOnlyTracksDoNotTryTheFallback() {
        val resolver = resolver(newPipe = {
            throw StreamUnavailableException(UnavailableReason.PREMIUM_ONLY, "Music Premium")
        })
        try {
            resolver.resolve(uri("a"))
            fail("expected the premium refusal")
        } catch (e: StreamUnavailableException) {
            assertEquals(UnavailableReason.PREMIUM_ONLY, e.reason)
        }
        assertTrue(fallbackCalls.isEmpty())
    }

    @Test
    fun whenBothFailTheErrorNamesBoth() {
        val resolver = resolver(
            newPipe = botCheck,
            fallback = { throw StreamUnavailableException(UnavailableReason.NOT_AVAILABLE, "no", detail = "ITX UNAVAILABLE") },
        )
        try {
            resolver.resolve(uri("a"))
            fail("expected a refusal")
        } catch (e: StreamUnavailableException) {
            assertEquals(UnavailableReason.BLOCKED_BY_SERVICE, e.reason)
            assertEquals("SignInConfirmNotBot, ITX UNAVAILABLE", e.detail)
        }
    }

    @Test
    fun aRefusedNewPipeStreamSendsTheNextRequestToTheFallback() {
        val resolver = resolver()
        resolver.resolve(uri("a"))
        resolver.invalidate(uri("a"))

        assertEquals("ITX", resolver.resolve(uri("a")).client)
        assertEquals(1, newPipeCalls.size)
    }

    @Test
    fun aRefusedFallbackClientIsSkippedForAWhile() {
        val resolver = resolver(newPipe = botCheck)
        resolver.resolve(uri("a"))
        resolver.invalidate(uri("a"))
        resolver.resolve(uri("a"))
        assertEquals(listOf(emptySet(), setOf("ITX")), refusedSeen)

        resolver.invalidate(uri("a"))
        clock += 6 * 60 * 1000L
        resolver.resolve(uri("a"))
        assertEquals("the refusal is forgotten after five minutes", emptySet<String>(), refusedSeen.last())
    }

    @Test
    fun aFallbackThatFailsTooStopsLeading() {
        val resolver = resolver(fallback = { throw StreamUnavailableException(UnavailableReason.NOT_AVAILABLE, "no") })
        resolver.resolve(uri("a"))
        resolver.invalidate(uri("a"))

        assertEquals("NewPipe", resolver.resolve(uri("a")).client)
        assertEquals(1, fallbackCalls.size)
        resolver.resolve(uri("b"))
        assertEquals("the next track goes straight to NewPipe", 1, fallbackCalls.size)
    }

    @Test
    fun notAvailableSaysWhatYoutubeSaid() {
        val error = ContentNotAvailableException("Got error UNPLAYABLE: \"This content isn't available, try again later.\"")
        assertEquals("ContentNotAvailable «This content isn't available, try again later.»", StreamResolver.describe(error))
        assertEquals("SignInConfirmNotBot", StreamResolver.describe(SignInConfirmNotBotException("got error LOGIN_REQUIRED: \"a bot\"")))
    }

    @Test
    fun anAgeCheckGoesToTheFallbackAsOne() {
        val resolver = resolver(newPipe = {
            throw ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Sign in to confirm your age\"")
        })
        assertEquals("ITX", resolver.resolve(uri("a")).client)
        assertEquals(listOf(true), ageChecksSeen)

        resolver.resolve(uri("b"))
        assertEquals("an age check says nothing about the network, so NewPipe still leads", 2, newPipeCalls.size)
    }

    @Test
    fun anAgeCheckTheFallbackCannotPassIsReportedAsOne() {
        val resolver = resolver(
            newPipe = { throw ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Sign in to confirm your age\"") },
            fallback = { throw StreamUnavailableException(UnavailableReason.NOT_AVAILABLE, "no") },
        )
        try {
            resolver.resolve(uri("a"))
            fail("expected a refusal")
        } catch (e: StreamUnavailableException) {
            assertEquals(UnavailableReason.AGE_RESTRICTED, e.reason)
        }
    }
}
