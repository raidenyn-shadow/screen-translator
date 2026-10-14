package com.raidenyn.screentranslator.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.capture.CaptureError
import com.raidenyn.screentranslator.capture.CaptureOrchestrator
import com.raidenyn.screentranslator.capture.CaptureStore
import com.raidenyn.screentranslator.capture.ScreenshotTaker
import com.raidenyn.screentranslator.lens.IntentStarter
import com.raidenyn.screentranslator.lens.LensLauncher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Screenshot-only accessibility service. The system binds it once the user enables it,
 * and later tasks post captures through [requestCapture]. The service never reads
 * window content (`canRetrieveWindowContent="false"`); the only accessibility capability
 * it uses is [AccessibilityService.takeScreenshot].
 */
class ScreenCaptureService : AccessibilityService() {

    private lateinit var scope: CoroutineScope
    private var realOrchestrator: CaptureOrchestrator? = null

    /** Test seam: when set, [orchestrator] returns this instead of the real pipeline. */
    internal var orchestratorOverride: CaptureOrchestrator? = null

    /** The capture pipeline to dispatch on, overridable for tests. */
    fun orchestrator(): CaptureOrchestrator =
        orchestratorOverride ?: realOrchestrator ?: buildOrchestrator().also { realOrchestrator = it }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        instance = this
    }

    override fun onDestroy() {
        instance = null
        realOrchestrator = null
        scope.cancel()
        super.onDestroy()
    }

    /** Required override, deliberately empty: this service never inspects events. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() {}

    private fun buildOrchestrator(): CaptureOrchestrator {
        val main = Handler(Looper.getMainLooper())
        return CaptureOrchestrator(
            taker = RealScreenshotTaker(this),
            store = CaptureStore.from(this),
            launcher = LensLauncher(contentResolver, ContextStarter(this::startActivity)),
            onError = { error ->
                main.post { Toast.makeText(this, error.labelRes, Toast.LENGTH_LONG).show() }
            },
            scope = scope,
        )
    }

    private val CaptureError.labelRes: Int
        get() = when (this) {
            CaptureError.ScreenshotFailed -> R.string.error_screenshot_failed
            CaptureError.SaveFailed -> R.string.error_save_failed
            CaptureError.LaunchFailed -> R.string.error_launch_failed
        }

    /** Calls the accessibility screenshot API and copies the result out of hardware memory. */
    private class RealScreenshotTaker(private val service: ScreenCaptureService) : ScreenshotTaker {

        override suspend fun take(): Bitmap {
            // The display-scoped takeScreenshot overload only exists from API 33; on older
            // devices surface an error instead of crashing on a missing method.
            check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                "AccessibilityService.takeScreenshot requires API 33"
            }
            return suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    Runnable::run,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                if (continuation.isActive) {
                                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    if (hardware == null) {
                                        continuation.resumeWithException(
                                            IllegalStateException("screenshot buffer could not be wrapped")
                                        )
                                    } else {
                                        // Hardware bitmaps cannot be PNG-compressed: copy to software memory.
                                        continuation.resume(hardware.copy(Bitmap.Config.ARGB_8888, false))
                                    }
                                }
                            } finally {
                                buffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("takeScreenshot failed with error $errorCode")
                                )
                            }
                        }
                    }
                )
            }
        }
    }

    /** Starts intents straight through the service context; LensLauncher sets NEW_TASK itself. */
    private fun interface ContextStarter : IntentStarter

    companion object {
        /** The currently bound service; null whenever it is disabled or being torn down. */
        @Volatile
        var instance: ScreenCaptureService? = null

        /** Posts a capture to the running service; false if there is none or one is already in flight. */
        fun requestCapture(): Boolean = instance?.orchestrator()?.tryDispatch() ?: false
    }
}
