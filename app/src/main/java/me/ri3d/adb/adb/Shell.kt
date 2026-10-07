package me.ri3d.adb.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.util.UUID

/** POSIX single-quote quoting; safe for spaces, apostrophes and newlines in remote paths. */
fun q(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * Deletes files one by one. Android 4.x toolbox `rm` knows no `-f` and stops at the first
 * failure; stdin from /dev/null keeps toybox from prompting on old PTY-based shells.
 */
fun rmCommand(paths: List<String>): String = paths.joinToString("; ") { "rm ${q(it)} </dev/null" }

/** Renames; `mv` without options exists on every Android version. */
fun mvCommand(from: String, to: String): String = "mv ${q(from)} ${q(to)} </dev/null"

/**
 * [exitCode] is null when the car's shell can't report one: old ADB (before Android 7) has no exit
 * status, and the status line we append is missing if the command itself ended the shell.
 */
data class ShellResult(val stdout: String, val stderr: String, val exitCode: Int?) {
    val output: String get() = if (stderr.isEmpty()) stdout else if (stdout.isEmpty()) stderr else "$stdout\n$stderr"
}

class ShellTimeout(val seconds: Long) : AdbException("No result after $seconds s")

/** Splits a byte stream into UTF-8 lines; '\n' never occurs inside a multi-byte sequence. */
internal class LineSplitter(private val emit: (String) -> Unit) {
    private val pending = ByteArrayOutputStream()

    fun feed(data: ByteArray) {
        var start = 0
        for (i in data.indices) {
            if (data[i] == '\n'.code.toByte()) {
                pending.write(data, start, i - start)
                emit(line())
                start = i + 1
            }
        }
        pending.write(data, start, data.size - start)
    }

    fun finish() {
        if (pending.size() > 0) emit(line())
    }

    private fun line(): String = pending.toString(Charsets.UTF_8.name()).removeSuffix("\r").also { pending.reset() }
}

/**
 * Legacy "shell:" output carries our status line "<marker><code>" after a newline we added.
 * Holds one line back so that our extra newline (an empty line right before the marker) is dropped.
 */
internal class LegacyExit(private val marker: String, private val emit: (String) -> Unit) {
    var exitCode: Int? = null
        private set
    private var held: String? = null

    fun line(l: String) {
        if (exitCode == null && l.startsWith(marker)) {
            l.removePrefix(marker).trim().toIntOrNull()?.let { code ->
                exitCode = code
                held?.let { if (it.isNotEmpty()) emit(it) }
                held = null
                return
            }
        }
        held?.let(emit)
        held = l
    }

    fun finish() {
        held?.let(emit)
        held = null
    }
}

/**
 * Runs [command] with sh on the car. Uses the shell v2 protocol (stdout, stderr, exit code) when
 * the car offers it, else the legacy "shell:" service with an appended status line.
 * [onLine] streams output lines as they arrive (stderr flagged true).
 */
suspend fun AdbConnection.exec(
    command: String,
    timeoutMs: Long = 30_000,
    onLine: ((line: String, isError: Boolean) -> Unit)? = null,
): ShellResult = withContext(Dispatchers.IO) {
    val out = StringBuilder()
    val err = StringBuilder()
    fun add(sb: StringBuilder, l: String, isError: Boolean) {
        if (sb.isNotEmpty()) sb.append('\n')
        sb.append(l)
        onLine?.invoke(l, isError)
    }
    val v2 = "shell_v2" in features
    val marker = "__MHU_" + UUID.randomUUID().toString().take(8) + "__"
    val stream = open(if (v2) "shell,v2,raw:$command" else "shell:$command\necho \"\n$marker\$?\"")
    var exit: Int? = null
    try {
        val finished = withTimeoutOrNull(timeoutMs) {
            if (v2) {
                val so = LineSplitter { add(out, it, false) }
                val se = LineSplitter { add(err, it, true) }
                while (true) {
                    val h = try {
                        stream.readExactly(5)
                    } catch (_: EOFException) {
                        break
                    }
                    val len = (h[1].toInt() and 0xff) or ((h[2].toInt() and 0xff) shl 8) or
                        ((h[3].toInt() and 0xff) shl 16) or ((h[4].toInt() and 0xff) shl 24)
                    val data = stream.readExactly(len)
                    when (h[0].toInt()) {
                        1 -> so.feed(data)
                        2 -> se.feed(data)
                        3 -> if (data.isNotEmpty()) exit = data[0].toInt() and 0xff
                    }
                }
                so.finish()
                se.finish()
            } else {
                val legacy = LegacyExit(marker) { add(out, it, false) }
                val lines = LineSplitter(legacy::line)
                while (true) lines.feed(stream.read() ?: break)
                lines.finish()
                legacy.finish()
                exit = legacy.exitCode
            }
            true
        }
        if (finished == null) throw ShellTimeout(timeoutMs / 1000)
    } finally {
        stream.close()
    }
    ShellResult(out.toString(), err.toString(), exit)
}

// ---- Parsers for the car's command output (plain functions, unit-tested) ----

/** `getprop` lines look like "[ro.build.version.sdk]: [19]". */
fun parseGetprop(text: String): Map<String, String> {
    val re = Regex("""^\[(.+?)]: \[(.*)]$""")
    return text.lineSequence().mapNotNull { re.find(it.trim())?.destructured?.let { (k, v) -> k to v } }.toMap()
}

/** `pm list packages` lines look like "package:com.example". */
fun parsePackageList(text: String): List<String> =
    text.lineSequence().map { it.trim() }.filter { it.startsWith("package:") }
        .map { it.removePrefix("package:").substringBefore('=').trim() }.filter { it.isNotEmpty() }.toList()

data class InstalledVersion(val versionCode: Long, val versionName: String?)

/** Reads versionCode/versionName for [pkg] from `dumpsys package <pkg>` (Android 4.x through 16). */
fun parseDumpsysPackage(text: String, pkg: String): InstalledVersion? {
    val start = text.indexOf("Package [$pkg]")
    if (start < 0) return null
    val block = text.substring(start).lineSequence().drop(1).takeWhile { !it.trimStart().startsWith("Package [") }.joinToString("\n")
    val code = Regex("""versionCode=(\d+)""").find(block)?.groupValues?.get(1)?.toLongOrNull() ?: return null
    val name = Regex("""versionName=(.*)""").find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    return InstalledVersion(code, name)
}

sealed interface PmResult {
    data object Success : PmResult
    data class Failure(val code: String, val detail: String) : PmResult
}

/** `pm install` prints "Success" or "Failure [INSTALL_FAILED_X: details]" (older: "Failure [INSTALL_FAILED_X]"). */
fun parsePmInstall(output: String): PmResult {
    val lines = output.lines().map { it.trim() }
    if (lines.any { it == "Success" }) return PmResult.Success
    val failure = lines.firstOrNull { it.startsWith("Failure") || it.startsWith("Error") || it.startsWith("Exception") }
    val bracket = failure?.let { Regex("""\[(\w+)(?::\s*(.*))?]""").find(it) }
    if (bracket != null) return PmResult.Failure(bracket.groupValues[1], bracket.groupValues[2])
    val code = Regex("""(INSTALL_\w+)""").find(output)?.value ?: "UNKNOWN"
    return PmResult.Failure(code, (failure ?: output.trim()).take(300))
}

/** A user-facing explanation for a package manager failure code. */
fun explainPmFailure(code: String, detail: String): String = when (code) {
    "INSTALL_FAILED_INSUFFICIENT_STORAGE" -> "Not enough free storage on the car."
    "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "INSTALL_FAILED_SHARED_USER_INCOMPATIBLE", "INSTALL_PARSE_FAILED_INCONSISTENT_CERTIFICATES" ->
        "The app on the car is signed with a different key. Updating it would mean uninstalling it first, which deletes its data — My Headunit never does that on its own."
    "INSTALL_FAILED_VERSION_DOWNGRADE" -> "The car already has a newer version of this app. Downgrades are refused to keep the app's data safe."
    "INSTALL_FAILED_OLDER_SDK" -> "This APK needs a newer Android version than the car runs."
    "INSTALL_FAILED_NO_MATCHING_ABIS", "INSTALL_FAILED_CPU_ABI_INCOMPATIBLE" -> "This APK has no native code for the car's processor."
    "INSTALL_FAILED_MISSING_SPLIT" -> "This APK is part of a split bundle; the car needs the missing split APKs too."
    "INSTALL_PARSE_FAILED_NO_CERTIFICATES" -> "The APK isn't signed in a way the car accepts (Android 4.x–6 need v1/JAR signing)."
    "INSTALL_FAILED_INVALID_APK", "INSTALL_PARSE_FAILED_NOT_APK", "INSTALL_PARSE_FAILED_BAD_MANIFEST",
    "INSTALL_PARSE_FAILED_UNEXPECTED_EXCEPTION", "INSTALL_PARSE_FAILED_MANIFEST_MALFORMED" -> "The car couldn't read this APK."
    "INSTALL_FAILED_TEST_ONLY" -> "This is a test-only build; the car refuses to install it without the -t flag."
    "INSTALL_FAILED_DUPLICATE_PERMISSION", "INSTALL_FAILED_CONFLICTING_PROVIDER" -> "Another app on the car already claims a permission or provider this app needs."
    else -> "The car refused the APK."
} + " ($code${if (detail.isNotBlank()) ": ${detail.trim()}" else ""})"

data class Foreground(val packageName: String, val component: String?, val how: String)

/**
 * The app in front, from `dumpsys activity activities` (mResumedActivity / topResumedActivity) or
 * `dumpsys window windows` (mCurrentFocus / mFocusedApp), whichever the Android version prints.
 */
fun parseForeground(text: String): Foreground? {
    val sources = listOf("topResumedActivity", "mResumedActivity", "ResumedActivity", "mFocusedActivity", "mCurrentFocus", "mFocusedApp")
    for (key in sources) {
        for (line in text.lineSequence().filter { key in it }) {
            val m = Regex("""([A-Za-z][\w.]*)/([\w.$]+)""").find(line.substringAfter(key)) ?: continue
            val pkg = m.groupValues[1]
            if (!pkg.contains('.')) continue
            val cls = m.groupValues[2].let { if (it.startsWith('.')) pkg + it else it }
            return Foreground(pkg, "$pkg/$cls", key)
        }
    }
    return null
}
