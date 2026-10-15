package com.raidenyn.screentranslator.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.net.Uri
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
import com.raidenyn.screentranslator.capture.ScreenshotUnsupportedException
import com.raidenyn.screentranslator.lens.IntentStarter
import com.raidenyn.screentranslator.lens.LensLauncher
import com.raidenyn.screentranslator.probe.LensTranslateProbeSession
import com.raidenyn.screentranslator.probe.LensTranslateProbeStatus
import com.raidenyn.screentranslator.probe.ProbeCandidate
import com.raidenyn.screentranslator.probe.probeIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures screenshots for the sidebar pipeline. The explicit J1 diagnostic also
 * reads Google Lens controls and performs at most one Translate click. Window
 * inspection is active only during that bounded, explicitly requested diagnostic.
 */
class ScreenCaptureService : AccessibilityService() {

    private lateinit var scope: CoroutineScope
    private var realOrchestrator: CaptureOrchestrator? = null
    private var translateProbe: LensTranslateProbeSession? = null

    /** Test seam: when set, [orchestrator] returns this instead of the real pipeline. */
    internal var orchestratorOverride: CaptureOrchestrator? = null

    /** The capture pipeline to dispatch on, overridable for tests. */
    fun orchestrator(): CaptureOrchestrator =
        orchestratorOverride ?: realOrchestrator ?: buildOrchestrator().also { realOrchestrator = it }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        LensTranslateProbeStatus.load(this)
        instance = this
    }

    override fun onDestroy() {
        cancelTranslateProbe("Capture service stopped.")
        instance = null
        realOrchestrator = null
        scope.cancel()
        super.onDestroy()
    }

    /** J1 polls only while armed; passive events themselves never trigger inspection or clicks. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() { cancelTranslateProbe("Accessibility service interrupted.") }

    /** Called on the main thread after the diagnostic screenshot has been saved. */
    fun launchTranslateProbe(uri: Uri): Boolean {
        if ((serviceInfo?.capabilities ?: 0) and
            AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT == 0) {
            LensTranslateProbeStatus.publish(this,
                "J1 cannot inspect UI. Turn Screen Translator off and on in Accessibility settings after updating.")
            return false
        }
        val session = translateProbe ?: LensTranslateProbeSession(
            readWindow = { rootInActiveWindow },
            onReport = { LensTranslateProbeStatus.publish(this, it) },
        ).also { translateProbe = it }
        return session.start {
            startActivity(probeIntent(ProbeCandidate.SHARE_AUTO_TRANSLATE, uri, contentResolver))
        }
    }

    fun cancelTranslateProbe(reason: String) { translateProbe?.cancel(reason) }

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

    /** Calls the accessibility screenshot API and copies the result out of hardware memory. */
    private class RealScreenshotTaker(private val service: ScreenCaptureService) : ScreenshotTaker {

        override suspend fun take(): Bitmap {
            // The display-scoped takeScreenshot overload only exists from API 33; on older
            // devices throw the dedicated type so the toast states the permanent condition
            // instead of a "try again" hint.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) throw ScreenshotUnsupportedException()
            return suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    Runnable::run,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                if (continuation.isActive) {
                                    try {
                                        val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                        if (hardware == null) {
                                            continuation.resumeWithException(
                                                IllegalStateException("screenshot buffer could not be wrapped")
                                            )
                                        } else {
                                            // Hardware bitmaps cannot be PNG-compressed: copy to software memory.
                                            continuation.resume(hardware.copy(Bitmap.Config.ARGB_8888, false))
                                        }
                                    } catch (t: Throwable) {
                                        // The copy is the pipeline's largest allocation and OOM is an Error,
                                        // not an Exception: resume anyway so the failure reaches the
                                        // orchestrator instead of escaping the callback (killing the process)
                                        // or leaving take() suspended forever.
                                        continuation.resumeWithException(t)
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

/**
 * Maps each capture failure onto its toast copy, one-to-one. Internal so tests can pin
 * the version-guard failure to the dedicated "unsupported" string.
 */
internal val CaptureError.labelRes: Int
    get() = when (this) {
        CaptureError.ScreenshotUnsupported -> R.string.error_screenshot_unsupported
        CaptureError.ScreenshotFailed -> R.string.error_screenshot_failed
        CaptureError.SaveFailed -> R.string.error_save_failed
        CaptureError.LaunchFailed -> R.string.error_launch_failed
    }
