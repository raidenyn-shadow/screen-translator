package com.raidenyn.screentranslator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

/**
 * Opened by [TrampolineActivity] when the accessibility service is not enabled.
 * Hosts [SetupScreen], the only real page in the app.
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { SetupScreen() }
    }
}
