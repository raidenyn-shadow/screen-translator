package com.raidenyn.screentranslator.capture

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectionFrameSourceTest {

    private val environment = FakeEnvironment()
    private var stopSignals = 0

    private fun newSource(timeoutMs: Long = 2_000): ProjectionFrameSource =
        ProjectionFrameSource(environment, timeoutMs = timeoutMs) { stopSignals++ }

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
                val bytes = ByteArray(24)   // two rows of two pixels plus 4-byte padding
                for (row in 0..1) for (col in 0..1)
                    rgba.copyInto(bytes, row * 12 + col * 4)
                return RgbaPlane(ByteBuffer.wrap(bytes), 4, 12)
            }
        override fun close() { closes++ }
    }

    /**
     * Reader fake: [offer] queues a frame whose callback task has not run yet, [deliver] queues
     * one and fires the callback, and acquiring keeps the real newest-wins, older-closed
     * semantics so the source's drain behaves as it would against a production queue.
     */
    private class FakeReader : FrameReader {
        private val queued = ArrayDeque<FakeFrame>()
        private var listener: (() -> Unit)? = null
        var closes = 0; private set

        override fun onImageAvailable(onFrame: () -> Unit) { listener = onFrame }

        override fun acquireLatestImage(): FrameImage? {
            val latest = queued.removeLastOrNull() ?: return null
            queued.forEach { it.close() }
            return latest
        }

        override fun close() { closes++ }

        fun offer(frame: FakeFrame) { queued.addLast(frame) }

        fun deliver(frame: FakeFrame) {
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
     * assertions depend on ordering alone, never on real thread timing. The event log proves
     * creation order; [offer]able readers let tests stage queued frames deterministically.
     */
    private class FakeEnvironment : ProjectionEnvironment {
        override val executor: Executor = Executor { it.run() }
        val events = mutableListOf<String>()
        val readers = mutableListOf<FakeReader>()
        var screen: FakeScreen? = null; private set
        var registered: ProjectionEvents? = null; private set
        var maxImages = -1; private set
        var releases = 0; private set

        override fun geometry() = ScreenGeometry(4, 4, 160)

        override fun createReader(width: Int, height: Int, maxImages: Int): FrameReader {
            this.maxImages = maxImages
            events.add("reader $width x $height")
            return FakeReader().also { readers.add(it) }
        }

        override fun attachDisplay(reader: FrameReader, geometry: ScreenGeometry): VirtualScreen {
            events.add("attach")
            return FakeScreen().also { screen = it }
        }

        override fun register(events: ProjectionEvents) {
            this.events.add("register")
            registered = events
        }

        override fun release() {
            releases++
            events.add("release")
        }
    }

    @Test
    fun idleFramesAreClosedWithoutBitmapAllocation() {
        val source = newSource()

        // Projection callbacks were registered before any reader/display existed, and the
        // display is created exactly once for the session's full-display geometry.
        assertEquals(listOf("register", "reader 4 x 4", "attach"), environment.events)
        assertEquals(2, environment.maxImages)

        val first = FakeFrame(0xFF00FF00.toInt())
        val second = FakeFrame(0xFF0000FF.toInt())
        val reader = environment.readers.single()
        reader.deliver(first)
        reader.deliver(second)

        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
        assertEquals(0, first.reads)   // unsolicited frames never reach conversion
        assertEquals(0, second.reads)
        source.close()
    }

    @Test
    fun takeDiscardsQueuedFramesAndWaitsForFreshImage() {
        val source = newSource()
        val reader = environment.readers.single()
        val stale = FakeFrame(0xFF111111.toInt())
        reader.offer(stale)   // arrived before the request, its callback task not yet run
        val fresh = FakeFrame(0xFF222222.toInt())

        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            // take() has run to its first suspension: the drain already discarded the stale
            // frame, and no conversion happened for it.
            assertEquals(1, stale.closes)
            assertEquals(0, stale.reads)
            reader.deliver(fresh)
            taken.await()
        }

        assertEquals(1, fresh.reads)   // exactly the fresh frame was converted...
        assertEquals(1, fresh.closes)  // ...and closed after its pixels were copied out
        assertEquals(2, bitmap.width)
        assertEquals(2, bitmap.height)
        for (x in 0..1) for (y in 0..1) assertEquals(0xFF222222.toInt(), bitmap.getPixel(x, y))
        source.close()
    }

    @Test
    fun missingFrameTimesOutAfter5000ms() {
        assertEquals(5_000L, FRAME_TIMEOUT_MS)   // the configured frame wait is 5 s

        val source = newSource(timeoutMs = 100)
        val error = runCatching { runBlocking { source.take() } }.exceptionOrNull()

        assertTrue(error is IllegalStateException)   // a timeout fails the capture, it does not cancel it
        source.close()
    }

    @Test
    fun lateFrameAfterTimeoutDoesNotCompleteNextRequest() {
        val source = newSource(timeoutMs = 100)
        val reader = environment.readers.single()
        runCatching { runBlocking { source.take() } }   // times out with no frame

        val late = FakeFrame(0xFF333333.toInt())
        reader.deliver(late)   // arrives after its request died

        assertEquals(1, late.closes)
        assertEquals(0, late.reads)   // handled as an idle frame, never converted

        val second = runCatching { runBlocking { source.take() } }.exceptionOrNull()
        assertTrue(second is IllegalStateException)   // the late frame did not satisfy it

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
    fun resizeReusesDisplayAndClosesOldReader() {
        val source = newSource()
        val old = environment.readers.single()
        val screen = environment.screen!!

        environment.registered!!.onGeometryChanged(6, 6)

        val replacement = environment.readers.last()
        assertEquals(2, environment.readers.size)
        assertEquals(1, environment.events.count { it == "attach" })   // one display per source
        assertEquals("reader 6 x 6", environment.events.last())         // a replacement reader, sized for the new geometry
        assertEquals(1, screen.clears)                                  // the old surface was detached...
        assertEquals(1, screen.resizes.size)
        assertArrayEquals(intArrayOf(6, 6, 160), screen.resizes.single())   // ...then the display resized...
        assertTrue(screen.surface === replacement)                     // ...then the new surface attached
        assertEquals(1, old.closes)                                    // and the replaced reader was closed

        val stale = FakeFrame(0xFF555555.toInt())
        old.deliver(stale)   // generation identity: a replaced reader's frames are inert
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
    fun staleReaderCallbackAfterCloseOnlyClosesImage() {
        val source = newSource()
        val reader = environment.readers.single()
        val screen = environment.screen!!
        source.close()
        source.close()   // idempotent

        val stale = FakeFrame(0xFF777777.toInt())
        reader.deliver(stale)   // a native callback racing teardown

        assertEquals(1, stale.closes)
        assertEquals(0, stale.reads)   // nothing is completed or converted after teardown
        assertEquals(0, stopSignals)   // user-initiated close is not an unexpected stop
        assertEquals(1, environment.releases)
        assertEquals(1, reader.closes)
        assertEquals(1, screen.releases)

        environment.registered!!.onStopped()   // a later projection stop converges on the same cleanup
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
            // The child catches its own failure: the inline executor resumes it on this very
            // thread, and an unhandled child exception would cancel the runBlocking parent.
            val taken = async(start = CoroutineStart.UNDISPATCHED) { runCatching { source.take() }.exceptionOrNull() }
            environment.registered!!.onStopped()   // revocation while the capture is armed
            taken.await()
        }

        assertTrue(failed is IllegalStateException)   // the stop failed the capture instead of hanging it
        assertEquals(1, stopSignals)                  // the service heard exactly once
        assertEquals(1, environment.releases)
        assertEquals(1, reader.closes)
        assertEquals(1, screen.releases)

        environment.registered!!.onStopped()   // repeated revocation events change nothing
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
            taken.cancel()   // the request pipeline died mid-capture
        }

        val late = FakeFrame(0xFF888888.toInt())
        reader.deliver(late)   // with the slot gone this is just an idle frame

        assertEquals(1, late.closes)
        assertEquals(0, late.reads)   // it never reached the dead request's conversion

        val fresh = FakeFrame(0xFF999999.toInt())
        val bitmap = runBlocking {
            val taken = async(start = CoroutineStart.UNDISPATCHED) { source.take() }
            reader.deliver(fresh)
            taken.await()
        }
        assertEquals(0xFF999999.toInt(), bitmap.getPixel(0, 0))
        assertEquals(1, fresh.reads)   // the next request was not blocked by the cancelled slot
        source.close()
    }
}