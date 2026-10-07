package me.ri3d.adb

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.ri3d.adb.car.CarState

/** A long-running operation (download, transfer, install) that must survive leaving the app. */
class Op(val title: String) {
    /** done/total bytes; total < 0 = indeterminate. */
    data class Progress(val phase: String, val done: Long = 0, val total: Long = -1) {
        val fraction get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
    }

    val progress = MutableStateFlow(Progress("Starting…"))
    internal var job: Job? = null

    fun phase(phase: String, done: Long = 0, total: Long = -1) {
        progress.value = Progress(phase, done, total)
    }

    fun cancel() {
        job?.cancel()
    }
}

/** Registry of running operations; CarService shows them while any exist. */
object Ops {
    val active = MutableStateFlow<List<Op>>(emptyList())

    /** Runs [block] in [scope] as a tracked operation with a progress notification. */
    fun launch(context: Context, scope: CoroutineScope, title: String, block: suspend (Op) -> Unit): Op {
        val op = Op(title)
        active.update { it + op }
        CarService.start(context)
        // Main, not Main.immediate: the caller gets the Op before the block starts.
        op.job = scope.launch(Dispatchers.Main) {
            try {
                block(op)
            } finally {
                active.update { it - op }
            }
        }
        return op
    }
}

/**
 * Foreground service while the car is connected or an operation runs.
 *
 * Android 15+ destroys the TCP sockets of apps that leave the foreground (for instance while the
 * system file picker is open), which would cut the ADB link and make the car ask for
 * authorization again. Type connectedDevice (a network connection to an external device) keeps
 * the link; dataSync is added while transfers run. The notification offers Disconnect / Cancel.
 */
class CarService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as App
        when (intent?.action) {
            ACTION_CANCEL -> Ops.active.value.forEach { it.cancel() }
            ACTION_DISCONNECT -> {
                Ops.active.value.forEach { it.cancel() }
                app.car.disconnect()
            }
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel(CHANNEL, "Car connection and transfers", NotificationManager.IMPORTANCE_LOW))
        promote(notification(app.car.state.value, Ops.active.value.firstOrNull(), null), Ops.active.value.isNotEmpty(), app.car.state.value is CarState.Connected)
        if (!watching) {
            watching = true
            @OptIn(ExperimentalCoroutinesApi::class)
            val opProgress = Ops.active.flatMapLatest { ops -> ops.firstOrNull()?.let { op -> op.progress.map { op to it } } ?: flowOf(null) }
            scope.launch {
                combine(app.car.state, opProgress) { s, p -> s to p }.collect { (s, p) ->
                    if (s !is CarState.Connected && p == null) {
                        ServiceCompat.stopForeground(this@CarService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        promote(notification(s, p?.first, p?.second), p != null, s is CarState.Connected)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    @SuppressLint("InlinedApi") // ServiceCompat ignores the type before Android 10
    private fun promote(n: Notification, transferring: Boolean, connected: Boolean) {
        val type = if (!connected) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        else ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or (if (transferring) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        ServiceCompat.startForeground(this, ID, n, type)
    }

    // Android 15+: dataSync has a daily time budget. Stop the transfers cleanly when it runs out.
    override fun onTimeout(startId: Int, fgsType: Int) {
        Ops.active.value.forEach { it.cancel() }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(state: CarState, op: Op?, p: Op.Progress?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(if (op != null) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
        if (op != null) {
            val fraction = p?.fraction
            b.setContentTitle(op.title).setContentText(p?.phase ?: "Working…")
                .setProgress(1000, ((fraction ?: 0f) * 1000).toInt(), fraction == null)
                .addAction(action("Cancel", ACTION_CANCEL, 1))
        } else {
            val info = (state as? CarState.Connected)?.info
            b.setContentTitle("Connected to ${info?.name ?: "the car"}")
                .setContentText(info?.let { "${it.endpoint} · Android ${it.release}" } ?: "")
                .addAction(action("Disconnect", ACTION_DISCONNECT, 2))
        }
        return b.build()
    }

    private fun action(label: String, action: String, code: Int) = Notification.Action.Builder(
        null, label, PendingIntent.getService(this, code, Intent(this, CarService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE),
    ).build()

    companion object {
        private const val CHANNEL = "car"
        private const val ID = 1
        private const val ACTION_CANCEL = "me.ri3d.adb.CANCEL_OPS"
        private const val ACTION_DISCONNECT = "me.ri3d.adb.DISCONNECT"

        /** Called from foreground contexts only (connect and operations start from the UI). */
        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, CarService::class.java))
            } catch (e: IllegalStateException) {
                Log.w(App.TAG, "Can't start the foreground service right now", e)
            }
        }
    }
}
