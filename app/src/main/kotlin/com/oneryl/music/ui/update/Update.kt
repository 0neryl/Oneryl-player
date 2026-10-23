package com.oneryl.music.ui.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneryl.music.R
import com.oneryl.music.update.AppUpdater
import com.oneryl.music.update.UpdateState

/** On Home: a new version, how its download goes, or why it failed; nothing otherwise. */
@Composable
fun UpdateCard(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    val dismissed by updater.dismissed.collectAsStateWithLifecycle()
    val shown = state as? UpdateState.ForRelease ?: return
    if (shown is UpdateState.Available && shown.release.version == dismissed) return

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp)
            .testTag("update_card"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(R.string.update_available, shown.release.version),
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            statusText(shown, updater.currentVersion),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Progress(shown)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (shown is UpdateState.Available) {
                TextButton(onClick = updater::dismiss) { Text(stringResource(R.string.update_later)) }
                Spacer(Modifier.width(8.dp))
            }
            Action(updater, shown)
        }
    }
}

/** In Library: the installed version and the way to look for a new one. */
@Composable
fun UpdateRow(updater: AppUpdater) {
    val state by updater.state.collectAsStateWithLifecycle()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.SystemUpdate,
            contentDescription = null,
            tint = if (state is UpdateState.ForRelease) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(R.string.update_version, updater.currentVersion),
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                when (val shown = state) {
                    UpdateState.Idle -> stringResource(R.string.update_auto)
                    UpdateState.Checking -> stringResource(R.string.update_checking)
                    UpdateState.UpToDate -> stringResource(R.string.update_latest)
                    UpdateState.CheckFailed -> stringResource(R.string.update_check_failed)
                    is UpdateState.ForRelease -> stringResource(R.string.update_available, shown.release.version)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag(
                    when (state) {
                        UpdateState.UpToDate -> "update_latest"
                        UpdateState.CheckFailed -> "update_check_failed"
                        else -> "update_status"
                    },
                ),
            )
            (state as? UpdateState.ForRelease)?.let { Progress(it) }
        }
        Spacer(Modifier.width(8.dp))
        when (val shown = state) {
            is UpdateState.ForRelease -> Action(updater, shown)
            UpdateState.Checking -> Unit
            else -> TextButton(onClick = { updater.check() }, modifier = Modifier.testTag("update_check")) {
                Text(stringResource(R.string.update_check))
            }
        }
    }
}

@Composable
private fun statusText(state: UpdateState.ForRelease, current: String): String = when (state) {
    is UpdateState.Available -> stringResource(R.string.update_available_hint, current)
    is UpdateState.Downloading ->
        stringResource(R.string.update_downloading, (state.progress * 100).toInt())
    is UpdateState.Installing -> stringResource(R.string.update_installing)
    is UpdateState.NeedsConfirmation -> stringResource(R.string.update_confirm_hint)
    is UpdateState.Failed -> when (state.step) {
        UpdateState.Step.DOWNLOAD -> stringResource(R.string.update_failed_download)
        UpdateState.Step.INSTALL ->
            listOfNotNull(stringResource(R.string.update_failed_install), state.detail).joinToString(": ")
    }
}

@Composable
private fun Progress(state: UpdateState.ForRelease) {
    when (state) {
        is UpdateState.Downloading -> {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
        }
        is UpdateState.Installing -> {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        else -> Unit
    }
}

/** The one thing to do next about a new version, if any. */
@Composable
private fun Action(updater: AppUpdater, state: UpdateState.ForRelease) {
    val context = LocalContext.current
    when (state) {
        is UpdateState.Available -> Button(onClick = updater::update, modifier = Modifier.testTag("update_action")) {
            Text(stringResource(R.string.update_action))
        }
        is UpdateState.NeedsConfirmation -> Button(onClick = { confirm(context, state.confirm) }, modifier = Modifier.testTag("update_confirm")) {
            Text(stringResource(R.string.update_install))
        }
        is UpdateState.Failed -> FilledTonalButton(onClick = updater::update) { Text(stringResource(R.string.retry)) }
        is UpdateState.Downloading, is UpdateState.Installing -> Unit
    }
}

private fun confirm(context: Context, intent: Intent) {
    try {
        context.startActivity(Intent(intent))
    } catch (e: ActivityNotFoundException) {
        // Nothing on this phone installs apps; the card keeps offering it.
    }
}
