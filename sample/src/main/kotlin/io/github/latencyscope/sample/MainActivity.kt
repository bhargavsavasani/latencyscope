package io.github.latencyscope.sample

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** The sample's only screen. */
internal class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Android 15 draws every app edge to edge whether or not it asks to,
        // so the window insets have to be handled rather than opted out of.
        // SampleScreen's Scaffold is what applies them.
        enableEdgeToEdge()
        requestNotificationPermission()
        setContent {
            // MaterialTheme with no scheme is light even when the system is
            // dark, so the sample would ignore the setting its own report
            // screens honour.
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(Modifier.fillMaxSize()) {
                    val controller = remember { SampleController(this) }
                    // The clients hold connection pools and, for Volley, a
                    // dispatcher thread; leaving them running after the screen
                    // is gone is the leak Phase 9 goes looking for.
                    DisposableEffect(controller) {
                        onDispose { controller.close() }
                    }
                    SampleScreen(controller)
                }
            }
        }
    }

    /**
     * Asks for POST_NOTIFICATIONS, which Android 13 made a runtime permission.
     *
     * The library never asks on an app's behalf — a debug tool prompting the
     * user is the app's decision, not the library's — so a host that wants
     * the recording notification asks here. Denying it costs nothing: the
     * session records exactly the same, it is just invisible.
     */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val launcher = registerForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { /* Granted or not, the sample behaves the same. */ }
        launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
