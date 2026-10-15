package com.raidenyn.screentranslator.probe

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo

/** One explicitly armed diagnostic attempt; all calls are made on the main thread. */
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
    private var detail = "Not run."
    private var controls = "No Lens window inspected."
    val report: String get() = "J1 $outcome: $detail\nLast Lens controls: $controls"

    fun start() {
        startedAt = nowMs()
        sawLens = false
        controls = "No Lens window inspected."
        detail = "Waiting up to 10 seconds for a visible Translate button/filter (English label)."
        outcome = Outcome.WAITING
    }

    fun cancel(reason: String) {
        if (!active) return
        outcome = Outcome.CANCELED
        detail = reason
    }

    fun inspect(root: AccessibilityNodeInfo?) {
        if (!active) return
        if (nowMs() - startedAt >= TIMEOUT_MS) {
            outcome = Outcome.TIMED_OUT
            detail = "No unambiguous Translate control found before timeout. " +
                "Check service UI-access capability and the controls below."
            return
        }
        if (root == null) return
        if (root.packageName?.toString() != GSA_PACKAGE) {
            if (sawLens) cancel("Left the Google app before selecting Translate.")
            return
        }
        sawLens = true
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        val inventory = mutableListOf<String>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            if (!node.isVisibleToUser || !node.isEnabled) continue
            if (node.packageName?.toString() == GSA_PACKAGE && node.isClickable) {
                val labels = labels(node)
                if (inventory.size < 16) {
                    inventory.add("${node.className} id=${node.viewIdResourceName ?: "none"} " +
                        "label=${labels.joinToString(" / ").replace('\n', ' ').take(80)} " +
                        "selected=${isSelected(node)}")
                }
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
        controls = if (inventory.isEmpty()) "No visible enabled clickable controls (scanned $visited nodes)."
            else inventory.joinToString("; ")
        if (candidates.size != 1) {
            detail = if (candidates.size > 1) "Waiting: ambiguous Translate controls (${candidates.size}); no click."
                else "Waiting for Translate; Lens window is accessible."
            return
        }
        val target = candidates.single()
        if (isSelected(target)) {
            outcome = Outcome.ALREADY_SELECTED
            detail = "Translate control is already selected; no click needed. Verify the image on screen."
            return
        }
        // Disarm BEFORE invoking Android: a click may synchronously emit events.
        outcome = Outcome.CLICK_REJECTED
        try {
            if (click(target)) {
                outcome = Outcome.CLICK_ACCEPTED
                detail = "Translate click accepted; stopped after one action. " +
                    "Verify translation visually (accepted does not prove translation completed)."
            } else {
                detail = "Translate control rejected ACTION_CLICK; no retry."
            }
        } catch (e: RuntimeException) {
            detail = "Translate click failed: ${e.javaClass.simpleName}; no retry."
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
