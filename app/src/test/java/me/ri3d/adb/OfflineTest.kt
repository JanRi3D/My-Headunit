package me.ri3d.adb

import me.ri3d.adb.adb.InstalledVersion
import me.ri3d.adb.apps.ApkCache
import me.ri3d.adb.apps.CarSnapshot
import me.ri3d.adb.apps.CarTraits
import me.ri3d.adb.apps.Release
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class OfflineTest {
    private fun release(pkg: String, code: Long) = Release(pkg, code, "v$code", 16, emptySet(), "https://x/$pkg.apk", 10, null, "k$code", 0)

    @Test fun snapshotSurvivesStorage() {
        val s = CarSnapshot(
            "ADAYO AC822X", null, CarTraits(17, "4.2.2", listOf("armeabi-v7a", "armeabi")),
            mapOf("me.ri3d.welle" to InstalledVersion(3, "1.0.2"), "x.y" to InstalledVersion(1L shl 33, null)), 1_791_000_000_000,
        )
        assertEquals(s, CarSnapshot.fromJson(JSONObject(s.toJson().toString())))
    }

    @Test fun whichDownloadsAreStillNeeded() {
        // Car has 3, download is 4 and still the newest release: keep.
        assertFalse(ApkCache.isStale(4, InstalledVersion(3, null), release("a", 4)))
        // Not on the car: keep for installing.
        assertFalse(ApkCache.isStale(4, null, release("a", 4)))
        // The car got this version (or newer) meanwhile: drop.
        assertTrue(ApkCache.isStale(4, InstalledVersion(4, null), release("a", 4)))
        assertTrue(ApkCache.isStale(4, InstalledVersion(5, null), null))
        // A newer release replaced it: drop.
        assertTrue(ApkCache.isStale(4, InstalledVersion(3, null), release("a", 5)))
    }

    @Test fun keepAndPruneFiles() {
        val dir = Files.createTempDirectory("apks").toFile()
        try {
            dir.resolve("me.ri3d.welle-3.apk").writeText("old")
            dir.resolve("me.ri3d.welle-4.apk").writeText("new")
            dir.resolve("me.ri3d.openauto-2.apk.part").writeText("incomplete download")
            val cache = ApkCache(dir)
            assertEquals(setOf("me.ri3d.welle"), cache.versions.value.keys)

            cache.keep("me.ri3d.welle", 4)
            assertEquals(mapOf("me.ri3d.welle" to 4L), cache.versions.value)
            assertFalse(dir.resolve("me.ri3d.welle-3.apk").exists())
            assertTrue(cache.has(release("me.ri3d.welle", 4)))
            assertFalse(cache.has(release("me.ri3d.welle", 5)))

            cache.prune(mapOf("me.ri3d.welle" to InstalledVersion(4, "1.1")), mapOf("me.ri3d.welle" to release("me.ri3d.welle", 4)))
            assertTrue(cache.versions.value.isEmpty())
            assertFalse(dir.resolve("me.ri3d.welle-4.apk").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
