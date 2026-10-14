package com.raidenyn.screentranslator.capture

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CaptureStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val dir = File(context.cacheDir, "captures").apply { mkdirs() }
    private val store = CaptureStore(dir, "com.raidenyn.screentranslator.files")
    private val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)

    @After fun clean() { dir.listFiles()?.forEach { it.delete() } }

    @Test fun saveWritesPngAndReturnsContentUri() = runBlocking {
        val uri = store.save(bitmap)
        assertTrue(uri.toString().startsWith("content://com.raidenyn.screentranslator.files/captures/"))
        val written = dir.listFiles()!!.single()
        assertTrue(written.name.endsWith(".png"))
    }

    @Test fun savedFileSurvivesAfterSave() = runBlocking {   // Review Focus 4
        store.save(bitmap)
        assertEquals(1, dir.listFiles()!!.size)
    }

    @Test fun twoSavesCreateDistinctFiles() = runBlocking {
        store.save(bitmap); store.save(bitmap)
        assertEquals(2, dir.listFiles()!!.size)
    }

    @Test fun purgeDeletesEverythingAndReturnsCount() = runBlocking {
        store.save(bitmap); store.save(bitmap)
        assertEquals(2, store.purge())
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun fromUsesPackageAuthorityAndCacheDir() {
        val s = CaptureStore.from(context)
        assertEquals("com.raidenyn.screentranslator.files", s.authority)
    }
}
