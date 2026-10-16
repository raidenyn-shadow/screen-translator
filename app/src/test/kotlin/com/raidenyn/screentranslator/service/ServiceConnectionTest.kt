package com.raidenyn.screentranslator.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.capture.CAPTURE_DELAY_MS
import com.raidenyn.screentranslator.capture.CaptureRequests
import com.raidenyn.screentranslator.capture.FrameImage
import com.raidenyn.screentranslator.capture.FrameReader
import com.raidenyn.screentranslator.capture.ProjectionEnvironment
import com.raidenyn.screentranslator.capture.ProjectionEvents
import com.raidenyn.screentranslator.capture.ProjectionFrameSource
import com.raidenyn.screentranslator.capture.ScreenGeometry
import com.raidenyn.screentranslator.capture.VirtualScreen
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
@OptIn(ExperimentalCoroutinesApi::class)
class ServiceConnectionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val testScope = TestScope()
    private var booted: Booted? = null

    @After fun clean() {
        booted?.controller?.destroy()
        booted = null
        testScope.cancel()
    }

    /** A session the service booted, with the pieces its assertions need to observe. */
    private class Booted(
        val controller: ServiceController<ScreenCaptureService>,
        val session: ScriptedSession,
        val environment: RecordingEnvironment,
    ) {
        val service get() = controller.get()
    }

    /**
     * Environment fake behind the real [ProjectionFrameSource]: no frames are ever delivered,
     * so a capture request parks inside its bounded frame wait — exactly the in-flight state
     * cleanup must cancel. [registered] fires the projection's revocation event.
     */
    private class RecordingEnvironment : ProjectionEnvironment {
        val events = mutableListOf<String>()
        var released = 0
            private set
        var registered: ProjectionEvents? = null
            private set

        override val executor: Executor = Executor { it.run() }

        override fun geometry() = ScreenGeometry(2, 2, 160)

        override fun createReader(width: Int, height: Int, maxImages: Int): FrameReader {
            events += "reader"
            return object : FrameReader {
                override fun onImageAvailable(onFrame: () -> Unit) {}
                override fun acquireLatestImage(): FrameImage? = null
                override fun close() {}
            }
        }

        override fun attachDisplay(reader: FrameReader, geometry: ScreenGeometry): VirtualScreen {
            events += "display"
            return object : VirtualScreen {
                override fun clearSurface() {}
                override fun resize(width: Int, height: Int, densityDpi: Int) {}
                override fun setSurface(reader: FrameReader) {}
                override fun release() {}
            }
        }

        override fun register(events: ProjectionEvents) {
            this.events += "register"
            registered = events
        }

        override fun release() {
            released++
        }
    }

    /**
     * Session seam scripting a successful acquisition of a real frame source and recording
     * the moment it runs, so publication ordering is observable without framework mocking.
     */
    private class ScriptedSession(private val environment: RecordingEnvironment) : ProjectionSession {
        val calls = mutableListOf<String>()
        var connectionObservedDuringAcquire: ScreenCaptureService? = null
            private set
        var sources = 0
            private set
        var failAcquire = false

        override fun startForeground(service: ScreenCaptureService) {
            calls += "foreground"
        }

        override fun acquire(
            context: Context,
            resultCode: Int,
            data: Intent,
            onStopped: () -> Unit,
        ): ProjectionFrameSource? {
            calls += "acquire"
            if (failAcquire) throw RuntimeException("projection acquisition failed")
            connectionObservedDuringAcquire = ScreenCaptureService.connection.value
            sources++
            return ProjectionFrameSource(environment, onStopped = onStopped)
        }
    }

    /** Boots a session through the real companion start path, with a scripted framework seam. */
    private fun boot(resultCode: Int = Activity.RESULT_OK, data: Intent = Intent()): Booted {
        val start = capturedStartIntent(resultCode, data)
        val environment = RecordingEnvironment()
        val session = ScriptedSession(environment)
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        controller.get().session = session
        controller.get().scopeFactory = { testScope }
        driveCommand(controller, start)
        val started = Booted(controller, session, environment)
        booted = started
        return started
    }

    /** The start intent the companion really sends, captured from the started-service queue. */
    private fun capturedStartIntent(resultCode: Int, data: Intent): Intent {
        ScreenCaptureService.start(context, resultCode, data)
        return Shadows.shadowOf(app).nextStartedService
    }

    /** The stop intent the companion really sends, captured from the started-service queue. */
    private fun capturedStopIntent(): Intent {
        ScreenCaptureService.stop(context)
        return Shadows.shadowOf(app).nextStartedService
    }

    @Suppress("DEPRECATION")
    private fun driveCommand(controller: ServiceController<ScreenCaptureService>, intent: Intent) {
        controller.withIntent(intent).startCommand(0, 0)
    }

    /** Parks one capture inside its frame wait: the delay elapsed, but no frame ever arrives. */
    private fun parkCapture(requests: CaptureRequests): Job? {
        assertTrue(requests.request("pending"))
        testScope.testScheduler.advanceTimeBy(CAPTURE_DELAY_MS)
        testScope.testScheduler.runCurrent()
        return testScope.coroutineContext[Job]!!.children.singleOrNull { it.isActive }
    }

    @Test fun connectionPublishesOnlyFullyReadySessions() {
        val started = boot()

        assertNull(started.session.connectionObservedDuringAcquire)
        assertSame(started.service, ScreenCaptureService.connection.value)
        assertTrue(started.service.requests.request("probe"))
        testScope.testScheduler.runCurrent()
    }

    @Test fun duplicateStartsPreserveTheLiveSession() {
        val first = boot()
        ScreenCaptureService.start(context, Activity.RESULT_OK, Intent().putExtra("other", true))
        val duplicate = Shadows.shadowOf(app).nextStartedService

        driveCommand(first.controller, duplicate)

        assertSame(first.service, ScreenCaptureService.connection.value)
        assertEquals(1, first.session.sources)
        assertEquals(listOf("foreground", "acquire"), first.session.calls)
        assertEquals(listOf("register", "reader", "display"), first.environment.events)
    }

    @Test fun stopActionCancelsInFlightWorkAndReleasesTheSession() {
        val started = boot()
        val requests = started.service.requests
        val pending = parkCapture(requests)
        assertTrue(pending!!.isActive)

        driveCommand(started.controller, capturedStopIntent())

        assertNull(ScreenCaptureService.connection.value)
        assertFalse(pending.isActive)
        assertEquals(1, started.environment.released)
        assertFalse(requests.request("late"))
        assertTrue(Shadows.shadowOf(started.service).isStoppedBySelf)
        assertTrue(Shadows.shadowOf(started.service).isForegroundStopped)
    }

    @Test fun stopBeforeAnySessionIsHarmless() {
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        driveCommand(controller, capturedStopIntent())
        assertNull(ScreenCaptureService.connection.value)
        assertTrue(Shadows.shadowOf(controller.get()).isStoppedBySelf)
    }

    @Test fun destroyingARunningServiceClearsTheSession() {
        val started = boot()
        parkCapture(started.service.requests)

        started.controller.destroy()

        assertNull(ScreenCaptureService.connection.value)
        assertEquals(1, started.environment.released)
        assertEquals(0, testScope.coroutineContext[Job]!!.children.count { it.isActive })
    }

    @Test fun destroyAfterStopReleasesEachResourceOnce() {
        val started = boot()
        driveCommand(started.controller, capturedStopIntent())
        val released = started.environment.released

        started.controller.destroy()

        assertEquals(released, started.environment.released)
        assertNull(ScreenCaptureService.connection.value)
    }

    @Test fun projectionRevocationClearsTheSessionAndStopsTheService() {
        val started = boot()
        val requests = started.service.requests
        val pending = parkCapture(requests)

        started.environment.registered!!.onStopped()
        ShadowLooper.idleMainLooper()

        assertNull(ScreenCaptureService.connection.value)
        assertFalse(pending!!.isActive)
        assertEquals(1, started.environment.released)
        assertFalse(requests.request("late"))
        assertTrue(Shadows.shadowOf(started.service).isStoppedBySelf)
    }

    @Test fun companionStopSendsAPlainStopActionIntent() {
        ScreenCaptureService.stop(context)

        val intent = Shadows.shadowOf(app).nextStartedService
        assertEquals(ScreenCaptureService.ACTION_STOP, intent.action)
        assertEquals(ComponentName(context, ScreenCaptureService::class.java), intent.component)
    }

    @Test fun aLaterSuccessfulSessionClearsTheStartupError() {
        val failedEnvironment = RecordingEnvironment()
        val failedSession = ScriptedSession(failedEnvironment).apply { failAcquire = true }
        val failedController = Robolectric.buildService(ScreenCaptureService::class.java).create()
        failedController.get().session = failedSession
        ScreenCaptureService.start(context, Activity.RESULT_OK, Intent())
        val failedStart = Shadows.shadowOf(app).nextStartedService
        driveCommand(failedController, failedStart)
        assertEquals(
            context.getString(com.raidenyn.screentranslator.R.string.error_session_failed),
            ScreenCaptureService.startupError.value,
        )

        val good = boot()
        try {
            assertNull(ScreenCaptureService.startupError.value)
            assertSame(good.service, ScreenCaptureService.connection.value)
        } finally {
            failedController.destroy()
        }
    }
}