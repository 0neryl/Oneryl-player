package com.oneryl.music.ui.library

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneryl.music.R
import com.oneryl.music.net.ServerTunnel
import com.oneryl.music.net.SubscriptionException
import com.oneryl.music.net.TunnelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The user's own server: what it is doing, and the way to set it up, switch it off or remove it. */
@Composable
internal fun ServerRow(tunnel: ServerTunnel) {
    val settings by tunnel.settings.collectAsStateWithLifecycle()
    val state by tunnel.state.collectAsStateWithLifecycle()
    var settingUp by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    val configured = settings != null
    val enabled = settings?.enabled == true
    val (title, subtitle) = when {
        !configured -> stringResource(R.string.server_title) to stringResource(R.string.server_why)
        !enabled -> stringResource(R.string.server_off) to stringResource(R.string.server_off_hint)
        else -> when (val shown = state) {
            is TunnelState.Connected -> stringResource(R.string.server_connected) to shown.name
            TunnelState.Connecting, TunnelState.Off -> stringResource(R.string.server_connecting) to stringResource(R.string.server_title)
            TunnelState.Unreachable -> stringResource(R.string.server_unreachable) to stringResource(R.string.server_unreachable_hint)
            is TunnelState.Broken -> stringResource(R.string.server_broken) to shown.detail
        }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Dns,
            contentDescription = null,
            tint = if (state is TunnelState.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(if (state is TunnelState.Connected) "server_connected" else "server_status"),
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            !configured -> FilledTonalButton(onClick = { settingUp = true }, modifier = Modifier.testTag("server_set_up")) {
                Text(stringResource(R.string.server_set_up))
            }
            !enabled -> FilledTonalButton(onClick = { tunnel.setEnabled(true) }) { Text(stringResource(R.string.server_turn_on)) }
            state is TunnelState.Unreachable || state is TunnelState.Broken ->
                TextButton(onClick = tunnel::retry) { Text(stringResource(R.string.retry)) }
        }
        if (configured) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (enabled) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.server_turn_off)) },
                            onClick = {
                                menuOpen = false
                                tunnel.setEnabled(false)
                            },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_change)) },
                        onClick = {
                            menuOpen = false
                            settingUp = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.server_remove)) },
                        onClick = {
                            menuOpen = false
                            tunnel.remove()
                        },
                    )
                }
            }
        }
    }

    if (settingUp) ServerDialog(tunnel, onDone = { settingUp = false })
}

/** Asks for the subscription link and connects through it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ServerDialog(tunnel: ServerTunnel, onDone: () -> Unit) {
    var link by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SubscriptionException?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDone() },
        title = { Text(stringResource(R.string.server_title)) },
        text = {
            // A dialog is a window of its own, so the emulator test needs the tags exposed here too.
            Column(Modifier.semantics { testTagsAsResourceId = true }) {
                Text(stringResource(R.string.server_hint), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = link,
                    onValueChange = {
                        link = it
                        failure = null
                    },
                    label = { Text(stringResource(R.string.server_link)) },
                    singleLine = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag("server_link"),
                )
                if (busy) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                failure?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when (it.reason) {
                            SubscriptionException.Reason.BAD_LINK -> stringResource(R.string.server_error_link)
                            SubscriptionException.Reason.NETWORK -> stringResource(R.string.server_error_network, it.message.orEmpty())
                            SubscriptionException.Reason.NOT_XRAY -> stringResource(R.string.server_error_not_xray)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("server_error"),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    tunnel.coreVersion ?: stringResource(R.string.server_no_core),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.testTag("xray_version"),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && link.isNotBlank() && tunnel.coreVersion != null,
                onClick = {
                    busy = true
                    failure = null
                    scope.launch {
                        try {
                            tunnel.setUp(link)
                            onDone()
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: SubscriptionException) {
                            failure = e
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(stringResource(R.string.server_connect)) }
        },
        dismissButton = {
            TextButton(enabled = !busy, onClick = onDone) { Text(stringResource(R.string.cancel)) }
        },
    )
}
