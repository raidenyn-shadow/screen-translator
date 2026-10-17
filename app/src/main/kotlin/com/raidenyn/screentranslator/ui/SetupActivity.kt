package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** Hosts setup or a transparent root that submits translation and removes its task. */
open class SetupActivity : ComponentActivity() {

    private var pendingCapture = false
    private var captureMode = false
    private var submitted = false
    private var awaitingSession = false
    private val consentState by viewModels<SetupConsentViewModel>()

    private val consent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        consentResult(result.resultCode, result.data)
    }

    protected open fun captureRequested(intent: Intent): Boolean =
        intent.getBooleanExtra(EXTRA_PENDING_CAPTURE, true)

    override fun onCreate(savedInstanceState: Bundle?) {
        captureMode = savedInstanceState?.getBoolean(STATE_CAPTURE_MODE)
            ?: intent.getBooleanExtra(EXTRA_CAPTURE_MODE, false)
        pendingCapture = savedInstanceState?.getBoolean(STATE_PENDING_CAPTURE)
            ?: captureRequested(intent)
        submitted = savedInstanceState?.getBoolean(STATE_SUBMITTED) ?: false
        awaitingSession = savedInstanceState?.getBoolean(STATE_AWAITING_SESSION) ?: false
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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                deliverDeferredConsent()
                awaitCancellation()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        when {
            captureMode -> submitTranslation()
            !captureMode && pendingCapture && ScreenCaptureService.connection.value == null && !consentState.consentInFlight && !awaitingSession -> requestConsent()
            !captureMode && pendingCapture -> beginCapture()
        }
    }

    private fun deliverDeferredConsent() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        consentState.deferredConsent?.let { (code, data) ->
            consentState.deferredConsent = null
            consentResult(code, data)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!captureRequested(intent)) {
            pendingCapture = false
            if (captureMode) returnToSettings()
            return
        }
        pendingCapture = true
        if (ScreenCaptureService.connection.value != null) beginCapture()
        else if (!consentState.consentInFlight && !awaitingSession) requestConsent()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_PENDING_CAPTURE, pendingCapture)
        outState.putBoolean(STATE_CAPTURE_MODE, captureMode)
        outState.putBoolean(STATE_SUBMITTED, submitted)
        outState.putBoolean(STATE_AWAITING_SESSION, awaitingSession)
        super.onSaveInstanceState(outState)
    }

    private fun requestConsent() {
        if (consentState.consentInFlight || awaitingSession) return
        consentState.consentInFlight = true
        try {
            val intent = consentIntent()
            launchConsent(intent)
        } catch (_: Throwable) {
            failToSettings(getString(R.string.error_session_failed))
        }
    }

    protected open fun consentResult(resultCode: Int, data: Intent?) {
        if (!consentState.consentInFlight) return
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            consentState.deferredConsent = resultCode to data
            return
        }
        consentState.consentInFlight = false
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

    protected open fun launchConsent(intent: Intent) {
        consent.launch(intent)
    }

    protected open fun startSession(resultCode: Int, data: Intent) {
        ScreenCaptureService.start(this, resultCode, data)
    }

    private fun beginCapture() {
        if (!pendingCapture || captureMode || ScreenCaptureService.connection.value == null) return
        captureMode = true
        intent.putExtra(EXTRA_CAPTURE_MODE, true)
        recreate()
    }

    private fun submitTranslation() {
        if (!submitted) {
            if (ScreenCaptureService.connection.value == null) { returnToSettings(); return }
            submitted = true
            try { ScreenCaptureService.translate(this) }
            catch (_: Throwable) { failToSettings(getString(R.string.error_session_failed)); return }
        }
        removeOwnTask()
    }

    private fun removeOwnTask() {
        try {
            getSystemService(ActivityManager::class.java).appTasks
                .firstOrNull { it.taskInfo.taskId == taskId }?.finishAndRemoveTask()
        } catch (_: IllegalArgumentException) {
        } catch (_: SecurityException) {
        }
    }

    private fun returnToSettings() {
        captureMode = false
        pendingCapture = false
        submitted = false
        awaitingSession = false
        consentState.consentInFlight = false
        consentState.deferredConsent = null
        intent.removeExtra(EXTRA_CAPTURE_MODE)
        recreate()
    }

    private fun failToSettings(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        returnToSettings()
    }

    companion object {
        const val EXTRA_PENDING_CAPTURE = "pending_capture"
        internal const val EXTRA_CAPTURE_MODE = "capture_mode"
        private const val STATE_PENDING_CAPTURE = "pending_capture_state"
        private const val STATE_CAPTURE_MODE = "capture_mode_state"
        private const val STATE_SUBMITTED = "submitted_state"
        private const val STATE_AWAITING_SESSION = "awaiting_session"
    }
}
