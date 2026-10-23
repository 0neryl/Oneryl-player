package com.oneryl.music.update

import android.Manifest
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.oneryl.music.MainActivity
import com.oneryl.music.R
import com.oneryl.music.app

/** Hears from Android's package installer how installing an update went. */
class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val updater = context.app.updater
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                    ?: return updater.onInstallFailed(null)
                updater.onConfirmationNeeded(confirm)
                // Shown at once while the app is on screen; otherwise its update card offers it.
                try {
                    context.startActivity(Intent(confirm).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: ActivityNotFoundException) {
                    Log.w(TAG, "No installer to confirm the update", e)
                }
            }
            // The new version replaces this process; AppUpdatedReceiver takes over in it.
            PackageInstaller.STATUS_SUCCESS -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> updater.onInstallCancelled()
            else -> {
                val detail = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(TAG, "Update not installed: status $status, $detail")
                updater.onInstallFailed(detail)
            }
        }
    }

    private companion object {
        const val TAG = "UpdateInstallReceiver"
    }
}

/**
 * Runs in the new version once Android has installed it. The update closed
 * the app, so a notification offers to open it again.
 */
class AppUpdatedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val version = AppUpdater.takeInstalledVersion(context) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val notifications = NotificationManagerCompat.from(context)
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.updates_channel))
                .build(),
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notifications.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.update_done_title, version))
                .setContentText(context.getString(R.string.update_done_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    private companion object {
        const val CHANNEL_ID = "updates"
        const val NOTIFICATION_ID = 3
    }
}
