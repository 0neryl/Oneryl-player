package com.oneryl.music.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** One way to reach the server, as the subscription lists it: its name and its Xray outbounds. */
@Serializable
data class ServerConnection(val name: String, val outbounds: String) {
    /** Keeps keys and passwords out of logs. */
    override fun toString() = "ServerConnection($name)"
}

/**
 * Reads the Xray JSON that a Remnawave subscription gives Xray apps such as
 * Happ, and builds what the app's own Xray core runs: the subscription's
 * outbounds behind two local inbounds, SOCKS for the app's HTTP client and
 * HTTP for its WebViews, both on 127.0.0.1 only. The subscription's routing
 * and DNS are left out: everything goes to the server, which decides itself
 * what to open directly, and no geo databases are needed.
 */
object XrayConfig {

    private val json = Json { ignoreUnknownKeys = true }
    private val LOCAL_PROTOCOLS = setOf("freedom", "blackhole", "dns", "loopback")

    class NotXrayJson(message: String) : IllegalArgumentException(message)

    /** The connections in a subscription answer, in its order; the first is the server's preferred one. */
    fun parseSubscription(body: String): List<ServerConnection> {
        val root = runCatching { json.parseToJsonElement(body.trim()) }.getOrNull()
            ?: throw NotXrayJson("The subscription is not JSON")
        val configs = when (root) {
            is JsonArray -> root.mapNotNull { it as? JsonObject }
            is JsonObject -> listOf(root)
            else -> emptyList()
        }
        return configs.mapIndexedNotNull { index, config ->
            val outbounds = (config["outbounds"] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()
            val proxy = outbounds.firstOrNull { it.text("tag") == PROXY_TAG }
                ?: outbounds.firstOrNull { it.text("protocol") !in LOCAL_PROTOCOLS }
                ?: return@mapIndexedNotNull null
            // The proxy goes first: Xray sends everything to the first outbound when no rule says otherwise.
            val ordered = listOf(proxy) + outbounds.filter { it !== proxy }
            ServerConnection(
                name = config.text("remarks")?.takeIf { it.isNotBlank() } ?: "Server ${index + 1}",
                outbounds = JsonArray(ordered).toString(),
            )
        }.ifEmpty { throw NotXrayJson("No Xray connections in the subscription") }
    }

    /** The whole config for the core: [connection]'s outbounds behind local SOCKS and HTTP inbounds. */
    fun build(connection: ServerConnection, socksPort: Int, httpPort: Int): String = buildJsonObject {
        putJsonObject("log") { put("loglevel", "warning") }
        putJsonArray("inbounds") {
            add(inbound("socks", socksPort) { put("auth", "noauth"); put("udp", false) })
            add(inbound("http", httpPort) {})
        }
        put("outbounds", json.parseToJsonElement(connection.outbounds))
        putJsonObject("routing") {
            put("domainStrategy", "AsIs")
            putJsonArray("rules") {}
        }
    }.toString()

    private fun inbound(
        protocol: String,
        port: Int,
        settings: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonElement = buildJsonObject {
        put("tag", "$protocol-in")
        put("listen", "127.0.0.1")
        put("port", port)
        put("protocol", protocol)
        putJsonObject("settings", settings)
    }

    private fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.contentOrNull

    private const val PROXY_TAG = "proxy"
}
