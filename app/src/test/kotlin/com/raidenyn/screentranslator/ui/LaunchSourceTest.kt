package com.raidenyn.screentranslator.ui

import android.content.Intent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LaunchSourceTest {

    @Test
    fun recognizesTheSmartSidebarFromPackageExtra() {
        val intent = Intent(Intent.ACTION_MAIN).putExtra("from_package", "com.coloros.smartsidebar")

        assertTrue(LaunchSource.isSidebarLaunch(intent))
    }

    @Test
    fun launcherAndUnknownExtrasAreNotSidebarLaunches() {
        assertFalse(LaunchSource.isSidebarLaunch(Intent(Intent.ACTION_MAIN)))
        assertFalse(LaunchSource.isSidebarLaunch(Intent().putExtra("from_package", "com.microsoft.launcher")))
        assertFalse(LaunchSource.isSidebarLaunch(Intent().putExtra("unrelated", "com.coloros.smartsidebar")))
    }
}