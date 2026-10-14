package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.raidenyn.screentranslator.service.ScreenCaptureService

/**
 * The app's UI-less launcher entry point: Smart Sidebar taps land here while the sidebar
 * dismissal animation is still running. It dispatches a capture through the running
 * accessibility service, or opens [SetupActivity] when the service is not enabled,
 * and never stays on screen itself.
 */
class TrampolineActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when {
            ScreenCaptureService.requestCapture() -> Unit
            ScreenCaptureService.instance == null -> startActivity(Intent(this, SetupActivity::class.java))
            else -> Unit   // duplicate tap while a capture is already in flight
        }
        finish()
        @Suppress("DEPRECATION") overridePendingTransition(0, 0)
    }
}