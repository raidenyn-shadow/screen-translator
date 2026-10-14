package com.raidenyn.screentranslator

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AppTest {
    @Test
    fun applicationIsApp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals("com.raidenyn.screentranslator", app.packageName)
        assertTrue(app is App)
    }
}
