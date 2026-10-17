package com.raidenyn.screentranslator.lens

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensLauncherTest {
    private val uri = Uri.parse("content://com.raidenyn.screentranslator.files/captures/1.png")
    private val caller = "com.raidenyn.screentranslator"

    @Test fun translateRequestTargetsTheExportedLensComponentWithTranslateFilter() {
        val intent = translateRequest(uri, caller)
        // The explicit component parses as integration mode 2, which skips the
        // direct-intent caller-eligibility gate that rejected the OEM-style
        // google://lens data-URI request on the installed Google build.
        assertEquals(
            ComponentName("com.google.android.googlequicksearchbox", LENS_EXPORTED_ACTIVITY),
            intent.component,
        )
        assertNull(intent.data)
        assertEquals(uri, IntentCompat.getParcelableExtra(intent, EXTRA_INJECTED_IMAGE_URI, Uri::class.java))
        assertEquals("translate", intent.getStringExtra(EXTRA_OPEN_FILTER))
        assertEquals(caller, intent.getStringExtra(EXTRA_CALLER_PACKAGE))
        // The request carries the screenshot grant itself: the context-API grant
        // proved ineffective on device (Lens was denied reading the provider), while
        // the user-verified share flow granted access through these intent means.
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        // The installed build reaches the image/filter parsing only when the
        // history bit is set; without it LensExportedActivity crashes with
        // "Launch mode only supported for shortcuts." before reading extras.
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0)
        // The request still launches for a result without forcing a new task.
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0)
    }

    @Test fun fullscreenOptionsRequestFullscreenWindowing() {
        assertEquals(1, fullscreenOptions().getInt("android.activity.windowingMode"))
    }
}
