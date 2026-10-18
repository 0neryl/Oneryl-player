package com.oneryl.music.data

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Connects to YouTube over IPv4 whenever it can. googlevideo.com serves a
 * stream only to the address that asked for its link, and VPNs often leave
 * through different addresses for IPv4 and IPv6: when the link is asked for
 * over one and the audio over the other, the stream is refused with HTTP 403.
 */
class YoutubeIpv4Dns(private val system: Dns = Dns.SYSTEM) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val addresses = system.lookup(hostname)
        if (YOUTUBE_DOMAINS.none { hostname == it || hostname.endsWith(".$it") }) return addresses
        return addresses.filterIsInstance<Inet4Address>().ifEmpty { addresses }
    }

    private companion object {
        val YOUTUBE_DOMAINS = listOf("youtube.com", "googlevideo.com", "googleapis.com")
    }
}
