package com.oneryl.music.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YoutubeAccountTest {

    private val signedInCookie = "PREF=f6=40000000; SAPISID=abc/def; __Secure-3PAPISID=abc/def; SID=xyz"

    @Test
    fun onlyASapisidCookieMeansSignedIn() {
        assertTrue(YoutubeAccount.isSignedIn(signedInCookie))
        assertFalse(YoutubeAccount.isSignedIn("PREF=f6=40000000; __Secure-3PAPISID=abc; VISITOR_INFO1_LIVE=q"))
        assertFalse(YoutubeAccount.isSignedIn(""))
    }

    @Test
    fun theSessionComesFromTheCookiesAndThePageConfig() {
        val session = YoutubeAccount.pageSession(
            signedInCookie,
            """{"visitorData":"CgtWaXNpdG9y","dataSyncId":"117000000000000000000||","authUser":"1"}""",
        )
        assertEquals(YoutubeSession(signedInCookie, "CgtWaXNpdG9y", "117000000000000000000", "1"), session)
    }

    @Test
    fun aPageThatIsNotReadyGivesNoSession() {
        assertNull(YoutubeAccount.pageSession(signedInCookie, "null"))
        assertNull(YoutubeAccount.pageSession(signedInCookie, null))
        assertNull(YoutubeAccount.pageSession(signedInCookie, """{"visitorData":"","dataSyncId":"","authUser":"0"}"""))
        assertNull(YoutubeAccount.pageSession("PREF=1", """{"visitorData":"Cgt","dataSyncId":"","authUser":"0"}"""))
    }

    @Test
    fun theCookiesStayOutOfLogs() {
        val session = YoutubeSession(signedInCookie, "v", "d", name = "Oneryl")
        assertFalse(session.toString().contains("SAPISID"))
    }
}
