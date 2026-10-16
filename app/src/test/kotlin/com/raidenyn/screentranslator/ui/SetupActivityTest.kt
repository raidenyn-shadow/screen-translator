package com.raidenyn.screentranslator.ui

import android.content.Intent
import android.os.Looper
import com.raidenyn.screentranslator.capture.CaptureOrchestrator
import com.raidenyn.screentranslator.capture.CaptureStore
import com.raidenyn.screentranslator.capture.ScreenshotTaker
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SetupActivityTest {
    private val scope = TestScope()
    private var serviceController: ServiceController<ScreenCaptureService>? = null
    private var dispatches = 0

    @After fun clean() {
        serviceController?.destroy()
        scope.cancel()
    }

    private fun connectService() {
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        serviceController = controller
        val service = controller.get()
        ReflectionHelpers.setField(service, "realOrchestrator", CaptureOrchestrator(
            taker = ScreenshotTaker { error("capture must wait until the setup screen is closed") },
            store = CaptureStore.from(service),
            launcher = { null },
            onError = {},
            scope = scope,
            delayer = { dispatches++; awaitCancellation() },
        ))
        ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
    }

    @Test fun alreadyConnectedServiceClosesSetupAndDispatchesCapture() {
        connectService()
        val controller = Robolectric.buildActivity(SetupActivity::class.java).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(controller.get().isFinishing)
        scope.testScheduler.runCurrent()
        assertEquals(1, dispatches)
        controller.pause().stop().destroy()
    }

    @Test fun lateServiceConnectionResumesThePendingCaptureInsteadOfLeavingSetupVisible() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)

        connectService()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(controller.get().isFinishing)
        scope.testScheduler.runCurrent()
        assertEquals(1, dispatches)
        controller.pause().stop().destroy()
    }

    @Test fun settingsModeStaysOpenWhenTheServiceConnects() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java,
            Intent().putExtra(SetupActivity.EXTRA_PENDING_CAPTURE, false)).create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)

        connectService()
        shadowOf(Looper.getMainLooper()).idle()

        assertFalse(controller.get().isFinishing)
        scope.testScheduler.runCurrent()
        assertEquals(0, dispatches)
        controller.pause().stop().destroy()
    }

    @Test fun backgroundSetupDoesNotStartCaptureUntilTheUserReturns() {
        val controller = Robolectric.buildActivity(SetupActivity::class.java).create().start().resume()
        controller.pause().stop()
        shadowOf(Looper.getMainLooper()).idle()

        connectService()
        shadowOf(Looper.getMainLooper()).idle()
        scope.testScheduler.runCurrent()
        assertEquals(0, dispatches)

        controller.restart().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(controller.get().isFinishing)
        scope.testScheduler.runCurrent()
        assertEquals(1, dispatches)
        controller.pause().stop().destroy()
    }
}
