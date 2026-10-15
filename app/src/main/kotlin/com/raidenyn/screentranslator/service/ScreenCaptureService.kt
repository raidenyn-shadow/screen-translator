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
import com.raidenyn.screentranslator.lens.LensTranslationSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Captures the display, shares it with Lens, and selects Translate once. Window
 * inspection runs only for the bounded translation attempt following a capture.
 */
class ScreenCaptureService : AccessibilityService() {

    private lateinit var scope: CoroutineScope
    private var realOrchestrator: CaptureOrchestrator? = null
    private var translationSession: LensTranslationSession? = null
    private val main = Handler(Looper.getMainLooper())

    private fun orchestrator(): CaptureOrchestrator =
        realOrchestrator ?: buildOrchestrator().also { realOrchestrator = it }

    override fun onCreate() {
        super.onCreate()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        instance = this
    }

    override fun onDestroy() {
        translationSession?.cancel()
        instance = null
        realOrchestrator = null
        scope.cancel()
        super.onDestroy()
    }

    /** Passive events never start a capture or arm UI interaction. */
    override fun onAccessibilityEvent(event: AccessibilityEvent) {}

    override fun onInterrupt() { translationSession?.cancel() }

    private suspend fun launchAndTranslate(uri: Uri, launcher: LensLauncher): CaptureError? =
        withContext<CaptureError?>(Dispatchers.Main.immediate) {
            if ((serviceInfo?.capabilities ?: 0) and
                AccessibilityServiceInfo.CAPABILITY_CAN_RETRIEVE_WINDOW_CONTENT == 0) {
                return@withContext CaptureError.AccessibilityUnavailable
            }
            suspendCancellableCoroutine<CaptureError?> { continuation ->
                lateinit var session: LensTranslationSession
                session = LensTranslationSession(
                    readWindow = { rootInActiveWindow },
                    onFinished = { result ->
                        if (translationSession === session) translationSession = null
                        if (continuation.isActive) {
                            continuation.resume(when (result) {
                                LensTranslationSession.Result.SELECTED,
                                LensTranslationSession.Result.CANCELED -> null
                                LensTranslationSession.Result.LAUNCH_FAILED -> CaptureError.LaunchFailed
                                LensTranslationSession.Result.SELECTION_FAILED -> CaptureError.TranslationFailed
                            })
                        }
                    },
                )
                translationSession = session
                continuation.invokeOnCancellation {
                    main.post {
                        if (translationSession === session) {
                            translationSession = null
                            session.cancel()
                        }
                    }
                }
                if (continuation.isActive) session.start { launcher.launch(uri) }
            }
        }

    private fun buildOrchestrator(): CaptureOrchestrator {
        val launcher = LensLauncher(contentResolver, IntentStarter(this::startActivity))
        return CaptureOrchestrator(
            taker = RealScreenshotTaker(this),
            store = CaptureStore.from(this),
            launcher = { uri -> launchAndTranslate(uri, launcher) },
            onError = { error ->
                main.post { Toast.makeText(this, error.labelRes, Toast.LENGTH_SHORT).show() }
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

    companion object {
        /** The currently bound service; null whenever it is disabled or being torn down. */
        @Volatile
        var instance: ScreenCaptureService? = null

        /** Posts a capture to the running service; false if there is none or one is already in flight. */
        fun requestCapture(): Boolean = instance?.orchestrator()?.tryDispatch() ?: false
    }
}

/**
 * Maps each capture/translation failure onto its user-facing toast message.
 */
internal val CaptureError.labelRes: Int
    get() = when (this) {
        CaptureError.ScreenshotUnsupported -> R.string.error_screenshot_unsupported
        CaptureError.ScreenshotFailed -> R.string.error_screenshot_failed
        CaptureError.SaveFailed -> R.string.error_save_failed
    CaptureError.LaunchFailed -> R.string.error_launch_failed
    CaptureError.TranslationFailed -> R.string.error_translation_failed
    CaptureError.AccessibilityUnavailable -> R.string.error_accessibility_unavailable
    }
