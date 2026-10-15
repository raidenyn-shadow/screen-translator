package com.raidenyn.screentranslator.lens

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensLauncherTest {
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val uri = Uri.parse("content://com.raidenyn.screentranslator.files/captures/1.png")

    @Test fun sharesScreenshotThroughTheDeviceVerifiedEntryPoint() {
        val intents = mutableListOf<Intent>()
        val launcher = LensLauncher(resolver, IntentStarter { intents.add(it) })

        assertEquals(true, launcher.launch(uri))
        val intent = intents.single()
        assertEquals("com.google.android.googlequicksearchbox", intent.component?.packageName)
        assertEquals("com.google.android.apps.search.lens.LensShareEntryPointActivity", intent.component?.className)
        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("image/png", intent.type)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun missingLensReportsFailureInsteadOfLaunchingUnrelatedFallbacks() {
        var attempts = 0
        val launcher = LensLauncher(resolver, IntentStarter {
            attempts++
            throw ActivityNotFoundException("missing Lens")
        })
        assertEquals(false, launcher.launch(uri))
        assertEquals(1, attempts)
    }

    @Test fun inaccessibleLensReportsFailure() {
        val launcher = LensLauncher(resolver, IntentStarter { throw SecurityException("not exported") })
        assertEquals(false, launcher.launch(uri))
    }
}
