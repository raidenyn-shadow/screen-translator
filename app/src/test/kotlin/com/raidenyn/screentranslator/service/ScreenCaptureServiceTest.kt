package com.raidenyn.screentranslator.service

import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.capture.CaptureError
import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenCaptureServiceTest {

    @Test
    fun versionGuardErrorPicksDedicatedToastCopy() {
        // The API 30–32 dead end is permanent, so it must surface the dedicated
        // "unsupported" string — not error_screenshot_failed's "try again" hint.
        assertEquals(
            R.string.error_screenshot_unsupported,
            CaptureError.ScreenshotUnsupported.labelRes,
        )
    }
}