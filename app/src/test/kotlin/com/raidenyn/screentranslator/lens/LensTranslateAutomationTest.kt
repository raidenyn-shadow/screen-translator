package com.raidenyn.screentranslator.lens

import android.view.accessibility.AccessibilityNodeInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensTranslateAutomationTest {
    private var now = 0L
    private val clicks = mutableListOf<AccessibilityNodeInfo>()
    private val automation = LensTranslateAutomation(
        nowMs = { now },
        click = { clicks.add(it); true },
    )

    private fun node(
        label: String? = null,
        clickable: Boolean = false,
        role: String = "android.widget.Button",
        pkg: String = GSA_PACKAGE,
    ) = AccessibilityNodeInfo().apply {
        packageName = pkg
        className = role
        text = label
        isClickable = clickable
        isEnabled = true
        isVisibleToUser = true
    }

    private fun tree(vararg children: AccessibilityNodeInfo, pkg: String = GSA_PACKAGE) =
        node(role = "android.widget.FrameLayout", pkg = pkg).apply {
            children.forEach { shadowOf(this).addChild(it) }
        }

    @Test fun clicksTranslateExactlyOnceAndOnlyAfterExplicitStart() {
        val target = node("Translate", clickable = true).apply {
            viewIdResourceName = "lens:test/translate_button"
        }
        val root = tree(node("Search", clickable = true), target)
        automation.inspect(root)
        assertTrue(clicks.isEmpty())

        automation.start()
        automation.inspect(root)
        automation.inspect(root)

        assertEquals(listOf("lens:test/translate_button"), clicks.map { it.viewIdResourceName })
        assertEquals(LensTranslateAutomation.Outcome.CLICK_ACCEPTED, automation.outcome)
        assertFalse(automation.active)
    }

    @Test fun waitsForContentToLoadAndFindsAccessibleDescription() {
        automation.start()
        automation.inspect(null)
        automation.inspect(tree(node("Search", clickable = true)))
        assertTrue(automation.active)
        val target = node(clickable = true).apply {
            contentDescription = "Translate"
            viewIdResourceName = "lens:test/description_button"
        }
        automation.inspect(tree(target))
        assertEquals(listOf("lens:test/description_button"), clicks.map { it.viewIdResourceName })
    }

    @Test fun doesNotToggleAnAlreadySelectedTranslateControl() {
        automation.start()
        automation.inspect(tree(node("Translate", clickable = true).apply { isSelected = true }))
        assertTrue(clicks.isEmpty())
        assertEquals(LensTranslateAutomation.Outcome.ALREADY_SELECTED, automation.outcome)
    }

    @Test fun ignoresHiddenDisabledOcrTextAndPartialLabels() {
        automation.start()
        automation.inspect(tree(
            node("Translate", clickable = true).apply { isVisibleToUser = false },
            node("Translate", clickable = true).apply { isEnabled = false },
            node("Translate", clickable = true, role = "android.widget.TextView"),
            node("Translate this page", clickable = true),
        ))
        assertTrue(clicks.isEmpty())
        assertTrue(automation.active)
    }

    @Test fun clicksFilterContainerForItsNonClickableLabel() {
        val target = node(clickable = true, role = "android.widget.LinearLayout").apply {
            viewIdResourceName = "$GSA_PACKAGE:id/lens_filter_chip"
            shadowOf(this).addChild(node("Translate", role = "android.widget.TextView"))
        }
        automation.start()
        automation.inspect(tree(target))
        assertEquals(listOf("$GSA_PACKAGE:id/lens_filter_chip"), clicks.map { it.viewIdResourceName })
    }

    @Test fun ignoresOtherAppsAndCancelsWhenLeavingLens() {
        automation.start()
        automation.inspect(tree(node("Translate", clickable = true, pkg = "other.app"), pkg = "other.app"))
        assertTrue(clicks.isEmpty())
        automation.inspect(tree(node("Search", clickable = true)))
        automation.inspect(tree(pkg = "other.app"))
        automation.inspect(tree(node("Translate", clickable = true)))
        assertTrue(clicks.isEmpty())
        assertEquals(LensTranslateAutomation.Outcome.CANCELED, automation.outcome)
    }

    @Test fun timeoutPreventsALateClick() {
        automation.start()
        automation.inspect(tree(node("Search", clickable = true)))
        now = 10_000
        automation.inspect(tree(node("Translate", clickable = true)))
        assertTrue(clicks.isEmpty())
        assertEquals(LensTranslateAutomation.Outcome.TIMED_OUT, automation.outcome)
    }

    @Test fun rejectedClickIsNotRepeatedOrReportedAsSuccess() {
        var attempts = 0
        val rejected = LensTranslateAutomation(nowMs = { now }, click = { attempts++; false })
        rejected.start()
        rejected.inspect(tree(node("Translate", clickable = true)))
        rejected.inspect(tree(node("Translate", clickable = true)))
        assertEquals(1, attempts)
        assertEquals(LensTranslateAutomation.Outcome.CLICK_REJECTED, rejected.outcome)
    }

    @Test fun thrownClickFailureDisarmsTheAttempt() {
        var attempts = 0
        val rejected = LensTranslateAutomation(nowMs = { now }, click = {
            attempts++
            throw SecurityException("stale connection")
        })
        rejected.start()
        rejected.inspect(tree(node("Translate", clickable = true)))
        rejected.inspect(tree(node("Translate", clickable = true)))
        assertEquals(1, attempts)
        assertEquals(LensTranslateAutomation.Outcome.CLICK_REJECTED, rejected.outcome)
    }

    @Test fun foreignPackageChildCannotSupplyTheTarget() {
        automation.start()
        automation.inspect(tree(node("Translate", clickable = true, pkg = "other.app")))
        assertTrue(clicks.isEmpty())
        assertTrue(automation.active)
    }

    @Test fun foreignDescendantCaptionCannotAuthorizeAGoogleParentClick() {
        val parent = node(clickable = true)
        shadowOf(parent).addChild(node("Translate", pkg = "other.app", role = "android.widget.TextView"))
        automation.start()
        automation.inspect(tree(parent))
        assertTrue(clicks.isEmpty())
        assertTrue(automation.active)
    }

    @Test fun ambiguousControlsAreNotClicked() {
        automation.start()
        automation.inspect(tree(node("Translate", clickable = true), node("Translate", clickable = true)))
        assertTrue(clicks.isEmpty())
        assertTrue(automation.active)
    }

    @Test fun explicitCancellationPreventsClicksUntilANewStart() {
        automation.start()
        automation.cancel()
        automation.inspect(tree(node("Translate", clickable = true)))
        assertTrue(clicks.isEmpty())
        assertEquals(LensTranslateAutomation.Outcome.CANCELED, automation.outcome)
        automation.start()
        automation.inspect(tree(node("Translate", clickable = true)))
        assertEquals(1, clicks.size)
    }
}
