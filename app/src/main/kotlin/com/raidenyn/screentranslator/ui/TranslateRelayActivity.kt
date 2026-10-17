package com.raidenyn.screentranslator.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import com.raidenyn.screentranslator.R
import com.raidenyn.screentranslator.lens.GSA_PACKAGE
import com.raidenyn.screentranslator.lens.translateRequest

/**
 * Transparent owner of a Lens translation request and its temporary screenshot grant.
 * Keeps one request alive across configuration changes and releases access when it ends.
 */
class TranslateRelayActivity : Activity() {

    private var relayUri: Uri? = null
    private var waitingForLens = false

    /** Framework seam scripted by tests; production starts the request for a result. */
    internal var requestLauncher: (Intent, Int) -> Unit = { intent, code ->
        startActivityForResult(intent, code)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        relayUri = savedInstanceState?.let {
            BundleCompat.getParcelable(it, STATE_IMAGE_URI, Uri::class.java)
        } ?: IntentCompat.getParcelableExtra(intent, EXTRA_IMAGE_URI, Uri::class.java)
        val uri = relayUri
        if (uri == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        waitingForLens = savedInstanceState?.getBoolean(STATE_WAITING_FOR_LENS) == true
        if (waitingForLens) return
        try {
            grantUriPermission(GSA_PACKAGE, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            requestLauncher(translateRequest(uri, packageName), REQUEST_LENS)
            waitingForLens = true
        } catch (_: Exception) {
            // Lens missing, unavailable for this caller, or the start was rejected.
            Toast.makeText(this, R.string.error_launch_failed, Toast.LENGTH_SHORT).show()
            releaseGrant()
            setResult(RESULT_CANCELED)
            finish()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putParcelable(STATE_IMAGE_URI, relayUri)
        outState.putBoolean(STATE_WAITING_FOR_LENS, waitingForLens)
        super.onSaveInstanceState(outState)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_LENS) return
        if (resultCode != RESULT_OK) {
            // Lens reports a served request with RESULT_OK.
            Toast.makeText(this, R.string.error_launch_failed, Toast.LENGTH_SHORT).show()
        }
        releaseGrant()
        setResult(if (resultCode == RESULT_OK) RESULT_OK else RESULT_CANCELED, data)
        finish()
    }

    override fun onDestroy() {
        if (!isChangingConfigurations) releaseGrant()
        super.onDestroy()
    }

    private fun releaseGrant() {
        relayUri?.let { revokeUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        relayUri = null
    }

    companion object {
        const val EXTRA_IMAGE_URI = "image_uri"
        private const val STATE_IMAGE_URI = "image_uri_state"
        private const val STATE_WAITING_FOR_LENS = "waiting_for_lens_state"
        private const val REQUEST_LENS = 1

        fun intent(context: android.content.Context, uri: Uri): Intent =
            Intent(context, TranslateRelayActivity::class.java).putExtra(EXTRA_IMAGE_URI, uri)
    }
}
