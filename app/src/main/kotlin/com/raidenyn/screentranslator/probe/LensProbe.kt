package com.raidenyn.screentranslator.probe

import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.content.ClipData
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Display
import com.raidenyn.screentranslator.capture.CaptureStore
import com.raidenyn.screentranslator.capture.ScreenshotTaker
import com.raidenyn.screentranslator.service.ScreenCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * SPIKE CODE — throwaway diagnostics, not production code.
 *
 * Goal: find an intent that opens a translation target with the screenshot already
 * translating, replicating ColorOS's OEM "Screen Translate (powered by Google Lens)"
 * hand-off. The probe (a) captures the screen through the accessibility API and fires
 * one candidate intent at a time, and (b) dumps the relevant Lens/Google/Translate
 * components so the candidate list can be refined from real device data.
 *
 * The screenshot-taker logic duplicates ScreenCaptureService's private implementation
 * deliberately: no production file should change for this probe.
 */

const val PROBE_CAPTURE_DELAY_MS = 400L
const val GSA_PACKAGE = "com.google.android.googlequicksearchbox"
const val LENS_STANDALONE_PACKAGE = "com.google.ar.lens"
const val TRANSLATE_PACKAGE = "com.google.android.apps.translate"
const val LENS_ACTIVITY = "com.google.android.apps.gsa.staticplugins.lens.LensActivity"

enum class ProbeCandidate(val label: String, val needsCapture: Boolean) {
    IMPLICIT_TRANSLATE("D1: ImplicitTranslateSearchEntrypoint (direct, no capture)", false),
    LIVE_DEEPLINK_DIRECT("D2: Lens Live deeplink activity (direct, no capture)", false),
    LENS_URL_BASE("D3: googleapp://lens (no capture)", false),
    LENS_URL_LIVE("D4: googleapp://lens/live (no capture)", false),
    LENS_URL_TRANSLATE("D5: googleapp://lens/translate (no capture)", false),
    LENS_SHARE_MODE("D6: LensShareEntryPoint + screenshot + lens_mode=translate", true),
    GOOGLE_APP_SHARE("C: Google app image share (reference, works today)", true),
}

/** Builds the intent for one probe candidate; [uri] is only required for capture candidates. */
fun probeIntent(candidate: ProbeCandidate, uri: Uri?, resolver: ContentResolver): Intent {
    fun gsaComponent(cls: String) =
        Intent().setClassName(GSA_PACKAGE, cls).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun view(url: String) =
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    fun sendTo(uri: Uri, configure: Intent.() -> Unit = {}) = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(resolver, "screenshot", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        configure()
    }
    return when (candidate) {
        ProbeCandidate.IMPLICIT_TRANSLATE ->
            gsaComponent("com.google.android.googlequicksearchbox.ImplicitTranslateSearchEntrypointInternal")
        ProbeCandidate.LIVE_DEEPLINK_DIRECT ->
            gsaComponent("com.google.android.apps.search.lens.live.deeplink.LiveActivity")
        ProbeCandidate.LENS_URL_BASE -> view("googleapp://lens")
        ProbeCandidate.LENS_URL_LIVE -> view("googleapp://lens/live")
        ProbeCandidate.LENS_URL_TRANSLATE -> view("googleapp://lens/translate")
        ProbeCandidate.LENS_SHARE_MODE -> sendTo(requireNotNull(uri)) {
            setClassName(GSA_PACKAGE, "com.google.android.apps.search.lens.LensShareEntryPointActivity")
            putExtra("lens_mode", "translate")
        }
        ProbeCandidate.GOOGLE_APP_SHARE -> sendTo(requireNotNull(uri)) { setPackage(GSA_PACKAGE) }
    }
}

/** Captures the screen and hands the URI back on the main thread; one request at a time. */
class LensProbe(private val service: ScreenCaptureService) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    private val inFlight = AtomicBoolean(false)

    fun requestCapture(onCaptured: (Uri) -> Unit, onError: (String) -> Unit) {
        if (!inFlight.compareAndSet(false, true)) {
            main.post { onError("a capture is already in flight") }
            return
        }
        scope.launch {
            try {
                delay(PROBE_CAPTURE_DELAY_MS)
                val bitmap = ProbeTaker(service).take()
                val uri = CaptureStore.from(service).save(bitmap)
                main.post { onCaptured(uri) }
            } catch (t: Throwable) {
                main.post { onError(t.message ?: t.javaClass.simpleName) }
            } finally {
                inFlight.set(false)
            }
        }
    }

    /** Copy of the production screenshot taker (private there), unchanged in behavior. */
    private class ProbeTaker(private val service: ScreenCaptureService) : ScreenshotTaker {
        override suspend fun take(): Bitmap {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                throw IllegalStateException("API ${Build.VERSION.SDK_INT} < 33")
            }
            return suspendCancellableCoroutine { continuation ->
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    Runnable::run,
                    object : TakeScreenshotCallback {
                        override fun onSuccess(result: ScreenshotResult) {
                            val buffer = result.hardwareBuffer
                            try {
                                if (continuation.isActive) {
                                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                                    if (hardware == null) {
                                        continuation.resumeWithException(
                                            IllegalStateException("screenshot buffer could not be wrapped")
                                        )
                                    } else {
                                        continuation.resume(hardware.copy(Bitmap.Config.ARGB_8888, false))
                                    }
                                }
                            } catch (t: Throwable) {
                                if (continuation.isActive) continuation.resumeWithException(t)
                            } finally {
                                buffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("takeScreenshot failed with error $errorCode")
                                )
                            }
                        }
                    }
                )
            }
        }
    }
}

/** Device-side inventory of every translation-relevant component, for offline analysis. */
fun componentDump(context: Context): String = buildString {
    val pm = context.packageManager
    appendLine("Screen Translator — Lens probe dump")
    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
    appendLine()

    fun dumpPackage(pkg: String, activityFilter: (String) -> Boolean = { true }) {
        appendLine("== package $pkg ==")
        val info = try {
            pm.getPackageInfo(pkg, android.content.pm.PackageManager.GET_ACTIVITIES)
        } catch (e: Exception) {
            appendLine("  NOT INSTALLED (${e.message})")
            appendLine()
            return
        }
        appendLine("  version: ${info.versionName} (${info.longVersionCode})")
        info.activities.orEmpty()
            .filter { activityFilter(it.name) }
            .sortedBy { it.name }
            .forEach {
                appendLine("  activity ${it.name} [exported=${it.exported}, enabled=${it.enabled}]")
            }
        appendLine()
    }

    dumpPackage(GSA_PACKAGE) { name -> name.contains("lens", true) || name.contains("translate", true) }
    dumpPackage(LENS_STANDALONE_PACKAGE)
    dumpPackage(TRANSLATE_PACKAGE)

    fun resolvers(title: String, intent: Intent) {
        appendLine("== $title ==")
        pm.queryIntentActivities(intent, 0).forEach {
            appendLine("  ${it.activityInfo.packageName}/${it.activityInfo.name}")
        }
        appendLine()
    }

    resolvers("ACTION_SEND image/png resolvers (all packages)", Intent(Intent.ACTION_SEND).apply { type = "image/png" })
    for (pkg in listOf(GSA_PACKAGE, LENS_STANDALONE_PACKAGE, TRANSLATE_PACKAGE)) {
        resolvers(
            "ACTION_SEND image/png restricted to $pkg",
            Intent(Intent.ACTION_SEND).apply { type = "image/png"; setPackage(pkg) }
        )
    }
    resolvers("ACTION_VIEW https://lens.google.com/", Intent(Intent.ACTION_VIEW, Uri.parse("https://lens.google.com/")))
    resolvers("ACTION_VIEW googleapp://lens", Intent(Intent.ACTION_VIEW, Uri.parse("googleapp://lens")))

    // Deep-link / action resolution matrix: which URLs and actions the device answers,
    // discovered WITHOUT launching anything.
    appendLine("== resolution matrix (no launching, resolver queries only) ==")
    val matrix = listOf(
        "googleapp://lens" to "googleapp://lens",
        "googleapp://lens/live" to "googleapp://lens/live",
        "googleapp://lens/translate" to "googleapp://lens/translate",
        "googleapp://lens?mode=translate" to "googleapp://lens?mode=translate",
        "googleapp://lens?mode=live" to "googleapp://lens?mode=live",
        "googleapp://translate" to "googleapp://translate",
        "googleapp://lenslive" to "googleapp://lenslive",
        "https://lens.google.com/translate" to "https://lens.google.com/translate",
        "https://lens.google.com/live" to "https://lens.google.com/live",
    )
    for ((label, url) in matrix) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val all = pm.queryIntentActivities(intent, 0)
        appendLine(
            "  VIEW $label -> all[${all.joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name.substringAfterLast('.')}" }}] " +
                "gsa[${pm.queryIntentActivities(Intent(intent).setPackage(GSA_PACKAGE), 0).joinToString { it.activityInfo.name.substringAfterLast('.') }}]"
        )
    }
    for (action in listOf("android.intent.action.TRANSLATE", "android.intent.action.PROCESS_TEXT")) {
        for (mime in listOf("text/plain", "image/png")) {
            val intent = Intent(action).setType(mime)
            val all = pm.queryIntentActivities(intent, 0)
            appendLine(
                "  $action [$mime] -> all[${all.joinToString { "${it.activityInfo.packageName}/${it.activityInfo.name.substringAfterLast('.')}" }}] " +
                    "gsa[${pm.queryIntentActivities(Intent(intent).setPackage(GSA_PACKAGE), 0).joinToString { it.activityInfo.name.substringAfterLast('.') }}]"
            )
        }
    }
    appendLine()
}