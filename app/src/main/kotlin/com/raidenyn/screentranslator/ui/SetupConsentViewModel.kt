package com.raidenyn.screentranslator.ui

import android.content.Intent
import androidx.lifecycle.ViewModel

/** Process-local consent state retained across configuration changes only. */
internal class SetupConsentViewModel : ViewModel() {
    var consentInFlight = false
    var deferredConsent: Pair<Int, Intent?>? = null
}
