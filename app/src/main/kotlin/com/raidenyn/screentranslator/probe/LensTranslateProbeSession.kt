package com.raidenyn.screentranslator.probe

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Owns the polling lifetime so timeout does not depend on Lens emitting accessibility events. */
class LensTranslateProbeSession(
    private val readWindow: () -> AccessibilityNodeInfo?,
    private val onReport: (String) -> Unit,
    private val automation: LensTranslateAutomation = LensTranslateAutomation(),
) {
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            if (!active) return
            try {
                automation.inspect(readWindow())
            } catch (e: RuntimeException) {
                automation.cancel("Cannot inspect Lens UI: ${e.javaClass.simpleName}.")
            }
            onReport(automation.report)
            if (active) main.postDelayed(this, 200L)
        }
    }

    val active: Boolean get() = automation.active

    fun start(launch: () -> Unit): Boolean {
        if (active) return false
        main.removeCallbacks(tick)
        automation.start()
        onReport(automation.report)
        try {
            launch()
        } catch (e: RuntimeException) {
            cancel("Lens launch failed: ${e.javaClass.simpleName}: ${e.message}")
            return false
        }
        main.postDelayed(tick, 200L)
        return true
    }

    fun cancel(reason: String) {
        main.removeCallbacks(tick)
        if (!active) return
        automation.cancel(reason)
        onReport(automation.report)
    }
}

/** Small, bounded diagnostic report survives activity recreation and is included in the dump. */
object LensTranslateProbeStatus {
    private const val PREFS = "lens_translate_probe"
    private const val REPORT = "report"
    private const val NOT_RUN = "J1 has not run."
    private val current = MutableStateFlow(NOT_RUN)
    val updates = current.asStateFlow()

    fun read(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(REPORT, NOT_RUN) ?: NOT_RUN

    fun load(context: Context) { current.value = read(context) }

    fun publish(context: Context, report: String) {
        if (current.value == report) return
        current.value = report
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(REPORT, report).apply()
    }
}
