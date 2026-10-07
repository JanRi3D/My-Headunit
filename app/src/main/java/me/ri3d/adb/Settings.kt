package me.ri3d.adb

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import me.ri3d.adb.apps.ManagedApp
import me.ri3d.adb.apps.Release
import me.ri3d.adb.apps.Source
import org.json.JSONArray
import org.json.JSONObject

/** Everything persisted besides the ADB identity (which is in the Android Keystore). */
class Settings(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var endpoint: String
        get() = p.getString("endpoint", null) ?: DEFAULT_ENDPOINT
        set(v) = p.edit { putString("endpoint", v) }

    /** A path on the car, not on this phone. */
    var filesPath: String
        @SuppressLint("SdCardPath") get() = p.getString("filesPath", null) ?: "/sdcard"
        set(v) = p.edit { putString("filesPath", v) }

    var openAfterInstall: Boolean
        get() = p.getBoolean("openAfterInstall", true)
        set(v) = p.edit { putBoolean("openAfterInstall", v) }

    var notificationsAsked: Boolean
        get() = p.getBoolean("notificationsAsked", false)
        set(v) = p.edit { putBoolean("notificationsAsked", v) }

    /** Time of the last update check that reached every configured source. */
    var lastCheckOk: Long
        get() = p.getLong("lastCheckOk", 0)
        set(v) = p.edit { putLong("lastCheckOk", v) }

    var customApps: List<ManagedApp>
        get() = json(p.getString("customApps", null), ::JSONArray)?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map {
                ManagedApp(it.getString("pkg"), it.getString("name"), Source.parse(it.optString("source")))
            }
        } ?: emptyList()
        set(v) = p.edit {
            putString("customApps", JSONArray(v.map { JSONObject().put("pkg", it.packageName).put("name", it.name).put("source", it.source?.text() ?: "") }).toString())
        }

    /** User-supplied sources for catalog apps, by package. */
    var sourceOverrides: Map<String, String>
        get() = json(p.getString("sourceOverrides", null), ::JSONObject)?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap()
        set(v) = p.edit { putString("sourceOverrides", JSONObject(v).toString()) }

    /** Last known release per package, so metadata stays visible offline. */
    var releases: Map<String, Release>
        get() = json(p.getString("releases", null), ::JSONObject)?.let { o ->
            o.keys().asSequence().mapNotNull { k -> runCatching { k to Release.fromJson(o.getJSONObject(k)) }.getOrNull() }.toMap()
        } ?: emptyMap()
        set(v) = p.edit { putString("releases", JSONObject().apply { v.forEach { (k, r) -> put(k, r.toJson()) } }.toString()) }

    private fun <T> json(s: String?, parse: (String) -> T): T? = s?.let { runCatching { parse(it) }.getOrNull() }

    companion object {
        const val DEFAULT_ENDPOINT = "192.168.43.1:5555"
    }
}
