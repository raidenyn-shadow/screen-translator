package com.raidenyn.screentranslator.lens

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Bundle

internal const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"

/**
 * The direct Lens request OxygenOS's screenshot translation sends: the image URI and
 * translate filter are query parameters, started for a result without a new task.
 * Verified in Screenshot 16.15.24's TranslatorDummyActivity; caller admission still
 * requires device testing.
 */
fun translateRequest(uri: Uri): Intent = Intent(Intent.ACTION_VIEW).apply {
    data = Uri.parse("google://lens").buildUpon()
        .appendQueryParameter("image_uri", uri.toString())
        .appendQueryParameter("filter", "translate")
        .build()
    setPackage(GSA_PACKAGE)
}

/** Requests a fullscreen relay task instead of inheriting the sidebar's floating window. */
fun fullscreenOptions(): Bundle = ActivityOptions.makeBasic().toBundle().apply {
    // ActivityOptions' windowing-mode setter is hidden from the public SDK.
    putInt("android.activity.windowingMode", 1)
}
