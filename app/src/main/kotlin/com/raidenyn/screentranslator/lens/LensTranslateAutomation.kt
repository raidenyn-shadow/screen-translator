package com.raidenyn.screentranslator.lens

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** Selects Lens's Translate control at most once per captured screenshot, on the main thread. */
class LensTranslateAutomation(
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
    private val click: (AccessibilityNodeInfo) -> Boolean = {
        it.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    },
) {
    companion object {
        const val TIMEOUT_MS = 10_000L
        private const val MAX_NODES = 500
        private val BUTTON_ROLES = setOf(
            "android.widget.Button", "android.widget.ImageButton",
            "android.widget.RadioButton", "android.widget.ToggleButton",
        )
    }

    enum class Outcome { IDLE, WAITING, CLICK_ACCEPTED, ALREADY_SELECTED, CLICK_REJECTED, TIMED_OUT, CANCELED }

    var outcome = Outcome.IDLE
        private set
    val active: Boolean get() = outcome == Outcome.WAITING
    private var startedAt = 0L
    private var sawLens = false

    fun start() {
        startedAt = nowMs()
        sawLens = false
        outcome = Outcome.WAITING
    }

    fun cancel() {
        if (!active) return
        outcome = Outcome.CANCELED
    }

    fun inspect(root: AccessibilityNodeInfo?) {
        if (!active) return
        if (nowMs() - startedAt >= TIMEOUT_MS) {
            outcome = Outcome.TIMED_OUT
            return
        }
        if (root == null) return
        if (root.packageName?.toString() != GSA_PACKAGE) {
            if (sawLens) cancel()
            return
        }
        sawLens = true
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser || !node.isEnabled) continue
            if (node.packageName?.toString() == GSA_PACKAGE && node.isClickable) {
                val labels = labels(node)
                val role = node.className?.toString()
                val id = node.viewIdResourceName.orEmpty().substringAfter(":id/")
                val filter = id.contains("filter", true) || id.contains("chip", true) || id.contains("tab", true)
                // Plain clickable OCR TextViews are not filter controls. Require a
                // button role or an identifiable tab/chip/filter container as well as
                // the exact caption. No coordinate taps or partial text matches.
                if ((role in BUTTON_ROLES || filter) && labels.any { it.trim().equals("Translate", true) }) {
                    candidates.add(node)
                }
            }
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        if (candidates.size != 1) return
        val target = candidates.single()
        if (isSelected(target)) {
            outcome = Outcome.ALREADY_SELECTED
            return
        }
        // Disarm BEFORE invoking Android: a click may synchronously emit events.
        outcome = Outcome.CLICK_REJECTED
        try {
            if (click(target)) {
                outcome = Outcome.CLICK_ACCEPTED
            }
        } catch (_: RuntimeException) {
            // Stale or disconnected accessibility controls are a failed selection.
        }
    }

    @Suppress("DEPRECATION") // isChecked remains the compatibility accessor on Android 11–15.
    private fun isSelected(node: AccessibilityNodeInfo) = node.isSelected || node.isChecked

    private fun labels(node: AccessibilityNodeInfo, depth: Int = 0): List<String> = buildList {
        if (node.packageName?.toString() != GSA_PACKAGE) return@buildList
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
        node.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
        if (depth < 2) {
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                if (child.isVisibleToUser && child.isEnabled && !child.isClickable) {
                    addAll(labels(child, depth + 1))
                }
            }
        }
    }
}
