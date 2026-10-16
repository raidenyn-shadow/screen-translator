package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.launch
import java.util.UUID

/** Hosts opaque setup or an empty transparent root while a capture is handed to its child. */
open class SetupActivity : ComponentActivity() {

    private var pendingCapture = false
    private var consentInFlight = false
    private var captureMode = false
    private var requestId: String? = null
    private var childLaunched = false
    private var resumed = false

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consentResult(result.resultCode, result.data)
    }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (!isFinishing) returnToSettings()
    }

    protected open fun captureRequested(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_PENDING_CAPTURE, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        captureMode = savedInstanceState?.getBoolean(STATE_CAPTURE_MODE)
            ?: intent.getBooleanExtra(EXTRA_CAPTURE_MODE, false)
        pendingCapture = savedInstanceState?.getBoolean(STATE_PENDING_CAPTURE)
            ?: captureRequested(intent)
        consentInFlight = savedInstanceState?.getBoolean(STATE_CONSENT_IN_FLIGHT) ?: false
        requestId = savedInstanceState?.getString(STATE_REQUEST_ID)
            ?: intent.getStringExtra(EXTRA_REQUEST_ID)
        childLaunched = savedInstanceState?.getBoolean(STATE_CHILD_LAUNCHED) ?: false
        setTheme(if (captureMode) R.style.Theme_ScreenTranslator_Capture else R.style.Theme_ScreenTranslator_Setup)
        super.onCreate(savedInstanceState)
        if (!captureMode) {
            setContent {
                val service by ScreenCaptureService.connection.collectAsState()
                SetupScreen(
                    sharing = service != null,
                    onStartSharing = { requestConsent() },
                    onStopSharing = { ScreenCaptureService.stop(this) },
                )
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ScreenCaptureService.connection.collect { service ->
                    if (service != null && pendingCapture) beginCapture()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        when {
            captureMode && !childLaunched -> launchCapture()
            !captureMode && pendingCapture && ScreenCaptureService.connection.value == null && !consentInFlight -> requestConsent()
            !captureMode && pendingCapture -> beginCapture()
        }
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!captureRequested(intent)) {
            pendingCapture = false
            CaptureActivity.cancelActiveCapture()
            if (captureMode) returnToSettings()
            return
        }
        pendingCapture = true
        if (ScreenCaptureService.connection.value != null) beginCapture() else if (!consentInFlight) requestConsent()
    }

    @Deprecated("Activity Result API callback compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (consentInFlight) consentResult(resultCode, data)
        super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_CAPTURE, pendingCapture)
        outState.putBoolean(STATE_CONSENT_IN_FLIGHT, consentInFlight)
        outState.putBoolean(STATE_CAPTURE_MODE, captureMode)
        outState.putString(STATE_REQUEST_ID, requestId)
        outState.putBoolean(STATE_CHILD_LAUNCHED, childLaunched)
        super.onSaveInstanceState(outState)
    }

    private fun requestConsent() {
        if (consentInFlight) return
        consentInFlight = true
        val manager = getSystemService(MediaProjectionManager::class.java)
        val intent = if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        consent.launch(intent)
    }

    private fun consentResult(resultCode: Int, data: Intent?) {
        if (!consentInFlight) return
        consentInFlight = false
        if (resultCode == Activity.RESULT_OK && data != null &&
            lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        ) {
            ScreenCaptureService.start(this, resultCode, data)
        }
    }

    private fun beginCapture() {
        if (!pendingCapture || captureMode || ScreenCaptureService.connection.value == null) return
        requestId = requestId ?: UUID.randomUUID().toString()
        captureMode = true
        intent.putExtra(EXTRA_CAPTURE_MODE, true).putExtra(EXTRA_REQUEST_ID, requestId)
        recreate()
    }

    private fun launchCapture() {
        val id = requestId ?: run {
            returnToSettings()
            return
        }
        if (ScreenCaptureService.connection.value == null) returnToSettings()
        childLaunched = true
        capture.launch(CaptureActivity.intent(this, id))
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun returnToSettings() {
        captureMode = false
        pendingCapture = false
        requestId = null
        childLaunched = false
        intent.removeExtra(EXTRA_CAPTURE_MODE)
        intent.removeExtra(EXTRA_REQUEST_ID)
        recreate()
    }

    companion object {
        const val EXTRA_PENDING_CAPTURE = "pending_capture"
        internal const val EXTRA_CAPTURE_MODE = "capture_mode"
        internal const val EXTRA_REQUEST_ID = "request_id"
        private const val STATE_PENDING_CAPTURE = "pending_capture_state"
        private const val STATE_CONSENT_IN_FLIGHT = "consent_in_flight"
        private const val STATE_CAPTURE_MODE = "capture_mode_state"
        private const val STATE_REQUEST_ID = "request_id_state"
        private const val STATE_CHILD_LAUNCHED = "child_launched"
    }
}
