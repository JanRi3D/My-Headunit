package me.ri3d.adb.car

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import me.ri3d.adb.adb.AdbException
import me.ri3d.adb.adb.ConnectionLost
import me.ri3d.adb.adb.Foreground
import me.ri3d.adb.adb.InstalledVersion
import me.ri3d.adb.adb.PmResult
import me.ri3d.adb.adb.SyncFailed
import me.ri3d.adb.adb.explainPmFailure
import me.ri3d.adb.adb.parseDumpsysPackage
import me.ri3d.adb.adb.parseForeground
import me.ri3d.adb.adb.parsePackageList
import me.ri3d.adb.adb.parsePmInstall
import me.ri3d.adb.adb.q
import me.ri3d.adb.adb.rmCommand
import java.io.File
import java.util.UUID

// Package-level operations on the car, built from shell and SYNC.

class InstallFailed(message: String) : AdbException(message)

/** versionCode/versionName of each of [pkgs] that is installed on the car. */
suspend fun Car.installedVersions(pkgs: Collection<String>): Map<String, InstalledVersion> {
    if (pkgs.isEmpty()) return emptyMap()
    val sep = "@@MHU@@"
    val cmd = pkgs.joinToString("; ") { "echo ${q(sep + it)}; dumpsys package ${q(it)}" }
    val text = exec(cmd, 60_000).stdout
    return text.split(sep).drop(1).mapNotNull { section ->
        val pkg = section.substringBefore('\n').trim()
        parseDumpsysPackage(section, pkg)?.let { pkg to it }
    }.toMap()
}

/** Apps the user installed on the car (not part of the system image). */
suspend fun Car.userPackages(): List<String> = parsePackageList(exec("pm list packages -3", 60_000).stdout).sorted()

/** Starts [component] ("pkg/class") or the package's launcher activity; throws with the car's reason. */
suspend fun Car.launch(packageName: String, component: String?) {
    if (component != null) {
        val out = exec("am start -n ${q(component)}").output
        val error = out.lines().firstOrNull { it.startsWith("Error") && !it.startsWith("Error type") }
        if (error != null || "does not exist" in out) {
            throw AdbException(error?.removePrefix("Error:")?.trim() ?: "The car has no activity $component")
        }
    } else {
        val out = exec("monkey -p ${q(packageName)} -c android.intent.category.LAUNCHER 1").output
        if ("No activities found" in out || "monkey aborted" in out) throw AdbException("$packageName has no launcher activity on the car")
        if ("Events injected" !in out) throw AdbException(out.lines().lastOrNull { it.isNotBlank() } ?: "The car didn't start $packageName")
    }
}

/** The app currently in front of the car's screen, or null when it can't be told. */
suspend fun Car.foreground(): Foreground? {
    val sdk = info?.sdk ?: 0
    val first = if (sdk >= 29) "dumpsys activity activities" else "dumpsys window windows"
    val second = if (sdk >= 29) "dumpsys window windows" else "dumpsys activity activities"
    return parseForeground(exec(first, 30_000).stdout) ?: parseForeground(exec(second, 30_000).stdout)
}

sealed interface InstallPhase {
    data class Sending(val done: Long, val total: Long) : InstallPhase
    data object Installing : InstallPhase
}

/**
 * Installs one APK: transfer to a unique file in /data/local/tmp, `pm install -r` (keeps app data,
 * never uninstalls), check the package manager's answer, verify the installed version, clean up.
 */
suspend fun Car.installApk(apk: File, packageName: String, versionCode: Long, onPhase: (InstallPhase) -> Unit): InstalledVersion {
    val remote = "/data/local/tmp/mhu-${UUID.randomUUID()}.apk"
    var installing = false
    try {
        val total = apk.length()
        onPhase(InstallPhase.Sending(0, total))
        try {
            sync { s -> apk.inputStream().use { s.send(remote, 0b110_100_100, System.currentTimeMillis() / 1000, it) { n -> onPhase(InstallPhase.Sending(n, total)) } } }
        } catch (e: SyncFailed) {
            throw InstallFailed(
                if ("space" in e.message.orEmpty()) "Not enough free storage on the car for the APK (${e.message})."
                else "Couldn't copy the APK to the car: ${e.message}",
            )
        }
        installing = true
        onPhase(InstallPhase.Installing)
        val result = exec("pm install -r ${q(remote)}", 10 * 60_000)
        when (val pm = parsePmInstall(result.output)) {
            is PmResult.Failure -> throw InstallFailed(explainPmFailure(pm.code, pm.detail))
            PmResult.Success -> Unit
        }
        val now = installedVersions(listOf(packageName))[packageName]
            ?: throw InstallFailed("The car reported success, but $packageName isn't installed.")
        if (now.versionCode != versionCode) {
            throw InstallFailed("The car reported success, but has versionCode ${now.versionCode} instead of $versionCode.")
        }
        return now
    } catch (e: ConnectionLost) {
        throw if (installing) ConnectionLost("Connection lost while the car was installing. Reconnect to see whether it finished.", e)
        else ConnectionLost("Connection lost while sending the APK. Nothing was installed.", e)
    } finally {
        withContext(NonCancellable) {
            try {
                exec(rmCommand(listOf(remote)), 15_000)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Not connected any more: leftovers are removed on the next connection.
            }
        }
    }
}

/** Removes APKs left behind by installs that were interrupted by a lost connection. */
suspend fun Car.cleanTempApks() {
    runCatching { exec("rm /data/local/tmp/mhu-*.apk </dev/null", 15_000) }
}
