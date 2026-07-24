package com.v2ray.ang.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Measures a TCP handshake strictly over the physical Wi-Fi/mobile network.
 *
 * There is deliberately no unbound-socket fallback: after Android makes a VPN
 * the default network, an unbound socket would measure the tunnel instead.
 */
object DirectPingManager {
    @Volatile
    private var connectivity: ConnectivityManager? = null

    @Volatile
    private var physicalNetwork: Network? = null

    private val probes = Semaphore(MAX_CONCURRENT_PROBES)

    private val physicalNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            physicalNetwork = chooseBetterNetwork(physicalNetwork, network)
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (isPhysical(capabilities)) {
                physicalNetwork = chooseBetterNetwork(physicalNetwork, network)
            }
        }

        override fun onLost(network: Network) {
            if (physicalNetwork == network) {
                physicalNetwork = findPhysicalNetwork(connectivity)
            }
        }
    }

    suspend fun measure(context: Context, host: String?, portText: String?): Long =
        withContext(Dispatchers.IO) {
            val cleanHost = host?.trim()?.takeIf { it.isNotEmpty() } ?: return@withContext -1L
            val port = portText?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return@withContext -1L
            ensureNetworkTracking(context.applicationContext)

            // VPN activation briefly reshuffles Android's Network objects. Wait
            // for the NOT_VPN callback instead of falling back to the tunnel.
            val network = awaitPhysicalNetwork() ?: return@withContext -1L
            probes.withPermit {
                try {
                    Socket().use { socket ->
                        socket.tcpNoDelay = true
                        network.bindSocket(socket)
                        val address = network.getAllByName(cleanHost).firstOrNull()
                            ?: return@withPermit -1L
                        val started = SystemClock.elapsedRealtimeNanos()
                        socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
                        ((SystemClock.elapsedRealtimeNanos() - started) / 1_000_000L).coerceAtLeast(1L)
                    }
                } catch (_: Exception) {
                    // The Network object may have become stale while the screen
                    // was off. Drop it so the next probe resolves a fresh one.
                    if (physicalNetwork == network) physicalNetwork = null
                    -1L
                }
            }
        }

    /** Revalidates Android's Network handle after Doze/screen-off transitions. */
    fun refresh(context: Context) {
        ensureNetworkTracking(context.applicationContext)
        physicalNetwork = findPhysicalNetwork(connectivity)
    }

    /**
     * Returns a validated Wi-Fi/mobile/Ethernet Network for small direct HTTP
     * lookups such as source-IP geolocation. Call from a background thread.
     */
    fun directNetwork(context: Context): Network? {
        ensureNetworkTracking(context.applicationContext)
        currentPhysicalNetwork()?.let { return it }
        repeat(NETWORK_WAIT_STEPS) {
            Thread.sleep(NETWORK_WAIT_STEP_MS)
            currentPhysicalNetwork()?.let { return it }
        }
        return null
    }

    @Synchronized
    private fun ensureNetworkTracking(context: Context) {
        if (connectivity != null) return
        val manager = context.getSystemService(ConnectivityManager::class.java)
        connectivity = manager
        physicalNetwork = findPhysicalNetwork(manager)
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        manager.registerNetworkCallback(request, physicalNetworkCallback)
    }

    private suspend fun awaitPhysicalNetwork(): Network? {
        currentPhysicalNetwork()?.let { return it }
        repeat(NETWORK_WAIT_STEPS) {
            currentPhysicalNetwork()?.let { return it }
            delay(NETWORK_WAIT_STEP_MS)
        }
        return null
    }

    private fun currentPhysicalNetwork(): Network? {
        val manager = connectivity ?: return null
        val cached = physicalNetwork
        if (cached != null && isPhysical(manager.getNetworkCapabilities(cached))) return cached
        return findPhysicalNetwork(manager)?.also { physicalNetwork = it }
    }

    private fun findPhysicalNetwork(manager: ConnectivityManager?): Network? {
        manager ?: return null
        return manager.allNetworks
            .filter { isPhysical(manager.getNetworkCapabilities(it)) }
            .maxByOrNull { networkScore(manager.getNetworkCapabilities(it)) }
    }

    private fun chooseBetterNetwork(current: Network?, candidate: Network): Network {
        val manager = connectivity ?: return candidate
        if (current == null) return candidate
        val currentScore = networkScore(manager.getNetworkCapabilities(current))
        val candidateScore = networkScore(manager.getNetworkCapabilities(candidate))
        return if (candidateScore >= currentScore) candidate else current
    }

    private fun isPhysical(capabilities: NetworkCapabilities?): Boolean {
        capabilities ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN) &&
            (
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                )
    }

    private fun networkScore(capabilities: NetworkCapabilities?): Int {
        capabilities ?: return 0
        var score = 0
        if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) score += 100
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) score += 20
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) score += 15
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) score += 10
        return score
    }

    private const val CONNECT_TIMEOUT_MS = 2_500
    private const val MAX_CONCURRENT_PROBES = 3
    private const val NETWORK_WAIT_STEPS = 20
    private const val NETWORK_WAIT_STEP_MS = 100L
}
