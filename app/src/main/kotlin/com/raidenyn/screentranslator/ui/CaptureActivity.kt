package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.capture.CaptureError
import com.raidenyn.screentranslator.capture.CaptureResult
import com.raidenyn.screentranslator.lens.IntentStarter
import com.raidenyn.screentranslator.lens.LensLauncher
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/** Empty translucent owner for one capture request and its visible Lens handoff. */
class CaptureActivity : ComponentActivity() {

    private lateinit var requestId: String
    private var requested = false
    private var consumed = false
    private var owner: ScreenCaptureService? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = savedInstanceState?.getString(STATE_REQUEST_ID)
            ?: intent.getStringExtra(EXTRA_REQUEST_ID)
            ?: run { abandon(); return }
        requested = savedInstanceState?.getBoolean(STATE_REQUESTED) ?: false
        consumed = savedInstanceState?.getBoolean(STATE_CONSUMED) ?: false
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                active = ::cancel
                ScreenCaptureService.connection.collectLatest { service ->
                    owner = service
                    if (service == null) abandon() else {
                        request(service)
                        service.requests.results.collect { request(service) }
                    }
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations && !consumed) cancel()
        if (active != null) active = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_REQUEST_ID, requestId)
        outState.putBoolean(STATE_REQUESTED, requested)
        outState.putBoolean(STATE_CONSUMED, consumed)
        super.onSaveInstanceState(outState)
    }

    private fun request(service: ScreenCaptureService) {
        if (consumed) return
        if (!requested) requested = service.requests.request(requestId)
        when (val result = service.requests.results.value[requestId]) {
            is CaptureResult.Saved -> launchLens(service, result)
            is CaptureResult.Failed -> fail(service, result.error)
            null -> Unit
        }
    }

    private fun launchLens(service: ScreenCaptureService, result: CaptureResult.Saved) {
        if (consumed || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        consumed = true
        val launcher = LensLauncher(service.contentResolver, IntentStarter { intent, options ->
            service.startActivity(intent, options)
        })
        // v0.6.1 closed its Sidebar activity before the service launched Lens. Remove
        // this whole task (including the root), rather than leaving it available to
        // ColorOS's source-task fallback through the calling process.
        val launched = removeCaptureTask() && launcher.launch(result.uri)
        service.requests.acknowledge(requestId)
        if (launched) {
            setResult(Activity.RESULT_OK)
        } else {
            Toast.makeText(this, R.string.error_launch_failed, Toast.LENGTH_SHORT).show()
            setResult(Activity.RESULT_CANCELED)
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }

    private fun removeCaptureTask(): Boolean {
        return try {
            val task = getSystemService(ActivityManager::class.java).appTasks
                .firstOrNull { it.taskInfo.taskId == taskId } ?: return false
            task.finishAndRemoveTask()
            true
        } catch (_: IllegalArgumentException) {
            false // The task may have disappeared before the removal request reached Android.
        } catch (_: SecurityException) {
            false
        }
    }

    private fun fail(service: ScreenCaptureService, error: CaptureError) {
        if (consumed) return
        consumed = true
        service.requests.acknowledge(requestId)
        Toast.makeText(this, error.label(), Toast.LENGTH_SHORT).show()
        abandon()
    }

    private fun cancel() {
        if (::requestId.isInitialized && !consumed) owner?.requests?.cancel(requestId)
        abandon()
    }

    private fun abandon() {
        if (!isFinishing) {
            setResult(Activity.RESULT_CANCELED)
            finish()
        }
    }

    private fun CaptureError.label(): Int = when (this) {
        CaptureError.ScreenshotFailed -> R.string.error_screenshot_failed
        CaptureError.SaveFailed -> R.string.error_save_failed
        CaptureError.LaunchFailed -> R.string.error_launch_failed
    }

    companion object {
        const val EXTRA_REQUEST_ID = "request_id"
        private const val STATE_REQUEST_ID = "request_id_state"
        private const val STATE_REQUESTED = "requested"
        private const val STATE_CONSUMED = "consumed"
        private var active: (() -> Unit)? = null

        fun intent(context: Context, requestId: String): Intent =
            Intent(context, CaptureActivity::class.java).putExtra(EXTRA_REQUEST_ID, requestId)

        internal fun cancelActiveCapture() {
            active?.invoke()
        }
    }
}
