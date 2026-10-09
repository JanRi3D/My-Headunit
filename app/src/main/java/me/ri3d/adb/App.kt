package me.ri3d.adb

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import me.ri3d.adb.adb.KeystoreKey
import me.ri3d.adb.apps.AppsModel
import me.ri3d.adb.apps.Releases
import me.ri3d.adb.apps.SelfUpdate
import me.ri3d.adb.car.Car
import me.ri3d.adb.car.CarState
import me.ri3d.adb.car.Net
import me.ri3d.adb.console.Console
import me.ri3d.adb.files.FilesModel

/**
 * Process-wide state. Operations live here rather than in screens, so they survive rotation and
 * leaving a screen; OpsService keeps the process alive while they run.
 */
class App : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings
    lateinit var net: Net
    lateinit var car: Car
    lateinit var releases: Releases
    lateinit var selfUpdate: SelfUpdate
    lateinit var apps: AppsModel
    lateinit var files: FilesModel
    lateinit var console: Console

    /** Short status messages for the snack bar. */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 16)

    fun say(message: String) {
        Log.i(TAG, message)
        messages.tryEmit(message)
    }

    override fun onCreate() {
        super.onCreate()
        settings = Settings(this)
        net = Net(this)
        val key by lazy { KeystoreKey.load() }
        car = Car(scope, settings, net) { key }
        releases = Releases(net)
        selfUpdate = SelfUpdate(this) // before AppsModel, whose first update check includes it
        apps = AppsModel(this)
        files = FilesModel(this)
        console = Console(this)
        scope.launch {
            car.state.collect {
                Log.i(TAG, "car: $it")
                if (it is CarState.Connected) CarService.start(this@App)
            }
        }
    }

    companion object {
        const val TAG = "MyHeadunit"
    }
}
