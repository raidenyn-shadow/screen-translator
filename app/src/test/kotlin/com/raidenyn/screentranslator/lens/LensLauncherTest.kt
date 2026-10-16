package com.raidenyn.screentranslator.lens

import android.content.Intent
import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensLauncherTest {
    private val uri = Uri.parse("content://com.raidenyn.screentranslator.files/captures/1.png")

    @Test fun translateRequestMatchesTheOxygenosDirectLensContract() {
        val intent = translateRequest(uri)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("com.google.android.googlequicksearchbox", intent.`package`)
        assertEquals(uri, intent.data?.getQueryParameter("image_uri")?.let(Uri::parse))
        assertEquals("translate", intent.data?.getQueryParameter("filter"))
        assertEquals("google", intent.data?.scheme)
        assertEquals("lens", intent.data?.host)
        // The OEM contract launches for a result without forcing a new task.
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK == 0)
    }

    @Test fun fullscreenOptionsRequestFullscreenWindowing() {
        assertEquals(1, fullscreenOptions().getInt("android.activity.windowingMode"))
    }
}
