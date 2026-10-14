package com.raidenyn.screentranslator.lens

import android.content.ComponentName

/**
 * Known Google Lens entry points tried by [LensLauncher]'s fallback chain.
 *
 * COMPONENTS are best-guess placeholders: they are replaced by on-device probe
 * output (probe P1, see the Task 6 README) once the real activity names are
 * known. PACKAGES restricts the share fallback to the Google app and the
 * standalone Lens app.
 */
object LensCandidates {
    val COMPONENTS: List<ComponentName> = listOf(
        ComponentName("com.google.android.apps.lens", "com.google.android.apps.lens.lens.LensLauncherActivity"),
        ComponentName("com.google.android.googlequicksearchbox", "com.google.android.apps.gsa.searchcore.lens.LensExportActivity"),
    )

    val PACKAGES: List<String> = listOf(
        "com.google.android.googlequicksearchbox",
        "com.google.android.apps.lens",
    )
}
