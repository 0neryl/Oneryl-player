package com.oneryl.music.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneryl.music.R
import com.oneryl.music.data.YoutubeAccount
import com.oneryl.music.data.YoutubeSession
import com.oneryl.music.net.ServerTunnel
import com.oneryl.music.ui.components.ScreenTitle
import com.oneryl.music.ui.update.UpdateRow
import com.oneryl.music.update.AppUpdater

/** The YouTube account, the user's own server and the app's version, out of the way of the music. */
@Composable
fun SettingsScreen(
    account: YoutubeAccount,
    tunnel: ServerTunnel,
    updater: AppUpdater,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onSignIn: () -> Unit,
) {
    val session by account.session.collectAsStateWithLifecycle()
    var confirmSignOut by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        state = rememberLazyListState(),
        contentPadding = contentPadding,
        modifier = Modifier.testTag("settings_screen"),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(4.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                }
            }
        }
        item { ScreenTitle(stringResource(R.string.settings)) }
        item { Category(stringResource(R.string.settings_youtube)) }
        item { AccountRow(session, onSignIn = onSignIn, onSignOut = { confirmSignOut = true }) }
        item { Category(stringResource(R.string.settings_connection)) }
        item { ServerRow(tunnel) }
        item { Category(stringResource(R.string.settings_app)) }
        item { UpdateRow(updater) }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text(stringResource(R.string.account_sign_out_title)) },
            text = { Text(stringResource(R.string.account_sign_out_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmSignOut = false
                    account.signOut()
                }) { Text(stringResource(R.string.account_sign_out)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

@Composable
private fun Category(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

/** The optional YouTube account: why to sign in, or whose account is in use. */
@Composable
private fun AccountRow(session: YoutubeSession?, onSignIn: () -> Unit, onSignOut: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.AccountCircle,
            contentDescription = null,
            tint = if (session != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    session == null -> stringResource(R.string.account_youtube)
                    else -> session.name ?: stringResource(R.string.account_signed_in)
                },
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (session == null) stringResource(R.string.account_why) else stringResource(R.string.account_in_use),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (session == null) {
            FilledTonalButton(onClick = onSignIn) { Text(stringResource(R.string.account_sign_in)) }
        } else {
            TextButton(onClick = onSignOut) { Text(stringResource(R.string.account_sign_out)) }
        }
    }
}
