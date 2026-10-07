package me.ri3d.adb.car

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class NoInternet : IOException("No internet connection. Update checks and downloads need mobile data or a Wi-Fi with internet; the car's Wi-Fi usually has none.")

/**
 * Network routing. The car's Wi-Fi usually has no internet, so:
 * - ADB sockets are bound to the Wi-Fi network that routes to the car (never the whole process),
 * - HTTP goes through a validated internet network, which may be cellular.
 */
class Net(context: Context) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = ConcurrentHashMap<Network, LinkProperties>()
    private val validated = ConcurrentHashMap.newKeySet<Network>()

    /** Fires when a Wi-Fi network appears or changes, a cue to look for the car again. */
    val wifiChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Fires with every Wi-Fi network that went away. */
    val wifiLost = MutableSharedFlow<Network>(extraBufferCapacity = 4)

    val hasWifi get() = wifi.isNotEmpty()

    init {
        val wifiRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) // the car's hotspot has none
            .build()
        cm.registerNetworkCallback(wifiRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                cm.getLinkProperties(network)?.let { wifi[network] = it }
                wifiChanged.tryEmit(Unit)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                wifi[network] = lp
                wifiChanged.tryEmit(Unit)
            }

            override fun onLost(network: Network) {
                wifi.remove(network)
                wifiLost.tryEmit(network)
            }
        })
        val internetRequest = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            .build()
        cm.registerNetworkCallback(internetRequest, object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                validated += network
            }

            override fun onLost(network: Network) {
                validated -= network
            }
        })
    }

    class CarSocket(val socket: Socket, val network: Network?, val label: String)

    /**
     * An unconnected socket for the car at [address]. Bound to the Wi-Fi network whose subnet
     * contains the car; otherwise unbound (the phone may itself be the hotspot, or use a VPN).
     */
    fun carSocket(address: InetAddress): CarSocket {
        val match = wifi.entries.firstOrNull { (_, lp) -> lp.routes.any { !it.isDefaultRoute && it.matches(address) } }?.key
        return if (match != null) CarSocket(match.socketFactory.createSocket(), match, "Car Wi-Fi")
        else CarSocket(Socket(), null, "Default route")
    }

    /** Runs [block] with an internet-capable network, asking for cellular if the default network has no internet. */
    suspend fun <T> withInternet(block: suspend (Network?) -> T): T {
        val active = cm.activeNetwork
        if (active != null && cm.getNetworkCapabilities(active)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true) {
            return withContext(Dispatchers.IO) { block(active) }
        }
        validated.firstOrNull()?.let { n -> return withContext(Dispatchers.IO) { block(n) } }
        val got = CompletableDeferred<Network?>()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                got.complete(network)
            }

            override fun onUnavailable() {
                got.complete(null)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            cm.requestNetwork(request, cb, 10_000)
            val n = withTimeoutOrNull(11_000) { got.await() } ?: throw NoInternet()
            return withContext(Dispatchers.IO) { block(n) }
        } finally {
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
    }

    companion object {
        fun http(network: Network?, url: String): HttpURLConnection {
            val u = URL(url)
            return ((network?.openConnection(u) ?: u.openConnection()) as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "MyHeadunit/1.0 (Android)")
            }
        }
    }
}
