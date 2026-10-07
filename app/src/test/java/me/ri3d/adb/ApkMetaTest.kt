package me.ri3d.adb

import me.ri3d.adb.apps.Axml
import me.ri3d.adb.apps.InvalidApk
import me.ri3d.adb.apps.ZipIndex
import me.ri3d.adb.apps.incompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ApkMetaTest {
    // AndroidManifest.xml from github.com/JanRi3D/OpenAuto release v0.1.0 (aapt: versionCode 1, versionName 0.1.0, sdkVersion 16).
    private val manifest = javaClass.classLoader!!.getResourceAsStream("openauto-AndroidManifest.xml")!!.readBytes()

    @Test fun readsARealBinaryManifest() {
        val m = Axml.parse(manifest)
        assertEquals("me.ri3d.openauto", m.packageName)
        assertEquals(1L, m.versionCode)
        assertEquals("0.1.0", m.versionName)
        assertEquals(16, m.minSdk)
        assertNull(m.split)
        assertFalse(m.needsSplits)
    }

    @Test(expected = InvalidApk::class)
    fun rejectsTextXml() {
        Axml.parse("<manifest package=\"x\"/>".toByteArray())
    }

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z ->
            z.setComment("x".repeat(100)) // EOCD not at the very end
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (name in stored) {
                    e.method = ZipEntry.STORED
                    e.size = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e)
                z.write(data)
                z.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    /** Ranged reads as an HTTP server would serve them, counting bytes fetched. */
    private class Ranged(val bytes: ByteArray) {
        var fetched = 0L
        val read = { from: Long, to: Long ->
            require(from in 0..to && to < bytes.size) { "bad range $from-$to" }
            fetched += to - from + 1
            bytes.copyOfRange(from.toInt(), to.toInt() + 1)
        }
    }

    @Test fun readsManifestAndAbisThroughRangesOnly() {
        val filler = ByteArray(600_000) { (it * 31).toByte() }
        val apk = zip("classes.dex" to filler, "AndroidManifest.xml" to manifest, "lib/armeabi-v7a/libx.so" to ByteArray(10), "lib/x86/libx.so" to ByteArray(10), stored = setOf("classes.dex"))
        val r = Ranged(apk)
        val index = ZipIndex.read(apk.size.toLong(), r.read)
        assertEquals(setOf("armeabi-v7a", "x86"), index.abis)
        assertFalse(index.isSplitContainer)
        val m = Axml.parse(ZipIndex.entry(index.entries.getValue("AndroidManifest.xml"), r.read))
        assertEquals("me.ri3d.openauto", m.packageName)
        assertTrue("fetched ${r.fetched} of ${apk.size}", r.fetched < 100_000)
    }

    @Test fun storedManifestAndSplitContainers() {
        val apk = zip("AndroidManifest.xml" to manifest, stored = setOf("AndroidManifest.xml"))
        val r = Ranged(apk)
        val index = ZipIndex.read(apk.size.toLong(), r.read)
        assertEquals(1L, Axml.parse(ZipIndex.entry(index.entries.getValue("AndroidManifest.xml"), r.read)).versionCode)
        assertTrue(index.abis.isEmpty())

        val bundle = zip("base.apk" to ByteArray(10), "split_config.arm64_v8a.apk" to ByteArray(10), "toc.pb" to ByteArray(3))
        assertTrue(ZipIndex.read(bundle.size.toLong(), Ranged(bundle).read).isSplitContainer)
    }

    @Test(expected = InvalidApk::class)
    fun notAZip() {
        val junk = ByteArray(5000) { 7 }
        ZipIndex.read(junk.size.toLong(), Ranged(junk).read)
    }

    @Test fun compatibility() {
        assertNull(incompatibility(16, setOf("armeabi-v7a", "x86"), 19, "4.4.2", listOf("armeabi-v7a", "armeabi")))
        assertEquals("Needs Android 5.0+ · car runs 4.4.2", incompatibility(21, emptySet(), 19, "4.4.2", listOf("armeabi-v7a")))
        assertEquals("Built for arm64-v8a · car is armeabi-v7a, armeabi", incompatibility(16, setOf("arm64-v8a"), 19, "4.4.2", listOf("armeabi-v7a", "armeabi")))
        // No native code runs anywhere.
        assertNull(incompatibility(16, emptySet(), 16, "4.1", listOf("x86")))
    }
}
