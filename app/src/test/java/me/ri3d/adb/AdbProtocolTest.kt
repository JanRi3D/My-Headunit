package me.ri3d.adb

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ri3d.adb.adb.AdbConnection
import me.ri3d.adb.adb.AdbException
import me.ri3d.adb.adb.AdbKey
import me.ri3d.adb.adb.AuthNotGranted
import me.ri3d.adb.adb.ConnectionLost
import me.ri3d.adb.adb.Sync
import me.ri3d.adb.adb.SyncFailed
import me.ri3d.adb.adb.WRTE
import me.ri3d.adb.adb.exec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher

class AdbProtocolTest {
    companion object {
        private val pair: KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val key = AdbKey.create(pair.public as RSAPublicKey, "test@junit") { AdbKey.signToken(pair.private, it) }
        private val SHA1_PREFIX = byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)

        /** What adbd does: RSA_verify(NID_sha1, token, signature) with the stored public key. */
        fun verifies(token: ByteArray, signature: ByteArray): Boolean = runCatching {
            Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
                init(Cipher.DECRYPT_MODE, pair.public)
                doFinal(signature).contentEquals(SHA1_PREFIX + token)
            }
        }.getOrDefault(false)
    }

    private fun connect(d: FakeAdbd, onPrompt: () -> Unit = {}): AdbConnection = AdbConnection.connect(Socket("127.0.0.1", d.port), key, onPrompt)

    /** Device-side SYNC over an in-memory file system, like an Android 4.x adbd. */
    private fun syncService(fs: ConcurrentHashMap<String, ByteArray>, failAfter: Int? = null): (String, FakeAdbd.DevStream) -> Unit = handler@{ name, s ->
        if (name == "sync:") while (true) {
            val h = s.read(8) ?: break
            val id = String(h, 0, 4)
            val len = ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
            fun reply(id: String, vararg ints: Int, tail: ByteArray = ByteArray(0)) {
                val b = ByteBuffer.allocate(4 + ints.size * 4 + tail.size).order(ByteOrder.LITTLE_ENDIAN)
                b.put(id.toByteArray()); ints.forEach { b.putInt(it) }; b.put(tail)
                s.write(b.array())
            }
            when (id) {
                "QUIT" -> break
                "STAT" -> {
                    val p = String(s.read(len)!!)
                    val f = fs[p]
                    if (f == null) reply("STAT", 0, 0, 0) else reply("STAT", 0x81a4, f.size, 1_700_000_000)
                }
                "LIST" -> {
                    val p = String(s.read(len)!!).trimEnd('/')
                    val names = fs.keys.filter { it.substringBeforeLast('/') == p }.map { it.substringAfterLast('/') }
                    if (fs.keys.any { it.startsWith("$p/") }) (listOf(".", "..") + names).forEach { n ->
                        val mode = if (n == "." || n == "..") 0x41f9 else 0x81a4
                        reply("DENT", mode, fs["$p/$n"]?.size ?: 4096, 1_700_000_000, n.toByteArray().size, tail = n.toByteArray())
                    }
                    reply("DONE", 0, 0, 0, 0)
                }
                "SEND" -> {
                    val spec = String(s.read(len)!!)
                    val path = spec.substringBeforeLast(',')
                    val data = ByteArrayOutputStream()
                    var chunks = 0
                    while (true) {
                        val dh = s.read(8)!!
                        val dlen = ByteBuffer.wrap(dh, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                        if (String(dh, 0, 4) == "DONE") break
                        data.write(s.read(dlen)!!)
                        if (failAfter != null && ++chunks >= failAfter) {
                            val msg = "No space left on device".toByteArray()
                            reply("FAIL", msg.size, tail = msg)
                            return@handler // the handler ends; the stream is closed like adbd does
                        }
                    }
                    fs[path] = data.toByteArray()
                    reply("OKAY", 0)
                }
                "RECV" -> {
                    val p = String(s.read(len)!!)
                    val f = fs[p]
                    if (f == null) {
                        val msg = "No such file or directory".toByteArray()
                        reply("FAIL", msg.size, tail = msg)
                    } else {
                        f.toList().chunked(65536).forEach { c -> reply("DATA", c.size, tail = c.toByteArray()) }
                        reply("DONE", 0)
                    }
                }
            }
        }
    }

    @Test fun legacyDeviceShellAndSyncWith4KiBPackets() = runBlocking {
        val fs = ConcurrentHashMap<String, ByteArray>()
        fs["/sdcard/Download/a b.txt"] = "hello".toByteArray()
        val sync = syncService(fs)
        FakeAdbd(service = { name, s ->
            if (name.startsWith("shell:")) {
                // Legacy: no exit status of its own; the client appends a status line after a newline.
                val marker = Regex("""(__MHU_\w+__)\$\?""").find(name)!!.groupValues[1]
                assertTrue(name.startsWith("shell:getprop ro.build.version.sdk\necho \"\n"))
                s.write("16\r\n\r\n${marker}0\r\n")
            } else sync(name, s)
        }).use { d ->
            val c = connect(d)
            assertEquals(4096, c.maxPayload)
            assertFalse(c.authenticated)
            assertTrue(c.features.isEmpty())
            assertEquals("HU", c.properties["ro.product.model"])

            val r = c.exec("getprop ro.build.version.sdk")
            assertEquals("16", r.stdout)
            assertEquals(0, r.exitCode)

            val payload = ByteArray(20_000) { (it % 251).toByte() }
            Sync.open(c).use { it.send("/sdcard/Download/new file's.bin", 420, 1, payload.inputStream()) {} }
            assertArrayEquals(payload, fs["/sdcard/Download/new file's.bin"])
            assertTrue("host packets must fit the car's 4 KiB limit", d.fromHost.filter { it.cmd == WRTE }.all { it.data.size <= 4096 })

            val list = Sync.open(c).use { it.list("/sdcard/Download") }
            assertEquals(setOf(".", "..", "a b.txt", "new file's.bin"), list.map { it.name }.toSet())
            assertTrue(list.first { it.name == "." }.isDir)
            assertEquals(20_000L, list.first { it.name == "new file's.bin" }.size)

            val out = ByteArrayOutputStream()
            val n = Sync.open(c).use { it.recv("/sdcard/Download/a b.txt", out) {} }
            assertEquals(5L, n)
            assertEquals("hello", out.toString())
            assertEquals(5L, Sync.open(c).use { it.stat("/sdcard/Download/a b.txt") }.size)
            assertFalse(Sync.open(c).use { it.stat("/nope") }.exists)
            c.close()
        }
    }

    @Test fun shellV2ReportsStderrAndExitCode() = runBlocking {
        fun pkt(id: Int, data: ByteArray) = ByteBuffer.allocate(5 + data.size).order(ByteOrder.LITTLE_ENDIAN).put(id.toByte()).putInt(data.size).put(data).array()
        FakeAdbd(version = 0x01000001, maxData = 256 * 1024, banner = "device::ro.product.model=New;features=shell_v2,cmd,stat_v2", service = { name, s ->
            assertEquals("shell,v2,raw:ls /nope", name)
            s.write(pkt(1, "partial ".toByteArray()) + pkt(1, "line\n".toByteArray()) + pkt(2, "ls: /nope: No such file\n".toByteArray()) + pkt(3, byteArrayOf(1)))
        }).use { d ->
            val c = connect(d)
            val lines = mutableListOf<Pair<String, Boolean>>()
            val r = c.exec("ls /nope") { l, e -> lines += l to e }
            assertEquals("partial line", r.stdout)
            assertEquals("ls: /nope: No such file", r.stderr)
            assertEquals(1, r.exitCode)
            assertEquals(listOf("partial line" to false, "ls: /nope: No such file" to true), lines)
            c.close()
        }
    }

    @Test fun signatureIsAcceptedByAdbdsCheck() {
        FakeAdbd(auth = FakeAdbd.Auth.Required(::verifies, allowKey = null)).use { d ->
            var prompted = false
            val c = connect(d) { prompted = true }
            assertTrue(c.authenticated)
            assertFalse(prompted)
            assertNull(d.publicKeyReceived)
            c.close()
        }
    }

    @Test fun unknownKeyPromptsAndCanBeAllowed() {
        FakeAdbd(auth = FakeAdbd.Auth.Required({ _, _ -> false }, allowKey = true)).use { d ->
            var prompted = false
            val c = connect(d) { prompted = true }
            assertTrue(prompted)
            val sent = String(d.publicKeyReceived!!)
            assertTrue(sent.endsWith(" test@junit\u0000"))
            assertEquals(524, Base64.getDecoder().decode(sent.substringBefore(' ')).size)
            c.close()
        }
    }

    @Test fun deniedPromptIsAnAuthorizationFailure() {
        FakeAdbd(auth = FakeAdbd.Auth.Required({ _, _ -> false }, allowKey = false)).use { d ->
            var prompted = false
            try {
                connect(d) { prompted = true }
                fail("connected without authorization")
            } catch (e: AuthNotGranted) {
                assertTrue(prompted)
            }
        }
    }

    @Test fun tlsOnlyEndpointIsExplained() {
        FakeAdbd(auth = FakeAdbd.Auth.Tls).use { d ->
            val e = runCatching { connect(d) }.exceptionOrNull()
            assertTrue(e is AdbException && "wireless debugging" in e.message!!)
        }
    }

    @Test fun somethingElseOnThePortIsNotAdb() {
        FakeAdbd(auth = FakeAdbd.Auth.Garbage).use { d ->
            val e = runCatching { connect(d) }.exceptionOrNull()
            assertTrue(e is AdbException && "doesn't speak ADB" in e.message!!)
        }
    }

    @Test fun refusedServiceFailsCleanly() = runBlocking {
        FakeAdbd(service = { _, s -> s.close() }, refuse = { it.startsWith("shell,v2") }).use { d ->
            val c = connect(d)
            val e = runCatching { c.open("shell,v2,raw:id") }.exceptionOrNull()
            assertTrue("got $e", e is AdbException && "refused" in e.message!!)
            // A service that closes right after opening reads as EOF, not a hang; the connection stays usable.
            val s = c.open("nonexistent:")
            assertNull(withTimeout(5_000) { s.read() })
            assertFalse(c.closed.isCompleted)
            c.close()
        }
    }

    @Test fun uploadInterruptedByTheCarReportsItsReason() = runBlocking {
        val fs = ConcurrentHashMap<String, ByteArray>()
        FakeAdbd(service = syncService(fs, failAfter = 2)).use { d ->
            val c = connect(d)
            val e = runCatching { Sync.open(c).use { it.send("/data/local/tmp/x.apk", 420, 1, ByteArray(300_000).inputStream()) {} } }.exceptionOrNull()
            assertTrue("got $e", e is SyncFailed && e.message == "No space left on device")
            assertNull(fs["/data/local/tmp/x.apk"])
            c.close()
        }
    }

    @Test fun connectionLossDuringDownloadIsReportedAndClosesTheConnection() = runBlocking {
        lateinit var dev: FakeAdbd
        dev = FakeAdbd(service = { name, s ->
            if (name == "sync:") {
                val h = s.read(8)!!
                s.read(ByteBuffer.wrap(h, 4, 4).order(ByteOrder.LITTLE_ENDIAN).int)
                s.write(byteArrayOf('D'.code.toByte(), 'A'.code.toByte(), 'T'.code.toByte(), 'A'.code.toByte(), 0, 0x10, 0, 0) + ByteArray(1000))
                dev.drop() // 3096 bytes of DATA still owed
            }
        })
        dev.use { d ->
            val c = connect(d)
            val out = ByteArrayOutputStream()
            val e = runCatching { Sync.open(c).use { it.recv("/x.mp3", out) {} } }.exceptionOrNull()
            assertTrue("got $e", e is ConnectionLost)
            assertTrue(c.closed.isCompleted)
            assertTrue(runCatching { c.exec("echo") }.exceptionOrNull() is ConnectionLost)
        }
    }

    @Test fun publicKeyStructMatchesMincrypt() {
        val pub = pair.public as RSAPublicKey
        val b = ByteBuffer.wrap(AdbKey.adbPublicKeyStruct(pub)).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(524, b.capacity())
        assertEquals(64, b.getInt(0))
        fun le(off: Int) = BigInteger(1, ByteArray(256) { b.get(off + 255 - it) })
        assertEquals(pub.modulus, le(8))
        assertEquals(BigInteger.ONE.shiftLeft(4096).mod(pub.modulus), le(264))
        assertEquals(65537, b.getInt(520))
        // n0inv * n[0] == -1 (mod 2^32)
        val n0 = pub.modulus.mod(BigInteger.ONE.shiftLeft(32)).toLong()
        val n0inv = b.getInt(4).toLong() and 0xffffffffL
        assertEquals(0xffffffffL, (n0 * n0inv) and 0xffffffffL)
        assertEquals(47, key.fingerprint.length) // 16 bytes as AA:BB:…
    }
}
