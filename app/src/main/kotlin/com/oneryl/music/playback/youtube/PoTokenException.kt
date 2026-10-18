/*
 * Adapted from Metrolist (GPL-3.0), which took it from NewPipe (GPL-3.0).
 */
package com.oneryl.music.playback.youtube

class PoTokenException(message: String) : Exception(message)

/** Thrown when the system WebView cannot run the BotGuard script at all. */
class BadWebViewException(message: String) : Exception(message)

fun buildExceptionForJsError(error: String): Exception =
    if (error.contains("SyntaxError")) BadWebViewException(error) else PoTokenException(error)
