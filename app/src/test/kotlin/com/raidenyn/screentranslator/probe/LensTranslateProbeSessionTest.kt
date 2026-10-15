package com.raidenyn.screentranslator.probe

import android.content.ActivityNotFoundException
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@LooperMode(LooperMode.Mode.PAUSED)
class LensTranslateProbeSessionTest {
    private val reports = mutableListOf<String>()
    private var reads = 0
    private val session = LensTranslateProbeSession(
        readWindow = { reads++; null },
        onReport = { reports.add(it) },
    )

    @Test fun timesOutEvenWhenNoAccessibilityEventsArrive() {
        assertTrue(session.start {})
        assertTrue(session.active)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(session.active)
        assertTrue(reads > 0)
        assertTrue(reports.last().contains("TIMED_OUT"))
    }

    @Test fun failedLaunchDisarmsWithoutReadingAnyWindows() {
        assertFalse(session.start { throw ActivityNotFoundException("missing Lens") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(0, reads)
        assertFalse(session.active)
        assertTrue(reports.last().contains("launch failed"))
    }

    @Test fun cancellationRemovesScheduledWorkAndDuplicateStartsDoNotLaunch() {
        var launches = 0
        assertTrue(session.start { launches++ })
        assertFalse(session.start { launches++ })
        assertEquals(1, launches)
        session.cancel("service interrupted")
        val previousReads = reads
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(previousReads, reads)
        assertTrue(reports.last().contains("service interrupted"))
    }

    @Test fun acceptedClickStopsPollingAndPublishesTheOutcome() {
        val target = AccessibilityNodeInfo().apply {
            packageName = GSA_PACKAGE
            className = "android.widget.Button"
            text = "Translate"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        var clicks = 0
        val clickSession = LensTranslateProbeSession(
            readWindow = { target },
            onReport = { reports.add(it) },
            automation = LensTranslateAutomation(click = { clicks++; true }),
        )
        assertTrue(clickSession.start {})
        assertEquals(0, clicks)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(1, clicks)
        assertFalse(clickSession.active)
        assertTrue(reports.last().contains("CLICK_ACCEPTED"))
    }

    @Test fun inaccessibleWindowStopsWithoutCrashingTheService() {
        val brokenSession = LensTranslateProbeSession(
            readWindow = { throw SecurityException("disconnected") },
            onReport = { reports.add(it) },
        )
        assertTrue(brokenSession.start {})
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(brokenSession.active)
        assertTrue(reports.last().contains("Cannot inspect Lens UI"))
    }
}
