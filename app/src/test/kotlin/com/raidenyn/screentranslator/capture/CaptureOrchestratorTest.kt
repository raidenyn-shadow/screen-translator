package com.raidenyn.screentranslator.capture

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.lens.IntentStarter
import com.raidenyn.screentranslator.lens.LensLauncher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureOrchestratorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "captures")
    private val store = CaptureStore(dir, "com.raidenyn.screentranslator.files")
    private val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).apply {
        eraseColor(0xFF40E0D0.toInt())
    }
    private val testScope = TestScope()

    @After fun clean() { dir.listFiles()?.forEach { it.delete() } }

    /** Returns [bitmap], throwing the scripted [failure] once before switching back, recording every call. */
    private class RecordingTaker(private val bitmap: Bitmap, failure: Throwable? = null) : ScreenshotTaker {
        var calls = 0
        private var pendingFailure = failure

        override suspend fun take(): Bitmap {
            calls++
            pendingFailure?.let {
                pendingFailure = null
                throw it
            }
            return bitmap
        }
    }

    /** Cancels the calling pipeline's job, then hands back the bitmap — the service died mid-take. */
    private class CancellingTaker(private val bitmap: Bitmap) : ScreenshotTaker {
        override suspend fun take(): Bitmap {
            coroutineContext[Job]!!.cancel()
            return bitmap
        }
    }

    /** Records every started intent; [failEverything] scripts the whole chain unresolvable. */
    private class RecordingStarter(private val failEverything: Boolean = false) : IntentStarter {
        val intents = mutableListOf<Intent>()

        override fun start(intent: Intent) {
            intents.add(intent)
            if (failEverything) throw ActivityNotFoundException("nothing resolves")
        }
    }

    /**
     * Advances virtual time until the dispatched pipeline completes. A plain scheduler
     * [TestCoroutineScheduler.advanceUntilIdle] is not enough: [CaptureStore.save] hops to a
     * real Dispatchers.IO thread, invisible to the scheduler, so real time is yielded between
     * advances until the child job finishes.
     */
    private fun TestScope.advanceUntilPipelineIdle() {
        val scopeJob = coroutineContext[Job]!!
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (scopeJob.children.any { it.isActive }) {
            testScheduler.advanceUntilIdle()
            check(System.nanoTime() < deadline) { "capture pipeline did not complete in time" }
            Thread.sleep(1)
        }
        testScheduler.advanceUntilIdle()
    }

    private fun newOrchestrator(
        taker: ScreenshotTaker,
        starter: IntentStarter,
        errors: MutableList<CaptureError>,
        delayer: suspend (Long) -> Unit,
    ): CaptureOrchestrator = CaptureOrchestrator(
        delayer = delayer,
        taker = taker,
        store = store,
        launcher = { uri ->
            if (LensLauncher(context.contentResolver, starter).launch(uri)) null else CaptureError.LaunchFailed
        },
        onError = { errors.add(it) },
        scope = testScope,
    )

    @Test
    fun successDelaysSavesLaunches() {
        val delays = mutableListOf<Long>()
        val errors = mutableListOf<CaptureError>()
        val taker = RecordingTaker(bitmap)
        val starter = RecordingStarter()
        val orchestrator = newOrchestrator(taker, starter, errors) { delays.add(it) }

        val dispatched = orchestrator.tryDispatch()
        testScope.advanceUntilPipelineIdle()

        assertTrue(dispatched)
        assertEquals(400L, CAPTURE_DELAY_MS)   // the configured delay is 400 ms
        assertEquals(listOf(CAPTURE_DELAY_MS), delays)
        assertEquals(1, taker.calls)
        // the store saved the taker's bitmap: the single written PNG decodes back to it
        val files = dir.listFiles()!!
        assertEquals(1, files.size)
        val saved = BitmapFactory.decodeFile(files[0].absolutePath)
        assertEquals(bitmap.width, saved.width)
        assertEquals(bitmap.height, saved.height)
        assertEquals(bitmap.getPixel(0, 0), saved.getPixel(0, 0))
        // the launcher received exactly the uri the store returned for that file
        assertEquals(1, starter.intents.size)
        val sent = starter.intents.single().getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertEquals("content://com.raidenyn.screentranslator.files/captures/${files[0].name}", sent.toString())
        assertTrue(errors.isEmpty())
    }

    @Test
    fun screenshotFailureSurfacesError() {
        val errors = mutableListOf<CaptureError>()
        val taker = RecordingTaker(bitmap, failure = RuntimeException("screenshot denied"))
        val starter = RecordingStarter()
        val orchestrator = newOrchestrator(taker, starter, errors) { }

        val dispatched = orchestrator.tryDispatch()
        testScope.advanceUntilPipelineIdle()

        assertTrue(dispatched)
        assertEquals(listOf(CaptureError.ScreenshotFailed), errors)
        assertEquals(0, dir.listFiles()?.size ?: 0)   // the store was never touched
        assertTrue(starter.intents.isEmpty())          // and nothing was launched
    }

    @Test
    fun launchFailureSurfacesError() {
        val errors = mutableListOf<CaptureError>()
        val starter = RecordingStarter(failEverything = true)
        val orchestrator = newOrchestrator(RecordingTaker(bitmap), starter, errors) { }

        val dispatched = orchestrator.tryDispatch()
        testScope.advanceUntilPipelineIdle()

        assertTrue(dispatched)
        assertEquals(listOf(CaptureError.LaunchFailed), errors)
        assertEquals(1, dir.listFiles()?.size ?: 0)   // the save succeeded before the launch failed
    }

    @Test
    fun secondInFlightRequestDropped() {
        val errors = mutableListOf<CaptureError>()
        val taker = RecordingTaker(bitmap)
        val starter = RecordingStarter()
        val orchestrator = newOrchestrator(taker, starter, errors) { }

        val first = orchestrator.tryDispatch()
        val second = orchestrator.tryDispatch()   // before the first pipeline has run
        testScope.advanceUntilPipelineIdle()

        assertTrue(first)
        assertFalse(second)
        assertEquals(1, taker.calls)           // one screenshot...
        assertEquals(1, starter.intents.size)  // ...and one launch, no duplicates
        assertTrue(errors.isEmpty())
    }

    @Test
    fun outOfMemorySurfacesErrorAndNextDispatchWorks() {
        // A copy OOM resumes take() with an OutOfMemoryError (an Error, not an Exception):
        // it must surface as ScreenshotFailed, not crash, and release the in-flight guard.
        val errors = mutableListOf<CaptureError>()
        val taker = RecordingTaker(bitmap, failure = OutOfMemoryError("screen-sized bitmap"))
        val starter = RecordingStarter()
        val orchestrator = newOrchestrator(taker, starter, errors) { }

        val first = orchestrator.tryDispatch()
        testScope.advanceUntilPipelineIdle()

        assertTrue(first)
        assertEquals(listOf(CaptureError.ScreenshotFailed), errors)
        assertTrue(starter.intents.isEmpty())

        errors.clear()
        val second = orchestrator.tryDispatch()   // the guard must have been released
        testScope.advanceUntilPipelineIdle()

        assertTrue(second)
        assertEquals(2, taker.calls)
        assertEquals(1, starter.intents.size)      // the retry captured and launched
        assertTrue(errors.isEmpty())
    }

    @Test
    fun cancellationFromTakerSurfacesNoError() {
        // The service being disabled or killed resumes take() with a CancellationException:
        // that is not a failure — no toast may fire and the coroutine must stay cancelled.
        val errors = mutableListOf<CaptureError>()
        val taker = RecordingTaker(bitmap, failure = CancellationException("service disabled"))
        val starter = RecordingStarter()
        val orchestrator = newOrchestrator(taker, starter, errors) { }

        val dispatched = orchestrator.tryDispatch()
        val pipeline = testScope.coroutineContext[Job]!!.children.single()
        testScope.advanceUntilPipelineIdle()

        assertTrue(dispatched)
        assertTrue(errors.isEmpty())         // no spurious ScreenshotFailed toast
        assertFalse(pipeline.isActive)      // the pipeline did not keep running...
        assertTrue(pipeline.isCancelled)    // ...it completed as a cancelled coroutine

        val retried = orchestrator.tryDispatch()   // the rethrow still released the guard
        testScope.advanceUntilPipelineIdle()

        assertTrue(retried)
        assertEquals(2, taker.calls)              // the next dispatch captured...
        assertEquals(1, starter.intents.size)     // ...and launched, still no failure toast
        assertTrue(errors.isEmpty())
    }

    @Test
    fun cancellationDuringSaveSurfacesNoError() {
        // The service dies right after the screenshot: save()'s withContext then throws
        // CancellationException on the cancelled job, which must not surface as SaveFailed.
        val errors = mutableListOf<CaptureError>()
        val starter = RecordingStarter()
        val orchestrator = newOrchestrator(CancellingTaker(bitmap), starter, errors) { }

        val dispatched = orchestrator.tryDispatch()
        val pipeline = testScope.coroutineContext[Job]!!.children.single()
        testScope.advanceUntilPipelineIdle()

        assertTrue(dispatched)
        assertTrue(errors.isEmpty())         // no spurious SaveFailed toast
        assertEquals(0, dir.listFiles()?.size ?: 0)   // the store never wrote a capture
        assertTrue(starter.intents.isEmpty())         // and nothing was launched
        assertFalse(pipeline.isActive)
        assertTrue(pipeline.isCancelled)     // the pipeline completed as a cancelled coroutine
    }

    @Test
    fun unsupportedApiSurfacesDedicatedError() {
        // Below API 33 the taker's version guard throws the dedicated exception: the
        // permanent condition must surface as ScreenshotUnsupported, not the retry hint.
        val errors = mutableListOf<CaptureError>()
        val taker = RecordingTaker(bitmap, failure = ScreenshotUnsupportedException())
        val starter = RecordingStarter()
        val orchestrator = newOrchestrator(taker, starter, errors) { }

        val dispatched = orchestrator.tryDispatch()
        testScope.advanceUntilPipelineIdle()

        assertTrue(dispatched)
        assertEquals(listOf(CaptureError.ScreenshotUnsupported), errors)
        assertEquals(0, dir.listFiles()?.size ?: 0)   // the store was never touched
        assertTrue(starter.intents.isEmpty())         // and nothing was launched
    }

    @Test fun duplicateTapsRemainBlockedUntilTranslationSelectionFinishes() {
        val entered = CompletableDeferred<Uri>()
        val selected = CompletableDeferred<CaptureError?>()
        val errors = mutableListOf<CaptureError>()
        val taker = RecordingTaker(bitmap)
        val orchestrator = CaptureOrchestrator(
            delayer = {}, taker = taker, store = store,
            launcher = { uri -> entered.complete(uri); selected.await() },
            onError = { errors.add(it) }, scope = testScope,
        )
        assertTrue(orchestrator.tryDispatch())
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (!entered.isCompleted) {
            testScope.testScheduler.advanceUntilIdle()
            check(System.nanoTime() < deadline) { "screenshot did not reach translation" }
            Thread.sleep(1)
        }
        assertFalse(orchestrator.tryDispatch())
        selected.complete(null)
        testScope.advanceUntilPipelineIdle()
        assertEquals(1, taker.calls)
        assertTrue(errors.isEmpty())

        assertTrue(orchestrator.tryDispatch())
        testScope.advanceUntilPipelineIdle()
        assertEquals(2, taker.calls)
    }

    @Test fun translationSelectionFailureReachesTheUserFacingErrorPath() {
        val errors = mutableListOf<CaptureError>()
        val orchestrator = CaptureOrchestrator(
            delayer = {}, taker = RecordingTaker(bitmap), store = store,
            launcher = { CaptureError.TranslationFailed },
            onError = { errors.add(it) }, scope = testScope,
        )
        assertTrue(orchestrator.tryDispatch())
        testScope.advanceUntilPipelineIdle()
        assertEquals(listOf(CaptureError.TranslationFailed), errors)
    }
}
