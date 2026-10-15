package com.raidenyn.screentranslator.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.probe.LensProbe
import com.raidenyn.screentranslator.probe.ProbeCandidate
import com.raidenyn.screentranslator.probe.componentDump
import com.raidenyn.screentranslator.probe.probeIntent
import com.raidenyn.screentranslator.service.ScreenCaptureService

/**
 * SPIKE CODE — throwaway diagnostics screen (see probe/LensProbe.kt).
 * Tries one candidate translation target at a time with a live screenshot and
 * dumps the relevant components for offline analysis.
 */
class ProbeActivity : ComponentActivity() {

    private var status by mutableStateOf<String?>(null)

    /** H2 launches LensExportedActivity FOR RESULT so it sees a non-null calling package. */
    private val lensForResult =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            status = "LensExportedActivity closed with result code ${it.resultCode} " +
                "(SDK decompiles: 1 = unavailable / possible caller restriction, " +
                "3 = empty caller; 0 = canceled / inconclusive. Codes depend on Google app version)."
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.probe_title),
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = stringResource(R.string.probe_instructions),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Card {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("この文章はスクリーン翻訳のテスト用です。翻訳が自動的に表示されるか確認します。")
                                Text("Este texto es una prueba de traducción de pantalla. Comprueba si la traducción aparece automáticamente.")
                            }
                        }
                        Text(
                            text = stringResource(R.string.probe_try_section),
                            style = MaterialTheme.typography.titleMedium
                        )
                        ProbeCandidate.entries.forEach { candidate ->
                            OutlinedButton(
                                onClick = { tryCandidate(candidate) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(candidate.label)
                            }
                        }
                        Text(
                            text = stringResource(R.string.probe_dump_section),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Button(onClick = { dumpAndCopy() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.probe_dump_copy))
                        }
                        OutlinedButton(onClick = { shareDump() }, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.probe_dump_share))
                        }
                        status?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }

    private fun tryCandidate(candidate: ProbeCandidate) {
        if (candidate == ProbeCandidate.LENS_EXPORTED_FOR_RESULT) {
            status = "Launching LensExportedActivity for result… watch the result code below."
            try {
                lensForResult.launch(probeIntent(candidate, null, contentResolver))
            } catch (e: Exception) {
                status = "Launch failed for ${candidate.label}: ${e.message}"
            }
            return
        }
        if (!candidate.needsCapture) {
            try {
                startActivity(probeIntent(candidate, null, contentResolver))
                status = if (candidate == ProbeCandidate.TRANSLATE_CAMERA) {
                    "Requested translation-only Lens through Google Translate (auto → English). " +
                        "This opens the camera, not the screenshot. Check that translation is selected."
                } else {
                    "Launched: ${candidate.label}. What happened on screen?"
                }
            } catch (t: Exception) {
                status = "Failed ${candidate.label}: ${t.message}"
            }
            return
        }
        val service = ScreenCaptureService.instance
        if (service == null) {
            status = "Capture service is off — enable it in the setup screen first."
            return
        }
        status = "Capturing screen…"
        LensProbe(service).requestCapture(
            onCaptured = { uri ->
                val shareUri = if (candidate == ProbeCandidate.MEDIASTORE_SHARE) {
                    toGalleryUri(uri)
                } else {
                    uri
                }
                if (shareUri == null) {
                    status = "Copying the screenshot into the gallery failed."
                    return@requestCapture
                }
                try {
                    startActivity(probeIntent(candidate, shareUri, contentResolver))
                    status = if (candidate == ProbeCandidate.MEDIASTORE_SHARE) {
                        "Launched: ${candidate.label} (screenshot saved to Pictures/ScreenTranslatorProbe). Behavior?"
                    } else {
                        "Launched: ${candidate.label}. Behavior?"
                    }
                } catch (t: Exception) {
                    status = "Launch failed for ${candidate.label}: ${t.message}"
                }
            },
            onError = { status = "Capture failed: $it" }
        )
    }

    /** Copies the private capture into MediaStore, mirroring the OEM flow that saves to the gallery. */
    private fun toGalleryUri(uri: Uri): Uri? = try {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "lens_probe_${System.currentTimeMillis()}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ScreenTranslatorProbe")
        }
        val target = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        contentResolver.openInputStream(uri)?.use { input ->
            contentResolver.openOutputStream(target)?.use { input.copyTo(it) }
        }
        target
    } catch (e: Exception) {
        null
    }

    private fun dumpAndCopy() {
        val dump = componentDump(this)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("lens probe dump", dump))
        status = "Dump (${dump.length} chars) copied to clipboard — paste it into our chat."
    }

    private fun shareDump() {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, componentDump(this@ProbeActivity))
        }
        startActivity(Intent.createChooser(send, "Share probe dump"))
    }
}
