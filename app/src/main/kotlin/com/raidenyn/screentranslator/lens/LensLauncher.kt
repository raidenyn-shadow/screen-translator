package com.raidenyn.screentranslator.lens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/** How a [LensLauncher.launch] attempt ended: which chain step handed the screenshot off, or why none did. */
sealed interface LensLaunchResult {
    /** A specific Lens activity was started directly. */
    data class DirectLaunch(val component: ComponentName) : LensLaunchResult

    /** No Lens component resolved; an ACTION_SEND restricted to [packageName] was started. */
    data class PackageFallback(val packageName: String) : LensLaunchResult

    /** No Lens package resolved; the system chooser was started. */
    data object ChooserFallback : LensLaunchResult

    /** Not even the chooser could be started; [reason] carries the exception message. */
    data class Failed(val reason: String) : LensLaunchResult
}

/** Indirection over Context.startActivity so tests can script which targets are resolvable. */
fun interface IntentStarter {
    fun start(intent: Intent)
}

/**
 * Hands a screenshot to Google Lens through a three-step intent chain: explicit
 * Lens components, then package-restricted ACTION_SEND, then the system
 * chooser. A start that fails with [ActivityNotFoundException] or
 * SecurityException advances the chain instead of crashing.
 */
class LensLauncher(private val resolver: ContentResolver, private val starter: IntentStarter) {

    fun launch(uri: Uri): LensLaunchResult {
        for (candidate in LensCandidates.COMPONENTS) {
            if (tryStart(sendIntent(uri).setComponent(candidate))) {
                return LensLaunchResult.DirectLaunch(candidate)
            }
        }
        for (pkg in LensCandidates.PACKAGES) {
            if (tryStart(sendIntent(uri).setPackage(pkg))) {
                return LensLaunchResult.PackageFallback(pkg)
            }
        }
        val chooser = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, sendIntent(uri))
            addFlags(SEND_FLAGS)
        }
        return try {
            starter.start(chooser)
            LensLaunchResult.ChooserFallback
        } catch (e: Exception) {
            LensLaunchResult.Failed(e.message ?: "chooser unavailable")
        }
    }

    /**
     * Starts [intent]; false when the target is missing ([ActivityNotFoundException])
     * or not exported to us (SecurityException), so the chain can advance.
     */
    private fun tryStart(intent: Intent): Boolean =
        try {
            starter.start(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }

    /** ACTION_SEND carrying the screenshot URI, with read grant and NEW_TASK for service-context starts. */
    private fun sendIntent(uri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(resolver, "screenshot", uri)
            addFlags(SEND_FLAGS)
        }

    private companion object {
        const val SEND_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
    }
}
