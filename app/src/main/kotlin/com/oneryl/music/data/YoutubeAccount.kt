package com.oneryl.music.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A signed-in YouTube session: the youtube.com cookies and page settings, and whose they are. */
@Serializable
data class YoutubeSession(
    val cookie: String,
    val visitorData: String,
    val dataSyncId: String,
    val authUser: String = "0",
    val name: String? = null,
    val email: String? = null,
) {
    /** Keeps the cookies out of logs and crash reports. */
    override fun toString() = "YoutubeSession(name=$name)"
}

/**
 * The optional YouTube account, used when YouTube refuses to play a track
 * without one. The session is stored encrypted with a key that never leaves
 * the phone's keystore; it stays out of backups and logs and is only ever
 * sent to YouTube. [describe] looks up the account's name before saving.
 */
class YoutubeAccount(
    context: Context,
    private val describe: suspend (YoutubeSession) -> YoutubeSession = { it },
) {
    private val preferences = context.getSharedPreferences("youtube_account", Context.MODE_PRIVATE)
    private val box = SecretBox(KEY_ALIAS)

    private val _session = MutableStateFlow(load())
    val session: StateFlow<YoutubeSession?> = _session.asStateFlow()

    suspend fun signIn(session: YoutubeSession) {
        val described = describe(session)
        preferences.edit().putString(KEY_SESSION, box.seal(json.encodeToString(YoutubeSession.serializer(), described))).apply()
        _session.value = described
    }

    fun signOut() {
        preferences.edit().remove(KEY_SESSION).apply()
        _session.value = null
    }

    private fun load(): YoutubeSession? {
        val sealed = preferences.getString(KEY_SESSION, null) ?: return null
        return try {
            json.decodeFromString(YoutubeSession.serializer(), box.open(sealed))
        } catch (e: Exception) {
            // A key lost with a reinstall or a reset keystore: the user signs in again.
            Log.w(TAG, "Cannot read the saved session: ${e.javaClass.simpleName}")
            preferences.edit().remove(KEY_SESSION).apply()
            null
        }
    }

    companion object {
        /** Where sign-in starts; Google sends the browser to YouTube Music once it is done. */
        const val SIGN_IN_URL = "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

        /** Run on a YouTube Music page, it returns what [pageSession] reads. */
        const val PAGE_CONFIG_SCRIPT =
            "(function(){var c=window.yt&&window.yt.config_;" +
                "return c?{visitorData:c.VISITOR_DATA||'',dataSyncId:c.DATASYNC_ID||'',authUser:String(c.SESSION_INDEX||0)}:null})()"

        private const val TAG = "YoutubeAccount"
        private const val KEY_SESSION = "session"
        private const val KEY_ALIAS = "youtube_session"
        private val json = Json { ignoreUnknownKeys = true }

        /** True once YouTube's cookies hold a signed-in session. */
        fun isSignedIn(cookie: String): Boolean =
            cookie.split(';').any { it.trim().substringBefore('=') == "SAPISID" }

        /**
         * Builds the session from the cookies of a signed-in YouTube Music page
         * and the result of [PAGE_CONFIG_SCRIPT] there, or null if the page is
         * not ready yet.
         */
        fun pageSession(cookie: String, pageConfig: String?): YoutubeSession? {
            if (!isSignedIn(cookie)) return null
            val config = runCatching { json.parseToJsonElement(pageConfig.orEmpty()) as? JsonObject }.getOrNull()
                ?: return null
            fun field(name: String) = config[name]?.jsonPrimitive?.contentOrNull.orEmpty()
            val visitorData = field("visitorData").ifBlank { return null }
            return YoutubeSession(
                cookie = cookie,
                visitorData = visitorData,
                dataSyncId = field("dataSyncId").substringBefore("||"),
                authUser = field("authUser").filter(Char::isDigit).ifBlank { "0" },
            )
        }
    }
}
