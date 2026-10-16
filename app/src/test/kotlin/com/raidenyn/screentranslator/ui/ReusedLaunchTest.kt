package com.raidenyn.screentranslator.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
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
class ReusedLaunchTest {
    private val scope = TestScope()
    private var service: ServiceController<ScreenCaptureService>? = null
    private var dispatches = 0

    @After fun clean() {
        service?.destroy()
        scope.cancel()
    }

    private fun sidebar() = Intent(Intent.ACTION_MAIN)
        .putExtra("from_package", "com.coloros.smartsidebar")

    private fun connect() {
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        service = controller
        val instance = controller.get()
        ReflectionHelpers.setField(instance, "realOrchestrator", CaptureOrchestrator(
            taker = ScreenshotTaker { error("capture must wait for the settings UI to close") },
            store = CaptureStore.from(instance), launcher = { null }, onError = {}, scope = scope,
            delayer = { dispatches++; awaitCancellation() },
        ))
        ReflectionHelpers.callInstanceMethod<Unit>(instance, "onServiceConnected")
    }

    @Test fun launcherRemainsTheTaskRootAndReceivesNewLaunches() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info = context.packageManager.getActivityInfo(ComponentName(context, TrampolineActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals(0, info.flags and ActivityInfo.FLAG_NO_HISTORY)
    }

    @Test fun sidebarIntentToExistingSettingsDispatchesAndClosesTheSameActivity() {
        connect()
        val controller = Robolectric.buildActivity(TrampolineActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)
        assertEquals(0, dispatches)

        controller.newIntent(sidebar())
        shadowOf(Looper.getMainLooper()).idle()
        scope.testScheduler.runCurrent()
        assertTrue(controller.get().isFinishing)
        assertEquals(1, dispatches)
        controller.pause().stop().destroy()
    }

    @Test fun sidebarIntentToExistingSettingsWaitsForAServiceConnection() {
        val controller = Robolectric.buildActivity(TrampolineActivity::class.java).setup()
        controller.newIntent(sidebar())
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(controller.get().isFinishing)

        connect()
        shadowOf(Looper.getMainLooper()).idle()
        scope.testScheduler.runCurrent()
        assertTrue(controller.get().isFinishing)
        assertEquals(1, dispatches)
        controller.pause().stop().destroy()
    }

    @Test fun iconIntentCancelsAnOlderPendingSidebarCaptureAcrossRecreation() {
        val controller = Robolectric.buildActivity(TrampolineActivity::class.java, sidebar()).setup()
        controller.newIntent(Intent(Intent.ACTION_MAIN))
        controller.recreate()
        connect()
        shadowOf(Looper.getMainLooper()).idle()
        scope.testScheduler.runCurrent()
        assertFalse(controller.get().isFinishing)
        assertEquals(0, dispatches)
        controller.pause().stop().destroy()
    }

    @Test fun restoredPendingCaptureWaitsForANewerIconIntentBeforeResume() {
        val original = Robolectric.buildActivity(TrampolineActivity::class.java, sidebar()).setup()
        val state = Bundle()
        original.pause().saveInstanceState(state).stop().destroy()
        connect()

        val restored = Robolectric.buildActivity(TrampolineActivity::class.java, sidebar()).create(state)
        assertFalse(restored.get().isFinishing)
        restored.newIntent(Intent(Intent.ACTION_MAIN)).start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        scope.testScheduler.runCurrent()
        assertFalse(restored.get().isFinishing)
        assertEquals(0, dispatches)
        restored.pause().stop().destroy()
    }
}
