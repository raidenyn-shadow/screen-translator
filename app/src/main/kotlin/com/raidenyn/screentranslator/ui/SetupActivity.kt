package com.raidenyn.screentranslator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.launch

/**
 * Opened by [TrampolineActivity] when the accessibility service is not enabled.
 * Hosts [SetupScreen], the only real page in the app.
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SetupScreen() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ScreenCaptureService.connection.collect { service ->
                    // The launcher can reach setup before Android reconnects an
                    // enabled service, or resume an existing setup task. Continue
                    // the original capture once ready, closing our UI before the
                    // pipeline's 400 ms sidebar-dismissal/screenshot delay.
                    if (service != null && !isFinishing) {
                        ScreenCaptureService.requestCapture()
                        finish()
                        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
                    }
                }
            }
        }
    }
}
