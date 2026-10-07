package me.ri3d.adb

import kotlinx.coroutines.runBlocking
import me.ri3d.adb.adb.AdbConnection
import me.ri3d.adb.adb.ConnectionLost
import me.ri3d.adb.adb.PmResult
import me.ri3d.adb.adb.Sync
import me.ri3d.adb.adb.exec
import me.ri3d.adb.adb.parseDumpsysPackage
import me.ri3d.adb.adb.parseForeground
import me.ri3d.adb.adb.parseGetprop
import me.ri3d.adb.adb.parsePackageList
import me.ri3d.adb.adb.parsePmInstall
import me.ri3d.adb.adb.mvCommand
import me.ri3d.adb.adb.q
import me.ri3d.adb.adb.rmCommand
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.Socket
import kotlin.random.Random

/**
 * Runs the ADB client against a real adbd (an emulator or the head unit).
 * Opt-in: MHU_CAR=host:port, optionally MHU_APK=path/to/single.apk to install.
 *   MHU_CAR=127.0.0.1:5555 ./gradlew :app:testDebugUnitTest --tests '*RealCarTest*' --rerun
 */
class RealCarTest {
    private val car = System.getenv("MHU_CAR")

    private fun connect(): AdbConnection {
        val (host, port) = car!!.split(':')
        return AdbConnection.connect(Socket(host, port.toInt()), AdbProtocolTest.key) { println("car asks to allow key ${AdbProtocolTest.key.fingerprint}") }
    }

    @Test fun everythingTheAppDoes() = runBlocking {
        assumeTrue("set MHU_CAR=host:port to run against a real adbd", car != null)
        val c = connect()
        println("banner=${c.banner} version=${Integer.toHexString(c.version)} maxPayload=${c.maxPayload} features=${c.features} auth=${c.authenticated}")

        val props = parseGetprop(c.exec("getprop").stdout)
        val sdk = props["ro.build.version.sdk"]!!.toInt()
        println("Android ${props["ro.build.version.release"]} API $sdk abi=${props["ro.product.cpu.abilist"] ?: props["ro.product.cpu.abi"]} model=${props["ro.product.model"]}")

        val ok = c.exec("echo hi")
        assertEquals("hi", ok.stdout)
        assertEquals(0, ok.exitCode)
        val bad = c.exec("ls /definitely/not/here")
        println("ls missing -> exit=${bad.exitCode} out=${bad.output}")
        assertTrue(bad.exitCode != 0)

        val pkgs = parsePackageList(c.exec("pm list packages -3", 60_000).stdout)
        println("${pkgs.size} user packages: ${pkgs.take(5)}")

        // SYNC with an awkward name, through the same quoting the app uses for shell commands.
        val dir = "/data/local/tmp/mhu-it"
        val path = "$dir/it's a file.bin"
        val data = Random(1).nextBytes(300_000)
        c.exec("mkdir ${q(dir)}")
        Sync.open(c).use { it.send(path, 420, System.currentTimeMillis() / 1000, data.inputStream()) {} }
        val st = Sync.open(c).use { it.stat(path) }
        assertEquals(300_000L, st.size)
        assertTrue(st.isFile)
        val listing = Sync.open(c).use { it.list(dir) }
        assertTrue(listing.any { it.name == "." })
        assertTrue(listing.any { it.name == "it's a file.bin" })
        val back = ByteArrayOutputStream()
        Sync.open(c).use { it.recv(path, back) {} }
        assertArrayEquals(data, back.toByteArray())
        val mv = c.exec(mvCommand(path, "$dir/renamed file.bin"))
        assertEquals("mv: ${mv.output}", 0, mv.exitCode)
        c.exec(rmCommand(listOf("$dir/renamed file.bin")))
        assertFalse(Sync.open(c).use { it.stat("$dir/renamed file.bin") }.exists)
        // Unreadable folder: adbd lists nothing, not even ".".
        val denied = Sync.open(c).use { it.list("/data/data") }
        println("LIST /data/data -> ${denied.size} entries")

        val fg = parseForeground(c.exec(if (sdk >= 29) "dumpsys activity activities" else "dumpsys window windows", 30_000).stdout)
        println("foreground=$fg")
        assertNotNull(fg)

        // A file that isn't an APK must be refused, and that refusal must be read as failure.
        val junk = "/data/local/tmp/mhu-junk.apk"
        Sync.open(c).use { it.send(junk, 420, 0, Random(2).nextBytes(4096).inputStream()) {} }
        val refused = parsePmInstall(c.exec("pm install -r ${q(junk)}", 120_000).output)
        println("junk install -> $refused")
        assertTrue(refused is PmResult.Failure)
        c.exec(rmCommand(listOf(junk)))

        System.getenv("MHU_APK")?.let { apkPath ->
            val apk = File(apkPath)
            val remote = "/data/local/tmp/mhu-it.apk"
            Sync.open(c).use { s -> apk.inputStream().use { s.send(remote, 420, 0, it) {} } }
            val res = c.exec("pm install -r ${q(remote)}", 600_000)
            println("pm install -> ${res.output.trim()} (exit ${res.exitCode})")
            assertEquals(PmResult.Success, parsePmInstall(res.output))
            val pkg = System.getenv("MHU_APK_PKG") ?: "me.ri3d.openauto"
            val v = parseDumpsysPackage(c.exec("dumpsys package ${q(pkg)}", 60_000).stdout, pkg)
            println("installed $pkg -> $v")
            assertNotNull(v)
            c.exec(rmCommand(listOf(remote)))
        }

        // Connection loss in the middle of an upload surfaces as ConnectionLost.
        val endless = object : InputStream() {
            var n = 0
            override fun read() = 0
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++n == 20) c.close(ConnectionLost("test: dropped"))
                return len
            }
        }
        val e = runCatching { Sync.open(c).use { it.send("$dir/endless.bin", 420, 0, endless) {} } }.exceptionOrNull()
        assertTrue("got $e", e is ConnectionLost)

        // A fresh connection still works and can clean up after the interrupted upload.
        val c2 = connect()
        c2.exec("rm -r ${q(dir)}")
        assertFalse(Sync.open(c2).use { it.stat(dir) }.exists)
        c2.close()
    }
}
