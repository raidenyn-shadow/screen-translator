package com.raidenyn.screentranslator.lens

import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo

/** Owns a single launch-and-select operation; all entry points run on the main thread. */
class LensTranslationSession(
    private val readWindow: () -> AccessibilityNodeInfo?,
    private val onFinished: (Result) -> Unit,
    private val automation: LensTranslateAutomation = LensTranslateAutomation(),
) {
    enum class Result { SELECTED, LAUNCH_FAILED, SELECTION_FAILED, CANCELED }

    private val main = Handler(Looper.getMainLooper())
    private var pending = false
    val active: Boolean get() = pending

    private val tick = object : Runnable {
        override fun run() {
            if (!pending) return
            try {
                automation.inspect(readWindow())
            } catch (_: RuntimeException) {
                finish(Result.SELECTION_FAILED)
                return
            }
            if (automation.active) {
                main.postDelayed(this, 200L)
            } else {
                finish(when (automation.outcome) {
                    LensTranslateAutomation.Outcome.CLICK_ACCEPTED,
                    LensTranslateAutomation.Outcome.ALREADY_SELECTED -> Result.SELECTED
                    LensTranslateAutomation.Outcome.CANCELED -> Result.CANCELED
                    else -> Result.SELECTION_FAILED
                })
            }
        }
    }

    fun start(launch: () -> Boolean): Boolean {
        if (pending) return false
        pending = true
        automation.start()
        val launched = try { launch() } catch (_: RuntimeException) { false }
        if (!launched) {
            finish(Result.LAUNCH_FAILED)
            return false
        }
        if (pending) main.postDelayed(tick, 200L)
        return true
    }

    fun cancel() { finish(Result.CANCELED) }

    private fun finish(result: Result) {
        if (!pending) return
        pending = false
        main.removeCallbacks(tick)
        automation.cancel()
        onFinished(result)
    }
}
