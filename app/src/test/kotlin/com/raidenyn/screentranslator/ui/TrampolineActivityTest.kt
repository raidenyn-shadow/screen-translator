package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.capture.CaptureResult
import com.raidenyn.screentranslator.service.ScreenCaptureService
import com.raidenyn.screentranslator.service.SessionBoot
import com.raidenyn.screentranslator.service.bootFrom
import com.raidenyn.screentranslator.service.bootSession
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import org.robolectric.shadows.ShadowAppTask
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TrampolineActivityTest {

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

    private fun isTranslucent(activity: Activity): Boolean =
        activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            .getBoolean(0, false)

    private fun contentChildren(activity: Activity): Int =
        activity.findViewById<ViewGroup>(android.R.id.content)!!.childCount

    private fun shadow(activity: Activity): ShadowActivity = Shadow.extract(activity) as ShadowActivity

    private fun sidebarIntent(): Intent =
        Intent(Intent.ACTION_MAIN).putExtra("from_package", "com.coloros.smartsidebar")

    private fun consentData(): Intent =
        Intent().putExtra("media_projection_token", UUID.randomUUID().toString())

    private fun launchedConsent(activity: Activity) =
        shadow(activity).nextStartedActivityForResult
            ?: error("no consent intent was launched")

    @Test fun iconLaunchOpensSettingsWithoutConsent() {
        val root = Robolectric.buildActivity(TrampolineActivity::class.java).setup().also { activities.add(it) }
        ShadowLooper.idleMainLooper()

        val activity = root.get()
        assertFalse(activity.isFinishing)
        assertNull(shadow(activity).peekNextStartedActivityForResult())
        assertTrue(contentChildren(activity) > 0)
    }

    @Test fun sidebarTapWithoutSessionLaunchesOneConsentIntent() {
        val root = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).setup()
            .also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        launchedConsent(root.get())
        assertNull(Shadows.shadowOf(app).peekNextStartedService())

        root.newIntent(sidebarIntent())
        ShadowLooper.idleMainLooper()
        assertNull(shadow(root.get()).peekNextStartedActivityForResult())
    }

    @Test fun sidebarTapWithLiveSessionSubmitsTranslationAndClosesItsTask() {
        boot = bootSession(testScope)
        val root = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).setup()
            .also { activities.add(it) }
        val task = ShadowAppTask.newInstance().also {
            Shadows.shadowOf(it).setTaskInfo(ActivityManager.RecentTaskInfo().apply { taskId = root.get().taskId })
        }
        Shadows.shadowOf(root.get().getSystemService(ActivityManager::class.java)).setAppTasks(listOf(task))
        root.pause().resume()
        ShadowLooper.idleMainLooper()

        val captureRoot = root.get()
        assertTrue(isTranslucent(captureRoot))
        assertEquals(ScreenCaptureService.ACTION_TRANSLATE, Shadows.shadowOf(app).nextStartedService?.action)
        assertTrue(Shadow.extract<ShadowAppTask>(task).isFinishedAndRemoved)
        assertNull(shadow(captureRoot).peekNextStartedActivityForResult())
    }

    @Test fun iconIntentSupersedesSidebarBeforeConsentReturns() {
        val root = Robolectric.buildActivity(TrampolineActivity::class.java, sidebarIntent()).setup()
            .also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(root.get())

        root.newIntent(Intent(Intent.ACTION_MAIN))
        ShadowLooper.idleMainLooper()
        val superseding = root.get()
        assertTrue(contentChildren(superseding) > 0)

        Shadows.shadowOf(superseding).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        val start = Shadows.shadowOf(app).nextStartedService ?: error("the granted consent was not forwarded")
        assertEquals(ScreenCaptureService.ACTION_START, start.action)

        boot = bootFrom(start, testScope)
        ShadowLooper.idleMainLooper()

        val settings = root.get()
        assertNull(shadow(settings).peekNextStartedActivityForResult())
        assertTrue(contentChildren(settings) > 0)
        assertEquals(emptyMap<String, CaptureResult>(), boot!!.service.requests.results.value)
    }
}
