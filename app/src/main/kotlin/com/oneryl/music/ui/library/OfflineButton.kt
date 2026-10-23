package com.oneryl.music.ui.library

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.oneryl.music.R

/**
 * A download button in a ring that fills as the [ready] of [total] tracks
 * are downloaded. Once all of them play without a network it shows a tick
 * and does nothing; until then [onDownload] gets the rest downloaded.
 */
@Composable
internal fun OfflineButton(ready: Int, total: Int, onDownload: () -> Unit) {
    val complete = total > 0 && ready >= total
    val progress by animateFloatAsState(if (total > 0) ready.toFloat() / total else 0f, label = "offline")
    IconButton(
        onClick = onDownload,
        enabled = !complete,
        modifier = Modifier.testTag(if (complete) "offline_complete" else "offline_download"),
    ) {
        Box(contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                progress = { progress },
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                strokeWidth = 3.dp,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
            )
            Icon(
                if (complete) Icons.Filled.DownloadDone else Icons.Filled.Download,
                contentDescription = if (complete) {
                    stringResource(R.string.offline_all_ready)
                } else {
                    stringResource(R.string.offline_download_rest, ready, total)
                },
                tint = if (complete) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
