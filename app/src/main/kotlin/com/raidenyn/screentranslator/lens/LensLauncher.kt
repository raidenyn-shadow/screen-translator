package com.raidenyn.screentranslator.lens

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

internal const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"

/** Starts the image-share activity from the owning Android context. */
fun interface IntentStarter {
    fun start(intent: Intent)
}

/** Shares the private screenshot through the Google Lens entry point verified on the device. */
class LensLauncher(private val resolver: ContentResolver, private val starter: IntentStarter) {
    fun launch(uri: Uri): Boolean {
        val intent = Intent(Intent.ACTION_SEND).apply {
            setClassName(GSA_PACKAGE, "com.google.android.apps.search.lens.LensShareEntryPointActivity")
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(resolver, "screenshot", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            starter.start(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
