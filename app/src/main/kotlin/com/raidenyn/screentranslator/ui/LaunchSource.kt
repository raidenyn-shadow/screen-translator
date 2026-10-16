package com.raidenyn.screentranslator.ui

import android.content.Intent

/**
 * Recognizes ColorOS Smart Sidebar launches. The sidebar starts the app with a
 * `from_package` extra naming its own package, while home-screen and app-drawer
 * launches arrive without extras (verified on a OnePlus PYB110, Android 17:
 * `from_package=com.coloros.smartsidebar`). Unrecognized sources are treated as
 * launcher launches so unknown launchers fall back to settings, never capture.
 */
internal object LaunchSource {
    private const val FROM_PACKAGE_EXTRA = "from_package"
    private const val SMART_SIDEBAR_PACKAGE = "com.coloros.smartsidebar"

    fun isSidebarLaunch(intent: Intent): Boolean =
        intent.getStringExtra(FROM_PACKAGE_EXTRA) == SMART_SIDEBAR_PACKAGE
}