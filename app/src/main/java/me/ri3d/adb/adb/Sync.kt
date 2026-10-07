package me.ri3d.adb.adb

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.Closeable
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One entry from the SYNC protocol (v1: 32-bit sizes and seconds, which every Android version speaks). */
data class RemoteFile(val name: String, val mode: Int, val size: Long, val mtimeSec: Long) {
    val type get() = mode and 0xF000
    val isDir get() = type == 0x4000
    val isLink get() = type == 0xA000
    val isFile get() = type == 0x8000
    val exists get() = mode != 0
}

class SyncFailed(message: String) : AdbException(message)

/** File transfer service ("sync:"): STAT, LIST, SEND and RECV. Use from Dispatchers.IO. */
class Sync private constructor(private val s: AdbStream) : Closeable {

    private suspend fun request(id: String, path: String) {
        val p = path.toByteArray(Charsets.UTF_8)
        s.write(header(id, p.size) + p)
    }

    private suspend fun readHeader(): Pair<String, Int> {
        val h = s.readExactly(8)
        return String(h, 0, 4, Charsets.US_ASCII) to le(h, 4)
    }

    /** mode == 0 means the path doesn't exist (or isn't visible to the shell user). */
    suspend fun stat(path: String): RemoteFile {
        request("STAT", path)
        val r = s.readExactly(16)
        if (String(r, 0, 4, Charsets.US_ASCII) != "STAT") throw AdbException("Unexpected SYNC answer to STAT")
        return RemoteFile(path.substringAfterLast('/'), le(r, 4), le(r, 8).toLong() and 0xffffffffL, le(r, 12).toLong() and 0xffffffffL)
    }

    /**
     * Directory entries, including "." and "..". adbd answers an unreadable folder with an empty
     * list, so a listing without "." means the folder couldn't be opened.
     */
    suspend fun list(path: String): List<RemoteFile> {
        request("LIST", path)
        val out = mutableListOf<RemoteFile>()
        while (true) {
            val h = s.readExactly(20)
            when (String(h, 0, 4, Charsets.US_ASCII)) {
                "DENT" -> {
                    val name = String(s.readExactly(le(h, 16)), Charsets.UTF_8)
                    out += RemoteFile(name, le(h, 4), le(h, 8).toLong() and 0xffffffffL, le(h, 12).toLong() and 0xffffffffL)
                }
                "DONE" -> return out
                "FAIL" -> throw SyncFailed(String(s.readExactly(le(h, 4)), Charsets.UTF_8))
                else -> throw AdbException("Unexpected SYNC answer to LIST")
            }
        }
    }

    /** Streams [input] to [path] on the car. The car creates missing parent folders. */
    suspend fun send(path: String, mode: Int, mtimeSec: Long, input: InputStream, onProgress: (Long) -> Unit) {
        request("SEND", "$path,$mode")
        val buf = ByteArray(8 + CHUNK)
        var total = 0L
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buf, 8, CHUNK)
                if (n < 0) break
                if (n == 0) continue
                header("DATA", n).copyInto(buf)
                s.write(buf, 0, 8 + n)
                total += n
                onProgress(total)
            }
            s.write(header("DONE", mtimeSec.toInt()))
        } catch (e: EOFException) {
            // The car may already have said why it stopped (e.g. FAIL "No space left on device").
            val reason = runCatching { readStatus() }.exceptionOrNull()
            throw reason as? SyncFailed ?: e
        }
        readStatus()
    }

    private suspend fun readStatus() {
        val (id, len) = readHeader()
        when (id) {
            "OKAY" -> Unit
            "FAIL" -> throw SyncFailed(String(s.readExactly(len), Charsets.UTF_8))
            else -> throw AdbException("Unexpected SYNC answer ($id)")
        }
    }

    /** Streams [path] from the car into [out]; returns the number of bytes received. */
    suspend fun recv(path: String, out: OutputStream, onProgress: (Long) -> Unit): Long {
        request("RECV", path)
        var total = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val (id, len) = readHeader()
            when (id) {
                "DATA" -> {
                    out.write(s.readExactly(len))
                    total += len
                    onProgress(total)
                }
                "DONE" -> return total
                "FAIL" -> throw SyncFailed(String(s.readExactly(len), Charsets.UTF_8))
                else -> throw AdbException("Unexpected SYNC answer ($id)")
            }
        }
    }

    override fun close() = s.close()

    companion object {
        /** SYNC_DATA_MAX: the largest DATA block any adbd accepts. */
        const val CHUNK = 64 * 1024

        suspend fun open(conn: AdbConnection) = Sync(conn.open("sync:"))

        private fun header(id: String, value: Int): ByteArray =
            ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).put(id.toByteArray(Charsets.US_ASCII)).putInt(value).array()

        private fun le(b: ByteArray, off: Int) = ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int
    }
}
