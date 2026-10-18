package com.oneryl.music.ui.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.oneryl.music.playback.PlayerConnection
import kotlinx.coroutines.delay

/** The playback position, read from the player a few times a second while on screen. */
@Composable
fun rememberPosition(connection: PlayerConnection, key: Any?): Long {
    var position by remember { mutableLongStateOf(connection.positionMs) }
    LaunchedEffect(connection, key) {
        while (true) {
            position = connection.positionMs
            delay(250)
        }
    }
    return position
}
