package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.view.ViewGroup
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import com.raidenyn.screentranslator.service.ScreenCaptureService
import com.raidenyn.screentranslator.service.SessionBoot
import com.raidenyn.screentranslator.service.bootSession
import com.raidenyn.screentranslator.service.ScriptedEnvironment
import com.raidenyn.screentranslator.service.ScriptedSession
import com.raidenyn.screentranslator.service.driveCommand
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
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowLooper
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class SetupActivityTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val testScope = TestScope()
    private val activities = mutableListOf<ActivityController<out Activity>>()
    private var boot: SessionBoot? = null
    private var failedService: ServiceController<ScreenCaptureService>? = null

    @After fun clean() {
        activities.forEach { it.pause().stop().destroy() }
        boot?.controller?.destroy()
        boot = null
        failedService?.destroy()
        failedService = null
        testScope.cancel()
    }

    private fun isTranslucent(activity: Activity): Boolean =
        activity.theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent))
            .getBoolean(0, false)

    private fun contentChildren(activity: Activity): Int =
        activity.findViewById<ViewGroup>(android.R.id.content)!!.childCount

    private fun shadow(activity: Activity): ShadowActivity = Shadow.extract(activity) as ShadowActivity

    private fun setupActivity(pendingCapture: Boolean): ActivityController<SetupActivity> =
        Robolectric.buildActivity(
            SetupActivity::class.java,
            Intent(context, SetupActivity::class.java)
                .putExtra(SetupActivity.EXTRA_PENDING_CAPTURE, pendingCapture),
        ).setup().also { activities.add(it) }

    private fun pendingIntent(): Intent =
        Intent(context, SetupActivity::class.java)
            .putExtra(SetupActivity.EXTRA_PENDING_CAPTURE, true)

    /** The consent intent the activity launched, or an error describing its absence. */
    private fun launchedConsent(activity: Activity) =
        shadow(activity).nextStartedActivityForResult
            ?: error("no consent intent was launched")

    /** One granted consent result the activity has not consumed yet. */
    private fun consentData(): Intent =
        Intent().putExtra("media_projection_token", UUID.randomUUID().toString())

    private fun assertIdleOpaqueSettings(controller: ActivityController<out SetupActivity>) {
        ShadowLooper.idleMainLooper()
        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertFalse(isTranslucent(activity))
        assertTrue(contentChildren(activity) > 0)
        assertNull(shadow(activity).peekNextStartedActivityForResult())
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
    }

    private fun assertLaterStartDispatches(controller: ActivityController<out SetupActivity>) {
        controller.newIntent(pendingIntent())
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())
        Shadows.shadowOf(controller.get()).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        assertEquals(ScreenCaptureService.ACTION_START, Shadows.shadowOf(app).nextStartedService?.action)
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
    }

    private class ThrowingConsentIntentActivity : SetupActivity() {
        override fun consentIntent(): Intent {
            if (throwOnNextConsent) {
                throwOnNextConsent = false
                throw IllegalStateException("intent creation failed")
            }
            return Intent("test.consent")
        }

        companion object { var throwOnNextConsent = true }
    }

    private class ThrowingConsentLauncherActivity : SetupActivity() {
        override fun consentIntent() = Intent("test.consent")

        override fun launchConsent(intent: Intent) {
            if (throwOnNextLaunch) {
                throwOnNextLaunch = false
                throw IllegalStateException("launcher failed")
            }
            super.launchConsent(intent)
        }

        companion object { var throwOnNextLaunch = true }
    }

    private class ThrowingServiceStartActivity : SetupActivity() {
        override fun consentIntent() = Intent("test.consent")

        override fun startSession(resultCode: Int, data: Intent) {
            if (throwOnNextStart) {
                throwOnNextStart = false
                throw IllegalStateException("service dispatch failed")
            }
            super.startSession(resultCode, data)
        }

        companion object { var throwOnNextStart = true }
    }

    private class StateSavingSetupActivity : SetupActivity() {
        fun save(state: Bundle) = onSaveInstanceState(state)
        fun deliverConsentResult(resultCode: Int, data: Intent?) = consentResult(resultCode, data)
    }

    @Test fun settingsModeOpensWithoutConsent() {
        val controller = setupActivity(pendingCapture = false)
        ShadowLooper.idleMainLooper()

        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertFalse(isTranslucent(activity))
        assertTrue(contentChildren(activity) > 0)
        assertNull(shadow(activity).peekNextStartedActivityForResult())
    }

    @Test fun pendingCaptureWithoutSessionLaunchesOneConsentIntent() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()

        launchedConsent(controller.get())
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
        assertNull(shadow(controller.get()).peekNextStartedActivityForResult())
    }

    @Test fun duplicatePendingCaptureLaunchesNoSecondConsent() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        launchedConsent(controller.get())

        controller.newIntent(pendingIntent())
        ShadowLooper.idleMainLooper()

        assertNull(shadow(controller.get()).peekNextStartedActivityForResult())
    }

    @Test fun consentCancellationLeavesUsableSettingsWithoutRetry() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())

        Shadows.shadowOf(controller.get()).receiveResult(consent.intent, Activity.RESULT_CANCELED, null)
        ShadowLooper.idleMainLooper()

        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertTrue(contentChildren(activity) > 0)
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
        assertNull(shadow(activity).peekNextStartedActivityForResult())

        controller.newIntent(pendingIntent())
        ShadowLooper.idleMainLooper()
        assertNotNull(shadow(activity).peekNextStartedActivityForResult())
    }

    @Test fun nullConsentDataLeavesUsableSettings() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())

        Shadows.shadowOf(controller.get()).receiveResult(consent.intent, Activity.RESULT_OK, null)
        ShadowLooper.idleMainLooper()

        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertTrue(contentChildren(activity) > 0)
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
        assertNull(shadow(activity).peekNextStartedActivityForResult())
    }

    @Test fun api34ConsentUsesDefaultDisplayCaptureConfig() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())

        assertNotNull(consent.intent.extras?.keySet()?.map { consent.intent.extras!!.get(it) }
            ?.filterIsInstance<MediaProjectionConfig>()?.singleOrNull())
    }

    @Test fun consentSuccessStartsTheServiceOnceWhileVisible() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())
        val data = consentData()

        Shadows.shadowOf(controller.get()).receiveResult(consent.intent, Activity.RESULT_OK, data)
        ShadowLooper.idleMainLooper()

        val start = Shadows.shadowOf(app).nextStartedService ?: error("consent did not start the service")
        assertEquals(ScreenCaptureService.ACTION_START, start.action)
        assertEquals(Activity.RESULT_OK, start.getIntExtra(ScreenCaptureService.EXTRA_RESULT_CODE, -2))
        assertEquals(
            data,
            IntentCompat.getParcelableExtra(start, ScreenCaptureService.EXTRA_CONSENT_DATA, Intent::class.java),
        )
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
        assertNull(shadow(controller.get()).peekNextStartedActivityForResult())
    }

    @Test fun consentResultWhileStoppedIsForwardedOnceAfterResume() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())

        controller.pause().stop()
        Shadows.shadowOf(controller.get()).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        assertNull(Shadows.shadowOf(app).peekNextStartedService())

        controller.restart().start().resume()
        ShadowLooper.idleMainLooper()
        assertEquals(
            ScreenCaptureService.ACTION_START,
            Shadows.shadowOf(app).nextStartedService?.action ?: error("deferred grant was not forwarded"),
        )
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
    }

    @Test fun deferredConsentSurvivesSameProcessConfigurationRecreationAndStartsOnceAfterResume() {
        val controller = Robolectric.buildActivity(StateSavingSetupActivity::class.java, pendingIntent()).setup()
            .also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())

        controller.pause().stop()
        controller.get().deliverConsentResult(Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        assertNull(Shadows.shadowOf(app).peekNextStartedService())

        controller.recreate()
        ShadowLooper.idleMainLooper()

        controller.start().resume()
        ShadowLooper.idleMainLooper()
        assertEquals(Lifecycle.State.RESUMED, controller.get().lifecycle.currentState)
        assertEquals(ScreenCaptureService.ACTION_START, Shadows.shadowOf(app).nextStartedService?.action)
        assertNull(Shadows.shadowOf(app).peekNextStartedService())

        controller.recreate()
        ShadowLooper.idleMainLooper()
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
    }

    @Test fun stateOnlyProcessRecreationDiscardsDeferredConsentAndRequestsFreshConsent() {
        val original = Robolectric.buildActivity(StateSavingSetupActivity::class.java, pendingIntent()).setup()
            .also { activities.add(it) }
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(original.get())

        original.pause().stop()
        original.get().deliverConsentResult(Activity.RESULT_OK, consentData())
        val state = Bundle()
        original.get().save(state)
        original.destroy()
        activities.remove(original)

        val restored = Robolectric.buildActivity(StateSavingSetupActivity::class.java, pendingIntent()).create(state)
            .also { activities.add(it) }
        restored.start().resume()
        ShadowLooper.idleMainLooper()

        assertNull(Shadows.shadowOf(app).peekNextStartedService())
        assertNotNull(shadow(restored.get()).peekNextStartedActivityForResult())
    }

    @Test fun consentIntentFailureReturnsToSettingsAndAllowsAnotherStart() {
        ThrowingConsentIntentActivity.throwOnNextConsent = true
        val controller = Robolectric.buildActivity(ThrowingConsentIntentActivity::class.java, pendingIntent()).setup()
            .also { activities.add(it) }

        assertIdleOpaqueSettings(controller)
        assertLaterStartDispatches(controller)
    }

    @Test fun consentLauncherFailureReturnsToSettingsAndAllowsAnotherStart() {
        ThrowingConsentLauncherActivity.throwOnNextLaunch = true
        val controller = Robolectric.buildActivity(ThrowingConsentLauncherActivity::class.java, pendingIntent()).setup()
            .also { activities.add(it) }

        assertIdleOpaqueSettings(controller)
        assertLaterStartDispatches(controller)
    }

    @Test fun serviceStartFailureReturnsToSettingsAndAllowsAnotherStart() {
        ThrowingServiceStartActivity.throwOnNextStart = true
        val controller = Robolectric.buildActivity(ThrowingServiceStartActivity::class.java, pendingIntent()).setup()
            .also { activities.add(it) }
        val consent = launchedConsent(controller.get())
        Shadows.shadowOf(controller.get()).receiveResult(consent.intent, Activity.RESULT_OK, consentData())

        assertIdleOpaqueSettings(controller)
        assertLaterStartDispatches(controller)
    }

    @Test fun publishedStartupErrorReturnsToSettingsAndAllowsAnotherStart() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())
        Shadows.shadowOf(controller.get()).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        val start = Shadows.shadowOf(app).nextStartedService ?: error("consent did not dispatch service start")
        val session = ScriptedSession(ScriptedEnvironment()).apply { failAcquire = true }
        failedService = Robolectric.buildService(ScreenCaptureService::class.java).create().also {
            it.get().session = session
            it.get().scopeFactory = { testScope }
            driveCommand(it, start)
        }

        assertIdleOpaqueSettings(controller)
        assertLaterStartDispatches(controller)
    }

    @Test fun recreationDoesNotRelaunchConsent() {
        val controller = setupActivity(pendingCapture = true)
        ShadowLooper.idleMainLooper()
        val consent = launchedConsent(controller.get())

        controller.recreate()
        ShadowLooper.idleMainLooper()
        val reborn = controller.get()
        assertNull(shadow(reborn).peekNextStartedActivityForResult())

        Shadows.shadowOf(reborn).receiveResult(consent.intent, Activity.RESULT_OK, consentData())
        ShadowLooper.idleMainLooper()
        assertEquals(
            ScreenCaptureService.ACTION_START,
            Shadows.shadowOf(app).nextStartedService?.action ?: error("recreated instance dropped the grant"),
        )
        assertNull(Shadows.shadowOf(app).peekNextStartedService())
    }

    @Test fun settingsModeStaysOpenWhenAProjectionSessionStarts() {
        val controller = setupActivity(pendingCapture = false)
        ShadowLooper.idleMainLooper()

        boot = bootSession(testScope)
        ShadowLooper.idleMainLooper()

        val activity = controller.get()
        assertFalse(activity.isFinishing)
        assertTrue(contentChildren(activity) > 0)
        assertNull(shadow(activity).peekNextStartedActivityForResult())
    }
}
