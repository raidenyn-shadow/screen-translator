package com.raidenyn.screentranslator.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.capture.CAPTURE_DELAY_MS
import com.raidenyn.screentranslator.capture.CaptureOrchestrator
import com.raidenyn.screentranslator.capture.CaptureStore
import com.raidenyn.screentranslator.capture.ScreenshotTaker
import com.raidenyn.screentranslator.lens.IntentStarter
import com.raidenyn.screentranslator.lens.LensLauncher
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrampolineActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testScope = TestScope()

    /**
     * Holds every dispatched pipeline inside the delay step: a dispatch is observable
     * through the recorded delay, while the store and Lens launcher stay untouched.
     */
    private val gate = CompletableDeferred<Unit>()

    @After fun resetService() { ScreenCaptureService.instance = null }

    /** The next activity [trampoline] started, or null when it started nothing. */
    private fun nextStartedBy(trampoline: TrampolineActivity): Intent? =
        shadowOf(trampoline).nextStartedActivityForResult?.intent

    /** A service instance standing in for the system-bound one; no lifecycle is driven. */
    private fun attachedService(): ScreenCaptureService =
        Robolectric.buildService(ScreenCaptureService::class.java).get()

    /**
     * A real [CaptureOrchestrator] whose delay step records the dispatch and then parks
     * at [gate], so routing tests observe the dispatch without running the pipeline.
     */
    private fun recordingOrchestrator(dispatches: MutableList<Long>): CaptureOrchestrator =
        CaptureOrchestrator(
            taker = RecordingTaker(),
            store = CaptureStore.from(context),
            launcher = { uri ->
                LensLauncher(context.contentResolver, RecordingStarter()).launch(uri)
                null
            },
            onError = { },
            scope = testScope,
            delayer = { dispatches.add(it); gate.await() },
        )

    private class RecordingTaker : ScreenshotTaker {
        var calls = 0
        override suspend fun take(): Bitmap {
            calls++
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        }
    }

    private class RecordingStarter : IntentStarter {
        val intents = mutableListOf<Intent>()
        override fun start(intent: Intent) { intents.add(intent) }
    }

    @Test
    fun noServiceRoutesToSetup() {
        assertNull(ScreenCaptureService.instance)   // the accessibility service is not running

        val trampoline = Robolectric.buildActivity(TrampolineActivity::class.java).create().get()

        assertTrue(trampoline.isFinishing)
        val started = nextStartedBy(trampoline)
        assertEquals(ComponentName(context, SetupActivity::class.java), started?.component)
    }

    @Test
    fun servicePresentDispatches() {
        val service = attachedService()
        ScreenCaptureService.instance = service
        val dispatches = mutableListOf<Long>()
        ReflectionHelpers.setField(service, "realOrchestrator", recordingOrchestrator(dispatches))

        val trampoline = Robolectric.buildActivity(TrampolineActivity::class.java).create().get()

        assertTrue(trampoline.isFinishing)
        assertNull(nextStartedBy(trampoline))               // the service is live: no setup screen
        testScope.testScheduler.advanceUntilIdle()          // the dispatched pipeline parks at the gate
        assertEquals(listOf(CAPTURE_DELAY_MS), dispatches)  // the tap dispatched exactly one capture
    }

    @Test
    fun inFlightTapDoesNothing() {
        val service = attachedService()
        ScreenCaptureService.instance = service
        val dispatches = mutableListOf<Long>()
        val orchestrator = recordingOrchestrator(dispatches)
        ReflectionHelpers.setField(service, "realOrchestrator", orchestrator)

        assertTrue(orchestrator.tryDispatch())             // a capture is already in flight
        testScope.testScheduler.advanceUntilIdle()          // it parks at the gate, holding the in-flight guard

        val trampoline = Robolectric.buildActivity(TrampolineActivity::class.java).create().get()

        assertTrue(trampoline.isFinishing)
        assertNull(nextStartedBy(trampoline))               // duplicate tap: no setup screen either
        assertEquals(listOf(CAPTURE_DELAY_MS), dispatches)  // and it dispatched no second capture
    }
}
