package com.raidenyn.screentranslator.capture

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@OptIn(ExperimentalCoroutinesApi::class)
class ProjectionFrameSourceTest {

    private val environment = FakeEnvironment()
    private var stopSignals = 0

    private fun newSource(
        timeoutMs: Long = 2_000,
        environment: FakeEnvironment = this.environment,
        bitmapConverter: ((ByteBuffer, Int, Int, Int, Int) -> android.graphics.Bitmap)? = null,
    ): ProjectionFrameSource =
        ProjectionFrameSource(environment, timeoutMs = timeoutMs, onStopped = { stopSignals++ }, bitmapConverter = bitmapConverter ?: ::rgbaToBitmap)

    /** 2x2 RGBA frame with 12-byte rows in one flat color; [reads] and [closes] witness its lifecycle. */
    private class FakeFrame(private val argb: Int) : FrameImage {
        var reads = 0; private set
        var closes = 0; private set
        override val width = 2
        override val height = 2
        override val plane: RgbaPlane
            get() {
                reads++
                val rgba = byteArrayOf(
                    (argb ushr 16).toByte(),
                    (argb ushr 8).toByte(),
                    argb.toByte(),
                    (argb ushr 24).toByte(),
                )
                val bytes = ByteArray(24)
                for (row in 0..1) for (col in 0..1)
                    rgba.copyInto(bytes, row * 12 + col * 4)
                return RgbaPlane(ByteBuffer.wrap(bytes), 4, 12)
            }
        override fun close() { closes++ }
    }

    /** Frame whose plane cannot be read: conversion must fail and the frame still close once. */
    private class UnreadableFrame : FrameImage {
        var closes = 0; private set
        override val width = 2
        override val height = 2
        override val plane: RgbaPlane
            get() = throw IllegalStateException("unreadable plane")
        override fun close() { closes++ }
    }

    /** [offer] queues a frame whose callback has not fired yet; [deliver] queues one and fires it. */
    private class FakeReader : FrameReader {
        private val queued = ArrayDeque<FrameImage>()
        private var listener: (() -> Unit)? = null
        var closes = 0; private set
        var failRegistration = false

        override fun onImageAvailable(onFrame: () -> Unit) {
            if (failRegistration) throw RuntimeException("listener registration failed")
            listener = onFrame
        }

        override fun acquireLatestImage(): FrameImage? {
            val latest = queued.removeLastOrNull() ?: return null
            while (queued.isNotEmpty()) queued.removeLast().close()
            return latest
        }

        override fun close() { closes++ }

        fun offer(frame: FrameImage) { queued.addLast(frame) }

        fun deliver(frame: FrameImage) {
            queued.addLast(frame)
            listener?.invoke()
        }
    }

    private class FakeScreen : VirtualScreen {
        var surface: FrameReader? = null; private set
        val resizes = mutableListOf<IntArray>()
        var clears = 0; private set
        var releases = 0; private set

        override fun clearSurface() { clears++; surface = null }
        override fun resize(width: Int, height: Int, densityDpi: Int) { resizes.add(intArrayOf(width, height, densityDpi)) }
        override fun setSurface(reader: FrameReader) { surface = reader }
        override fun release() { releases++ }
    }

    /**
     * Environment fake: the inline executor keeps every state mutation on the test thread, so
     * assertions depend on ordering alone. [geometryOnRegister] fires a geometry event during
     * registration, before the source can create a reader or display.
     */
    private class FakeEnvironment(override val executor: Executor = Executor { it.run() }) : ProjectionEnvironment {
        val events = mutableListOf<String>()
        val readers = mutableListOf<FakeReader>()
        var screen: FakeScreen? = null; private set
        var registered: ProjectionEvents? = null; private set
        var maxImages = -1; private set
        var releases = 0; private set
        var geometryOnRegister: Pair<Int, Int>? = null
        var failReaderRegistration = false
        var failReaderCreation = false
        var failDisplayAttachment = false

        override fun geometry() = ScreenGeometry(4, 4, 160)

        override fun createReader(width: Int, height: Int, maxImages: Int): FrameReader {
            if (failReaderCreation) throw IllegalStateException("reader creation failed")
            this.maxImages = maxImages
            events.add("reader $width x $height")
            return FakeReader().also {
                it.failRegistration = failReaderRegistration
                readers.add(it)
            }
        }

        override fun attachDisplay(reader: FrameReader, geometry: ScreenGeometry): VirtualScreen {
            events.add("attach")
            if (failDisplayAttachment) throw IllegalStateException("display attachment failed")
            return FakeScreen().also { screen = it }
        }

        override fun register(events: ProjectionEvents) {
            this.events.add("register")
            registered = events
            geometryOnRegister?.let { events.onGeometryChanged(it.first, it.second) }
        }

        override fun release() {
            releases++
            events.add("release")
        }
    }

    private class QueuedExecutor : Executor {
        private val tasks = ArrayDeque<Runnable>()

        override fun execute(command: Runnable) {
            tasks.addLast(command)
        }

        fun runNext() = tasks.removeFirst().run()
        fun runAll() { while (tasks.isNotEmpty()) runNext() }
    }

    @Test
    fun idleFramesAreClosedWithoutBitmapAllocation() {
        val source = newSource()

        assertEquals(listOf("register", "reader 4 x 4", "attach"), environment.events)
        assertEquals(2, environment.maxImages)

        val first = FakeFrame(0xFF00FF00.toInt())
        val second = FakeFrame(0xFF0000FF.toInt())
        val reader = environment.readers.single()
        reader.deliver(first)
        reader.deliver(second)

        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        assertEquals(0, first.reads)
        assertEquals(0, second.reads)
        source.close()
    }

    @Test
    fun takeDiscardsQueuedFramesAndWaitsForFreshImage() {
        val source = newSource()
        val reader = environment.readers.single()
        val stale = FakeFrame(0xFF111111.toInt())
        reader.offer(stale)
        val fresh = FakeFrame(0xFF222222.toInt())

        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            assertEquals(1, stale.closes)
            assertEquals(0, stale.reads)
            reader.deliver(fresh)
            taken.await()
        }

        assertEquals(1, fresh.reads)
        assertEquals(1, fresh.closes)
        assertEquals(2, bitmap.width)
        assertEquals(2, bitmap.height)
        for (x in 0..1) for (y in 0..1) assertEquals(0xFF222222.toInt(), bitmap.getPixel(x, y))
        source.close()
    }

    @Test
    fun missingFrameTimesOutAfter5000ms() {
        assertEquals(5_000L, FRAME_TIMEOUT_MS)

        val source = newSource(timeoutMs = 100)
        val error = runCatching { runBlocking { source.take() } }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        source.close()
    }

    @Test
    fun outerTimeoutCancelsTakeInsteadOfFailingIt() {
        val source = newSource()
        val cancelled = runCatching {
            runBlocking { withTimeout(50) { source.take() } }
        }.exceptionOrNull()

        assertTrue(cancelled is TimeoutCancellationException)

        val fresh = FakeFrame(0xFFAAAAAA.toInt())
        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            environment.readers.single().deliver(fresh)
            taken.await()
        }
        assertEquals(0xFFAAAAAA.toInt(), bitmap.getPixel(0, 0))
        source.close()
    }

    @Test
    fun lateFrameAfterTimeoutDoesNotCompleteNextRequest() {
        val source = newSource(timeoutMs = 100)
        val reader = environment.readers.single()
        runCatching { runBlocking { source.take() } }

        val late = FakeFrame(0xFF333333.toInt())
        reader.deliver(late)

        assertEquals(1, late.closes)
        assertEquals(0, late.reads)

        val second = runCatching { runBlocking { source.take() } }.exceptionOrNull()
        assertTrue(second is IllegalStateException)

        val fresh = FakeFrame(0xFF444444.toInt())
        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            reader.deliver(fresh)
            taken.await()
        }
        assertEquals(0xFF444444.toInt(), bitmap.getPixel(0, 0))
        assertEquals(1, fresh.reads)
        source.close()
    }

    @Test
    fun failingFrameFailsOnlyItsRequestAndClosesOnce() {
        val source = newSource()
        val reader = environment.readers.single()
        val broken = UnreadableFrame()

        val failed = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { runCatching { source.take() }.exceptionOrNull() }
            reader.deliver(broken)
            taken.await()
        }

        assertEquals("unreadable plane", failed?.message)
        assertEquals(1, broken.closes)

        val fresh = FakeFrame(0xFFC0C0C0.toInt())
        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            reader.deliver(fresh)
            taken.await()
        }
        assertEquals(0xFFC0C0C0.toInt(), bitmap.getPixel(0, 0))
        assertEquals(1, fresh.reads)
        source.close()
    }

    @Test
    fun resizeReusesDisplayAndClosesOldReader() {
        val source = newSource()
        val old = environment.readers.single()
        val screen = environment.screen!!

        environment.registered!!.onGeometryChanged(6, 6)

        val replacement = environment.readers.last()
        assertEquals(2, environment.readers.size)
        assertEquals(1, environment.events.count { it == "attach" })
        assertEquals("reader 6 x 6", environment.events.last())
        assertEquals(1, screen.clears)
        assertEquals(1, screen.resizes.size)
        assertArrayEquals(intArrayOf(6, 6, 160), screen.resizes.single())
        assertTrue(screen.surface === replacement)
        assertEquals(1, old.closes)

        val stale = FakeFrame(0xFF555555.toInt())
        old.deliver(stale)
        assertEquals(1, stale.closes)
        assertEquals(0, stale.reads)

        val fresh = FakeFrame(0xFF666666.toInt())
        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            replacement.deliver(fresh)
            taken.await()
        }
        assertEquals(0xFF666666.toInt(), bitmap.getPixel(0, 0))
        assertEquals(1, fresh.reads)
        source.close()
    }

    @Test
    fun geometryEventBeforeStartSizesOnlyTheStartupReader() {
        environment.geometryOnRegister = 6 to 6
        val source = newSource()

        assertEquals(listOf("register", "reader 6 x 6", "attach"), environment.events)
        assertEquals(1, environment.readers.size)
        assertEquals(0, environment.readers.single().closes)

        val fresh = FakeFrame(0xFF0B0B0B.toInt())
        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            environment.readers.single().deliver(fresh)
            taken.await()
        }
        assertEquals(0xFF0B0B0B.toInt(), bitmap.getPixel(0, 0))
        source.close()
    }

    @Test
    fun startupFailureClosesItsPartiallyCreatedReader() {
        environment.failReaderRegistration = true
        val source = newSource(timeoutMs = 100)

        assertEquals(1, environment.readers.single().closes)
        assertNull(environment.screen)

        val error = runCatching { runBlocking { source.take() } }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        source.close()
    }

    @Test
    fun awaitReadyCompletesOnlyAfterQueuedReaderAndDisplayAttachment() = runBlocking {
        val executor = QueuedExecutor()
        val queuedEnvironment = FakeEnvironment(executor)
        val source = newSource(environment = queuedEnvironment)
        val ready = async(start = CoroutineStart.UNDISPATCHED) { source.awaitReady() }

        assertTrue(!ready.isCompleted)
        assertEquals(listOf("register"), queuedEnvironment.events)

        executor.runNext()

        ready.await()
        assertEquals(listOf("register", "reader 4 x 4", "attach"), queuedEnvironment.events)
        source.close()
        executor.runAll()
    }

    @Test
    fun queuedReaderStartupFailureCompletesReadinessExceptionallyAndReleasesEnvironment() = runBlocking {
        val executor = QueuedExecutor()
        val queuedEnvironment = FakeEnvironment(executor).apply { failReaderCreation = true }
        val source = newSource(environment = queuedEnvironment)
        val ready = async(start = CoroutineStart.UNDISPATCHED) { runCatching { source.awaitReady() }.exceptionOrNull() }

        executor.runNext()

        assertEquals("reader creation failed", ready.await()?.message)
        assertEquals(1, queuedEnvironment.releases)
        assertNull(queuedEnvironment.screen)
    }

    @Test
    fun queuedDisplayStartupFailureClosesReaderAndReleasesEnvironment() = runBlocking {
        val executor = QueuedExecutor()
        val queuedEnvironment = FakeEnvironment(executor).apply { failDisplayAttachment = true }
        val source = newSource(environment = queuedEnvironment)
        val ready = async(start = CoroutineStart.UNDISPATCHED) { runCatching { source.awaitReady() }.exceptionOrNull() }

        executor.runNext()

        assertEquals("display attachment failed", ready.await()?.message)
        assertEquals(1, queuedEnvironment.readers.single().closes)
        assertEquals(1, queuedEnvironment.releases)
        assertNull(queuedEnvironment.screen)
    }

    @Test
    fun staleReaderCallbackAfterCloseOnlyClosesImage() {
        val source = newSource()
        val reader = environment.readers.single()
        val screen = environment.screen!!
        source.close()
        source.close()

        val stale = FakeFrame(0xFF777777.toInt())
        reader.deliver(stale)

        assertEquals(1, stale.closes)
        assertEquals(0, stale.reads)
        assertEquals(0, stopSignals)
        assertEquals(1, environment.releases)
        assertEquals(1, reader.closes)
        assertEquals(1, screen.releases)

        environment.registered!!.onStopped()
        assertEquals(1, environment.releases)
        assertEquals(1, reader.closes)
        assertEquals(1, screen.releases)
        assertEquals(0, stopSignals)
    }

    @Test
    fun unexpectedStopFailsPendingCaptureAndSignalsServiceOnce() {
        val source = newSource()
        val reader = environment.readers.single()
        val screen = environment.screen!!

        val failed = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { runCatching { source.take() }.exceptionOrNull() }
            environment.registered!!.onStopped()
            taken.await()
        }

        assertTrue(failed is IllegalStateException)
        assertEquals(1, stopSignals)
        assertEquals(1, environment.releases)
        assertEquals(1, reader.closes)
        assertEquals(1, screen.releases)

        environment.registered!!.onStopped()
        source.close()
        assertEquals(1, stopSignals)
        assertEquals(1, environment.releases)
        assertEquals(1, reader.closes)
        assertEquals(1, screen.releases)
    }

    @Test
    fun cancelledCaptureReleasesItsPendingSlot() {
        val source = newSource()
        val reader = environment.readers.single()

        runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            taken.cancel()
        }

        val late = FakeFrame(0xFF888888.toInt())
        reader.deliver(late)

        assertEquals(1, late.closes)
        assertEquals(0, late.reads)

        val fresh = FakeFrame(0xFF999999.toInt())
        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            reader.deliver(fresh)
            taken.await()
        }
        assertEquals(0xFF999999.toInt(), bitmap.getPixel(0, 0))
        assertEquals(1, fresh.reads)
        source.close()
    }

    @Test
    fun cancellationWinningDuringCompletionRecyclesBitmapAndLeavesNextRequestUsable() = runTest {
        val executor = QueuedExecutor()
        val queuedEnvironment = FakeEnvironment(executor)
        var converted: android.graphics.Bitmap? = null
        val source = newSource(environment = queuedEnvironment) { _, _, _, _, _ ->
            android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888).also {
                it.setPixel(0, 0, 0xFFBBBBBB.toInt())
                converted = it
            }
        }
        executor.runNext()
        source.awaitReady()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val taken = async(dispatcher) { source.take() }
        runCurrent()
        executor.runNext()

        queuedEnvironment.readers.single().deliver(FakeFrame(0xFFAAAAAA.toInt()))
        executor.runNext()
        taken.cancel()
        runCurrent()
        executor.runAll()

        assertTrue(converted!!.isRecycled)

        val next = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
        executor.runNext()
        queuedEnvironment.readers.single().deliver(FakeFrame(0xFFBBBBBB.toInt()))
        executor.runNext()
        assertEquals(0xFFBBBBBB.toInt(), next.await().getPixel(0, 0))
        source.close()
        executor.runAll()
    }
}
