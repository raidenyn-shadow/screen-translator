package com.raidenyn.screentranslator.ui

import android.content.Intent

/**
 * Stable launcher component used by the home screen and ColorOS Smart Sidebar.
 * Remains the task root while showing settings, so sidebar launches to an existing
 * task reach onNewIntent instead of merely bringing a separate settings task forward.
 * A connected sidebar capture closes this window before the screenshot delay.
 */
class TrampolineActivity : SetupActivity() {
    override fun captureRequested(intent: Intent): Boolean = LaunchSource.isSidebarLaunch(intent)
}
