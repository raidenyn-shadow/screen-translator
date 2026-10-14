package com.raidenyn.screentranslator.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text

/**
 * Opened by [TrampolineActivity] when the accessibility service is not enabled.
 * Placeholder content only; Task 6 replaces it with the real setup screen.
 */
class SetupActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Text("Setup") }
    }
}