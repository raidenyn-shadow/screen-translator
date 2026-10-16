package com.raidenyn.screentranslator.ui

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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TrampolineActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val testScope = TestScope()
    private var serviceController: ServiceController<ScreenCaptureService>? = null

    /**
     * Holds every dispatched pipeline inside the delay step: a dispatch is observable
     * through the recorded delay, while the store and Lens launcher stay untouched.
     */
    private val gate = CompletableDeferred<Unit>()

    @After fun resetService() {
        serviceController?.destroy()
        testScope.cancel()
    }

    /** The next activity [TrampolineActivity] started, or null when it started nothing. */
    private fun nextStartedBy(trampoline: TrampolineActivity): Intent? =
        shadowOf(trampoline).nextStartedActivityForResult?.intent

    private fun attachedService(): ScreenCaptureService {
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        serviceController = controller
        return controller.get().also {
            ReflectionHelpers.callInstanceMethod<Unit>(it, "onServiceConnected")
        }
    }

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

    private fun sidebarIntent(): Intent =
        Intent(Intent.ACTION_MAIN).putExtra("from_package", "com.coloros.smartsidebar")

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
    fun sidebarTapWithoutServiceKeepsSettingsInTheLauncherActivity() {
        assertNull(ScreenCaptureService.instance)   // the accessibility service is not running

        val controller = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).create()
        val trampoline = controller.get()

        assertFalse(trampoline.isFinishing)
        assertNull(nextStartedBy(trampoline))
        controller.destroy()
    }

    @Test
    fun sidebarTapWithServiceDispatches() {
        val service = attachedService()
        val dispatches = mutableListOf<Long>()
        ReflectionHelpers.setField(service, "realOrchestrator", recordingOrchestrator(dispatches))

        val trampoline = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).create().get()

        assertTrue(trampoline.isFinishing)
        assertNull(nextStartedBy(trampoline))               // the service is live: no setup screen
        testScope.testScheduler.advanceUntilIdle()          // the dispatched pipeline parks at the gate
        assertEquals(listOf(CAPTURE_DELAY_MS), dispatches)  // the tap dispatched exactly one capture
    }

    @Test
    fun duplicateSidebarTapWhileCapturingDoesNothing() {
        val service = attachedService()
        val dispatches = mutableListOf<Long>()
        val orchestrator = recordingOrchestrator(dispatches)
        ReflectionHelpers.setField(service, "realOrchestrator", orchestrator)

        assertTrue(orchestrator.tryDispatch())             // a capture is already in flight
        testScope.testScheduler.advanceUntilIdle()          // it parks at the gate, holding the in-flight guard

        val trampoline = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).create().get()

        assertTrue(trampoline.isFinishing)
        assertNull(nextStartedBy(trampoline))               // duplicate tap: no setup screen either
        assertEquals(listOf(CAPTURE_DELAY_MS), dispatches)  // and it dispatched no second capture
    }

    @Test
    fun iconTapWithoutServiceOpensPlainSettings() {
        assertNull(ScreenCaptureService.instance)   // the accessibility service is not running

        val controller = Robolectric.buildActivity(TrampolineActivity::class.java).create()
        val trampoline = controller.get()

        assertFalse(trampoline.isFinishing)
        assertNull(nextStartedBy(trampoline))
        controller.destroy()
    }

    @Test
    fun iconTapWithServiceOpensSettingsInsteadOfCapturing() {
        val service = attachedService()
        val dispatches = mutableListOf<Long>()
        ReflectionHelpers.setField(service, "realOrchestrator", recordingOrchestrator(dispatches))

        val controller = Robolectric.buildActivity(TrampolineActivity::class.java).create()
        val trampoline = controller.get()

        assertFalse(trampoline.isFinishing)
        assertNull(nextStartedBy(trampoline))
        testScope.testScheduler.advanceUntilIdle()
        assertEquals(emptyList<Long>(), dispatches)   // the icon tap must not start a capture
        controller.destroy()
    }
}
