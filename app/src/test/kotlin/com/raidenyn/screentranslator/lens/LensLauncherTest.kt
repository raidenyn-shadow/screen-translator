package com.raidenyn.screentranslator.lens

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LensLauncherTest {
    private val resolver = ApplicationProvider.getApplicationContext<Context>().contentResolver
    private val uri = Uri.parse("content://com.raidenyn.screentranslator.files/captures/1.png")

    /** Records every started intent, then throws for the scripted components/packages (and optionally the chooser). */
    private class ScriptedStarter(
        private val unresolvableComponents: Set<ComponentName> = emptySet(),
        private val unresolvablePackages: Set<String> = emptySet(),
        private val unresolvableChooser: Boolean = false,
    ) : IntentStarter {
        val intents = mutableListOf<Intent>()

        override fun start(intent: Intent) {
            intents.add(intent)
            val component = intent.component
            if (component != null && component in unresolvableComponents) {
                throw ActivityNotFoundException("no activity for $component")
            }
            val pkg = intent.`package`
            if (pkg != null && pkg in unresolvablePackages) {
                throw ActivityNotFoundException("no activity in $pkg")
            }
            if (unresolvableChooser && intent.action == Intent.ACTION_CHOOSER) {
                throw ActivityNotFoundException()
            }
        }
    }

    private fun launchWith(starter: ScriptedStarter): LensLaunchResult =
        LensLauncher(resolver, starter).launch(uri)

    @Test fun firstResolvableCandidateWins() {
        val starter = ScriptedStarter(unresolvableComponents = setOf(LensCandidates.COMPONENTS[0]))
        val result = launchWith(starter)
        assertEquals(LensLaunchResult.DirectLaunch(LensCandidates.COMPONENTS[1]), result)
        assertEquals(2, starter.intents.size)   // c1 failed, c2 succeeded, chain stopped
        assertEquals(LensCandidates.COMPONENTS[0], starter.intents[0].component)
        val sent = starter.intents[1]
        assertEquals(LensCandidates.COMPONENTS[1], sent.component)
        assertEquals("image/png", sent.type)
        assertEquals(uri, sent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertEquals(uri, sent.clipData?.getItemAt(0)?.uri)
        assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(sent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun allCandidatesFailPackageFallbackUsed() {
        val starter = ScriptedStarter(
            unresolvableComponents = LensCandidates.COMPONENTS.toSet(),
            unresolvablePackages = setOf(LensCandidates.PACKAGES[0]),
        )
        val result = launchWith(starter)
        assertEquals(LensLaunchResult.PackageFallback(LensCandidates.PACKAGES[1]), result)
        assertEquals(4, starter.intents.size)   // both components, then PACKAGES[0], then PACKAGES[1]
        assertEquals(LensCandidates.COMPONENTS[0], starter.intents[0].component)
        assertEquals(LensCandidates.COMPONENTS[1], starter.intents[1].component)
        assertEquals(LensCandidates.PACKAGES[0], starter.intents[2].`package`)
        val sent = starter.intents[3]
        assertEquals(LensCandidates.PACKAGES[1], sent.`package`)
        assertNull(sent.component)
        assertEquals("image/png", sent.type)
        assertEquals(uri, sent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(sent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun nothingResolvesChooserFallback() {
        val starter = ScriptedStarter(
            unresolvableComponents = LensCandidates.COMPONENTS.toSet(),
            unresolvablePackages = LensCandidates.PACKAGES.toSet(),
        )
        val result = launchWith(starter)
        assertEquals(LensLaunchResult.ChooserFallback, result)
        assertEquals(5, starter.intents.size)   // 2 components + 2 packages, then the chooser
        val chooser = starter.intents[4]
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        val inner = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(Intent.ACTION_SEND, inner.action)
        assertEquals(uri, inner.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))
        assertTrue(inner.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test fun evenChooserFails() {
        val starter = ScriptedStarter(
            unresolvableComponents = LensCandidates.COMPONENTS.toSet(),
            unresolvablePackages = LensCandidates.PACKAGES.toSet(),
            unresolvableChooser = true,
        )
        val result = launchWith(starter)
        assertEquals(LensLaunchResult.Failed("chooser unavailable"), result)   // scripted throw has no message
    }
}
