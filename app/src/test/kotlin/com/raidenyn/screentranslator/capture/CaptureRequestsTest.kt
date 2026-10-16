package com.raidenyn.screentranslator.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureRequestsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "captures")
    private val store = CaptureStore(dir, "com.raidenyn.screentranslator.files")
    private val testScope = TestScope()

    @After fun clean() { dir.listFiles()?.forEach { it.delete() } }

    /** Hands out fresh bitmaps, throwing the scripted [failure] once, recording every call. */
    private class RecordingTaker(private val failure: Throwable? = null) : ScreenshotTaker {
        var calls = 0
        var cancelCaller = false
        val handedOut = mutableListOf<Bitmap>()
        private var pendingFailure = failure

        override suspend fun take(): Bitmap {
            calls++
            pendingFailure?.let {
                pendingFailure = null
                throw it
            }
            if (cancelCaller) coroutineContext[Job]!!.cancel()
            return Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888).also { handedOut.add(it) }
        }
    }

    /**
     * Suspends every take on [gate] behind a non-cancellable primitive, so a canceled request
     * still receives its bitmap late — the frame-delivered-while-tearing-down race.
     */
    private class GatedTaker(private val gate: CompletableDeferred<Unit>) : ScreenshotTaker {
        val handedOut = mutableListOf<Bitmap>()

        override suspend fun take(): Bitmap {
            val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
            handedOut.add(bitmap)
            return suspendCoroutine { c -> gate.invokeOnCompletion { c.resume(bitmap) } }
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

    /** The content URI the store hands out for the single saved capture. */
    private fun savedUri(): Uri {
        val file = dir.listFiles()!!.single()
        return Uri.parse("content://com.raidenyn.screentranslator.files/captures/${file.name}")
    }

    /**
     * Advances past the capture delay and fires it: advanceTimeBy runs only events strictly
     * before its target, so the delay task due at exactly the boundary needs runCurrent.
     */
    private fun TestScope.advanceThroughCaptureDelay() {
        testScheduler.advanceTimeBy(CAPTURE_DELAY_MS)
        testScheduler.runCurrent()
    }

    @Test
    fun requestWaits400msThenSavesAndReturnsUri() {
        val taker = RecordingTaker()
        val requests = CaptureRequests(taker, store, testScope)

        assertTrue(requests.request("a"))
        testScope.testScheduler.advanceTimeBy(CAPTURE_DELAY_MS - 1)
        assertEquals(0, taker.calls)
        testScope.testScheduler.advanceTimeBy(1)
        testScope.testScheduler.runCurrent()
        assertEquals(1, taker.calls)

        testScope.advanceUntilPipelineIdle()
        assertEquals(mapOf("a" to CaptureResult.Saved(savedUri())), requests.results.value)
    }

    @Test
    fun duplicateRequestsDoNotCaptureTwice() {
        val taker = RecordingTaker()
        val requests = CaptureRequests(taker, store, testScope)

        assertTrue(requests.request("a"))
        assertFalse(requests.request("a"))
        assertFalse(requests.request("b"))

        testScope.advanceUntilPipelineIdle()
        assertEquals(1, taker.calls)
        assertEquals(setOf("a"), requests.results.value.keys)

        assertFalse(requests.request("a"))
        requests.acknowledge("a")
        assertTrue(requests.request("b"))
        testScope.advanceUntilPipelineIdle()
        assertEquals(2, taker.calls)
    }

    @Test
    fun captureAndSaveFailuresHaveDistinctResults() {
        val blocked = File(context.cacheDir, "blocked-captures").apply { createNewFile() }
        val taker = RecordingTaker(failure = RuntimeException("frame extraction failed"))
        val requests = CaptureRequests(
            taker, CaptureStore(blocked, "com.raidenyn.screentranslator.files"), testScope,
        )

        assertTrue(requests.request("shot"))
        testScope.advanceUntilPipelineIdle()
        assertEquals(mapOf("shot" to CaptureResult.Failed(CaptureError.ScreenshotFailed)), requests.results.value)
        requests.acknowledge("shot")

        assertTrue(requests.request("save"))
        testScope.advanceUntilPipelineIdle()
        assertEquals(mapOf("save" to CaptureResult.Failed(CaptureError.SaveFailed)), requests.results.value)
        assertTrue(taker.handedOut.single().isRecycled)
        assertFalse(blocked.isDirectory)

        blocked.delete()
    }

    @Test
    fun completedResultSurvivesCollectorReplacementUntilAcknowledged() {
        val requests = CaptureRequests(RecordingTaker(), store, testScope)
        assertTrue(requests.request("a"))
        testScope.advanceUntilPipelineIdle()
        val expected = mapOf("a" to CaptureResult.Saved(savedUri()))

        val first = mutableListOf<Map<String, CaptureResult>>()
        val firstCollector = testScope.launch { requests.results.collect { first.add(it) } }
        testScope.testScheduler.runCurrent()
        assertEquals(listOf(expected), first)
        firstCollector.cancel()

        val second = mutableListOf<Map<String, CaptureResult>>()
        val secondCollector = testScope.launch { requests.results.collect { second.add(it) } }
        testScope.testScheduler.runCurrent()
        assertEquals(listOf(expected), second)

        requests.acknowledge("a")
        testScope.testScheduler.runCurrent()
        assertEquals(emptyMap<String, CaptureResult>(), requests.results.value)
        assertEquals(listOf(expected, emptyMap<String, CaptureResult>()), second)

        assertTrue(requests.request("b"))
        secondCollector.cancel()
        testScope.advanceUntilPipelineIdle()
    }

    @Test
    fun cancelThenLateCompletionCannotPublishOrBlockNextRequest() {
        val gate = CompletableDeferred<Unit>()
        val taker = GatedTaker(gate)
        val requests = CaptureRequests(taker, store, testScope)

        assertTrue(requests.request("a"))
        testScope.advanceThroughCaptureDelay()
        requests.cancel("a")
        assertTrue(requests.request("b"))
        testScope.advanceThroughCaptureDelay()

        gate.complete(Unit)
        testScope.advanceUntilPipelineIdle()

        assertFalse(requests.results.value.containsKey("a"))
        assertEquals(mapOf("b" to CaptureResult.Saved(savedUri())), requests.results.value)
        assertEquals(1, dir.listFiles()?.size ?: 0)
        assertTrue(taker.handedOut[0].isRecycled)
    }

    @Test
    fun bitmapIsRecycledAfterSaveOrCancellation() {
        val taker = RecordingTaker()
        val requests = CaptureRequests(taker, store, testScope)

        assertTrue(requests.request("saved"))
        testScope.advanceUntilPipelineIdle()
        assertEquals(mapOf("saved" to CaptureResult.Saved(savedUri())), requests.results.value)
        assertTrue(taker.handedOut.single().isRecycled)
        requests.acknowledge("saved")

        taker.cancelCaller = true
        assertTrue(requests.request("cancelled"))
        testScope.advanceUntilPipelineIdle()
        assertEquals(emptyMap<String, CaptureResult>(), requests.results.value)
        assertEquals(1, dir.listFiles()!!.size)
        assertTrue(taker.handedOut.last().isRecycled)

        taker.cancelCaller = false
        assertTrue(requests.request("next"))
        testScope.advanceUntilPipelineIdle()
        assertEquals(1, requests.results.value.size)
        assertEquals(3, taker.calls)
    }

    @Test
    fun closeCancelsInFlightWorkAndRejectsFurtherRequests() {
        val gate = CompletableDeferred<Unit>()
        val taker = GatedTaker(gate)
        val requests = CaptureRequests(taker, store, testScope)

        assertTrue(requests.request("a"))
        testScope.advanceThroughCaptureDelay()
        requests.close()
        assertFalse(requests.request("b"))

        gate.complete(Unit)
        testScope.advanceUntilPipelineIdle()
        assertEquals(emptyMap<String, CaptureResult>(), requests.results.value)
        assertTrue(taker.handedOut.single().isRecycled)
    }

    /**
     * With an immediate dispatcher and a zero delay the pipeline runs to completion inside
     * request() itself: the guard must already be assigned when publish is reached, or the
     * outcome is dropped even though request returned true.
     */
    @Test
    fun zeroDelayImmediateDispatcherStillPublishesFailure() {
        val taker = RecordingTaker(failure = RuntimeException("frame extraction failed"))
        val requests = CaptureRequests(
            taker, store, CoroutineScope(Dispatchers.Unconfined), delayMs = 0,
        )

        assertTrue(requests.request("a"))
        assertEquals(mapOf("a" to CaptureResult.Failed(CaptureError.ScreenshotFailed)), requests.results.value)
        assertFalse(requests.request("a"))
        requests.acknowledge("a")
        assertEquals(emptyMap<String, CaptureResult>(), requests.results.value)
    }
}