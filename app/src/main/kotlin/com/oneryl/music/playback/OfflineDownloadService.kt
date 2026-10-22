package com.oneryl.music.playback

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.oneryl.music.MainActivity
import com.oneryl.music.R
import com.oneryl.music.app

/**
 * Keeps downloads going while the app is in the background, with a progress
 * notification. It runs only while there is something to download; the app
 * starts it from the foreground, as Android requires.
 */
@UnstableApi
class OfflineDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel,
    0,
) {
    private val notifications by lazy { DownloadNotificationHelper(this, CHANNEL_ID) }

    override fun getDownloadManager(): DownloadManager = app.offline.manager

    /** Without a scheduler the service waits in the foreground when the network drops. */
    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val left = downloads.count { it.state == Download.STATE_QUEUED || it.state == Download.STATE_DOWNLOADING }
        return notifications.buildProgressNotification(
            this,
            android.R.drawable.stat_sys_download,
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
            resources.getQuantityString(R.plurals.downloads_left, left, left),
            downloads,
            notMetRequirements,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 2
        const val CHANNEL_ID = "downloads"
    }
}
