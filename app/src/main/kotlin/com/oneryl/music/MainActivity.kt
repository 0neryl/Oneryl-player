package com.oneryl.music

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadService
import com.oneryl.music.playback.OfflineDownloadService
import com.oneryl.music.playback.PlayerConnection
import com.oneryl.music.ui.OnerylApp
import com.oneryl.music.ui.theme.OnerylTheme
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var connection: PlayerConnection

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        // The app is always dark, so the system bars keep light icons whatever the phone's theme.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        connection = PlayerConnection(this, lifecycleScope)

        // The player lives in a notification, which Android 13+ hides without this.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // Downloads carry on in the background through a service, which Android
        // lets the app start only while it is on screen.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.offline.pending.collect { pending ->
                    if (pending) runCatching { DownloadService.start(this@MainActivity, OfflineDownloadService::class.java) }
                }
            }
        }

        setContent {
            OnerylTheme {
                OnerylApp(app.catalog, app.library, app.offline, app.account, app.importer, app.tunnel, connection)
            }
        }
    }

    override fun onDestroy() {
        connection.release()
        super.onDestroy()
    }
}
