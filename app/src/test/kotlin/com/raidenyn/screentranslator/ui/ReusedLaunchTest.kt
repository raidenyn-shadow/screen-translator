package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Bundle
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.service.ScreenCaptureService
import com.raidenyn.screentranslator.service.SessionBoot
import com.raidenyn.screentranslator.service.bootFrom
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class ReusedLaunchTest {

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

    private fun sidebar() = Intent(Intent.ACTION_MAIN)
        .putExtra("from_package", "com.coloros.smartsidebar")

    private fun icon() = Intent(Intent.ACTION_MAIN)

    private fun consentData(): Intent =
        Intent().putExtra("media_projection_token", UUID.randomUUID().toString())

    private fun isTranslucent(activity: Activity): Boolean =
        activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            .getBoolean(0, false)

    private fun contentChildren(activity: Activity): Int =
        activity.findViewById<ViewGroup>(android.R.id.content)!!.childCount

    private fun shadow(activity: Activity): ShadowActivity = Shadow.extract(activity) as ShadowActivity

    private fun launchedConsent(activity: Activity) =
        shadow(activity).nextStartedActivityForResult
            ?: error("no consent intent was launched")

    @Test fun launcherRemainsTheTaskRootAndReceivesNewLaunches() {
        val info = context.packageManager.getActivityInfo(ComponentName(context, TrampolineActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        assertEquals(0, info.flags and ActivityInfo.FLAG_NO_HISTORY)
    }

    @Test fun sidebarIntentToExistingSettingsRequestsConsentOnTheSameActivity() {
        val root = Robolectric.buildActivity(TrampolineActivity::class.java).setup().also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        val original = root.get()
        assertFalse(original.isFinishing)

        root.newIntent(sidebar())
        ShadowLooper.idleMainLooper()

        assertSame(original, root.get())
        launchedConsent(original)
        assertFalse(root.get().isFinishing)
    }

    @Test fun sidebarIntentToExistingSettingsWaitsForProjectionReadiness() {
        val root = Robolectric.buildActivity(TrampolineActivity::class.java).setup().also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        root.newIntent(sidebar())
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(root.get())

        Shadows.shadowOf(root.get()).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        val start = Shadows.shadowOf(app).nextStartedService ?: error("consent did not start the service")
        boot = bootFrom(start, testScope)
        ShadowLooper.idleMainLooper()

        val captureRoot = root.get()
        assertTrue(isTranslucent(captureRoot))
        assertEquals(0, contentChildren(captureRoot))
        assertEquals(ScreenCaptureService.ACTION_TRANSLATE, Shadows.shadowOf(app).nextStartedService?.action)
        assertNull(shadow(captureRoot).peekNextStartedActivityForResult())
    }

    @Test fun iconIntentCancelsAnOlderPendingSidebarCaptureAcrossRecreation() {
        val root = Robolectric.buildActivity(TrampolineActivity::class.java).setup().also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        root.newIntent(sidebar())
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(root.get())

        root.newIntent(icon())
        ShadowLooper.idleMainLooper()
        val settings = root.get()
        assertTrue(contentChildren(settings) > 0)

        Shadows.shadowOf(settings).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        val start = Shadows.shadowOf(app).nextStartedService ?: error("the granted consent was not forwarded")
        boot = bootFrom(start, testScope)
        ShadowLooper.idleMainLooper()

        assertNull(shadow(root.get()).peekNextStartedActivityForResult())
        assertTrue(contentChildren(root.get()) > 0)
    }

    @Test fun processRestorationDiscardsOldConsentUntilANewSidebarRequest() {
        val original = Robolectric.buildActivity(TrampolineActivity::class.java, sidebar()).setup()
            .also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(original.get())

        val state = Bundle()
        original.pause().saveInstanceState(state).stop().destroy()
        activities.remove(original)
        val restored = Robolectric.buildActivity(TrampolineActivity::class.java, sidebar())
            .create(state).also { activities.add(it) }

        restored.newIntent(icon()).start().resume()
        ShadowLooper.idleMainLooper()
        assertNull(shadow(restored.get()).peekNextStartedActivityForResult())

        Shadows.shadowOf(restored.get()).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        assertNull(Shadows.shadowOf(app).peekNextStartedService())

        restored.newIntent(sidebar())
        ShadowLooper.idleMainLooper()
        val freshConsent = launchedConsent(restored.get())
        Shadows.shadowOf(restored.get()).receiveResult(freshConsent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        val start = Shadows.shadowOf(app).nextStartedService ?: error("the fresh consent was not forwarded")
        assertEquals(ScreenCaptureService.ACTION_START, start.action)
    }
}
