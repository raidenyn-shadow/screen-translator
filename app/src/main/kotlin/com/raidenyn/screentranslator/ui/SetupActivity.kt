package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
    private var awaitingSession = false
    private var deferredConsent: Pair<Int, Intent?>? = null

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consentResult(result.resultCode, result.data)
    }

    private val capture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) finish() else if (!isFinishing) returnToSettings()
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
        awaitingSession = savedInstanceState?.getBoolean(STATE_AWAITING_SESSION) ?: false
        if (savedInstanceState?.getBoolean(STATE_DEFERRED_CONSENT) == true) {
            deferredConsent = savedInstanceState.getInt(STATE_DEFERRED_RESULT_CODE) to
                savedInstanceState.getParcelable(STATE_DEFERRED_DATA)
        }
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                ScreenCaptureService.startupError.collect { error ->
                    if (error != null && (pendingCapture || awaitingSession)) failToSettings(error)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { deliverDeferredConsent() }
        when {
            captureMode && !childLaunched -> launchCapture()
            !captureMode && pendingCapture && ScreenCaptureService.connection.value == null && !consentInFlight && !awaitingSession -> requestConsent()
            !captureMode && pendingCapture -> beginCapture()
        }
    }

    private fun deliverDeferredConsent() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        deferredConsent?.let { (code, data) ->
            deferredConsent = null
            consentResult(code, data)
        }
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
        if (ScreenCaptureService.connection.value != null) beginCapture()
        else if (!consentInFlight && !awaitingSession) requestConsent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_CAPTURE, pendingCapture)
        outState.putBoolean(STATE_CONSENT_IN_FLIGHT, consentInFlight)
        outState.putBoolean(STATE_CAPTURE_MODE, captureMode)
        outState.putString(STATE_REQUEST_ID, requestId)
        outState.putBoolean(STATE_CHILD_LAUNCHED, childLaunched)
        outState.putBoolean(STATE_AWAITING_SESSION, awaitingSession)
        deferredConsent?.let { (code, data) ->
            outState.putBoolean(STATE_DEFERRED_CONSENT, true)
            outState.putInt(STATE_DEFERRED_RESULT_CODE, code)
            outState.putParcelable(STATE_DEFERRED_DATA, data)
        }
        super.onSaveInstanceState(outState)
    }

    private fun requestConsent() {
        if (consentInFlight || awaitingSession) return
        consentInFlight = true
        try {
            val intent = consentIntent()
            consent.launch(intent)
        } catch (_: Throwable) {
            failToSettings(getString(R.string.error_session_failed))
        }
    }

    private fun consentResult(resultCode: Int, data: Intent?) {
        if (!consentInFlight) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            deferredConsent = resultCode to data
            return
        }
        consentInFlight = false
        if (resultCode != Activity.RESULT_OK || data == null) { returnToSettings(); return }
        awaitingSession = true
        try { startSession(resultCode, data) } catch (_: Throwable) { failToSettings(getString(R.string.error_session_failed)) }
    }

    protected open fun consentIntent(): Intent {
        val manager = getSystemService(MediaProjectionManager::class.java)
        return if (Build.VERSION.SDK_INT >= 34) {
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
    }

    protected open fun startSession(resultCode: Int, data: Intent) {
        ScreenCaptureService.start(this, resultCode, data)
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
        awaitingSession = false
        consentInFlight = false
        deferredConsent = null
        intent.removeExtra(EXTRA_CAPTURE_MODE)
        intent.removeExtra(EXTRA_REQUEST_ID)
        recreate()
    }

    private fun failToSettings(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        returnToSettings()
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
        private const val STATE_AWAITING_SESSION = "awaiting_session"
        private const val STATE_DEFERRED_CONSENT = "deferred_consent"
        private const val STATE_DEFERRED_RESULT_CODE = "deferred_result_code"
        private const val STATE_DEFERRED_DATA = "deferred_data"
    }
}
