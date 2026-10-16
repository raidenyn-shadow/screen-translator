package com.raidenyn.screentranslator.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.launch

/**
 * Hosts settings and resumes an explicitly pending capture when the service is ready.
 * The launcher subclass keeps this UI in the same task root that receives new intents.
 */
open class SetupActivity : ComponentActivity() {

    private var pendingCapture = false

    protected open fun captureRequested(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_PENDING_CAPTURE, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        pendingCapture = savedInstanceState?.getBoolean(EXTRA_PENDING_CAPTURE)
            ?: captureRequested(intent)
        // A cold, connected sidebar capture retains the transparent entry theme.
        // Every path that displays settings needs an opaque, normal settings window.
        if (savedInstanceState != null || !pendingCapture || ScreenCaptureService.instance == null) {
            setTheme(R.style.Theme_ScreenTranslator_Setup)
        }
        super.onCreate(savedInstanceState)
        // Restored tasks can receive a newer icon intent between onCreate and
        // onResume. Let it cancel stale sidebar state before dispatching a capture.
        if (savedInstanceState == null && captureIfReady()) return
        setContent { SetupScreen() }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ScreenCaptureService.connection.collect { service ->
                    if (service != null) captureIfReady()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new icon launch cancels an older pending capture. A sidebar launch
        // arms capture even if the service has been connected the whole time.
        pendingCapture = captureRequested(intent)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) captureIfReady()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(EXTRA_PENDING_CAPTURE, pendingCapture)
        super.onSaveInstanceState(outState)
    }

    private fun captureIfReady(): Boolean {
        if (!pendingCapture || isFinishing || ScreenCaptureService.instance == null) return false
        pendingCapture = false
        // A false result with a connected service means another capture is already
        // running. Close settings anyway and let that capture finish normally.
        ScreenCaptureService.requestCapture()
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
        return true
    }

    companion object {
        /** True when the opening tap requested a capture that setup should resume automatically. */
        const val EXTRA_PENDING_CAPTURE = "pending_capture"
    }
}
