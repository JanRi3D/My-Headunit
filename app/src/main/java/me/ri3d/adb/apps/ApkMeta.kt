package me.ri3d.adb.apps

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

class InvalidApk(message: String) : IOException(message)

/** What the car needs to know about an APK before it is installed. */
data class ManifestInfo(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    /** Set for a configuration/feature split ("split" attribute): not installable on its own. */
    val split: String?,
    /** android:isSplitRequired: the base APK can't run without its splits. */
    val needsSplits: Boolean,
)

/** Binary AndroidManifest.xml (AXML) reader, just enough for package, version and minSdk. */
object Axml {
    private const val ATTR_VERSION_CODE = 0x0101021b
    private const val ATTR_VERSION_NAME = 0x0101021c
    private const val ATTR_MIN_SDK = 0x0101020c
    private const val ATTR_VERSION_CODE_MAJOR = 0x01010576
    private const val ATTR_IS_SPLIT_REQUIRED = 0x01010591

    fun parse(bytes: ByteArray): ManifestInfo {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 8 || b.getShort(0).toInt() != 0x0003) throw InvalidApk("AndroidManifest.xml isn't compiled binary XML")
        var strings = emptyList<String>()
        var resIds = IntArray(0)
        var pkg: String? = null
        var code: Long? = null
        var major = 0L
        var name: String? = null
        var minSdk: Int? = null
        var split: String? = null
        var needsSplits = false
        var pos = b.getShort(2).toInt() and 0xffff
        while (pos + 8 <= bytes.size) {
            val type = b.getShort(pos).toInt() and 0xffff
            val headerSize = b.getShort(pos + 2).toInt() and 0xffff
            val size = b.getInt(pos + 4)
            if (size < 8 || pos + size > bytes.size) break
            when (type) {
                0x0001 -> strings = stringPool(b, pos)
                0x0180 -> resIds = IntArray((size - headerSize) / 4) { b.getInt(pos + headerSize + it * 4) }
                0x0102 -> {
                    val ext = pos + headerSize
                    val tag = strings.getOrNull(b.getInt(ext + 4)) ?: ""
                    val attrStart = b.getShort(ext + 8).toInt() and 0xffff
                    val attrSize = b.getShort(ext + 10).toInt() and 0xffff
                    val count = b.getShort(ext + 12).toInt() and 0xffff
                    for (i in 0 until count) {
                        val a = ext + attrStart + i * attrSize
                        val nameIdx = b.getInt(a + 4)
                        val id = resIds.getOrNull(nameIdx) ?: 0
                        val attrName = strings.getOrNull(nameIdx) ?: ""
                        val raw = b.getInt(a + 8)
                        val dataType = bytes[a + 15].toInt() and 0xff
                        val data = b.getInt(a + 16)
                        val str = if (dataType == 0x03) strings.getOrNull(data) else strings.getOrNull(raw)
                        val int = if (dataType in 0x10..0x1f) data else null
                        when (tag) {
                            "manifest" -> when {
                                id == ATTR_VERSION_CODE || (id == 0 && attrName == "versionCode") -> code = int?.toLong()?.and(0xffffffffL)
                                id == ATTR_VERSION_CODE_MAJOR -> major = int?.toLong() ?: 0
                                id == ATTR_VERSION_NAME || (id == 0 && attrName == "versionName") -> name = str
                                id == ATTR_IS_SPLIT_REQUIRED -> needsSplits = int != null && int != 0
                                attrName == "package" -> pkg = str
                                attrName == "split" -> split = str
                                attrName == "isSplitRequired" -> needsSplits = int != null && int != 0
                            }
                            "uses-sdk" -> if (id == ATTR_MIN_SDK || (id == 0 && attrName == "minSdkVersion")) {
                                minSdk = int ?: str?.let { codenameSdk(it) }
                            }
                        }
                    }
                }
            }
            pos += size
        }
        return ManifestInfo(
            packageName = pkg ?: throw InvalidApk("The manifest has no package name"),
            versionCode = (major shl 32) or (code ?: throw InvalidApk("The manifest has no versionCode")),
            versionName = name,
            minSdk = minSdk ?: 1,
            split = split,
            needsSplits = needsSplits,
        )
    }

    // A preview codename ("Baklava") means "newer than any released car": treat it as unsupported.
    private fun codenameSdk(s: String) = s.toIntOrNull() ?: 10_000

    private fun stringPool(b: ByteBuffer, pos: Int): List<String> {
        val count = b.getInt(pos + 8)
        val utf8 = b.getInt(pos + 16) and 0x100 != 0
        val stringsStart = pos + b.getInt(pos + 20)
        val offsets = pos + (b.getShort(pos + 2).toInt() and 0xffff)
        return List(count) { i ->
            var p = stringsStart + b.getInt(offsets + i * 4)
            if (utf8) {
                p += if (b.get(p).toInt() and 0x80 != 0) 2 else 1 // UTF-16 length, unused
                var len = b.get(p).toInt() and 0xff
                if (len and 0x80 != 0) {
                    len = ((len and 0x7f) shl 8) or (b.get(p + 1).toInt() and 0xff)
                    p += 2
                } else {
                    p += 1
                }
                String(b.array(), p, len, Charsets.UTF_8)
            } else {
                var len = b.getShort(p).toInt() and 0xffff
                if (len and 0x8000 != 0) {
                    len = ((len and 0x7fff) shl 16) or (b.getShort(p + 2).toInt() and 0xffff)
                    p += 4
                } else {
                    p += 2
                }
                String(b.array(), p, len * 2, Charsets.UTF_16LE)
            }
        }
    }
}

/** Central-directory facts about an APK (or anything zipped). */
class ZipIndex(val entries: Map<String, Entry>) {
    class Entry(val method: Int, val compressedSize: Long, val size: Long, val localHeaderOffset: Long)

    /** Native ABIs from the lib/<abi>/ folders; empty means pure Java/Kotlin, which runs on any CPU. */
    val abis: Set<String> get() = abisOf(entries.keys)

    /** .apks/.xapk/.apkm bundles: APKs inside a zip instead of a manifest. */
    val isSplitContainer get() = "AndroidManifest.xml" !in entries && entries.keys.any { it.endsWith(".apk", ignoreCase = true) }

    companion object {
        fun abisOf(names: Collection<String>): Set<String> =
            names.filter { it.startsWith("lib/") && it.endsWith(".so") }.mapNotNull { it.split('/').getOrNull(1) }.toSet()

        /** Parses the central directory from the tail of a zip of [total] bytes; [read] fetches byte ranges. */
        fun read(total: Long, read: (from: Long, to: Long) -> ByteArray): ZipIndex {
            val tailStart = maxOf(0L, total - (0xFFFF + 22))
            val tail = read(tailStart, total - 1)
            val t = ByteBuffer.wrap(tail).order(ByteOrder.LITTLE_ENDIAN)
            val eocd = (tail.size - 22 downTo 0).firstOrNull { t.getInt(it) == 0x06054b50 }
                ?: throw InvalidApk("Not a zip/APK file (no central directory)")
            val cdSize = t.getInt(eocd + 12).toLong() and 0xffffffffL
            val cdOffset = t.getInt(eocd + 16).toLong() and 0xffffffffL
            if (cdOffset == 0xffffffffL) throw InvalidApk("ZIP64 APKs aren't supported")
            val cd = if (cdOffset >= tailStart) tail.copyOfRange((cdOffset - tailStart).toInt(), (cdOffset - tailStart + cdSize).toInt())
            else read(cdOffset, cdOffset + cdSize - 1)
            val c = ByteBuffer.wrap(cd).order(ByteOrder.LITTLE_ENDIAN)
            val entries = LinkedHashMap<String, Entry>()
            var p = 0
            while (p + 46 <= cd.size && c.getInt(p) == 0x02014b50) {
                val nameLen = c.getShort(p + 28).toInt() and 0xffff
                val extraLen = c.getShort(p + 30).toInt() and 0xffff
                val commentLen = c.getShort(p + 32).toInt() and 0xffff
                val name = String(cd, p + 46, nameLen, Charsets.UTF_8)
                entries[name] = Entry(
                    method = c.getShort(p + 10).toInt() and 0xffff,
                    compressedSize = c.getInt(p + 20).toLong() and 0xffffffffL,
                    size = c.getInt(p + 24).toLong() and 0xffffffffL,
                    localHeaderOffset = c.getInt(p + 42).toLong() and 0xffffffffL,
                )
                p += 46 + nameLen + extraLen + commentLen
            }
            return ZipIndex(entries)
        }

        /** Reads one entry's bytes via ranged reads (local header, then data). */
        fun entry(e: Entry, read: (from: Long, to: Long) -> ByteArray): ByteArray {
            if (e.size > 8 * 1024 * 1024) throw InvalidApk("AndroidManifest.xml is implausibly large")
            val lh = ByteBuffer.wrap(read(e.localHeaderOffset, e.localHeaderOffset + 29)).order(ByteOrder.LITTLE_ENDIAN)
            if (lh.getInt(0) != 0x04034b50) throw InvalidApk("Corrupt zip entry header")
            val dataStart = e.localHeaderOffset + 30 + (lh.getShort(26).toInt() and 0xffff) + (lh.getShort(28).toInt() and 0xffff)
            val raw = if (e.compressedSize == 0L) ByteArray(0) else read(dataStart, dataStart + e.compressedSize - 1)
            return when (e.method) {
                0 -> raw
                8 -> {
                    val inf = Inflater(true)
                    try {
                        inf.setInput(raw)
                        val out = ByteArrayOutputStream(e.size.toInt())
                        val buf = ByteArray(16 * 1024)
                        while (!inf.finished()) {
                            val n = inf.inflate(buf)
                            if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                            out.write(buf, 0, n)
                        }
                        out.toByteArray()
                    } finally {
                        inf.end()
                    }
                }
                else -> throw InvalidApk("Unsupported zip compression method ${e.method}")
            }
        }
    }
}

/** Android release names for "Needs Android 4.1+" style messages. */
fun androidName(sdk: Int): String = when (sdk) {
    in 1..15 -> "API $sdk"
    16 -> "4.1"; 17 -> "4.2"; 18 -> "4.3"; 19, 20 -> "4.4"; 21 -> "5.0"; 22 -> "5.1"; 23 -> "6.0"
    24 -> "7.0"; 25 -> "7.1"; 26 -> "8.0"; 27 -> "8.1"; 28 -> "9"; 29 -> "10"; 30 -> "11"; 31 -> "12"
    32 -> "12L"; 33 -> "13"; 34 -> "14"; 35 -> "15"; 36 -> "16"
    else -> "API $sdk"
}

/** Null when the APK fits the car; otherwise the reason it doesn't. */
fun incompatibility(minSdk: Int?, abis: Set<String>, carSdk: Int, carRelease: String, carAbis: List<String>): String? {
    if (minSdk != null && carSdk < minSdk) return "Needs Android ${androidName(minSdk)}+ · car runs $carRelease"
    if (abis.isNotEmpty() && carAbis.isNotEmpty() && abis.none { it in carAbis }) {
        return "Built for ${abis.sorted().joinToString()} · car is ${carAbis.joinToString()}"
    }
    return null
}
