package com.raidenyn.screentranslator.service

import android.Manifest
import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.capture.FrameImage
import com.raidenyn.screentranslator.capture.FrameReader
import com.raidenyn.screentranslator.capture.ProjectionEnvironment
import com.raidenyn.screentranslator.capture.ProjectionEvents
import com.raidenyn.screentranslator.capture.ProjectionFrameSource
import com.raidenyn.screentranslator.capture.ScreenGeometry
import com.raidenyn.screentranslator.capture.VirtualScreen
import com.raidenyn.screentranslator.ui.TrampolineActivity
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
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ScreenCaptureServiceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ServiceController<ScreenCaptureService>? = null

    @After fun clean() {
        controller?.destroy()
        controller = null
    }

    /**
     * Environment fake behind the real [ProjectionFrameSource] the scripted session hands the
     * service. The inline executor keeps the source's startup synchronous, so the recorded
     * events prove the projection callback registered before the display was created, and an
     * empty event list on a failed start proves no reader or display leaked.
     */
    private class RecordingEnvironment : ProjectionEnvironment {
        val events = mutableListOf<String>()
        var released = 0
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
        }

        override fun release() {
            released++
        }
    }

    /**
     * Session seam scripting the framework calls the service makes. The recorded calls prove
     * foreground promotion precedes projection acquisition, and the scripted failures route
     * the service's startup-error handling without mocking the framework.
     */
    private class ScriptedSession(private val environment: RecordingEnvironment) : ProjectionSession {
        val calls = mutableListOf<String>()
        var failForeground = false
        var failAcquire = false
        var refuseProjection = false

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
            return ProjectionFrameSource(environment, onStopped = onStopped)
        }
    }

    /** A created service scripted by [session]; start intents are supplied by the caller. */
    private fun newService(session: ScriptedSession): ServiceController<ScreenCaptureService> =
        Robolectric.buildService(ScreenCaptureService::class.java).create().also {
            it.get().session = session
            controller = it
        }

    /** The start intent the companion really sends, captured from the started-service queue. */
    private fun capturedStartIntent(resultCode: Int, data: Intent): Intent {
        ScreenCaptureService.start(context, resultCode, data)
        return Shadows.shadowOf(app).nextStartedService
    }

    @Test fun manifestDeclaresMediaProjectionForegroundServiceWithoutAccessibility() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS or PackageManager.GET_SERVICES,
        )
        val requested = info.requestedPermissions!!.toList()
        assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE))
        assertTrue(requested.contains(Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION))
        assertFalse(requested.contains(Manifest.permission.BIND_ACCESSIBILITY_SERVICE))
        val service = info.services!!.single()
        assertEquals(ScreenCaptureService::class.java.name, service.name)
        assertFalse(service.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION, service.foregroundServiceType)
    }

    @Test fun missingConsentDataIsNotStickyAndNeverAcquires() {
        val session = ScriptedSession(RecordingEnvironment())
        val service = newService(session).get()
        val bare = Intent(app, ScreenCaptureService::class.java)
            .setAction(ScreenCaptureService.ACTION_START)

        assertEquals(Service.START_NOT_STICKY, service.handleCommand(bare))

        assertEquals(listOf("foreground"), session.calls)
        assertNull(ScreenCaptureService.connection.value)
        assertEquals(context.getString(R.string.error_session_failed), ScreenCaptureService.startupError.value)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test fun failedConsentResultNeverAcquiresAProjection() {
        val session = ScriptedSession(RecordingEnvironment())
        val service = newService(session).get()
        val refused = capturedStartIntent(Activity.RESULT_CANCELED, Intent())

        assertEquals(Service.START_NOT_STICKY, service.handleCommand(refused))

        assertEquals(listOf("foreground"), session.calls)
        assertNull(ScreenCaptureService.connection.value)
        assertEquals(context.getString(R.string.error_session_failed), ScreenCaptureService.startupError.value)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test fun foregroundPromotionPrecedesProjectionAndCallbackPrecedesDisplay() {
        val environment = RecordingEnvironment()
        val session = ScriptedSession(environment)
        val started = newService(session)
        started.get().handleCommand(capturedStartIntent(Activity.RESULT_OK, Intent()))

        assertEquals(listOf("foreground", "acquire"), session.calls)
        assertEquals(listOf("register", "reader", "display"), environment.events)
        assertSame(started.get(), ScreenCaptureService.connection.value)
    }

    @Test fun foregroundPromotionFailureIsTerminalWithoutLeakedResources() {
        val environment = RecordingEnvironment()
        val session = ScriptedSession(environment).apply { failForeground = true }
        val service = newService(session).get()

        assertEquals(Service.START_NOT_STICKY, service.handleCommand(capturedStartIntent(Activity.RESULT_OK, Intent())))

        assertEquals(listOf("foreground"), session.calls)
        assertNull(ScreenCaptureService.connection.value)
        assertEquals(context.getString(R.string.error_session_failed), ScreenCaptureService.startupError.value)
        assertEquals(emptyList<String>(), environment.events)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
        assertFalse(Shadows.shadowOf(service).isLastForegroundNotificationAttached)
    }

    @Test fun projectionAcquisitionFailureIsTerminalWithoutLeakedResources() {
        val environment = RecordingEnvironment()
        val session = ScriptedSession(environment).apply { failAcquire = true }
        val service = newService(session).get()

        assertEquals(Service.START_NOT_STICKY, service.handleCommand(capturedStartIntent(Activity.RESULT_OK, Intent())))

        assertEquals(listOf("foreground", "acquire"), session.calls)
        assertNull(ScreenCaptureService.connection.value)
        assertEquals(context.getString(R.string.error_session_failed), ScreenCaptureService.startupError.value)
        assertEquals(emptyList<String>(), environment.events)
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf)
    }

    @Test fun notificationCarriesSettingsAndPlainServiceStopControls() {
        val service = Robolectric.buildService(ScreenCaptureService::class.java).create().get()

        MediaProjectionSession.startForeground(service)

        val channel = service.getSystemService(NotificationManager::class.java)!!.notificationChannels.single()
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        val shadow = Shadows.shadowOf(service)
        assertEquals(
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
            ReflectionHelpers.getField<Int>(shadow, "foregroundServiceType"),
        )
        val notification = shadow.lastForegroundNotification
        assertEquals(R.drawable.ic_capture_notification, notification.smallIcon.resId)
        assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val settings = Shadows.shadowOf(notification.contentIntent!!)
        assertTrue(settings.isActivity)
        assertEquals(
            ComponentName(service, TrampolineActivity::class.java),
            settings.savedIntent.component,
        )
        val stop = Shadows.shadowOf(notification.actions.single().actionIntent)
        assertTrue(stop.isService)
        assertFalse(stop.isForegroundService)
        assertEquals(ScreenCaptureService.ACTION_STOP, stop.savedIntent.action)
    }

    @Test fun productionSessionRefusesTokenlessConsent() {
        assertNull(MediaProjectionSession.acquire(context, Activity.RESULT_CANCELED, Intent()) {})
        assertNull(MediaProjectionSession.acquire(context, Activity.RESULT_OK, Intent()) {})
    }
}