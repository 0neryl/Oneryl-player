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
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.oneryl.music.playback.PlayerConnection
import com.oneryl.music.ui.OnerylApp
import com.oneryl.music.ui.theme.OnerylTheme

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

        setContent {
            OnerylTheme {
                OnerylApp(app.catalog, app.library, app.offline.states, app.account, connection)
            }
        }
    }

    override fun onDestroy() {
        connection.release()
        super.onDestroy()
    }
}
