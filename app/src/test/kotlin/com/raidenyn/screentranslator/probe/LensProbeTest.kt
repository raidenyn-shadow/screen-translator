package com.raidenyn.screentranslator.probe

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensProbeTest {
    @Test fun automationProbeSharesTheCapturedImageWithReadAccessToLens() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val uri = Uri.parse("content://com.raidenyn.screentranslator.files/captures/probe.png")
        val intent = probeIntent(ProbeCandidate.SHARE_AUTO_TRANSLATE, uri, resolver)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals("com.google.android.apps.search.lens.LensShareEntryPointActivity", intent.component?.className)
        assertEquals(uri, intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test fun callerEligibilityProbeDoesNotStartANewTask() {
        val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
        val intent = probeIntent(ProbeCandidate.LENS_EXPORTED_FOR_RESULT, null, resolver)

        // NEW_TASK immediately cancels an Activity's result request, invalidating the
        // diagnostic for Lens's requirement that getCallingPackage() be non-null.
        assertEquals(0, intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
