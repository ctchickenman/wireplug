package org.metalmind.wireplug.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.metalmind.wireplug.data.HomeCheck
import org.metalmind.wireplug.data.HomeSettings
import org.metalmind.wireplug.data.RecordType
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * Decides Home, Away or Offline from the default network (F6 to F8). It runs
 * the home DNS check on every default-network change and calls [onResult]
 * with the mode and the network it was decided on.
 */
class ModeDetector(
    context: Context,
    private val scope: CoroutineScope,
    private val settings: () -> HomeSettings,
    private val onResult: (check: HomeCheck, network: Network?, networkChanged: Boolean) -> Unit,
) {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private var job: Job? = null
    private var lastNetwork: Network? = null

    @Volatile var currentNetwork: Network? = null
        private set

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = schedule()
        override fun onLost(network: Network) = schedule()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            // Validation and similar changes arrive often; only re-check when
            // the network itself changed.
            if (network != lastNetwork) schedule()
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = schedule()
    }

    fun start() {
        cm.registerDefaultNetworkCallback(callback)
        schedule()
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(callback) }
        job?.cancel()
    }

    /** Re-runs the check, e.g. after the home settings change. */
    fun recheck() = schedule(0)

    private fun schedule(debounceMs: Long = 400) {
        job?.cancel()
        job = scope.launch {
            delay(debounceMs)
            evaluate()
        }
    }

    private suspend fun evaluate() {
        val network = cm.activeNetwork
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val changed = network != lastNetwork
        lastNetwork = network
        currentNetwork = network
        val now = System.currentTimeMillis()
        if (network == null || caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            onResult(HomeCheck(now, "offline", false, "No usable network"), null, changed)
            return
        }
        val name = networkLabel(caps)
        val s = settings()
        if (!s.isConfigured) {
            onResult(HomeCheck(now, "away", false, "No home DNS check configured", networkName = name), network, changed)
            return
        }
        val lp = cm.getLinkProperties(network)
        val servers = lp?.dnsServers.orEmpty()
        val check = withContext(Dispatchers.IO) { homeLookup(network, servers, s) }
        var mode = if (check.first) "home" else "away"
        var probe = ""
        if (check.first && s.probeEnabled) {
            val ok = withContext(Dispatchers.IO) { probe(network, s.probeHost, s.probePort) }
            probe = if (ok == null) "Probe ${s.probeHost}:${s.probePort} connected" else "Probe failed: $ok"
            if (ok != null) mode = "away"
        }
        onResult(HomeCheck(now, mode, check.first, check.second, probe, name), network, changed)
    }

    private fun networkLabel(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
        else -> "Network"
    }

    /**
     * Queries the network's own DNS servers over a socket bound to that
     * network, within 2 s in total (F8). Returns whether the expected value
     * matched and a summary for the status screen.
     */
    private fun homeLookup(network: Network, servers: List<InetAddress>, s: HomeSettings): Pair<Boolean, String> {
        if (servers.isEmpty()) return false to "Network has no DNS servers"
        val type = if (s.recordType == RecordType.TXT) DnsQuery.TYPE_TXT else DnsQuery.TYPE_A
        val label = if (s.recordType == RecordType.TXT) "TXT value" else "Address"
        val deadline = SystemClock.elapsedRealtime() + 2000
        var lastError = "No answer"
        for (server in servers) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 50) break
            try {
                DatagramSocket().use { sock ->
                    network.bindSocket(sock)
                    sock.soTimeout = remaining.toInt()
                    val id = DnsQuery.newId()
                    val q = DnsQuery.build(id, s.hostName.trim(), type)
                    sock.send(DatagramPacket(q, q.size, InetSocketAddress(server, 53)))
                    val buf = ByteArray(1500)
                    val p = DatagramPacket(buf, buf.size)
                    sock.receive(p)
                    val answers = DnsQuery.parse(buf, p.length, id, type)
                    val expected = s.expected.trim()
                    val hit = answers.any { a ->
                        if (s.recordType == RecordType.TXT) a.trim().equals(expected, ignoreCase = true)
                        else runCatching { InetAddress.getByName(expected) == InetAddress.getByName(a) }.getOrDefault(false)
                    }
                    return if (hit) true to "$label matched"
                    else if (answers.isEmpty()) false to "No ${s.recordType} record for ${s.hostName}"
                    else false to "$label did not match (got ${answers.joinToString().take(60)})"
                }
            } catch (e: SocketTimeoutException) {
                lastError = "Lookup timed out"
            } catch (e: DnsQuery.DnsException) {
                return false to "Lookup failed: ${e.message}"
            } catch (e: Exception) {
                lastError = "Lookup failed: ${e.message ?: e.javaClass.simpleName}"
            }
        }
        return false to lastError
    }

    /** F7: returns null on success or the failure reason. */
    private fun probe(network: Network, host: String, port: Int): String? = try {
        network.socketFactory.createSocket().use { sock ->
            sock.connect(InetSocketAddress(network.getByName(host.trim()), port), 1000)
        }
        null
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
