package me.ri3d.adb.car

import android.net.Network
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ri3d.adb.Settings
import me.ri3d.adb.adb.AdbConnection
import me.ri3d.adb.adb.AdbException
import me.ri3d.adb.adb.AdbKey
import me.ri3d.adb.adb.AuthNotGranted
import me.ri3d.adb.adb.ConnectionLost
import me.ri3d.adb.adb.ShellResult
import me.ri3d.adb.adb.Sync
import me.ri3d.adb.adb.closeOnCancel
import me.ri3d.adb.adb.exec
import me.ri3d.adb.adb.parseGetprop
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

data class Endpoint(val host: String, val port: Int) {
    override fun toString() = "$host:$port"

    companion object {
        fun parse(text: String): Endpoint? {
            val m = Regex("""^([A-Za-z0-9][A-Za-z0-9.-]*)(?::(\d{1,5}))?$""").matchEntire(text.trim()) ?: return null
            val port = m.groupValues[2].ifEmpty { "5555" }.toInt()
            return if (port in 1..65535) Endpoint(m.groupValues[1], port) else null
        }
    }
}

/** What the car told us about itself, read after the connection was established. */
data class CarInfo(
    val endpoint: Endpoint,
    val network: String,
    val authenticated: Boolean,
    val release: String,
    val sdk: Int,
    val abis: List<String>,
    val manufacturer: String,
    val model: String,
    val serial: String?,
    /** No shell v2 (Android < 7): no exit codes, no separate stderr. */
    val legacyShell: Boolean,
    val maxPayload: Int,
    /** Increments with every new connection, so consumers can react once per connection. */
    val session: Long,
) {
    val traits get() = me.ri3d.adb.apps.CarTraits(sdk, release, abis)
    val name get() = listOf(manufacturer, model).filter { it.isNotBlank() && !it.equals("unknown", true) }.distinct().joinToString(" ").ifBlank { "Head unit" }
}

sealed interface CarState {
    data object Disconnected : CarState
    data class Connecting(val stage: String) : CarState
    data class AuthorizationRequired(val fingerprint: String) : CarState
    data class Connected(val info: CarInfo) : CarState
    data class Failed(val reason: String, val retryAt: Long?) : CarState
    data class Lost(val reason: String, val retryAt: Long?) : CarState
}

class NotConnected : AdbException("Not connected to the car")

/**
 * The single ADB connection to the car: connects, authenticates, watches health and reconnects
 * with bounded backoff while the app is in the foreground. State changes happen on the main thread.
 */
class Car(
    private val scope: CoroutineScope,
    private val settings: Settings,
    private val net: Net,
    private val key: () -> AdbKey,
) {
    private val _state = MutableStateFlow<CarState>(CarState.Disconnected)
    val state: StateFlow<CarState> = _state

    val info: CarInfo? get() = (_state.value as? CarState.Connected)?.info

    @Volatile private var conn: AdbConnection? = null
    private var bound: Network? = null
    private var attemptJob: Job? = null
    private var retryJob: Job? = null
    private var failures = 0
    private var recovering = false
    private var userStopped = false
    private var foreground = false
    private var session = 0L

    init {
        scope.launch { net.wifiChanged.collect { if (canAutoConnect()) connect(auto = true) } }
        scope.launch { net.wifiLost.collect { n -> if (n == bound) conn?.close(ConnectionLost("The phone left the car's Wi-Fi")) } }
    }

    /** [auto] attempts come from the app itself and stop after a bounded number of failures. */
    fun connect(auto: Boolean = false) {
        if (attemptJob?.isActive == true || conn != null) return
        if (!auto) {
            userStopped = false
            failures = 0
        }
        retryJob?.cancel()
        attemptJob = scope.launch { attempt() }
    }

    fun cancel() {
        userStopped = true
        retryJob?.cancel()
        attemptJob?.cancel()
        _state.value = CarState.Disconnected
    }

    fun disconnect() {
        userStopped = true
        retryJob?.cancel()
        attemptJob?.cancel()
        val c = conn
        conn = null
        c?.close()
        _state.value = CarState.Disconnected
    }

    private var idleJob: Job? = null

    /**
     * Reconnect attempts only run while someone is looking at the app. In the background the
     * connection is kept (file pickers, running transfers) but dropped after [IDLE_MS] of nothing.
     */
    fun setForeground(fg: Boolean, busy: () -> Boolean = { false }) {
        foreground = fg
        idleJob?.cancel()
        if (fg) {
            if (canAutoConnect()) connect(auto = true)
            return
        }
        retryJob?.cancel()
        idleJob = scope.launch {
            while (true) {
                delay(IDLE_MS)
                if (!busy()) break
            }
            val c = conn ?: return@launch
            conn = null
            c.close()
            _state.value = CarState.Disconnected // not userStopped: coming back reconnects
        }
    }

    private fun canAutoConnect() = foreground && !userStopped && conn == null && attemptJob?.isActive != true &&
        failures < MAX_AUTO_ATTEMPTS && _state.value !is CarState.AuthorizationRequired

    private suspend fun attempt() {
        val ep = Endpoint.parse(settings.endpoint)
        if (ep == null) {
            _state.value = CarState.Failed("\"${settings.endpoint}\" isn't a valid address. Use host:port, e.g. ${Settings.DEFAULT_ENDPOINT}.", null)
            return
        }
        _state.value = CarState.Connecting("Reaching $ep")
        try {
            val address = withContext(Dispatchers.IO) { InetAddress.getByName(ep.host) }
            val cs = net.carSocket(address)
            closeOnCancel(cs.socket) {
                cs.socket.connect(InetSocketAddress(address, ep.port), 5_000)
                cs.socket.tcpNoDelay = true
            }
            _state.value = CarState.Connecting("Car found")
            val k = withContext(Dispatchers.Default) { key() }
            val c = closeOnCancel(cs.socket) {
                AdbConnection.connect(cs.socket, k) { _state.value = CarState.AuthorizationRequired(k.fingerprint) }
            }
            val info = try {
                describe(c, ep, cs.label)
            } catch (e: Throwable) {
                c.close()
                throw e
            }
            conn = c
            bound = cs.network
            failures = 0
            recovering = false
            _state.value = CarState.Connected(info)
            scope.launch { monitor(c) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failures++
            val reason = explain(e, ep)
            val retry = e !is AuthNotGranted && failures < MAX_AUTO_ATTEMPTS && !userStopped
            val at = if (retry) System.currentTimeMillis() + backoff(failures) else null
            _state.value = if (recovering) CarState.Lost(reason, at) else CarState.Failed(reason, at)
            if (at != null) scheduleRetry(at)
        }
    }

    /** "Connected" requires a working shell: read the car's identity with getprop. */
    private suspend fun describe(c: AdbConnection, ep: Endpoint, network: String): CarInfo {
        val props = parseGetprop(c.exec("getprop", 15_000).stdout)
        val sdk = props["ro.build.version.sdk"]?.toIntOrNull() ?: throw AdbException("The car's shell didn't answer getprop")
        val abis = props["ro.product.cpu.abilist"]?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOfNotNull(props["ro.product.cpu.abi"], props["ro.product.cpu.abi2"]).filter { it.isNotBlank() }
        return CarInfo(
            endpoint = ep,
            network = network,
            authenticated = c.authenticated,
            release = props["ro.build.version.release"] ?: "?",
            sdk = sdk,
            abis = abis,
            manufacturer = props["ro.product.manufacturer"] ?: c.properties["ro.product.manufacturer"].orEmpty(),
            model = props["ro.product.model"] ?: c.properties["ro.product.model"].orEmpty(),
            serial = props["ro.serialno"]?.takeIf { it.isNotBlank() && it != "unknown" },
            legacyShell = "shell_v2" !in c.features,
            maxPayload = c.maxPayload,
            session = ++session,
        )
    }

    private suspend fun monitor(c: AdbConnection) {
        val pinger = scope.launch {
            var misses = 0
            while (true) {
                delay(10_000)
                if (System.currentTimeMillis() - c.lastReceived < 20_000) continue
                try {
                    c.exec("echo", 15_000)
                    misses = 0
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    if (++misses >= 2) c.close(ConnectionLost("The car stopped answering"))
                }
            }
        }
        val why = c.closed.await()
        pinger.cancel()
        if (conn !== c) return // disconnected on purpose
        conn = null
        bound = null
        recovering = true
        failures = 0
        val at = System.currentTimeMillis() + backoff(0)
        _state.value = CarState.Lost(why.message ?: "Connection lost", at)
        scheduleRetry(at)
    }

    private fun scheduleRetry(at: Long) {
        retryJob?.cancel()
        if (!foreground) return
        retryJob = scope.launch {
            delay(at - System.currentTimeMillis())
            connect(auto = true)
        }
    }

    private fun explain(e: Throwable, ep: Endpoint): String {
        val noWifi = if (!net.hasWifi) " This phone isn't connected to any Wi-Fi." else ""
        val msg = e.message.orEmpty()
        return when {
            e is UnknownHostException -> "Can't find ${ep.host}.$noWifi"
            e is ConnectException && "ECONNREFUSED" in msg ->
                "$ep refused the connection. Enable ADB over network (TCP port ${ep.port}) on the car."
            e is ConnectException || e is NoRouteToHostException -> "No route to ${ep.host}. Join the car's Wi-Fi.$noWifi"
            e is SocketTimeoutException -> "${ep.host} didn't answer. Is this phone on the car's Wi-Fi?$noWifi"
            e is AdbException -> msg
            else -> msg.ifBlank { e.javaClass.simpleName }
        }
    }

    // ---- Operations, for any screen. All throw NotConnected when there is no connection. ----

    private fun connection(): AdbConnection = conn ?: throw NotConnected()

    suspend fun exec(command: String, timeoutMs: Long = 30_000, onLine: ((String, Boolean) -> Unit)? = null): ShellResult =
        connection().exec(command, timeoutMs, onLine)

    suspend fun <T> sync(block: suspend (Sync) -> T): T = withContext(Dispatchers.IO) { Sync.open(connection()).use { block(it) } }

    companion object {
        const val MAX_AUTO_ATTEMPTS = 8
        const val IDLE_MS = 5 * 60_000L

        /** 2, 4, 8, 16, 30, 30… seconds. */
        fun backoff(failures: Int): Long = minOf(30_000L, 2_000L shl failures.coerceIn(0, 4))
    }
}
