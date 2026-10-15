package com.raidenyn.screentranslator.service

import android.content.Intent
import com.raidenyn.screentranslator.capture.CaptureOrchestrator
import com.raidenyn.screentranslator.capture.CaptureStore
import com.raidenyn.screentranslator.capture.ScreenshotTaker
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ServiceConnectionTest {
    @Test fun reconnectAcceptsOneCaptureBeforeOldCancellationCleanupRuns() {
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        val scope = TestScope()
        val service = controller.get()
        ReflectionHelpers.setField(service, "scope", scope)
        ReflectionHelpers.setField(service, "realOrchestrator", CaptureOrchestrator(
            taker = ScreenshotTaker { error("capture must still be waiting") },
            store = CaptureStore.from(service),
            launcher = { null },
            onError = {},
            scope = scope,
            delayer = { awaitCancellation() },
        ))
        try {
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            assertTrue(ScreenCaptureService.requestCapture())
            scope.testScheduler.runCurrent()
            assertFalse(ScreenCaptureService.requestCapture())

            ReflectionHelpers.callInstanceMethod<Boolean>(service, "onUnbind",
                ClassParameter.from(Intent::class.java, Intent()))
            // Deliberately do not advance the scheduler: the canceled pipeline
            // has not run finally and still owns its old in-flight guard.
            ReflectionHelpers.callInstanceMethod<Unit>(service, "onServiceConnected")
            assertTrue(ScreenCaptureService.requestCapture())
            assertFalse(ScreenCaptureService.requestCapture())
        } finally {
            controller.destroy()
            scope.testScheduler.runCurrent()
        }
    }

    @Test fun serviceIsAvailableOnlyAfterAndroidConnectsIt() {
        val controller = Robolectric.buildService(ScreenCaptureService::class.java).create()
        try {
            assertNull(ScreenCaptureService.instance)
            assertFalse(ScreenCaptureService.requestCapture())
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "onServiceConnected")
            assertSame(controller.get(), ScreenCaptureService.instance)
            ReflectionHelpers.callInstanceMethod<Boolean>(controller.get(), "onUnbind",
                ClassParameter.from(Intent::class.java, Intent()))
            assertNull(ScreenCaptureService.instance)
            assertFalse(ScreenCaptureService.requestCapture())
            ReflectionHelpers.callInstanceMethod<Unit>(controller.get(), "onServiceConnected")
            assertSame(controller.get(), ScreenCaptureService.instance)
        } finally {
            controller.destroy()
        }
        assertNull(ScreenCaptureService.instance)
    }
}
