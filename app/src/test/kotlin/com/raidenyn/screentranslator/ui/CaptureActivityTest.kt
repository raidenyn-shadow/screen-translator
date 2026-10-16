package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.capture.CAPTURE_DELAY_MS
import com.raidenyn.screentranslator.capture.CaptureResult
import com.raidenyn.screentranslator.service.SessionBoot
import com.raidenyn.screentranslator.service.ScreenCaptureService
import com.raidenyn.screentranslator.service.ScriptedEnvironment
import com.raidenyn.screentranslator.service.bootSession
import com.raidenyn.screentranslator.service.driveCommand
import com.raidenyn.screentranslator.service.advanceUntilPipelineIdle
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowInstrumentation
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class CaptureActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val testScope = TestScope()
    private val activities = mutableListOf<ActivityController<out Activity>>()
    private var boot: SessionBoot? = null

    @After fun clean() {
        activities.forEach { it.pause().stop().destroy() }
        boot?.controller?.destroy()
        boot = null
        testScope.cancel()
        disableActivityChecks()
    }

    /** A ready session against this test's scope, booted at most once. */
    private fun booted(): SessionBoot =
        boot ?: bootSession(testScope).also { boot = it }

    private fun isTranslucent(activity: Activity): Boolean =
        activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            .getBoolean(0, false)

    private fun contentChildren(activity: Activity): Int =
        activity.findViewById<ViewGroup>(android.R.id.content)!!.childCount

    private fun shadow(activity: Activity): ShadowActivity = Shadow.extract(activity) as ShadowActivity

    /** The content URI the session's store handed out for the single saved capture. */
    private fun savedCaptureUri(): Uri {
        val file = File(context.cacheDir, "captures").listFiles()!!.single()
        return Uri.parse("content://com.raidenyn.screentranslator.files/captures/${file.name}")
    }

    private fun sidebarIntent(): Intent =
        Intent(Intent.ACTION_MAIN).putExtra("from_package", "com.coloros.smartsidebar")

    /** A created, started and resumed capture activity for [requestId] against the booted session. */
    private fun resumedCapture(requestId: String): ActivityController<CaptureActivity> =
        Robolectric.buildActivity(CaptureActivity::class.java, CaptureActivity.intent(context, requestId))
            .create().start().resume().also { activities.add(it) }

    /**
     * Advances the capture delay so the in-flight request parks awaiting a frame, then
     * delivers one and waits for the pipeline to publish its saved result.
     */
    private fun completePendingCapture(session: SessionBoot) {
        testScope.testScheduler.advanceTimeBy(CAPTURE_DELAY_MS)
        testScope.testScheduler.runCurrent()
        session.environment.deliver()
        testScope.advanceUntilPipelineIdle()
        ShadowLooper.idleMainLooper()
    }

    private fun enableActivityChecks() {
        ReflectionHelpers.setField(
            Shadow.extract<ShadowInstrumentation>(InstrumentationRegistry.getInstrumentation()),
            "checkActivities",
            true,
        )
    }

    private fun disableActivityChecks() {
        runCatching {
            ReflectionHelpers.setField(
                Shadow.extract<ShadowInstrumentation>(InstrumentationRegistry.getInstrumentation()),
                "checkActivities",
                false,
            )
        }
    }

    @Test fun captureWindowIsTransparentWithoutSettingsContent() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, CaptureActivity::class.java), 0)
        assertFalse(info.exported)
        val capture = Robolectric.buildActivity(CaptureActivity::class.java, CaptureActivity.intent(context, "id"))
            .create().also { activities.add(it) }

        assertTrue(isTranslucent(capture.get()))
        assertEquals(0, contentChildren(capture.get()))
    }

    @Test fun warmSettingsRootBecomesTransparentBeforeCapture() {
        booted()
        val root = Robolectric.buildActivity(TrampolineActivity::class.java).setup().also { activities.add(it) }
        val settingsRoot = root.get()
        assertFalse(isTranslucent(settingsRoot))
        assertTrue(contentChildren(settingsRoot) > 0)

        root.newIntent(sidebarIntent())
        ShadowLooper.idleMainLooper()

        val captureRoot = root.get()
        assertNotSame(settingsRoot, captureRoot)
        assertTrue(isTranslucent(captureRoot))
        assertEquals(0, contentChildren(captureRoot))
        val launch = shadow(captureRoot).nextStartedActivityForResult ?: error("no capture child opened")
        assertEquals(ComponentName(context, CaptureActivity::class.java), launch.intent.component)
        UUID.fromString(launch.intent.getStringExtra(CaptureActivity.EXTRA_REQUEST_ID)!!)
        assertNull(shadow(captureRoot).peekNextStartedActivityForResult())

        captureRoot.recreate()
        ShadowLooper.idleMainLooper()
        assertNull(shadow(root.get()).peekNextStartedActivityForResult())
    }

    @Test fun screenshotHandoffRequestsFullscreenLens() {
        val session = booted()
        val capture = resumedCapture("fullscreen")

        completePendingCapture(session)

        val launch = shadow(capture.get()).nextStartedActivityForResult ?: error("no Lens handoff")
        assertEquals(1, launch.options?.getInt("android.activity.windowingMode"))
        assertEquals("com.google.android.apps.search.lens.LensShareEntryPointActivity", launch.intent.component?.className)
        assertEquals(savedCaptureUri(), IntentCompat.getParcelableExtra(launch.intent, Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(launch.intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(launch.intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(capture.get().isFinishing)
    }

    @Test fun pausedActivityDefersLensUntilResume() {
        val session = booted()
        val capture = resumedCapture("pausing")
        capture.pause()

        completePendingCapture(session)
        val uri = (session.service.requests.results.value["pausing"] as CaptureResult.Saved).uri
        assertNull(shadow(capture.get()).peekNextStartedActivity())

        capture.resume()
        ShadowLooper.idleMainLooper()
        val lens = shadow(capture.get()).nextStartedActivity ?: error("Lens was not started on resume")
        assertEquals(Intent.ACTION_SEND, lens.action)
        assertEquals(uri, IntentCompat.getParcelableExtra(lens, Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(capture.get().isFinishing)
        assertEquals(emptyMap<String, CaptureResult>(), session.service.requests.results.value)
    }

    @Test fun recreationDeliversSavedResultExactlyOnce() {
        val session = booted()
        resumedCapture("surviving").recreate()
        val reborn = activities.last().get()
        ShadowLooper.idleMainLooper()

        completePendingCapture(session)

        val lens = shadow(reborn).nextStartedActivity ?: error("no Lens handoff")
        assertEquals(Intent.ACTION_SEND, lens.action)
        assertEquals(savedCaptureUri(), IntentCompat.getParcelableExtra(lens, Intent.EXTRA_STREAM, Uri::class.java))
        assertNull(shadow(reborn).nextStartedActivity)
        assertTrue(reborn.isFinishing)
        assertEquals(emptyMap<String, CaptureResult>(), session.service.requests.results.value)
    }

    @Test fun newIconLaunchCancelsPendingCapture() {
        val session = booted()
        val root = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).setup()
            .also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        val launch = shadow(root.get()).nextStartedActivityForResult ?: error("no capture child")
        val child = Robolectric.buildActivity(CaptureActivity::class.java, launch.intent)
            .create().start().resume().also { activities.add(it) }
        shadow(child.get()).clearNextStartedActivities()
        testScope.testScheduler.advanceTimeBy(CAPTURE_DELAY_MS)
        testScope.testScheduler.runCurrent()

        root.newIntent(Intent(Intent.ACTION_MAIN))
        ShadowLooper.idleMainLooper()

        val settingsRoot = root.get()
        assertFalse(isTranslucent(settingsRoot))
        assertTrue(contentChildren(settingsRoot) > 0)

        session.environment.deliver()
        testScope.advanceUntilPipelineIdle()
        ShadowLooper.idleMainLooper()
        assertEquals(emptyMap<String, CaptureResult>(), session.service.requests.results.value)
        assertNull(shadow(child.get()).peekNextStartedActivity())
    }

    @Test fun duplicateTapsDoNotCreateAnotherCapture() {
        booted()
        val root = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).setup()
            .also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        val launch = shadow(root.get()).nextStartedActivityForResult ?: error("no capture child")
        val child = Robolectric.buildActivity(CaptureActivity::class.java, launch.intent)
            .create().start().resume().also { activities.add(it) }
        testScope.testScheduler.advanceTimeBy(CAPTURE_DELAY_MS)
        testScope.testScheduler.runCurrent()
        assertEquals(1, testScope.coroutineContext[Job]!!.children.count { it.isActive })

        root.newIntent(sidebarIntent())
        ShadowLooper.idleMainLooper()

        assertEquals(1, testScope.coroutineContext[Job]!!.children.count { it.isActive })
    }

    @Test fun missingSessionSendsTheCaptureActivityBackToSetup() {
        val capture = Robolectric.buildActivity(CaptureActivity::class.java, CaptureActivity.intent(context, "orphan"))
            .create().start().resume().also { activities.add(it) }
        ShadowLooper.idleMainLooper()

        val orphan = capture.get()
        assertTrue(orphan.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow(orphan).resultCode)
        assertNull(shadow(orphan).peekNextStartedActivity())
    }

    @Test fun readinessLossDoesNotHangTheActivity() {
        val session = booted()
        val capture = resumedCapture("stopping")
        testScope.testScheduler.advanceTimeBy(CAPTURE_DELAY_MS)
        testScope.testScheduler.runCurrent()

        ScreenCaptureService.stop(context)
        driveCommand(session.controller, Shadows.shadowOf(app).nextStartedService)
        ShadowLooper.idleMainLooper()

        val orphaned = capture.get()
        assertTrue(orphaned.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow(orphaned).resultCode)

        session.environment.deliver()
        testScope.advanceUntilPipelineIdle()
        ShadowLooper.idleMainLooper()
        assertNull(shadow(orphaned).peekNextStartedActivity())
    }

    @Test fun lensMissingShowsLaunchFailed() {
        enableActivityChecks()
        val session = booted()
        val capture = resumedCapture("nolens")

        completePendingCapture(session)

        assertEquals(context.getString(R.string.error_launch_failed), ShadowToast.getTextOfLatestToast())
        val failed = capture.get()
        assertTrue(failed.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow(failed).resultCode)
        assertEquals(emptyMap<String, CaptureResult>(), session.service.requests.results.value)
    }
}
