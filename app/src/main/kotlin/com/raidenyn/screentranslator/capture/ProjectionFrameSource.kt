package com.raidenyn.screentranslator.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import android.view.Surface
import android.view.WindowManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Bounded wait for a fresh frame before [ProjectionFrameSource.take] fails. */
internal const val FRAME_TIMEOUT_MS = 5_000L

/** Time after the first fresh frame during which newer frames replace it. */
internal const val SETTLE_WINDOW_MS = 750L

/** ImageReader depth: one frame for extraction, one spare so the producer never stalls. */
internal const val READER_MAX_IMAGES = 2

/** Full-display geometry a reader and virtual display are sized from. */
internal data class ScreenGeometry(val width: Int, val height: Int, val densityDpi: Int)

/** The RGBA_8888 plane of one captured frame. */
internal class RgbaPlane(val buffer: ByteBuffer, val pixelStride: Int, val rowStride: Int)

/** One captured frame; every instance must be closed exactly once. */
internal interface FrameImage : AutoCloseable {
    val width: Int
    val height: Int
    val plane: RgbaPlane
}

/** The bounded frame queue behind one display surface. */
internal interface FrameReader : AutoCloseable {
    /** Registers [onFrame]; production delivers it on the environment's state thread. */
    fun onImageAvailable(onFrame: () -> Unit)

    /** Newest queued frame with the older ones closed, or null when the queue is empty. */
    fun acquireLatestImage(): FrameImage?
}

/** The session's single virtual display, reused across geometry changes. */
internal interface VirtualScreen {
    fun clearSurface()
    fun resize(width: Int, height: Int, densityDpi: Int)
    fun setSurface(reader: FrameReader)
    fun release()
}

/** Projection revocation and captured-content geometry changes. */
internal interface ProjectionEvents {
    fun onGeometryChanged(width: Int, height: Int)
    fun onStopped()
}

/**
 * Reader/display factory seam over the capture framework: production adapts a real
 * MediaProjection, tests inject fakes, and [executor] serializes all source state.
 */
internal interface ProjectionEnvironment {
    val executor: Executor
    fun geometry(): ScreenGeometry
    fun createReader(width: Int, height: Int, maxImages: Int): FrameReader
    fun attachDisplay(reader: FrameReader, geometry: ScreenGeometry): VirtualScreen
    fun register(events: ProjectionEvents)

    /** Unregisters callbacks, stops the projection and terminates the state thread. */
    fun release()
}

/**
 * Screenshot taker over one consented MediaProjection: a single auto-mirroring display
 * whose reader is drained while idle. [take] drops queued frames, waits for one delivered
 * after the request and converts it to a software bitmap, failing on its own timeout.
 * Geometry changes replace the reader but reuse the display; revocation and [close]
 * converge on one cleanup that signals [onStopped] only for revocation.
 */
internal class ProjectionFrameSource internal constructor(
    private val environment: ProjectionEnvironment,
    private val timeoutMs: Long = FRAME_TIMEOUT_MS,
    private val settleWindowMs: Long = SETTLE_WINDOW_MS,
    private val onStopped: () -> Unit,
    private val bitmapConverter: (ByteBuffer, Int, Int, Int, Int) -> Bitmap = ::rgbaToBitmap,
) : ScreenshotTaker, AutoCloseable {

    constructor(context: Context, projection: MediaProjection, onStopped: () -> Unit) : this(
        MediaProjectionEnvironment(context, projection),
        onStopped = onStopped,
    )

    private val executor = environment.executor
    private val closed = AtomicBoolean(false)
    private val stoppedSignaled = AtomicBoolean(false)
    private val environmentReleased = AtomicBoolean(false)
    private val readiness = CompletableDeferred<Unit>()

    private var geometry = environment.geometry()
    private var reader: FrameReader? = null
    private var display: VirtualScreen? = null
    private var pending: PendingCapture? = null
    private var settled: SettledHolder? = null

    init {
        try {
            environment.register(object : ProjectionEvents {
                override fun onGeometryChanged(width: Int, height: Int) =
                    executor.execute { resize(width, height) }

                override fun onStopped() = executor.execute { projectionStopped() }
            })
            executor.execute { start() }
        } catch (error: Throwable) {
            startupFailed(error)
        }
    }

    /** Completes only when this source has attached its one reader/display pair. */
    internal suspend fun awaitReady() = readiness.await()

    override suspend fun take(): Bitmap {
        check(!closed.get()) { "frame source is closed" }
        val frame = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap> { continuation ->
                val slot = PendingCapture(continuation)
                continuation.invokeOnCancellation { executor.execute { cancelSlot(slot) } }
                executor.execute { arm(slot) }
            }
        }
        return frame ?: throw IllegalStateException("no fresh frame within $timeoutMs ms")
    }

    suspend fun takeSettled(): Bitmap {
        check(!closed.get()) { "frame source is closed" }
        val first = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Bitmap> { continuation ->
                val slot = PendingCapture(continuation)
                continuation.invokeOnCancellation { executor.execute { cancelSlot(slot) } }
                executor.execute { arm(slot) }
            }
        } ?: throw IllegalStateException("no fresh frame within $timeoutMs ms")
        return settleNewest(first)
    }

    /** Replaces [first] with each newer frame until [settleWindowMs] after arming. */
    private suspend fun settleNewest(first: Bitmap): Bitmap {
        val holder = SettledHolder(first)
        try {
            withTimeoutOrNull(settleWindowMs) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    continuation.invokeOnCancellation { executor.execute { holder.disarm() } }
                    executor.execute { armSettled(holder, continuation) }
                }
            }
        } catch (c: CancellationException) {
            executor.execute { holder.release() }
            throw c
        }
        return holder.finish()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        executor.execute {
            teardown()
            releaseEnvironment()
            readiness.completeExceptionally(IllegalStateException("frame source is closed"))
        }
    }

    private fun start() {
        if (closed.get()) return
        try {
            val fresh = environment.createReader(geometry.width, geometry.height, READER_MAX_IMAGES)
            reader = fresh
            fresh.onImageAvailable { executor.execute { onFrame(fresh) } }
            display = environment.attachDisplay(fresh, geometry)
            readiness.complete(Unit)
        } catch (error: Throwable) {
            startupFailed(error)
        }
    }

    private fun startupFailed(error: Throwable) {
        if (!closed.compareAndSet(false, true)) return
        teardown()
        releaseEnvironment()
        readiness.completeExceptionally(error)
    }

    private fun arm(slot: PendingCapture) {
        if (closed.get()) {
            slot.fail(IllegalStateException("frame source is closed"))
            return
        }
        if (!slot.active) return
        if (pending != null || settled != null) {
            slot.fail(IllegalStateException("another capture is already pending"))
            return
        }
        while (true) {
            val queued = reader?.acquireLatestImage() ?: break
            queued.close()
        }
        pending = slot
    }

    private fun cancelSlot(slot: PendingCapture) {
        if (pending === slot) pending = null
    }

    private fun armSettled(holder: SettledHolder, continuation: CancellableContinuation<Unit>) {
        if (closed.get()) {
            holder.fail(IllegalStateException("frame source is closed"))
            return
        }
        if (pending != null || settled != null) {
            holder.fail(IllegalStateException("another capture is already pending"))
            return
        }
        settled = holder
        holder.arm(continuation)
    }

    private fun onFrame(from: FrameReader) {
        val image = try {
            from.acquireLatestImage()
        } catch (_: IllegalStateException) {
            return
        } ?: return
        try {
            if (closed.get() || from !== reader) return
            val slot = pending
            if (slot != null) {
                pending = null
                try {
                    val plane = image.plane
                    slot.complete(bitmapConverter(plane.buffer, image.width, image.height, plane.pixelStride, plane.rowStride))
                } catch (t: Throwable) {
                    slot.fail(t)
                }
                return
            }
            val holder = settled ?: return
            try {
                val plane = image.plane
                holder.offer(bitmapConverter(plane.buffer, image.width, image.height, plane.pixelStride, plane.rowStride))
            } catch (_: Throwable) {
                // A malformed replacement frame leaves the prior candidate intact.
            }
        } finally {
            image.close()
        }
    }

    private fun resize(width: Int, height: Int) {
        if (closed.get()) return
        val screen = display
        if (screen == null) {
            geometry = ScreenGeometry(width, height, geometry.densityDpi)
            return
        }
        if (width == geometry.width && height == geometry.height) return
        val densityDpi = geometry.densityDpi
        val old = reader
        var fresh: FrameReader? = null
        val swapped = try {
            val created = environment.createReader(width, height, READER_MAX_IMAGES)
            fresh = created
            created.onImageAvailable { executor.execute { onFrame(created) } }
            screen.clearSurface()
            screen.resize(width, height, densityDpi)
            screen.setSurface(created)
            true
        } catch (_: Throwable) {
            if (old != null) runCatching { screen.setSurface(old) }
            fresh?.close()
            false
        }
        if (swapped) {
            reader = fresh
            geometry = ScreenGeometry(width, height, densityDpi)
            old?.close()
        }
    }

    private fun projectionStopped() {
        if (!closed.compareAndSet(false, true)) return
        teardown()
        releaseEnvironment()
        readiness.completeExceptionally(IllegalStateException("projection stopped"))
        if (stoppedSignaled.compareAndSet(false, true)) onStopped()
    }

    private fun releaseEnvironment() {
        if (environmentReleased.compareAndSet(false, true)) environment.release()
    }

    private fun teardown() {
        val screen = display
        val current = reader
        val slot = pending
        val holder = settled
        display = null
        reader = null
        pending = null
        settled = null
        screen?.release()
        current?.close()
        slot?.fail(IllegalStateException("projection stopped"))
        holder?.fail(IllegalStateException("projection stopped"))
    }

    /** One awaited capture; identity-matched so a late cancellation frees only its own slot. */
    private class PendingCapture(private val continuation: CancellableContinuation<Bitmap>) {
        val active get() = continuation.isActive
        fun complete(bitmap: Bitmap) = continuation.resume(bitmap) { _, value, _ -> value.recycle() }
        fun fail(error: Throwable) = continuation.resumeWithException(error)
    }

    private inner class SettledHolder(first: Bitmap) {
        private val lock = Any()
        private var candidate: Bitmap? = first
        private var collecting = false
        private var continuation: CancellableContinuation<Unit>? = null

        fun arm(continuation: CancellableContinuation<Unit>) {
            synchronized(lock) {
                collecting = true
                this.continuation = continuation
            }
            val image = try {
                reader?.acquireLatestImage()
            } catch (_: IllegalStateException) {
                null
            } ?: return
            try {
                val plane = image.plane
                offer(bitmapConverter(plane.buffer, image.width, image.height, plane.pixelStride, plane.rowStride))
            } catch (_: Throwable) {
                // A malformed queued frame leaves the first fresh candidate intact.
            } finally {
                image.close()
            }
        }

        fun offer(bitmap: Bitmap) {
            val replaced = synchronized(lock) {
                if (!collecting) bitmap else candidate.also { candidate = bitmap }
            }
            replaced?.recycle()
        }

        fun disarm() {
            synchronized(lock) {
                collecting = false
                continuation = null
            }
            if (settled === this) settled = null
        }

        fun finish(): Bitmap {
            val result = synchronized(lock) {
                collecting = false
                continuation = null
                candidate.also { candidate = null }
            } ?: throw IllegalStateException("settled capture has no frame")
            executor.execute { if (settled === this) settled = null }
            return result
        }

        fun fail(error: Throwable) {
            val state = synchronized(lock) {
                collecting = false
                candidate.also { candidate = null } to continuation.also { continuation = null }
            }
            state.first?.recycle()
            state.second?.resumeWithException(error)
        }

        fun release() {
            val bitmap = synchronized(lock) {
                collecting = false
                continuation = null
                candidate.also { candidate = null }
            }
            bitmap?.recycle()
            if (settled === this) settled = null
        }
    }
}

/** Production [ProjectionEnvironment] over a real MediaProjection and a state HandlerThread. */
private class MediaProjectionEnvironment(
    private val context: Context,
    private val projection: MediaProjection,
) : ProjectionEnvironment {

    private val stateThread = HandlerThread("projection-frames").apply { start() }
    private val stateHandler = Handler(stateThread.looper)
    private var projectionCallback: MediaProjection.Callback? = null
    private var displayListener: DisplayManager.DisplayListener? = null

    override val executor: Executor = Executor { stateHandler.post(it) }

    override fun geometry(): ScreenGeometry {
        val bounds = context.getSystemService(WindowManager::class.java)!!.maximumWindowMetrics.bounds
        return ScreenGeometry(bounds.width(), bounds.height(), context.resources.displayMetrics.densityDpi)
    }

    override fun createReader(width: Int, height: Int, maxImages: Int): FrameReader =
        ImageReaderFrameReader(
            ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, maxImages),
            stateHandler,
        )

    override fun attachDisplay(reader: FrameReader, geometry: ScreenGeometry): VirtualScreen {
        val display = projection.createVirtualDisplay(
            "screen-translator",
            geometry.width, geometry.height, geometry.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            (reader as ImageReaderFrameReader).surface,
            null,
            null,
        ) ?: throw IllegalStateException("createVirtualDisplay returned null")
        return ProjectionScreen(display)
    }

    override fun register(events: ProjectionEvents) {
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            object : MediaProjection.Callback() {
                override fun onCapturedContentResize(width: Int, height: Int) =
                    events.onGeometryChanged(width, height)

                override fun onStop() = events.onStopped()
            }
        } else {
            object : MediaProjection.Callback() {
                override fun onStop() = events.onStopped()
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, stateHandler)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) {}
                override fun onDisplayRemoved(displayId: Int) {}
                override fun onDisplayChanged(displayId: Int) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        val metrics = geometry()
                        events.onGeometryChanged(metrics.width, metrics.height)
                    }
                }
            }
            displayListener = listener
            context.getSystemService(DisplayManager::class.java)!!
                .registerDisplayListener(listener, stateHandler)
        }
    }

    override fun release() {
        projectionCallback?.let(projection::unregisterCallback)
        projectionCallback = null
        displayListener?.let { listener ->
            context.getSystemService(DisplayManager::class.java)!!.unregisterDisplayListener(listener)
        }
        displayListener = null
        projection.stop()
        stateThread.quitSafely()
    }
}

private class ImageReaderFrameReader(
    private val reader: ImageReader,
    private val handler: Handler,
) : FrameReader {
    val surface: Surface get() = reader.surface

    override fun onImageAvailable(onFrame: () -> Unit) {
        reader.setOnImageAvailableListener({ onFrame() }, handler)
    }

    override fun acquireLatestImage(): FrameImage? =
        reader.acquireLatestImage()?.let(::ImageFrame)

    override fun close() = reader.close()
}

private class ImageFrame(private val image: Image) : FrameImage {
    override val width get() = image.width
    override val height get() = image.height
    override val plane: RgbaPlane
        get() {
            val plane = image.planes[0]
            return RgbaPlane(plane.buffer, plane.pixelStride, plane.rowStride)
        }

    override fun close() = image.close()
}

private class ProjectionScreen(private val display: VirtualDisplay) : VirtualScreen {
    override fun clearSurface() = display.setSurface(null)
    override fun resize(width: Int, height: Int, densityDpi: Int) = display.resize(width, height, densityDpi)
    override fun setSurface(reader: FrameReader) = display.setSurface((reader as ImageReaderFrameReader).surface)
    override fun release() = display.release()
}
