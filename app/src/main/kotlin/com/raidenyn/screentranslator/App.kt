package com.raidenyn.screentranslator

import android.app.Application
import com.raidenyn.screentranslator.capture.CaptureStore

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CaptureStore.from(this).purge()
    }
}
