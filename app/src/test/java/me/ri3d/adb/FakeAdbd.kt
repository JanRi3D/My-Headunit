package me.ri3d.adb

import me.ri3d.adb.adb.AUTH
import me.ri3d.adb.adb.CLSE
import me.ri3d.adb.adb.CNXN
import me.ri3d.adb.adb.Msg
import me.ri3d.adb.adb.OKAY
import me.ri3d.adb.adb.OPEN
import me.ri3d.adb.adb.STLS
import me.ri3d.adb.adb.WRTE
import me.ri3d.adb.adb.encode
import me.ri3d.adb.adb.readMsg
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * A minimal adbd for tests: speaks the device side of the ADB protocol on a loopback socket,
 * with configurable version, max payload, authentication behaviour and services.
 */
internal class FakeAdbd(
    private val version: Int = 0x01000000,
    val maxData: Int = 4096,
    private val banner: String = "device::ro.product.name=hu;ro.product.model=HU;ro.product.device=hu;",
    private val auth: Auth = Auth.None,
    private val service: (name: String, s: DevStream) -> Unit = { _, _ -> },
    /** Services this adbd doesn't know: answered with CLSE instead of OKAY, like the real one. */
    private val refuse: (name: String) -> Boolean = { false },
) : Closeable {
    sealed interface Auth {
        data object None : Auth
        /** [accept] decides about a signature; [allowKey] answers the "Allow USB debugging?" prompt (null = no answer). */
        data class Required(val accept: (token: ByteArray, signature: ByteArray) -> Boolean, val allowKey: Boolean?) : Auth
        data object Tls : Auth
        data object Garbage : Auth
    }

    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val port get() = server.localPort
    /** Every packet the host sent, for assertions. */
    val fromHost: MutableList<Msg> = Collections.synchronizedList(mutableListOf())
    @Volatile var publicKeyReceived: ByteArray? = null
    private lateinit var socket: Socket
    private lateinit var out: OutputStream
    private val streams = ConcurrentHashMap<Int, DevStream>()
    private var nextId = 100

    init {
        thread(isDaemon = true) {
            socket = server.accept()
            out = socket.getOutputStream()
            val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
            val token = ByteArray(20) { it.toByte() }
            try {
                while (true) {
                    val m = input.readMsg()
                    fromHost += m
                    when (m.cmd) {
                        CNXN -> when (auth) {
                            Auth.None -> connected()
                            is Auth.Required -> send(AUTH, 1, 0, token)
                            Auth.Tls -> send(STLS, 0x01000000, 0)
                            Auth.Garbage -> synchronized(out) { out.write(ByteArray(24) { 0x41 }); out.flush() }
                        }
                        AUTH -> {
                            val a = auth as Auth.Required
                            when (m.arg0) {
                                2 -> if (a.accept(token, m.data)) connected() else send(AUTH, 1, 0, token)
                                3 -> {
                                    publicKeyReceived = m.data
                                    when (a.allowKey) {
                                        true -> connected()
                                        false -> socket.close()
                                        null -> Unit
                                    }
                                }
                            }
                        }
                        OPEN -> if (refuse(String(m.data).trimEnd(Char(0)))) send(CLSE, 0, m.arg0) else {
                            val id = nextId++
                            val name = String(m.data).trimEnd('\u0000')
                            val s = DevStream(this, id, m.arg0)
                            streams[id] = s
                            send(OKAY, id, m.arg0)
                            thread(isDaemon = true) {
                                try {
                                    service(name, s)
                                } catch (_: Exception) {
                                }
                                s.close()
                            }
                        }
                        WRTE -> {
                            streams[m.arg1]?.incoming?.put(m.data)
                            send(OKAY, m.arg1, m.arg0)
                        }
                        OKAY -> streams[m.arg1]?.acks?.put(Unit)
                        CLSE -> streams.remove(m.arg1)?.incoming?.put(ByteArray(0))
                    }
                }
            } catch (_: Exception) {
                // host went away or the test closed us
            }
        }
    }

    private fun connected() = send(CNXN, version, maxData, (banner + "\u0000").toByteArray())

    fun send(cmd: Int, a0: Int, a1: Int, data: ByteArray = ByteArray(0)) {
        synchronized(out) {
            out.write(encode(cmd, a0, a1, data))
            out.flush()
        }
    }

    /** Simulates the car disappearing (Wi-Fi gone, power off). */
    fun drop() = socket.close()

    override fun close() {
        runCatching { socket.close() }
        server.close()
    }

    class DevStream(private val d: FakeAdbd, val id: Int, private val hostId: Int) {
        val incoming = LinkedBlockingQueue<ByteArray>()
        val acks = LinkedBlockingQueue<Unit>()
        private var buf = ByteArray(0)
        private var pos = 0
        @Volatile private var closed = false

        fun write(data: ByteArray) {
            var o = 0
            while (o < data.size) {
                val n = minOf(d.maxData, data.size - o)
                d.send(WRTE, id, hostId, data.copyOfRange(o, o + n))
                acks.poll(5, TimeUnit.SECONDS) ?: error("host never acknowledged")
                o += n
            }
        }

        fun write(text: String) = write(text.toByteArray())

        /** Exactly [n] bytes, or null if the host closed the stream first. */
        fun read(n: Int): ByteArray? {
            val out = ByteArray(n)
            var o = 0
            while (o < n) {
                if (pos >= buf.size) {
                    buf = incoming.poll(5, TimeUnit.SECONDS) ?: error("host sent nothing")
                    pos = 0
                    if (buf.isEmpty()) return null
                }
                val k = minOf(n - o, buf.size - pos)
                System.arraycopy(buf, pos, out, o, k)
                pos += k
                o += k
            }
            return out
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { d.send(CLSE, id, hostId) }
        }
    }
}
