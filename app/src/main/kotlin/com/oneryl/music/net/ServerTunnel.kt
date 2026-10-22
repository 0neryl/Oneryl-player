package com.oneryl.music.net

import android.content.Context
import android.util.Log
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.oneryl.music.data.SecretBox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ServerSocket
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.Executor

/** The user's own server, from their subscription, and whether the app goes through it. */
@Serializable
data class ServerSettings(
    /** Empty when an Xray config was pasted instead of a subscription link. */
    val subscriptionUrl: String,
    val connections: List<ServerConnection>,
    val fetchedAtMillis: Long,
    val enabled: Boolean = true,
) {
    /** Keeps the subscription address, which grants access to the server, out of logs. */
    override fun toString() = "ServerSettings(${connections.size} connections, enabled=$enabled)"
}

sealed interface TunnelState {
    /** No server, or switched off: the app connects directly. */
    data object Off : TunnelState
    data object Connecting : TunnelState
    data class Connected(val name: String) : TunnelState

    /** None of the subscription's connections got through; it tries again by itself. */
    data object Unreachable : TunnelState

    /** The core would not run the subscription's connections. */
    data class Broken(val detail: String) : TunnelState
}

class SubscriptionException(val reason: Reason, message: String, cause: Throwable? = null) : IOException(message, cause) {
    enum class Reason { BAD_LINK, NETWORK, NOT_XRAY }
}

/**
 * Runs an Xray core inside the app so it reaches the internet through the
 * user's own server, the one their VPN uses, without a VPN on the phone. The
 * core listens on 127.0.0.1 only; the app's HTTP client goes through it via
 * [proxySelector] and its WebViews via a proxy override. The subscription's
 * connections are tried in order until one gets through, and while none does
 * the app connects directly. The subscription, which grants access to the
 * server, is stored encrypted.
 */
class ServerTunnel(
    private val context: Context,
    private val direct: OkHttpClient,
    private val scope: CoroutineScope,
    /** Drops pooled connections, so requests after a switch take the new route. */
    private val onRouteChange: () -> Unit,
) {
    private val preferences = context.getSharedPreferences("server_tunnel", Context.MODE_PRIVATE)
    private val box = SecretBox(KEY_ALIAS)

    /** One core operation at a time. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val coreThread = Dispatchers.IO.limitedParallelism(1)

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<ServerSettings?> = _settings.asStateFlow()

    private val _state = MutableStateFlow<TunnelState>(TunnelState.Off)
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    @Volatile
    private var proxy: Proxy? = null
    private var controller: CoreController? = null

    @Volatile
    private var retryJob: Job? = null

    val proxySelector: ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> = listOf(proxy ?: Proxy.NO_PROXY)
        override fun connectFailed(uri: URI?, address: SocketAddress?, error: IOException?) = Unit
    }

    /** The core's version, or null if it does not load on this phone. */
    val coreVersion: String? by lazy {
        runCatching { Libv2ray.checkVersionX() }.onFailure { Log.w(TAG, "No Xray core: $it") }.getOrNull()
    }

    /** Connects at app start if a server is set up, refreshing a stale subscription first. */
    fun start() {
        val settings = _settings.value?.takeIf { it.enabled } ?: return
        scope.launch {
            val stale = System.currentTimeMillis() - settings.fetchedAtMillis > REFRESH_MS
            if (stale && settings.subscriptionUrl.isNotEmpty()) refresh(settings)
            connect()
        }
    }

    /**
     * Reads the subscription [input] links to, or takes [input] itself as an
     * Xray config in JSON, saves it and connects through it.
     */
    suspend fun setUp(input: String) {
        val text = input.trim()
        val (source, connections) = if (text.startsWith("[") || text.startsWith("{")) {
            "" to parse(text)
        } else {
            val url = text.toHttpUrlOrNull()?.takeIf { it.isHttps }?.toString()
                ?: throw SubscriptionException(SubscriptionException.Reason.BAD_LINK, "Not an https link")
            url to fetch(url)
        }
        save(ServerSettings(source, connections, System.currentTimeMillis()))
        // In the app's scope, so leaving the screen does not stop the core halfway.
        scope.launch { connect() }.join()
    }

    fun setEnabled(enabled: Boolean) {
        val settings = _settings.value ?: return
        save(settings.copy(enabled = enabled))
        scope.launch { if (enabled) connect() else withContext(coreThread) { disconnect() } }
    }

    fun retry() {
        scope.launch { connect() }
    }

    fun remove() {
        save(null)
        scope.launch { withContext(coreThread) { disconnect() } }
    }

    private suspend fun refresh(settings: ServerSettings) {
        try {
            save(settings.copy(connections = fetch(settings.subscriptionUrl), fetchedAtMillis = System.currentTimeMillis()))
        } catch (e: IOException) {
            Log.w(TAG, "Kept the saved subscription: ${e.javaClass.simpleName}")
        }
    }

    /** Asks for the subscription the way Happ does, which Remnawave answers with full Xray configs. */
    private suspend fun fetch(url: String): List<ServerConnection> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", SUBSCRIPTION_AGENT).header("Accept", "application/json").build()
        val body = try {
            direct.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SubscriptionException(SubscriptionException.Reason.NETWORK, "HTTP ${response.code}")
                }
                response.body.string()
            }
        } catch (e: SubscriptionException) {
            throw e
        } catch (e: IOException) {
            throw SubscriptionException(SubscriptionException.Reason.NETWORK, e.javaClass.simpleName, e)
        }
        parse(body)
    }

    private fun parse(body: String): List<ServerConnection> = try {
        XrayConfig.parseSubscription(body)
    } catch (e: XrayConfig.NotXrayJson) {
        throw SubscriptionException(SubscriptionException.Reason.NOT_XRAY, e.message.orEmpty(), e)
    }

    private suspend fun connect(): Unit = withContext(coreThread) {
        retryJob?.cancel()
        disconnect()
        val settings = _settings.value?.takeIf { it.enabled } ?: return@withContext
        val controller = controller() ?: run {
            _state.value = TunnelState.Broken("Xray core did not load")
            return@withContext
        }
        _state.value = TunnelState.Connecting
        val socksPort = freePort()
        val httpPort = freePort()
        proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(InetAddress.getLoopbackAddress(), socksPort))
        onRouteChange()

        var startError: String? = null
        var started = false
        for (connection in settings.connections) {
            try {
                controller.startLoop(XrayConfig.build(connection, socksPort, httpPort), 0)
            } catch (e: Exception) {
                Log.w(TAG, "${connection.name} did not start: ${e.message}")
                startError = e.message
                continue
            }
            started = true
            val delayMs = try {
                controller.measureDelay("")
            } catch (e: Exception) {
                Log.w(TAG, "${connection.name} did not get through: ${e.message}")
                -1L
            }
            if (delayMs >= 0) {
                Log.i(TAG, "Connected through ${connection.name} in $delayMs ms")
                setWebViewProxy(httpPort)
                _state.value = TunnelState.Connected(connection.name)
                return@withContext
            }
            runCatching { controller.stopLoop() }
        }

        proxy = null
        onRouteChange()
        _state.value = if (started) TunnelState.Unreachable else TunnelState.Broken(startError ?: "no connections")
        retryJob = scope.launch {
            delay(RETRY_MS)
            // Cleared first: connect() cancels a pending retry, which would otherwise be this one.
            retryJob = null
            connect()
        }
    }

    /** Stops the core; the app connects directly until the next [connect]. */
    private fun disconnect() {
        retryJob?.cancel()
        runCatching { controller?.takeIf { it.isRunning }?.stopLoop() }
        if (proxy != null) {
            proxy = null
            onRouteChange()
        }
        clearWebViewProxy()
        _state.value = TunnelState.Off
    }

    private fun controller(): CoreController? = controller ?: runCatching {
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        Libv2ray.newCoreController(Callbacks)
    }.onFailure { Log.w(TAG, "No Xray core: $it") }.getOrNull()?.also { controller = it }

    private fun setWebViewProxy(port: Int) = onMain {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return@onMain
        val config = ProxyConfig.Builder().addProxyRule("127.0.0.1:$port").build()
        ProxyController.getInstance().setProxyOverride(config, SameThread) {}
    }

    private fun clearWebViewProxy() = onMain {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return@onMain
        ProxyController.getInstance().clearProxyOverride(SameThread) {}
    }

    private fun onMain(block: () -> Unit) {
        scope.launch(Dispatchers.Main) { runCatching(block) }
    }

    private fun load(): ServerSettings? {
        val sealed = preferences.getString(KEY_SETTINGS, null) ?: return null
        return try {
            json.decodeFromString(ServerSettings.serializer(), box.open(sealed))
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read the saved server: ${e.javaClass.simpleName}")
            preferences.edit().remove(KEY_SETTINGS).apply()
            null
        }
    }

    private fun save(settings: ServerSettings?) {
        _settings.value = settings
        preferences.edit().apply {
            if (settings == null) remove(KEY_SETTINGS) else putString(KEY_SETTINGS, box.seal(json.encodeToString(ServerSettings.serializer(), settings)))
        }.apply()
    }

    private object Callbacks : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(code: Long, message: String?): Long = 0
    }

    private object SameThread : Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private companion object {
        const val TAG = "ServerTunnel"
        const val KEY_ALIAS = "server_tunnel"
        const val KEY_SETTINGS = "settings"
        const val SUBSCRIPTION_AGENT = "Happ/3.13.0"
        const val REFRESH_MS = 4 * 60 * 60 * 1000L
        const val RETRY_MS = 60_000L
        val json = Json { ignoreUnknownKeys = true }

        fun freePort(): Int = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
    }
}
