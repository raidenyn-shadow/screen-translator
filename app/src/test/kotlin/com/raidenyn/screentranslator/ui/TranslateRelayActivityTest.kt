package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.lens.EXTRA_CALLER_PACKAGE
import com.raidenyn.screentranslator.lens.EXTRA_INJECTED_IMAGE_URI
import com.raidenyn.screentranslator.lens.EXTRA_OPEN_FILTER
import com.raidenyn.screentranslator.lens.LENS_EXPORTED_ACTIVITY
import org.junit.Assert.assertEquals
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
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class TranslateRelayActivityTest {

    private val uri = Uri.parse("content://com.raidenyn.screentranslator.files/captures/1.png")

    private fun relay(intent: Intent): ActivityController<TranslateRelayActivity> =
        Robolectric.buildActivity(TranslateRelayActivity::class.java, intent)
            .create().start().resume().visible()

    private fun translateIntent(): Intent =
        Intent(Intent.ACTION_MAIN).putExtra(TranslateRelayActivity.EXTRA_IMAGE_URI, uri)

    private fun translateIntentWithoutUri(): Intent = Intent(Intent.ACTION_MAIN)

    private fun shadow(activity: Activity): ShadowActivity = Shadows.shadowOf(activity)

    @Test fun forwardsTheScreenshotToTheExportedLensComponentForResult() {
        val controller = relay(translateIntent())
        val relayed = shadow(controller.get()).nextStartedActivityForResult ?: error("no Lens request")
        val intent = relayed.intent
        assertEquals(
            ComponentName("com.google.android.googlequicksearchbox", LENS_EXPORTED_ACTIVITY),
            intent.component,
        )
        assertNull(intent.data)
        assertEquals(uri, IntentCompat.getParcelableExtra(intent, EXTRA_INJECTED_IMAGE_URI, Uri::class.java))
        assertEquals("translate", intent.getStringExtra(EXTRA_OPEN_FILTER))
        assertEquals("com.raidenyn.screentranslator", intent.getStringExtra(EXTRA_CALLER_PACKAGE))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0)
        assertTrue(relayed.requestCode > 0)
    }

    @Test fun missingUriFinishesCanceledWithoutLaunching() {
        val controller = relay(translateIntentWithoutUri())
        val relayed = shadow(controller.get())
        assertNull(relayed.nextStartedActivityForResult)
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, relayed.resultCode)
    }

    @Test fun missingLensFinishesCanceledWithFailureToast() {
        val controller = Robolectric.buildActivity(TranslateRelayActivity::class.java, translateIntent())
        controller.get().requestLauncher = { _, _ -> throw android.content.ActivityNotFoundException("missing Lens") }
        controller.create().start().resume().visible()
        val relayed = shadow(controller.get())
        assertNull(relayed.nextStartedActivityForResult)
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, relayed.resultCode)
        assertEquals(controller.get().getString(R.string.error_launch_failed), ShadowToast.getTextOfLatestToast())
    }

    @Test fun lensResultIsPropagatedAndRelayFinishes() {
        val controller = relay(translateIntent())
        val relayed = shadow(controller.get()).nextStartedActivityForResult ?: error("no Lens request")
        shadow(controller.get()).receiveResult(relayed.intent, Activity.RESULT_OK, null)
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_OK, shadow(controller.get()).resultCode)
    }

    @Test fun recreationWhileWaitingForLensDoesNotLaunchItAgain() {
        val controller = relay(translateIntent())
        shadow(controller.get()).nextStartedActivityForResult ?: error("no Lens request")
        shadow(controller.get()).clearNextStartedActivities()

        controller.recreate()

        assertNull(shadow(controller.get()).nextStartedActivityForResult)
    }

    @Test fun lensRejectionIsPropagatedAsCanceledResult() {
        val controller = relay(translateIntent())
        val relayed = shadow(controller.get()).nextStartedActivityForResult ?: error("no Lens request")
        shadow(controller.get()).receiveResult(relayed.intent, 1, null)
        assertTrue(controller.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadow(controller.get()).resultCode)
    }
}
