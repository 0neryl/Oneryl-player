package com.oneryl.music.ui.account

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import com.oneryl.music.R
import com.oneryl.music.data.YoutubeAccount
import com.oneryl.music.data.YoutubeSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Google's own sign-in page in a WebView: the password goes to Google, never
 * to the app. Once Google lands on YouTube Music signed in, the session is
 * read from the page's cookies and handed to [onSignedIn], and everything the
 * WebView kept is wiped.
 */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun YoutubeLoginScreen(
    onSignedIn: suspend (YoutubeSession) -> Unit,
    onFailed: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }
    var busy by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        onDispose {
            webView?.run {
                clearCache(true)
                clearFormData()
                clearHistory()
                destroy()
            }
            forgetWebSession()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TopAppBar(
            title = { Text(stringResource(R.string.account_sign_in_title)) },
            navigationIcon = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.close))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            // Links to other apps or plain http stay closed.
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                request.url.scheme != "https"

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (busy || url?.toUri()?.host != "music.youtube.com") return
                                busy = true
                                scope.launch {
                                    val session = readSession(view)
                                    if (session == null) {
                                        busy = false
                                        return@launch
                                    }
                                    try {
                                        onSignedIn(session)
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        onFailed()
                                    }
                                }
                            }
                        }
                        webView = this
                        // Start from a clean slate, so no earlier session signs in by itself.
                        CookieManager.getInstance().removeAllCookies { loadUrl(YoutubeAccount.SIGN_IN_URL) }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (busy) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/** Waits up to ten seconds for the signed-in page to show its session. */
private suspend fun readSession(view: WebView): YoutubeSession? {
    repeat(20) {
        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
        if (YoutubeAccount.isSignedIn(cookie)) {
            YoutubeAccount.pageSession(cookie, view.evaluate(YoutubeAccount.PAGE_CONFIG_SCRIPT))?.let { return it }
        }
        delay(500)
    }
    return null
}

private suspend fun WebView.evaluate(script: String): String? = suspendCancellableCoroutine { continuation ->
    evaluateJavascript(script) { continuation.resume(it) }
}

/** The app keeps its own encrypted copy; the WebView's cookies and storage go. */
private fun forgetWebSession() {
    CookieManager.getInstance().run {
        removeAllCookies(null)
        flush()
    }
    WebStorage.getInstance().deleteAllData()
}
