package com.raidenyn.screentranslator.capture

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Private screenshot cache: saves bitmaps as PNGs under [capturesDir] and hands
 * out content URIs served by the manifest's FileProvider. Captures are purged
 * at app start; only the newest capture needs to survive between launches.
 */
class CaptureStore(private val capturesDir: File, val authority: String) {

    suspend fun save(bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        capturesDir.mkdirs()
        val file = newFile()
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        Uri.parse("content://$authority/captures/${file.name}")
    }

    fun purge(): Int = capturesDir.listFiles()?.count { it.delete() } ?: 0

    /** Claims a unique `<currentTimeMillis>.png` name, retrying within a shared millisecond. */
    private fun newFile(): File {
        var file = File(capturesDir, "${System.currentTimeMillis()}.png")
        while (!file.createNewFile()) {
            file = File(capturesDir, "${System.currentTimeMillis()}.png")
        }
        return file
    }

    companion object {
        fun from(context: Context): CaptureStore =
            CaptureStore(File(context.cacheDir, "captures"), context.packageName + ".files")
    }
}
