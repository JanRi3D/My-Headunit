package me.ri3d.adb.adb

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ADB wire protocol (system/core/adb/protocol.txt), host side, over one TCP socket.
// Written in-app because the socket has to be bound to the car's Wi-Fi Network object and
// the client has to fall back to the pre-Android-7 services; see README "Why an own ADB client".

open class AdbException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The connection to the car died (socket error, Wi-Fi gone, no answer). */
class ConnectionLost(message: String, cause: Throwable? = null) : AdbException(message, cause)

/** The car asked its user to allow this phone, and that didn't happen. Retrying needs the user. */
class AuthNotGranted(message: String, cause: Throwable? = null) : AdbException(message, cause)

internal const val CNXN = 0x4e584e43
internal const val AUTH = 0x48545541
internal const val OPEN = 0x4e45504f
internal const val OKAY = 0x59414b4f
internal const val CLSE = 0x45534c43
internal const val WRTE = 0x45545257
internal const val STLS = 0x534c5453

internal class Msg(val cmd: Int, val arg0: Int, val arg1: Int, val data: ByteArray)

private val EMPTY = ByteArray(0)

internal fun encode(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = EMPTY, off: Int = 0, len: Int = data.size): ByteArray {
    var sum = 0
    for (i in off until off + len) sum += data[i].toInt() and 0xff
    val b = ByteBuffer.allocate(24 + len).order(ByteOrder.LITTLE_ENDIAN)
    b.putInt(cmd).putInt(arg0).putInt(arg1).putInt(len).putInt(sum).putInt(cmd.inv())
    b.put(data, off, len)
    return b.array()
}

internal fun DataInputStream.readMsg(): Msg {
    val h = ByteArray(24)
    readFully(h)
    val b = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
    val cmd = b.int
    val arg0 = b.int
    val arg1 = b.int
    val len = b.int
    b.int // checksum: TCP already guarantees integrity
    if (b.int != cmd.inv()) throw AdbException("The other side doesn't speak ADB (bad packet header)")
    if (len < 0 || len > 1024 * 1024) throw AdbException("ADB packet too large ($len bytes)")
    val data = if (len == 0) EMPTY else ByteArray(len).also { readFully(it) }
    return Msg(cmd, arg0, arg1, data)
}

/**
 * The phone's ADB identity: [publicKey] in adbd's format ("<base64> name\0") and a signer for
 * AUTH tokens. The car shows [fingerprint] in its "Allow USB debugging?" dialog.
 */
class AdbKey(val publicKey: ByteArray, val fingerprint: String, val sign: (token: ByteArray) -> ByteArray) {
    companion object {
        // DER prefix of a SHA-1 DigestInfo: adbd verifies the token as if it were a SHA-1 digest.
        private val SHA1_DIGEST_INFO = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
        )

        /** RSA PKCS#1 v1.5 over the raw token, which is what adbd's RSA_verify(NID_sha1) expects. */
        fun signToken(privateKey: PrivateKey, token: ByteArray): ByteArray =
            Signature.getInstance("NONEwithRSA").run {
                initSign(privateKey)
                update(SHA1_DIGEST_INFO + token)
                sign()
            }

        fun create(pub: RSAPublicKey, name: String, sign: (ByteArray) -> ByteArray): AdbKey {
            val struct = adbPublicKeyStruct(pub)
            val text = Base64.getEncoder().encodeToString(struct) + " " + name + "\u0000"
            val fingerprint = MessageDigest.getInstance("MD5").digest(struct).joinToString(":") { "%02X".format(it) }
            return AdbKey(text.toByteArray(Charsets.US_ASCII), fingerprint, sign)
        }

        /** mincrypt's RSAPublicKey struct: len, n0inv, n[], rr[], e, all little-endian 32-bit words. */
        internal fun adbPublicKeyStruct(pub: RSAPublicKey): ByteArray {
            val words = 2048 / 32
            val n = pub.modulus
            require(n.bitLength() == 2048) { "ADB keys must be 2048-bit RSA" }
            val r32 = BigInteger.ONE.shiftLeft(32)
            val n0inv = r32.subtract(n.mod(r32).modInverse(r32)).mod(r32)
            val rr = BigInteger.ONE.shiftLeft(4096).mod(n)
            val b = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN)
            b.putInt(words).putInt(n0inv.toInt())
            b.put(littleEndian(n, 256)).put(littleEndian(rr, 256))
            b.putInt(pub.publicExponent.toInt())
            return b.array()
        }

        private fun littleEndian(v: BigInteger, size: Int): ByteArray {
            val be = v.toByteArray().let { if (it.size > size) it.copyOfRange(it.size - size, it.size) else it }
            val out = ByteArray(size)
            for (i in be.indices) out[i] = be[be.size - 1 - i]
            return out
        }
    }
}

/** One open ADB connection: demultiplexes streams over the socket. Use streams from Dispatchers.IO. */
class AdbConnection private constructor(
    private val socket: Socket,
    private val input: DataInputStream,
    private val output: OutputStream,
    /** "device::ro.product.name=…;ro.product.model=…;features=…" */
    val banner: String,
    /** Largest payload the car accepts per packet: 4 KiB on Android 4.x, up to 1 MiB later. */
    val maxPayload: Int,
    val version: Int,
    /** True when the car demanded an RSA signature (Android 4.2.2+ with ADB authentication). */
    val authenticated: Boolean,
) : Closeable {
    val properties: Map<String, String> = banner.substringAfter("::", "").split(';')
        .mapNotNull { kv -> kv.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()
    val features: Set<String> = properties["features"]?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    private val streams = ConcurrentHashMap<Int, AdbStream>()
    private val nextId = AtomicInteger(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val death = CompletableDeferred<ConnectionLost>()

    /** Completes with the reason once the connection is gone. */
    val closed: Deferred<ConnectionLost> get() = death

    /** Why the connection ended, or null while it is up. */
    @Volatile var lostReason: ConnectionLost? = null
        private set

    /** Uptime-independent time of the last packet from the car, for idle health checks. */
    @Volatile var lastReceived = System.currentTimeMillis()
        private set

    init {
        scope.launch {
            try {
                while (true) dispatch(input.readMsg())
            } catch (e: Throwable) {
                close(ConnectionLost(if (e is EOFException) "The car closed the ADB connection" else "Connection to the car lost: ${e.message ?: e.javaClass.simpleName}", e))
            }
        }
    }

    private fun dispatch(m: Msg) {
        lastReceived = System.currentTimeMillis()
        when (m.cmd) {
            OKAY -> streams[m.arg1]?.onOkay(m.arg0)
            WRTE -> streams[m.arg1]?.onData(m.data) ?: send(CLSE, m.arg1, m.arg0)
            CLSE -> streams.remove(m.arg1)?.onClose(null)
            // CNXN/AUTH after the handshake: nothing to do.
        }
    }

    internal fun send(cmd: Int, arg0: Int, arg1: Int, data: ByteArray = EMPTY, off: Int = 0, len: Int = data.size) {
        val packet = encode(cmd, arg0, arg1, data, off, len)
        try {
            synchronized(output) {
                output.write(packet)
                output.flush()
            }
        } catch (e: IOException) {
            val lost = ConnectionLost("Connection to the car lost: ${e.message}", e)
            close(lost)
            throw lost
        }
    }

    /** Opens a service such as "shell:ls" or "sync:". Throws [AdbException] if the car refuses it. */
    suspend fun open(service: String): AdbStream {
        lostReason?.let { throw it }
        val id = nextId.getAndIncrement()
        val stream = AdbStream(this, id, service)
        streams[id] = stream
        withContext(Dispatchers.IO) { send(OPEN, id, 0, (service + "\u0000").toByteArray()) }
        val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) { stream.opened.await() }
        if (opened == null) {
            streams.remove(id)
            throw AdbException("The car didn't answer the request to open \"${service.substringBefore(':')}\"")
        }
        return stream
    }

    internal fun closeStream(stream: AdbStream) {
        if (streams.remove(stream.localId) != null && !death.isCompleted) {
            runCatching { send(CLSE, stream.localId, stream.remoteId) }
        }
    }

    fun close(reason: ConnectionLost) {
        synchronized(this) {
            if (lostReason != null) return
            lostReason = reason
        }
        death.complete(reason)
        runCatching { socket.close() }
        streams.values.forEach { it.onClose(reason) }
        streams.clear()
        scope.cancel()
    }

    override fun close() = close(ConnectionLost("Disconnected"))

    companion object {
        const val VERSION = 0x01000001
        const val MAX_PAYLOAD = 256 * 1024
        const val HANDSHAKE_TIMEOUT_MS = 10_000
        const val AUTH_TIMEOUT_MS = 90_000
        const val OPEN_TIMEOUT_MS = 15_000L

        /**
         * CNXN/AUTH on an already connected socket. Blocking: call from Dispatchers.IO and close the
         * socket to abort. [onAuthPrompt] runs once the car has to ask its user to allow this phone.
         */
        fun connect(socket: Socket, key: AdbKey, onAuthPrompt: () -> Unit): AdbConnection {
            val input = DataInputStream(BufferedInputStream(socket.getInputStream(), 64 * 1024))
            val output = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            fun write(cmd: Int, a0: Int, a1: Int, data: ByteArray) {
                output.write(encode(cmd, a0, a1, data))
                output.flush()
            }
            socket.soTimeout = HANDSHAKE_TIMEOUT_MS
            write(CNXN, VERSION, MAX_PAYLOAD, "host::features=shell_v2,cmd\u0000".toByteArray())
            var signed = false
            var keySent = false
            while (true) {
                val m = try {
                    input.readMsg()
                } catch (e: SocketTimeoutException) {
                    throw if (keySent) AuthNotGranted("This phone wasn't allowed on the car in time. Tap Allow on the car's screen, then try again.", e)
                    else AdbException("No ADB answer. Is ADB over network enabled on the car?", e)
                } catch (e: EOFException) {
                    throw if (keySent) AuthNotGranted("The car refused this phone. Allow it on the car's screen, then try again.", e)
                    else AdbException("The car closed the connection during the ADB handshake", e)
                }
                when (m.cmd) {
                    CNXN -> {
                        socket.soTimeout = 0 // idle connections are checked with pings instead
                        val banner = String(m.data, Charsets.UTF_8).trimEnd('\u0000')
                        val max = if (m.arg1 in 1024..MAX_PAYLOAD) m.arg1 else 4096
                        return AdbConnection(socket, input, output, banner, max, m.arg0, signed)
                    }
                    AUTH -> if (m.arg0 == 1 /* TOKEN */) {
                        if (!signed) {
                            signed = true
                            write(AUTH, 2 /* SIGNATURE */, 0, key.sign(m.data))
                        } else if (!keySent) {
                            keySent = true
                            write(AUTH, 3 /* RSAPUBLICKEY */, 0, key.publicKey)
                            socket.soTimeout = AUTH_TIMEOUT_MS
                            onAuthPrompt()
                        }
                    }
                    STLS -> throw AdbException(
                        "The car only offers Android 11+ wireless debugging (TLS with pairing). " +
                            "Enable classic ADB over network on port 5555 instead.",
                    )
                }
            }
        }
    }
}

/** One ADB stream. Data is acknowledged as it is consumed, so a slow reader slows the car down. */
class AdbStream internal constructor(private val conn: AdbConnection, val localId: Int, val service: String) : Closeable {
    @Volatile internal var remoteId = 0
    internal val opened = CompletableDeferred<Unit>()
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
    private val acks = Channel<Unit>(Channel.UNLIMITED)
    @Volatile private var closed = false
    private var buf = EMPTY
    private var pos = 0

    internal fun onOkay(remote: Int) {
        if (!opened.isCompleted) {
            remoteId = remote
            opened.complete(Unit)
        } else {
            acks.trySend(Unit)
        }
    }

    internal fun onData(data: ByteArray) {
        incoming.trySend(data)
    }

    internal fun onClose(cause: Throwable?) {
        closed = true
        opened.completeExceptionally(cause ?: AdbException("The car refused \"${service.substringBefore(':')}\""))
        incoming.close(cause)
        acks.close(cause)
    }

    /** The next chunk from the car, or null once the car closed the stream. */
    suspend fun read(): ByteArray? {
        if (pos < buf.size) {
            val rest = buf.copyOfRange(pos, buf.size)
            buf = EMPTY
            pos = 0
            return rest
        }
        val r = incoming.receiveCatching()
        if (r.isClosed) {
            r.exceptionOrNull()?.let { throw it }
            return null
        }
        if (!closed) conn.send(OKAY, localId, remoteId)
        return r.getOrThrow()
    }

    suspend fun readExactly(n: Int): ByteArray {
        val out = ByteArray(n)
        var o = 0
        while (o < n) {
            if (pos >= buf.size) {
                buf = read() ?: throw EOFException("The car ended the transfer early")
                pos = 0
            }
            val k = minOf(n - o, buf.size - pos)
            System.arraycopy(buf, pos, out, o, k)
            pos += k
            o += k
        }
        return out
    }

    /** Sends [len] bytes in packets of the car's maximum size, waiting for each acknowledgement. */
    suspend fun write(data: ByteArray, off: Int = 0, len: Int = data.size) {
        var o = off
        val end = off + len
        while (o < end) {
            if (closed) throw conn.lostReason ?: EOFException("The car closed the transfer")
            val k = minOf(conn.maxPayload, end - o)
            conn.send(WRTE, localId, remoteId, data, o, k)
            val r = withTimeoutOrNull(ACK_TIMEOUT_MS) { acks.receiveCatching() }
                ?: throw AdbException("The car stopped acknowledging data for ${ACK_TIMEOUT_MS / 1000} s")
            if (r.isClosed) throw r.exceptionOrNull() ?: EOFException("The car closed the transfer")
            o += k
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        conn.closeStream(this)
        incoming.close()
        acks.close()
    }

    companion object {
        const val ACK_TIMEOUT_MS = 30_000L
    }
}

/**
 * Runs blocking socket work on Dispatchers.IO and closes [resource] if the calling coroutine is
 * cancelled, which is the only way to abort a blocking java.net read or connect.
 */
suspend fun <T> closeOnCancel(resource: Closeable, block: () -> T): T = coroutineScope {
    val done = AtomicBoolean(false)
    val watcher = launch {
        try {
            awaitCancellation()
        } finally {
            if (!done.get()) runCatching { resource.close() }
        }
    }
    try {
        withContext(Dispatchers.IO) { block() }
    } finally {
        done.set(true)
        watcher.cancel()
    }
}
