package me.ri3d.adb.apps

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ri3d.adb.car.CarInfo
import me.ri3d.adb.car.Net
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.security.MessageDigest

/** The newest APK a source offers for a car, with metadata read from the APK's own manifest. */
data class Release(
    val packageName: String,
    val versionCode: Long,
    val versionName: String?,
    val minSdk: Int?,
    val abis: Set<String>,
    val url: String,
    val size: Long,
    val sha256: String?,
    /** Identifies the asset; unchanged key = no need to read its manifest again. */
    val key: String,
    val checkedAt: Long,
) {
    val label get() = versionName ?: "build $versionCode"

    fun toJson(): JSONObject = JSONObject()
        .put("pkg", packageName).put("code", versionCode).put("name", versionName ?: JSONObject.NULL)
        .put("minSdk", minSdk ?: JSONObject.NULL).put("abis", JSONArray(abis.toList())).put("url", url)
        .put("size", size).put("sha256", sha256 ?: JSONObject.NULL).put("key", key).put("checkedAt", checkedAt)

    companion object {
        fun fromJson(o: JSONObject) = Release(
            packageName = o.getString("pkg"),
            versionCode = o.getLong("code"),
            versionName = o.optString("name").takeIf { !o.isNull("name") && it.isNotEmpty() },
            minSdk = if (o.isNull("minSdk")) null else o.getInt("minSdk"),
            abis = o.getJSONArray("abis").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() },
            url = o.getString("url"),
            size = o.getLong("size"),
            sha256 = o.optString("sha256").takeIf { !o.isNull("sha256") && it.isNotEmpty() },
            key = o.getString("key"),
            checkedAt = o.getLong("checkedAt"),
        )
    }
}

class SourceError(message: String) : IOException(message)

/**
 * Release providers. A check reads only a few KB per APK: the zip's central directory and its
 * AndroidManifest.xml via HTTP range requests, so versionCode is known without downloading.
 */
class Releases(private val net: Net) {

    /** The latest release of [app] that suits [car]; [cached] is reused when the asset is unchanged. */
    suspend fun latest(app: ManagedApp, source: Source, car: CarInfo?, cached: Release?): Release = net.withInternet { network ->
        val candidates = when (source) {
            is Source.GitHub -> gitHubAssets(network, source)
            is Source.Manifest -> manifestApks(network, source)
        }
        val chosen = pickForCar(candidates, car) ?: throw SourceError("No APK for this car in ${source.describe()}")
        if (cached != null && cached.key == chosen.key) return@withInternet cached.copy(checkedAt = System.currentTimeMillis())
        val release = chosen.declared ?: readRemoteApk(network, chosen)
        if (release.packageName != app.packageName) {
            throw SourceError("${source.describe()} publishes ${release.packageName}, not ${app.packageName}")
        }
        release
    }

    /** One downloadable APK as announced by a source; [declared] is set when the source states the metadata itself. */
    private class Candidate(val url: String, val name: String, val size: Long, val sha256: String?, val key: String, val declared: Release?)

    private fun gitHubAssets(network: android.net.Network?, s: Source.GitHub): List<Candidate> {
        val json = getJson(network, "https://api.github.com/repos/${s.repo}/releases/latest") { code ->
            if (code == 404) "github.com/${s.repo} has no published release" else null
        }
        val assets = json.getJSONArray("assets")
        val tag = json.optString("tag_name")
        return (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { a -> a.getString("name").endsWith(".apk", true) && (s.asset?.matches(a.getString("name")) ?: true) }
            .map { a ->
                val digest = a.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
                Candidate(a.getString("browser_download_url"), a.getString("name"), a.getLong("size"), digest,
                    "${a.getString("browser_download_url")}#${digest ?: a.optString("updated_at")}", null)
            }
            .ifEmpty { throw SourceError("Release $tag of github.com/${s.repo} has no APK") }
    }

    private fun manifestApks(network: android.net.Network?, s: Source.Manifest): List<Candidate> {
        val o = getJson(network, s.url) { null }
        val entries = o.optJSONArray("apks")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: listOf(o)
        return entries.map { e ->
            fun str(k: String) = (if (e.has(k)) e else o).optString(k).takeIf { it.isNotEmpty() }
            fun long(k: String) = (if (e.has(k)) e else o).optLong(k, -1).takeIf { it >= 0 }
            val url = str("url") ?: throw SourceError("Manifest entry without \"url\"")
            if (!url.startsWith("https://")) throw SourceError("APK URLs must use https")
            val sha = str("sha256")?.lowercase()
            val code = long("versionCode")
            val pkg = str("packageName")
            val abis = (e.optJSONArray("abis") ?: o.optJSONArray("abis"))?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
            Candidate(
                url, url.substringAfterLast('/'), long("size") ?: -1, sha, "$url#${sha ?: code}",
                declared = if (code != null && pkg != null && abis != null) Release(
                    pkg, code, str("versionName"), long("minSdk")?.toInt(), abis, url, long("size") ?: -1, sha,
                    "$url#${sha ?: code}", System.currentTimeMillis(),
                ) else null,
            )
        }
    }

    /** Prefers an asset named for the car's ABI, then "universal", then the first; declared metadata must fit. */
    private fun pickForCar(c: List<Candidate>, car: CarInfo?): Candidate? {
        val fitting = c.filter { it.declared == null || car == null || incompatibility(it.declared.minSdk, it.declared.abis, car.sdk, car.release, car.abis) == null }
        val abis = car?.abis.orEmpty()
        return fitting.firstOrNull { cand -> abis.any { cand.name.contains(it, true) } }
            ?: fitting.firstOrNull { it.name.contains("universal", true) }
            ?: fitting.firstOrNull()
            ?: c.firstOrNull()
    }

    private fun readRemoteApk(network: android.net.Network?, c: Candidate): Release {
        var url = c.url
        val total = rangeRequest(network, url, 0, 0) { finalUrl, conn ->
            url = finalUrl
            conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
        } ?: throw SourceError("The download server didn't report the APK size")
        val read = { from: Long, to: Long -> rangeRequest(network, url, from, to) { _, conn -> conn.inputStream.use { it.readBytes() } } }
        val zip = ZipIndex.read(total, read)
        if (zip.isSplitContainer) throw SourceError("${c.name} is a split-APK bundle, which isn't supported")
        val entry = zip.entries["AndroidManifest.xml"] ?: throw SourceError("${c.name} isn't an APK")
        val m = Axml.parse(ZipIndex.entry(entry, read))
        if (m.split != null || m.needsSplits) throw SourceError("${c.name} needs split APKs, which aren't supported")
        return Release(m.packageName, m.versionCode, m.versionName, m.minSdk, zip.abis, c.url, total, c.sha256, c.key, System.currentTimeMillis())
    }

    private fun <T> rangeRequest(network: android.net.Network?, url: String, from: Long, to: Long, read: (String, HttpURLConnection) -> T): T {
        val conn = Net.http(network, url)
        try {
            conn.setRequestProperty("Range", "bytes=$from-$to")
            conn.setRequestProperty("Accept-Encoding", "identity")
            if (conn.responseCode != 206) throw SourceError("The download server doesn't support partial downloads (HTTP ${conn.responseCode})")
            return read(conn.url.toString(), conn)
        } finally {
            conn.disconnect()
        }
    }

    private fun getJson(network: android.net.Network?, url: String, explain: (Int) -> String?): JSONObject {
        val conn = Net.http(network, url)
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json, application/json")
            val code = conn.responseCode
            if (code != 200) throw SourceError(explain(code) ?: "${url.removePrefix("https://").substringBefore('/')} answered HTTP $code")
            return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Downloads [r] into [dir], checking size and SHA-256 when known. Partial files are deleted. */
    suspend fun download(r: Release, dir: File, onProgress: (Long, Long) -> Unit): File = net.withInternet { network ->
        dir.mkdirs()
        val target = File(dir, "${r.packageName}-${r.versionCode}.apk")
        val part = File(dir, target.name + ".part")
        val conn = Net.http(network, r.url)
        try {
            if (conn.responseCode != 200) throw SourceError("Download failed: HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.size
            val sha = MessageDigest.getInstance("SHA-256")
            var done = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw SourceError("Download incomplete ($done of $total bytes)")
            val hex = sha.digest().joinToString("") { "%02x".format(it) }
            if (r.sha256 != null && !hex.equals(r.sha256, true)) throw SourceError("Downloaded APK doesn't match its published SHA-256")
            if (!part.renameTo(target)) throw IOException("Couldn't store the download")
            target
        } catch (e: Throwable) {
            part.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }
}
