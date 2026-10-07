package me.ri3d.adb

import me.ri3d.adb.adb.InstalledVersion
import me.ri3d.adb.adb.LegacyExit
import me.ri3d.adb.adb.LineSplitter
import me.ri3d.adb.adb.PmResult
import me.ri3d.adb.adb.parseDumpsysPackage
import me.ri3d.adb.adb.parseForeground
import me.ri3d.adb.adb.parseGetprop
import me.ri3d.adb.adb.parsePackageList
import me.ri3d.adb.adb.parsePmInstall
import me.ri3d.adb.adb.mvCommand
import me.ri3d.adb.adb.q
import me.ri3d.adb.adb.rmCommand
import me.ri3d.adb.apps.AppRow
import me.ri3d.adb.apps.ManagedApp
import me.ri3d.adb.apps.Release
import me.ri3d.adb.apps.Source
import me.ri3d.adb.car.Car
import me.ri3d.adb.car.Endpoint
import me.ri3d.adb.files.childOf
import me.ri3d.adb.files.parentOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class ParsersTest {

    // ---- numeric version comparison ----

    private fun row(installedCode: Long?, installedName: String?, releaseCode: Long, releaseName: String) = AppRow(
        app = ManagedApp("me.ri3d.welle", "Welle", Source.GitHub("JanRi3D/Welle")),
        installed = installedCode?.let { InstalledVersion(it, installedName) },
        installedKnown = true,
        release = Release("me.ri3d.welle", releaseCode, releaseName, 16, emptySet(), "https://x/y.apk", 1, null, "k", 0),
        checkError = null, incompatible = null, op = null,
    )

    @Test fun updateUsesVersionCodeNotVersionName() {
        // "1.10.0" > "1.9.0" numerically, but would sort lower as a string; versionCode decides.
        assertTrue(row(9, "1.9.0", 10, "1.10.0").updateAvailable)
        assertFalse(row(10, "1.10.0", 9, "1.9.0").updateAvailable)
        // Same versionName, higher versionCode: still an update.
        assertTrue(row(1, "1.0.0", 2, "1.0.0").updateAvailable)
        // Higher versionName, lower versionCode: not an update (and would be a downgrade).
        assertFalse(row(5, "0.1", 4, "9.9").updateAvailable)
        assertFalse(row(7, "x", 7, "x").updateAvailable)
        // Codes above Int.MAX_VALUE (versionCodeMajor) compare as Long.
        assertTrue(row(Int.MAX_VALUE.toLong(), "a", (1L shl 32) + 1, "b").updateAvailable)
    }

    @Test fun notInstalledIsInstallableNotUpdatable() {
        val r = row(null, null, 3, "1.0")
        assertFalse(r.updateAvailable)
        assertTrue(r.installable)
        assertFalse(r.copy(installedKnown = false).installable)
        assertFalse(r.copy(incompatible = "Needs Android 5.0+").installable)
    }

    // ---- remote path quoting ----

    @Test fun quotingIsSingleQuoteSafe() {
        assertEquals("'/sdcard/My Music/a.mp3'", q("/sdcard/My Music/a.mp3"))
        assertEquals("'/sdcard/Rock'\\''n'\\''roll.mp3'", q("/sdcard/Rock'n'roll.mp3"))
        assertEquals("'\$(reboot) `x` \$HOME'", q("\$(reboot) `x` \$HOME"))
        assertEquals("''", q(""))
    }

    @Test fun quotingRoundTripsThroughARealShell() {
        val sh = listOf("sh", "C:\\Program Files\\Git\\bin\\sh.exe").firstOrNull { runCatching { ProcessBuilder(it, "-c", "true").start().waitFor() == 0 }.getOrDefault(false) }
        assumeTrue("no POSIX shell on this machine", sh != null)
        for (name in listOf("plain", "with space", "it's", "a'b'c", "semi;colon", "\$(echo pwned)", "back`tick`", "star*", "new\nline", "\"dq\"", "back\\slash")) {
            // The script goes in on stdin: Windows' argument quoting would mangle it on the command line.
            val p = ProcessBuilder(sh, "-s").start()
            p.outputStream.use { it.write("printf %s ${q(name)}\n".toByteArray()) }
            val out = p.inputStream.readBytes().toString(Charsets.UTF_8)
            p.waitFor()
            assertEquals(name, out)
        }
    }

    @Test fun deletesWorkOnToolboxWithoutDashF() {
        assertEquals("rm '/sdcard/a b' </dev/null; rm '/sdcard/it'\\''s' </dev/null", rmCommand(listOf("/sdcard/a b", "/sdcard/it's")))
        assertFalse(rmCommand(listOf("x")).contains("-f"))
        assertEquals("mv '/x/.a.mhu-part' '/x/a' </dev/null", mvCommand("/x/.a.mhu-part", "/x/a"))
    }

    @Test fun remotePaths() {
        assertEquals("/sdcard", parentOf("/sdcard/Download"))
        assertEquals("/", parentOf("/sdcard"))
        assertEquals("/", parentOf("/"))
        assertEquals("/sdcard/a b", childOf("/sdcard", "a b"))
        assertEquals("/x", childOf("/", "x"))
    }

    // ---- legacy response parsing ----

    @Test fun getpropFromAndroid4() {
        val p = parseGetprop("[ro.build.version.release]: [4.4.2]\r\n[ro.build.version.sdk]: [19]\n[ro.product.cpu.abi]: [armeabi-v7a]\n[ro.product.cpu.abi2]: [armeabi]\n[empty]: []\n")
        assertEquals("4.4.2", p["ro.build.version.release"])
        assertEquals("19", p["ro.build.version.sdk"])
        assertEquals("", p["empty"])
        assertNull(p["ro.product.cpu.abilist"])
    }

    @Test fun packageList() {
        assertEquals(listOf("com.a", "org.b.c"), parsePackageList("package:com.a\r\npackage:org.b.c\n\nWARNING: linker: x\n"))
        // `pm list packages -f` style
        assertEquals(listOf("/data/app/x.apk"), parsePackageList("package:/data/app/x.apk=com.x"))
    }

    @Test fun dumpsysPackageOldAndNew() {
        val old = """
            Packages:
              Package [me.ri3d.welle] (41b2c8a0):
                userId=10052 gids=[3003]
                sharedUser=null
                pkg=Package{41b2d9b8 me.ri3d.welle}
                codePath=/data/app/me.ri3d.welle-1.apk
                versionCode=3 targetSdk=36
                versionName=1.0.2
                dataDir=/data/data/me.ri3d.welle
        """.trimIndent()
        assertEquals(InstalledVersion(3, "1.0.2"), parseDumpsysPackage(old, "me.ri3d.welle"))
        val new = """
            Packages:
              Package [me.ri3d.openauto] (c0ffee):
                appId=10187
                versionCode=12 minSdk=16 targetSdk=36
                minExtensionVersions=[]
                versionName=0.3.0 beta
              Package [other.pkg] (1):
                versionCode=99
        """.trimIndent()
        assertEquals(InstalledVersion(12, "0.3.0 beta"), parseDumpsysPackage(new, "me.ri3d.openauto"))
        assertNull(parseDumpsysPackage("Unable to find package: x\n", "x"))
        assertNull(parseDumpsysPackage(new, "missing.pkg"))
    }

    @Test fun pmInstallResults() {
        assertEquals(PmResult.Success, parsePmInstall("\tpkg: /data/local/tmp/mhu-1.apk\r\nSuccess\r\n"))
        assertEquals(PmResult.Success, parsePmInstall("Performing Streamed Install\nSuccess"))
        assertEquals(PmResult.Failure("INSTALL_FAILED_VERSION_DOWNGRADE", ""), parsePmInstall("\tpkg: /data/local/tmp/x.apk\nFailure [INSTALL_FAILED_VERSION_DOWNGRADE]"))
        val sig = parsePmInstall("Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package me.x signatures do not match previously installed version; ignoring!]")
        assertEquals("INSTALL_FAILED_UPDATE_INCOMPATIBLE", (sig as PmResult.Failure).code)
        assertTrue(sig.detail.startsWith("Package me.x signatures"))
        assertEquals("INSTALL_FAILED_INSUFFICIENT_STORAGE", (parsePmInstall("Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]") as PmResult.Failure).code)
        // No verdict at all must never count as success.
        assertTrue(parsePmInstall("") is PmResult.Failure)
        assertTrue(parsePmInstall("Error: java.lang.SecurityException: denied") is PmResult.Failure)
    }

    @Test fun foregroundFromOldAndNewDumpsys() {
        val kitkat = "  mCurrentFocus=Window{41f8a7d0 u0 com.estrongs.android.pop/com.estrongs.android.pop.view.FileExplorerActivity}\n  mFocusedApp=AppWindowToken{...}"
        val f = parseForeground(kitkat)!!
        assertEquals("com.estrongs.android.pop", f.packageName)
        assertEquals("mCurrentFocus", f.how)
        val q = "    topResumedActivity=ActivityRecord{8a1 u0 me.ri3d.welle/.ui.PlayerActivity t31}\n"
        val g = parseForeground(q)!!
        assertEquals("me.ri3d.welle", g.packageName)
        assertEquals("me.ri3d.welle/me.ri3d.welle.ui.PlayerActivity", g.component)
        assertNull(parseForeground("  mCurrentFocus=null\n  mFocusedApp=null"))
    }

    @Test fun legacyShellExitMarker() {
        fun run(chunks: List<String>): Pair<List<String>, Int?> {
            val out = mutableListOf<String>()
            val legacy = LegacyExit("__M__") { out += it }
            val s = LineSplitter(legacy::line)
            chunks.forEach { s.feed(it.toByteArray()) }
            s.finish()
            legacy.finish()
            return out to legacy.exitCode
        }
        // Output ending in a newline: our extra empty line is dropped. PTY line endings are \r\n.
        assertEquals(listOf("a", "b") to 0, run(listOf("a\r\nb\r\n", "\r\n__M__0\r\n")))
        // Output without a trailing newline, and the marker split across packets.
        assertEquals(listOf("partial") to 3, run(listOf("partial\r\n__", "M__3\r\n")))
        // A command that ended the shell: no marker, so no exit code is invented.
        assertEquals(listOf("bye") to null, run(listOf("bye\r\n")))
        // User output with its own blank last line keeps it.
        assertEquals(listOf("x", "") to 1, run(listOf("x\n\n\n__M__1\n")))
        // Multi-byte UTF-8 split between packets.
        val bytes = "grüß\n\n__M__0\n".toByteArray()
        val out = mutableListOf<String>()
        val legacy = LegacyExit("__M__") { out += it }
        val s = LineSplitter(legacy::line)
        s.feed(bytes.copyOfRange(0, 4))
        s.feed(bytes.copyOfRange(4, bytes.size))
        s.finish()
        assertEquals(listOf("grüß"), out)
    }

    // ---- configuration parsing ----

    @Test fun endpoints() {
        assertEquals(Endpoint("192.168.43.1", 5555), Endpoint.parse("192.168.43.1:5555"))
        assertEquals(Endpoint("192.168.43.1", 5555), Endpoint.parse(" 192.168.43.1 "))
        assertEquals(Endpoint("car.local", 7000), Endpoint.parse("car.local:7000"))
        assertNull(Endpoint.parse("192.168.43.1:99999"))
        assertNull(Endpoint.parse("http://x"))
        assertNull(Endpoint.parse(""))
    }

    @Test fun sources() {
        assertEquals(Source.GitHub("JanRi3D/Welle"), Source.parse("github:JanRi3D/Welle"))
        assertEquals(Source.GitHub("JanRi3D/OpenAuto"), Source.parse("https://github.com/JanRi3D/OpenAuto"))
        assertEquals(Source.GitHub("a/b"), Source.parse("https://github.com/a/b.git"))
        assertEquals(Source.Manifest("https://example.com/app.json"), Source.parse("https://example.com/app.json"))
        assertNull(Source.parse("http://insecure.example/app.json"))
        assertNull(Source.parse("welle"))
    }

    @Test fun backoffIsBounded() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L), (0..5).map { Car.backoff(it) })
        assertEquals(30_000L, Car.backoff(1_000))
    }

    @Test fun monograms() {
        assertEquals("OA", ManagedApp("x", "OpenAuto", null).monogram)
        assertEquals("FE", ManagedApp("x", "FileExplorer", null).monogram)
        assertEquals("W", ManagedApp("x", "Welle", null).monogram)
        assertEquals("MA", ManagedApp("x", "my app", null).monogram)
    }
}
