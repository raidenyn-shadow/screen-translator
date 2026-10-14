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

    /** Returns [bitmap], or throws [failure] when scripted, recording every call. */
    private class RecordingTaker(private val bitmap: Bitmap, private val failure: Exception? = null) : ScreenshotTaker {
        var calls = 0

        override suspend fun take(): Bitmap {
            calls++
            failure?.let { throw it }
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
        launcher = LensLauncher(context.contentResolver, starter),
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
}
