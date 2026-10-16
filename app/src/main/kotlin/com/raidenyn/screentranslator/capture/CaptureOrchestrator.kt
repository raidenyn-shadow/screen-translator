package com.raidenyn.screentranslator.capture

import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Wait between the request tap and the screenshot, letting the sidebar dismissal animation finish. */
const val CAPTURE_DELAY_MS = 400L

/** Which step of the capture pipeline failed, mapped one-to-one onto a user-facing toast. */
sealed interface CaptureError {
    /** The screenshot step is impossible on this device: its Android version predates the API. */
    data object ScreenshotUnsupported : CaptureError
    data object ScreenshotFailed : CaptureError
    data object SaveFailed : CaptureError
    data object LaunchFailed : CaptureError
    data object TranslationFailed : CaptureError
    data object AccessibilityUnavailable : CaptureError
}

/**
 * Thrown by a [ScreenshotTaker] on a device whose Android version predates the accessibility
 * screenshot API; mapped to [CaptureError.ScreenshotUnsupported] so the toast states the
 * permanent condition instead of a "try again" hint.
 */
class ScreenshotUnsupportedException : IllegalStateException("takeScreenshot requires API 33")

/**
 * Runs the capture pipeline on [scope]: wait → screenshot → save → launch and select Translate.
 * [tryDispatch] may be called from anywhere; overlapping requests are dropped by a
 * synchronous in-flight guard, and every failure path surfaces through [onError].
 */
class CaptureOrchestrator(
    private val delayMs: Long = CAPTURE_DELAY_MS,
    private val delayer: suspend (Long) -> Unit = { delay(it) },
    private val taker: ScreenshotTaker,
    private val store: CaptureStore,
    private val launcher: suspend (Uri) -> CaptureError?,
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
        // CancellationException is rethrown first: the service being torn down mid-pipeline
        // is not a failure, must not post a toast, and must leave the coroutine cancelled.
        val bitmap = try {
            taker.take()
        } catch (c: CancellationException) {
            throw c
        } catch (_: ScreenshotUnsupportedException) {
            onError(CaptureError.ScreenshotUnsupported)
            return
        } catch (_: Throwable) {
            onError(CaptureError.ScreenshotFailed)
            return
        }
        val uri = try {
            store.save(bitmap)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            onError(CaptureError.SaveFailed)
            return
        }
        launcher(uri)?.let(onError)
    }
}
