package com.raidenyn.screentranslator.lens

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
class LensTranslationSessionTest {
    private val results = mutableListOf<LensTranslationSession.Result>()
    private var reads = 0
    private val session = LensTranslationSession(
        readWindow = { reads++; null },
        onFinished = { results.add(it) },
    )

    @Test fun timesOutEvenWhenNoAccessibilityEventsArrive() {
        assertTrue(session.start { true })
        assertTrue(session.active)
        assertTrue(results.isEmpty())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(session.active)
        assertTrue(reads > 0)
        assertEquals(listOf(LensTranslationSession.Result.SELECTION_FAILED), results)
    }

    @Test fun failedLaunchFinishesWithoutReadingAnyWindows() {
        assertFalse(session.start { throw ActivityNotFoundException("missing Lens") })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(0, reads)
        assertFalse(session.active)
        assertEquals(listOf(LensTranslationSession.Result.LAUNCH_FAILED), results)
    }

    @Test fun rejectedLaunchReportsFailureOnlyOnce() {
        assertFalse(session.start { false })
        session.cancel()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(0, reads)
        assertEquals(listOf(LensTranslationSession.Result.LAUNCH_FAILED), results)
    }

    @Test fun cancellationRemovesScheduledWorkAndDuplicateStartsDoNotLaunch() {
        var launches = 0
        assertTrue(session.start { launches++; true })
        assertFalse(session.start { launches++; true })
        assertEquals(1, launches)
        session.cancel()
        session.cancel()
        val previousReads = reads
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(previousReads, reads)
        assertEquals(listOf(LensTranslationSession.Result.CANCELED), results)
    }

    @Test fun acceptedClickStopsPollingAndFinishesOnlyOnce() {
        val target = AccessibilityNodeInfo().apply {
            packageName = GSA_PACKAGE
            className = "android.widget.Button"
            text = "Translate"
            isClickable = true
            isEnabled = true
            isVisibleToUser = true
        }
        var clicks = 0
        val clickSession = LensTranslationSession(
            readWindow = { target },
            onFinished = { results.add(it) },
            automation = LensTranslateAutomation(click = { clicks++; true }),
        )
        assertTrue(clickSession.start { true })
        assertEquals(0, clicks)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertEquals(1, clicks)
        assertFalse(clickSession.active)
        assertEquals(listOf(LensTranslationSession.Result.SELECTED), results)
    }

    @Test fun inaccessibleWindowStopsWithoutCrashingTheService() {
        val brokenSession = LensTranslationSession(
            readWindow = { throw SecurityException("disconnected") },
            onFinished = { results.add(it) },
        )
        assertTrue(brokenSession.start { true })
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(11))
        assertFalse(brokenSession.active)
        assertEquals(listOf(LensTranslationSession.Result.SELECTION_FAILED), results)
    }
}
