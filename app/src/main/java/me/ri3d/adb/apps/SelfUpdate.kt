package me.ri3d.adb.apps

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.content.pm.PackageInfoCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import me.ri3d.adb.App
import me.ri3d.adb.Op
import me.ri3d.adb.Ops
import java.io.File

/**
 * My Headunit's own updates, from its GitHub releases. Checked together with the car's apps;
 * installed on this phone through Android's package installer, which asks the user to confirm.
 */
class SelfUpdate(private val app: App) {
    private val self = ManagedApp(app.packageName, "My Headunit", Source.GitHub("JanRi3D/My-Headunit", Regex("""my-headunit-.*-release\.apk""")))
    private val phone = CarTraits(Build.VERSION.SDK_INT, Build.VERSION.RELEASE, Build.SUPPORTED_ABIS.toList())
    private val info = app.packageManager.getPackageInfo(app.packageName, 0)
    val current: String = info.versionName ?: "build ${PackageInfoCompat.getLongVersionCode(info)}"

    /** A newer release that runs on this phone; null when there is none or the check failed. */
    val available = MutableStateFlow<Release?>(null)
    val op = MutableStateFlow<Op?>(null)

    /** Sessions with a final result; the installer can report an abort twice. */
    private val finished = mutableSetOf<Int>()

    init {
        ContextCompat.registerReceiver(app, object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
                if (status != PackageInstaller.STATUS_PENDING_USER_ACTION && !finished.add(intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1))) return
                when (status) {
                    // ponytail: started from a receiver, Android may block this prompt if the user left the app meanwhile; Update can be tapped again.
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                        ?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    PackageInstaller.STATUS_SUCCESS -> Unit // Android restarts the app as the new version
                    PackageInstaller.STATUS_FAILURE_ABORTED -> app.say("My Headunit update cancelled")
                    else -> app.say("My Headunit update failed: ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"}")
                }
            }
        }, IntentFilter(ACTION_RESULT), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    suspend fun check() {
        try {
            val r = app.releases.latest(self, self.source!!, phone, available.value)
            val fits = incompatibility(r.minSdk, r.abis, phone.sdk, phone.release, phone.abis) == null
            available.value = r.takeIf { fits && it.versionCode > PackageInfoCompat.getLongVersionCode(info) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i(App.TAG, "My Headunit update check failed: ${e.message}")
        }    }

    /** Downloads the release (size and SHA-256 checked) and hands it to the package installer. */
    fun install() {
        val r = available.value ?: return
        if (op.value != null) return
        op.value = Ops.launch(app, app.scope, "Updating My Headunit") { o ->
            try {
                val dir = File(app.cacheDir, "self-update").apply { deleteRecursively() }
                o.phase("Downloading ${r.label}…", 0, r.size)
                val apk = app.releases.download(r, dir) { done, total -> o.phase("Downloading ${r.label}…", done, total) }
                o.phase("Installing…")
                withContext(Dispatchers.IO) { commit(apk) }
                apk.delete()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                app.say("My Headunit update failed: ${e.message}")
            } finally {
                op.value = null
            }
        }
    }

    private fun commit(apk: File) {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(app.packageName) // the installer refuses an APK of any other app
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { s ->
                s.openWrite("base.apk", 0, apk.length()).use { out ->
                    apk.inputStream().use { it.copyTo(out) }
                    s.fsync(out)
                }
                val result = Intent(ACTION_RESULT).setPackage(app.packageName)
                s.commit(PendingIntent.getBroadcast(app, id, result, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT).intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    private companion object {
        const val ACTION_RESULT = "me.ri3d.adb.SELF_UPDATE_RESULT"
    }
}
