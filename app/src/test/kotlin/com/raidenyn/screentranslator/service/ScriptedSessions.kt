package com.raidenyn.screentranslator.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.capture.FrameImage
import com.raidenyn.screentranslator.capture.FrameReader
import com.raidenyn.screentranslator.capture.ProjectionEnvironment
import com.raidenyn.screentranslator.capture.ProjectionEvents
import com.raidenyn.screentranslator.capture.ProjectionFrameSource
import com.raidenyn.screentranslator.capture.RgbaPlane
import com.raidenyn.screentranslator.capture.ScreenGeometry
import com.raidenyn.screentranslator.capture.VirtualScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import org.robolectric.Robolectric
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * Session seam scripted by Activity tests: records the framework call order and hands the
 * service a real [ProjectionFrameSource] over [environment], so projection readiness and
 * capture results behave like production without mocking the framework.
 */
internal class ScriptedSession(val environment: ScriptedEnvironment) : ProjectionSession {
    val calls = mutableListOf<String>()
    var failForeground = false
    var failAcquire = false
    var refuseProjection = false
    var connectionObservedDuringAcquire: ScreenCaptureService? = null
        private set

    override fun startForeground(service: ScreenCaptureService) {
        calls += "foreground"
        if (failForeground) throw RuntimeException("foreground promotion failed")
    }

    override fun acquire(
        context: Context,
        resultCode: Int,
        data: Intent,
        onStopped: () -> Unit,
    ): ProjectionFrameSource? {
        calls += "acquire"
        if (failAcquire) throw RuntimeException("projection acquisition failed")
        if (refuseProjection) return null
        connectionObservedDuringAcquire = ScreenCaptureService.connection.value
        return ProjectionFrameSource(environment, onStopped = onStopped)
    }
}

/**
 * Environment fake behind the real frame source: no frame ever arrives unless [deliver]
 * queues one, so a capture request parks inside its bounded frame wait until the test
 * releases it; [registered] fires the projection's revocation event.
 */
internal class ScriptedEnvironment : ProjectionEnvironment {
    val events = mutableListOf<String>()
    var released = 0
        private set
    var registered: ProjectionEvents? = null
        private set
    private var listener: (() -> Unit)? = null
    private val queued = ArrayDeque<FrameImage>()

    override val executor: Executor = Executor { it.run() }

    override fun geometry() = ScreenGeometry(2, 2, 160)

    override fun createReader(width: Int, height: Int, maxImages: Int): FrameReader {
        events += "reader"
        return object : FrameReader {
            override fun onImageAvailable(onFrame: () -> Unit) {
                listener = onFrame
            }

            override fun acquireLatestImage(): FrameImage? = queued.removeLastOrNull()

            override fun close() {
                events += "reader-closed"
            }
        }
    }

    override fun attachDisplay(reader: FrameReader, geometry: ScreenGeometry): VirtualScreen {
        events += "display"
        return object : VirtualScreen {
            override fun clearSurface() {}
            override fun resize(width: Int, height: Int, densityDpi: Int) {}
            override fun setSurface(reader: FrameReader) {}
            override fun release() {
                events += "display-released"
            }
        }
    }

    override fun register(events: ProjectionEvents) {
        this.events += "register"
        registered = events
    }

    override fun release() {
        released++
    }

    /** Queues [frame] and fires the reader callback, satisfying one parked capture. */
    fun deliver(frame: FrameImage = SolidFrame()) {
        queued.addLast(frame)
        listener?.invoke()
    }
}

/** One 2x2 flat-color frame the frame source converts into a software bitmap. */
internal class SolidFrame(private val argb: Int = 0xFF3366CC.toInt()) : FrameImage {
    override val width = 2
    override val height = 2
    override val plane: RgbaPlane
        get() {
            val rgba = byteArrayOf(
                (argb ushr 16).toByte(),
                (argb ushr 8).toByte(),
                argb.toByte(),
                (argb ushr 24).toByte(),
            )
            val bytes = ByteArray(24)
            for (row in 0..1) for (col in 0..1) rgba.copyInto(bytes, row * 12 + col * 4)
            return RgbaPlane(ByteBuffer.wrap(bytes), 4, 12)
        }

    override fun close() {}
}

/** One session a test booted, with the pieces Activity tests observe. */
internal class SessionBoot(
    val controller: ServiceController<ScreenCaptureService>,
    val session: ScriptedSession,
    val environment: ScriptedEnvironment,
) {
    val service: ScreenCaptureService get() = controller.get()
}

/**
 * Boots a ready session through the companion start path the Setup UI uses, with a scripted
 * framework seam and the test's [scope] owning capture work.
 */
internal fun bootSession(
    scope: CoroutineScope,
    environment: ScriptedEnvironment = ScriptedEnvironment(),
): SessionBoot {
    val context = ApplicationProvider.getApplicationContext<Context>()
    ScreenCaptureService.start(context, Activity.RESULT_OK, Intent())
    val start = Shadows.shadowOf(ApplicationProvider.getApplicationContext<android.app.Application>())
        .nextStartedService
    return bootFrom(start, scope, environment)
}

/** Boots a ready session from an already-captured companion start intent. */
internal fun bootFrom(
    start: Intent,
    scope: CoroutineScope,
    environment: ScriptedEnvironment = ScriptedEnvironment(),
): SessionBoot {
    val session = ScriptedSession(environment)
    val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
    controller.get().session = session
    controller.get().scopeFactory = { scope }
    driveCommand(controller, start)
    return SessionBoot(controller, session, environment)
}

@Suppress("DEPRECATION")
internal fun driveCommand(controller: ServiceController<ScreenCaptureService>, intent: Intent) {
    controller.withIntent(intent).startCommand(0, 0)
}

/**
 * Advances virtual time until the capture pipeline completes: CaptureStore.save hops to a
 * real Dispatchers.IO thread invisible to the scheduler, so real time is yielded between
 * advances until the child job finishes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun TestScope.advanceUntilPipelineIdle() {
    val scopeJob = coroutineContext[Job]!!
    val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
    while (scopeJob.children.any { it.isActive }) {
        testScheduler.advanceUntilIdle()
        check(System.nanoTime() < deadline) { "capture pipeline did not complete in time" }
        Thread.sleep(1)
    }
    testScheduler.advanceUntilIdle()
}