package me.ri3d.adb.apps

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File
import java.util.zip.ZipFile

/** An APK picked on the phone, copied to app storage and inspected. */
data class LocalApk(
    val file: File,
    val displayName: String,
    val size: Long,
    val manifest: ManifestInfo,
    val abis: Set<String>,
    val label: String?,
)

/** Copies [uri] into [dir] and reads its metadata. Throws [InvalidApk] with a user-facing reason. */
fun inspectApk(context: Context, uri: Uri, dir: File): LocalApk {
    val cr = context.contentResolver
    val displayName = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment ?: "app.apk"
    dir.mkdirs()
    dir.listFiles()?.forEach { it.delete() } // one picked APK at a time
    val file = File(dir, "picked.apk")
    (cr.openInputStream(uri) ?: throw InvalidApk("Couldn't open the selected file")).use { input ->
        file.outputStream().use { input.copyTo(it, 64 * 1024) }
    }
    return inspectApkFile(context, file, displayName)
}

fun inspectApkFile(context: Context, file: File, displayName: String): LocalApk {
    val names: List<String>
    val manifestBytes: ByteArray?
    try {
        ZipFile(file).use { zip ->
            names = zip.entries().asSequence().map { it.name }.toList()
            manifestBytes = zip.getEntry("AndroidManifest.xml")?.let { e -> zip.getInputStream(e).use { it.readBytes() } }
        }
    } catch (_: java.util.zip.ZipException) {
        throw InvalidApk("$displayName isn't an APK (not a zip archive)")
    }
    if (manifestBytes == null) {
        throw InvalidApk(
            if (names.any { it.endsWith(".apk", true) }) "$displayName is a split-APK bundle (.apks/.xapk/.apkm). Only single, complete APKs are supported."
            else "$displayName isn't an APK (no AndroidManifest.xml)",
        )
    }
    val manifest = Axml.parse(manifestBytes)
    if (manifest.split != null) throw InvalidApk("$displayName is the \"${manifest.split}\" split of ${manifest.packageName}, not an installable app.")
    if (manifest.needsSplits) throw InvalidApk("$displayName needs additional split APKs. Only single, complete APKs are supported.")
    // The phone's own parser cross-checks our reading and provides the label. It may refuse APKs
    // the phone itself couldn't run (e.g. a newer minSdk); the car decides about those.
    val pm = context.packageManager
    @Suppress("DEPRECATION")
    val pi = pm.getPackageArchiveInfo(file.path, 0)
    if (pi != null && (pi.packageName != manifest.packageName || PackageInfoCompat.getLongVersionCode(pi) != manifest.versionCode)) {
        throw InvalidApk("$displayName has inconsistent metadata")
    }
    val label = pi?.applicationInfo?.let { ai ->
        ai.sourceDir = file.path
        ai.publicSourceDir = file.path
        runCatching { ai.loadLabel(pm).toString() }.getOrNull()
    }
    return LocalApk(file, displayName, file.length(), manifest, ZipIndex.abisOf(names), label?.takeIf { it != manifest.packageName })
}

/** Saves the APK's launcher icon so the app list can show it later. */
fun saveIcon(context: Context, apk: File, packageName: String) {
    runCatching {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val ai = pm.getPackageArchiveInfo(apk.path, 0)?.applicationInfo ?: return
        ai.sourceDir = apk.path
        ai.publicSourceDir = apk.path
        val bmp = ai.loadIcon(pm).toBitmap(144, 144)
        val dir = File(context.filesDir, "icons").apply { mkdirs() }
        File(dir, "$packageName.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

fun iconFile(context: Context, packageName: String) = File(context.filesDir, "icons/$packageName.png")
