package me.ri3d.adb.apps

import kotlinx.coroutines.flow.MutableStateFlow
import me.ri3d.adb.adb.InstalledVersion
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** What decides whether an APK fits a car. */
data class CarTraits(val sdk: Int, val release: String, val abis: List<String>)

/**
 * The car as last seen: its installed versions and traits. Lets the app check for updates and
 * download them at home, away from the car (whose Wi-Fi usually has no internet).
 */
data class CarSnapshot(
    val name: String,
    val serial: String?,
    val traits: CarTraits,
    val installed: Map<String, InstalledVersion>,
    val at: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("serial", serial ?: JSONObject.NULL).put("at", at)
        .put("sdk", traits.sdk).put("release", traits.release).put("abis", JSONArray(traits.abis))
        .put("installed", JSONObject().apply {
            installed.forEach { (pkg, v) -> put(pkg, JSONObject().put("code", v.versionCode).put("name", v.versionName ?: JSONObject.NULL)) }
        })

    companion object {
        fun fromJson(o: JSONObject): CarSnapshot {
            val inst = o.getJSONObject("installed")
            val abis = o.getJSONArray("abis")
            return CarSnapshot(
                name = o.getString("name"),
                serial = if (o.isNull("serial")) null else o.getString("serial"),
                traits = CarTraits(o.getInt("sdk"), o.getString("release"), (0 until abis.length()).map { abis.getString(it) }),
                installed = inst.keys().asSequence().associateWith { pkg ->
                    inst.getJSONObject(pkg).let { v -> InstalledVersion(v.getLong("code"), if (v.isNull("name")) null else v.getString("name")) }
                },
                at = o.getLong("at"),
            )
        }
    }
}

/**
 * APKs downloaded ahead of time, kept as `<package>-<versionCode>.apk` (verified when stored).
 * Installing uses them instead of downloading, so updates work at the car without internet.
 */
class ApkCache(val dir: File) {
    /** Package → versionCode of the APK on hand. */
    val versions = MutableStateFlow(scan())

    fun file(pkg: String, code: Long) = File(dir, "$pkg-$code.apk")

    fun has(r: Release) = versions.value[r.packageName] == r.versionCode && file(r.packageName, r.versionCode).exists()

    /** Records a new download and removes other versions of the same app. */
    fun keep(pkg: String, code: Long) {
        files().filter { (p, c, _) -> p == pkg && c != code }.forEach { it.third.delete() }
        versions.value = scan()
    }

    fun drop(pkg: String) {
        files().filter { it.first == pkg }.forEach { it.third.delete() }
        versions.value = scan()
    }

    /** Removes downloads the car doesn't need any more (installed already, or superseded by a newer release). */
    fun prune(onCar: Map<String, InstalledVersion>, releases: Map<String, Release>) {
        versions.value.forEach { (pkg, code) -> if (isStale(code, onCar[pkg], releases[pkg])) drop(pkg) }
    }

    private fun files(): List<Triple<String, Long, File>> = dir.listFiles().orEmpty().mapNotNull { f ->
        NAME.matchEntire(f.name)?.let { Triple(it.groupValues[1], it.groupValues[2].toLong(), f) }
    }

    private fun scan(): Map<String, Long> = files().associate { it.first to it.second }

    companion object {
        private val NAME = Regex("""^(.+)-(\d+)\.apk$""")

        fun isStale(code: Long, onCar: InstalledVersion?, newest: Release?): Boolean =
            (onCar != null && onCar.versionCode >= code) || (newest != null && newest.versionCode != code)
    }
}
