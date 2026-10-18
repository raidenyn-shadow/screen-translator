package com.raidenyn.screentranslator.lens

import android.app.ActivityOptions
import android.content.ClipData
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle

internal const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"
internal const val LENS_EXPORTED_ACTIVITY = "com.google.android.apps.search.lens.LensExportedActivity"
internal const val EXTRA_INJECTED_IMAGE_URI = "injected_image_uri"
internal const val EXTRA_OPEN_FILTER = "open_filter"
internal const val EXTRA_CALLER_PACKAGE = "caller_package"

/**
 * Opens a captured screenshot directly in Lens Translate. Device-verified with
 * Google 17.64.15.ve.arm64; see docs/lens-handoff.md for the private intent contract.
 * Must be started from an activity without NEW_TASK to retain the result channel.
 */
fun translateRequest(uri: Uri, callerPackage: String): Intent = Intent().apply {
    setComponent(ComponentName(GSA_PACKAGE, LENS_EXPORTED_ACTIVITY))
    // The history bit selects the image/filter entry mode instead of the shortcut-only guard.
    addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY or Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // ClipData makes the URI visible to Android's activity-launch permission machinery.
    clipData = ClipData.newRawUri("screenshot", uri)
    putExtra(EXTRA_INJECTED_IMAGE_URI, uri)
    putExtra(EXTRA_OPEN_FILTER, "translate")
    putExtra(EXTRA_CALLER_PACKAGE, callerPackage)
}

/** Requests a fullscreen relay task instead of inheriting the sidebar's floating window. */
fun fullscreenOptions(): Bundle = ActivityOptions.makeBasic().toBundle().apply {
    // ActivityOptions' windowing-mode setter is hidden from the public SDK.
    putInt("android.activity.windowingMode", 1)
}

/**
 * Store pages for installing or updating the Google app (host of Lens), most specific first:
 * the Play Store app, then the web page for any browser or other store handler.
 */
fun installGoogleAppRequests(): List<Intent> = listOf(
    "market://details?id=$GSA_PACKAGE",
    "https://play.google.com/store/apps/details?id=$GSA_PACKAGE",
).map { Intent(Intent.ACTION_VIEW, Uri.parse(it)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
