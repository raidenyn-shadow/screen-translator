package com.raidenyn.screentranslator.capture

import android.graphics.Bitmap
import com.raidenyn.screentranslator.lens.LensLauncher
import com.raidenyn.screentranslator.lens.LensLaunchResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Wait between the request tap and the screenshot, letting the sidebar dismissal animation finish. */
const val CAPTURE_DELAY_MS = 400L

/** Indirection over the accessibility screenshot API so the pipeline can run in tests. */
fun interface ScreenshotTaker {
    suspend fun take(): Bitmap
}

/** Which step of the capture pipeline failed, mapped one-to-one onto a user-facing toast. */
sealed interface CaptureError {
    data object ScreenshotFailed : CaptureError
    data object SaveFailed : CaptureError
    data object LaunchFailed : CaptureError
}

/**
 * Runs the capture pipeline on [scope]: wait [delayMs] → screenshot → save → hand to Lens.
 * [tryDispatch] may be called from anywhere; overlapping requests are dropped by a
 * synchronous in-flight guard, and every failure path surfaces through [onError].
 */
class CaptureOrchestrator(
    private val delayMs: Long = CAPTURE_DELAY_MS,
    private val delayer: suspend (Long) -> Unit = { delay(it) },
    private val taker: ScreenshotTaker,
    private val store: CaptureStore,
    private val launcher: LensLauncher,
    private val onError: (CaptureError) -> Unit,
    private val scope: CoroutineScope,
) {
    private val inFlight = AtomicBoolean(false)

    /** Posts one pipeline run; false when another capture is already in flight. */
    fun tryDispatch(): Boolean {
        if (!inFlight.compareAndSet(false, true)) return false
        scope.launch {
            try {
                captureOnce()
            } finally {
                inFlight.set(false)
            }
        }
        return true
    }

    private suspend fun captureOnce() {
        delayer(delayMs)
        // The catches take Throwable, not Exception: a copy or PNG-encode OOM (an Error) is
        // the pipeline's most likely failure and must surface as a toast, not kill the process.
        val bitmap = try {
            taker.take()
        } catch (_: Throwable) {
            onError(CaptureError.ScreenshotFailed)
            return
        }
        val uri = try {
            store.save(bitmap)
        } catch (_: Throwable) {
            onError(CaptureError.SaveFailed)
            return
        }
        if (launcher.launch(uri) is LensLaunchResult.Failed) {
            onError(CaptureError.LaunchFailed)
        }
    }
}
