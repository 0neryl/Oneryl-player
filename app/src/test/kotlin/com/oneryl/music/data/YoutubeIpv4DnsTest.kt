package com.oneryl.music.data

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Test
import java.net.InetAddress

class YoutubeIpv4DnsTest {

    private val v4 = InetAddress.getByAddress("host", byteArrayOf(10, 0, 0, 1))
    private val v6 = InetAddress.getByAddress("host", ByteArray(16).also { it[15] = 1 })

    private fun dns(vararg addresses: InetAddress) = YoutubeIpv4Dns(
        object : Dns {
            override fun lookup(hostname: String) = addresses.toList()
        },
    )

    @Test
    fun youtubeHostsGoOverIpv4() {
        assertEquals(listOf(v4), dns(v6, v4).lookup("rr3---sn-abc.googlevideo.com"))
        assertEquals(listOf(v4), dns(v6, v4).lookup("music.youtube.com"))
    }

    @Test
    fun ipv6OnlyNetworksStillConnect() {
        assertEquals(listOf(v6), dns(v6).lookup("www.youtube.com"))
    }

    @Test
    fun otherHostsAreLeftAlone() {
        assertEquals(listOf(v6, v4), dns(v6, v4).lookup("api-v2.soundcloud.com"))
        assertEquals(listOf(v6, v4), dns(v6, v4).lookup("notyoutube.com"))
    }
}
