package com.raidenyn.screentranslator.lens

import android.app.ActivityOptions
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
 * The installed Google app's exported Lens component, addressed explicitly with the
 * screenshot URI and translate filter as intent extras and no `google://lens` data
 * URI. The data-URI request resolved to the MainActivity alias, whose integration
 * mode runs the direct-intent caller-eligibility gate that rejected v0.7.4 with
 * "Lens eligibility error" (result 1). The explicit-component request parses in
 * that same build as integration mode 2, which reads `injected_image_uri` and
 * `open_filter` and skips the eligibility gate. Started for a result without a
 * new task; visible translation still requires device verification.
 */
fun translateRequest(uri: Uri, callerPackage: String): Intent =
    Intent().setComponent(ComponentName(GSA_PACKAGE, LENS_EXPORTED_ACTIVITY))
        .putExtra(EXTRA_INJECTED_IMAGE_URI, uri)
        .putExtra(EXTRA_OPEN_FILTER, "translate")
        .putExtra(EXTRA_CALLER_PACKAGE, callerPackage)

/** Requests a fullscreen relay task instead of inheriting the sidebar's floating window. */
fun fullscreenOptions(): Bundle = ActivityOptions.makeBasic().toBundle().apply {
    // ActivityOptions' windowing-mode setter is hidden from the public SDK.
    putInt("android.activity.windowingMode", 1)
}
