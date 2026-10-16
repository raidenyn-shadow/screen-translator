package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowAppTask
import org.robolectric.shadows.ShadowLooper
import org.robolectric.shadows.ShadowToast
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
            .create().start().resume().also {
                activities.add(it)
                val task = appTask(it.get().taskId)
                Shadows.shadowOf(it.get().getSystemService(ActivityManager::class.java)).setAppTasks(listOf(task))
            }

    private fun appTask(taskId: Int): ActivityManager.AppTask = ShadowAppTask.newInstance().also {
        Shadows.shadowOf(it).setTaskInfo(ActivityManager.RecentTaskInfo().apply { this.taskId = taskId })
    }

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

    @Test
    @Config(shadows = [OrderedAppTaskShadow::class])
    fun screenshotHandoffRemovesOnlyItsEntireTaskBeforeStartingTheTranslateRelay() {
        val session = booted()
        val capture = resumedCapture("fullscreen")
        val manager = capture.get().getSystemService(ActivityManager::class.java)
        val captureTask = manager.appTasks.single()
        val unrelatedTask = appTask(capture.get().taskId + 1)
        Shadows.shadowOf(manager).setAppTasks(listOf(unrelatedTask, captureTask))

        completePendingCapture(session)

        val launch = Shadows.shadowOf(app).nextStartedActivity ?: error("no relay handoff")
        val removedTask = Shadow.extract<OrderedAppTaskShadow>(captureTask)
        assertTrue(removedTask.isFinishedAndRemoved)
        assertNull(removedTask.activityAtRemoval)
        assertFalse(Shadows.shadowOf(unrelatedTask).isFinishedAndRemoved)
        assertEquals(ComponentName(context, TranslateRelayActivity::class.java), launch.component)
        assertEquals(
            savedCaptureUri(),
            IntentCompat.getParcelableExtra(launch, TranslateRelayActivity.EXTRA_IMAGE_URI, Uri::class.java),
        )
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(capture.get().isFinishing)
    }

    /** Observes task-removal ordering without replacing the actual Lens dispatch. */
    @Implements(ActivityManager.AppTask::class)
    class OrderedAppTaskShadow : ShadowAppTask() {
        var activityAtRemoval: Intent? = null

        @Implementation
        public override fun finishAndRemoveTask() {
            activityAtRemoval = Shadows.shadowOf(ApplicationProvider.getApplicationContext<Application>())
                .peekNextStartedActivity()
            super.finishAndRemoveTask()
        }
    }

    @Test fun missingCaptureTaskReportsFailureWithoutLaunchingLens() {
        val session = booted()
        val capture = resumedCapture("missing-task")
        Shadows.shadowOf(capture.get().getSystemService(ActivityManager::class.java)).setAppTasks(emptyList())

        completePendingCapture(session)

        assertNull(shadow(capture.get()).peekNextStartedActivity())
        assertEquals(context.getString(R.string.error_launch_failed), ShadowToast.getTextOfLatestToast())
        assertEquals(Activity.RESULT_CANCELED, shadow(capture.get()).resultCode)
        assertEquals(emptyMap<String, CaptureResult>(), session.service.requests.results.value)
    }

    @Test fun pausedActivityDefersRelayUntilResume() {
        val session = booted()
        val capture = resumedCapture("pausing")
        capture.pause()

        completePendingCapture(session)
        val uri = (session.service.requests.results.value["pausing"] as CaptureResult.Saved).uri
        assertNull(shadow(capture.get()).peekNextStartedActivity())

        capture.resume()
        ShadowLooper.idleMainLooper()
        val relay = shadow(capture.get()).nextStartedActivity ?: error("relay was not started on resume")
        assertEquals(ComponentName(context, TranslateRelayActivity::class.java), relay.component)
        assertEquals(uri, IntentCompat.getParcelableExtra(relay, TranslateRelayActivity.EXTRA_IMAGE_URI, Uri::class.java))
        assertTrue(capture.get().isFinishing)
        assertEquals(emptyMap<String, CaptureResult>(), session.service.requests.results.value)
    }

    @Test fun recreationDeliversSavedResultExactlyOnce() {
        val session = booted()
        resumedCapture("surviving").recreate()
        val reborn = activities.last().get()
        ShadowLooper.idleMainLooper()

        completePendingCapture(session)

        val relay = shadow(reborn).nextStartedActivity ?: error("no relay handoff")
        assertEquals(ComponentName(context, TranslateRelayActivity::class.java), relay.component)
        assertEquals(
            savedCaptureUri(),
            IntentCompat.getParcelableExtra(relay, TranslateRelayActivity.EXTRA_IMAGE_URI, Uri::class.java),
        )
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

}
