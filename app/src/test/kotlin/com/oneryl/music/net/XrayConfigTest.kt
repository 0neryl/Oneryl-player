package com.oneryl.music.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class XrayConfigTest {

    /** What a Remnawave panel gives Happ: one full Xray config per host. */
    private val subscription = """
        [
          {
            "remarks": "Tri-Node XHTTP",
            "dns": {"servers": ["1.1.1.1"]},
            "inbounds": [{"tag": "socks", "port": 10808, "listen": "127.0.0.1", "protocol": "socks"}],
            "outbounds": [
              {"tag": "proxy", "protocol": "vless",
               "settings": {"vnext": [{"address": "198.51.100.7", "port": 8443, "users": [{"id": "uuid-1", "encryption": "none"}]}]},
               "streamSettings": {"network": "xhttp", "security": "reality",
                 "xhttpSettings": {"path": "/x", "mode": "auto"},
                 "realitySettings": {"serverName": "www.samsung.com", "fingerprint": "chrome", "publicKey": "pbk", "shortId": "ab"}}},
              {"tag": "direct", "protocol": "freedom"},
              {"tag": "block", "protocol": "blackhole"}
            ],
            "routing": {"rules": [{"type": "field", "ip": ["geoip:private"], "outboundTag": "direct"}]}
          },
          {
            "remarks": "Tri-Node Hysteria2",
            "outbounds": [
              {"tag": "direct", "protocol": "freedom"},
              {"protocol": "hysteria", "settings": {"address": "198.51.100.7", "port": 443, "version": 2},
               "streamSettings": {"network": "hysteria", "security": "tls", "hysteriaSettings": {"version": 2, "auth": "uuid-1"},
                 "finalmask": {"udp": [{"type": "salamander", "settings": {"password": "obfs"}}]}}}
            ]
          }
        ]
    """

    @Test
    fun everyHostBecomesAConnectionWithItsProxyFirst() {
        val connections = XrayConfig.parseSubscription(subscription)
        assertEquals(listOf("Tri-Node XHTTP", "Tri-Node Hysteria2"), connections.map { it.name })

        val hysteria = Json.parseToJsonElement(connections[1].outbounds).jsonArray
        assertEquals("hysteria", hysteria[0].jsonObject["protocol"]?.jsonPrimitive?.content)
        assertEquals(2, hysteria.size)
        assertFalse("keys stay out of toString", connections[0].toString().contains("uuid-1"))
    }

    @Test
    fun theCoreConfigListensLocallyAndSendsEverythingToTheServer() {
        val connection = XrayConfig.parseSubscription(subscription).first()
        val config = Json.parseToJsonElement(XrayConfig.build(connection, socksPort = 20001, httpPort = 20002)).jsonObject

        val inbounds = config["inbounds"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("socks", "http"), inbounds.map { it["protocol"]!!.jsonPrimitive.content })
        assertEquals(listOf("20001", "20002"), inbounds.map { it["port"]!!.jsonPrimitive.content })
        assertTrue(inbounds.all { it["listen"]!!.jsonPrimitive.content == "127.0.0.1" })

        val outbounds = config["outbounds"]!!.jsonArray
        assertEquals("vless", outbounds[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        assertEquals(
            "reality",
            outbounds[0].jsonObject["streamSettings"]!!.jsonObject["security"]!!.jsonPrimitive.content,
        )
        assertEquals(JsonArray(emptyList()), config["routing"]!!.jsonObject["rules"])
        assertFalse("no DNS rules or geo files", config.containsKey("dns"))
    }

    @Test
    fun aSingleConfigWorksToo() {
        val connections = XrayConfig.parseSubscription(
            """{"outbounds": [{"protocol": "trojan", "settings": {"servers": [{"address": "x", "port": 443, "password": "p"}]}}]}""",
        )
        assertEquals(listOf("Server 1"), connections.map { it.name })
    }

    @Test
    fun linksInsteadOfXrayJsonAreRefused() {
        for (body in listOf("dmxlc3M6Ly91dWlkQGhvc3Q6NDQz", "[]", """[{"outbounds": [{"protocol": "freedom"}]}]""")) {
            try {
                XrayConfig.parseSubscription(body)
                fail("expected a refusal for $body")
            } catch (_: XrayConfig.NotXrayJson) {
            }
        }
    }

    @Test
    fun anOutboundTaggedProxyIsTakenWhateverItsProtocol() {
        val connection = XrayConfig.parseSubscription("""[{"remarks": "CI", "outbounds": [{"tag": "proxy", "protocol": "freedom"}]}]""").single()
        assertEquals("CI", connection.name)
        assertEquals("freedom", (Json.parseToJsonElement(connection.outbounds).jsonArray[0] as JsonObject)["protocol"]!!.jsonPrimitive.content)
    }
}
