package io.github.latencyscope.sample

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/** The sample's only screen. */
internal class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
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
}
