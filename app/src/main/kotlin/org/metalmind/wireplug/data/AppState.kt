package org.metalmind.wireplug.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class TunnelStatus(
    val state: String = "down",
    val error: String = "",
    val configured: Boolean = false,
    val endpoint: String = "",
    val since: Long = 0,
    val lastHandshake: Long = 0,
    val rx: Long = 0,
    val tx: Long = 0,
    val connections: Int = 0,
    val idleTimeout: Int = 60,
    val keepUp: Boolean = false,
)

data class ForwardStatus(
    val id: String,
    val name: String,
    val local: String,
    val remote: String,
    val state: String,
    val route: String,
    val connections: Int,
)

data class ProxyStatus(val enabled: Boolean = false, val local: String = "", val state: String = "off", val connections: Int = 0)

data class EngineStatus(
    val mode: String = "offline",
    val running: Boolean = false,
    val tunnel: TunnelStatus = TunnelStatus(),
    val forwards: List<ForwardStatus> = emptyList(),
    val proxy: ProxyStatus = ProxyStatus(),
) {
    val openConnections: Int get() = forwards.sumOf { it.connections } + proxy.connections

    companion object {
        fun parse(json: String): EngineStatus {
            val o = JSONObject(json)
            val t = o.optJSONObject("tunnel") ?: JSONObject()
            val fa = o.optJSONArray("forwards")
            val p = o.optJSONObject("proxy") ?: JSONObject()
            return EngineStatus(
                mode = o.optString("mode", "offline"),
                running = o.optBoolean("running"),
                tunnel = TunnelStatus(
                    state = t.optString("state", "down"),
                    error = t.optString("error"),
                    configured = t.optBoolean("configured"),
                    endpoint = t.optString("endpoint"),
                    since = t.optLong("since"),
                    lastHandshake = t.optLong("lastHandshake"),
                    rx = t.optLong("rx"),
                    tx = t.optLong("tx"),
                    connections = t.optInt("connections"),
                    idleTimeout = t.optInt("idleTimeout", 60),
                    keepUp = t.optBoolean("keepUp"),
                ),
                forwards = (0 until (fa?.length() ?: 0)).map { i ->
                    val f = fa!!.getJSONObject(i)
                    ForwardStatus(
                        id = f.optString("id"),
                        name = f.optString("name"),
                        local = f.optString("local"),
                        remote = f.optString("remote"),
                        state = f.optString("state"),
                        route = f.optString("route"),
                        connections = f.optInt("connections"),
                    )
                },
                proxy = ProxyStatus(
                    enabled = p.optBoolean("enabled"),
                    local = p.optString("local"),
                    state = p.optString("state", "off"),
                    connections = p.optInt("connections"),
                ),
            )
        }
    }
}

/** Outcome of the latest home DNS check (F8). */
data class HomeCheck(
    val time: Long = 0,
    val mode: String = "offline",
    val matched: Boolean = false,
    val summary: String = "Not checked yet",
    val probe: String = "",
    val networkName: String = "",
    /** Whether ACCESS_LOCAL_NETWORK was held when the check ran. */
    val lanAllowed: Boolean = true,
)

/** Process-wide live state shared by the service and the UI. */
object AppState {
    private val _status = MutableStateFlow(EngineStatus())
    val status: StateFlow<EngineStatus> = _status.asStateFlow()

    private val _serviceRunning = MutableStateFlow(false)
    val serviceRunning: StateFlow<Boolean> = _serviceRunning.asStateFlow()

    private val _homeCheck = MutableStateFlow(HomeCheck())
    val homeCheck: StateFlow<HomeCheck> = _homeCheck.asStateFlow()

    /** Errors applying forwards or the proxy, keyed by forward id or "proxy". */
    private val _applyErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val applyErrors: StateFlow<Map<String, String>> = _applyErrors.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private const val LOG_LINES = 200

    fun setStatus(s: EngineStatus) { _status.value = s }
    fun setServiceRunning(running: Boolean) {
        _serviceRunning.value = running
        if (!running) _status.value = EngineStatus()
    }
    fun setHomeCheck(c: HomeCheck) { _homeCheck.value = c }
    fun setApplyErrors(e: Map<String, String>) { _applyErrors.value = e }

    /** Appends a log line. Callers never pass keys or passwords (S6). */
    fun log(line: String) {
        val stamped = synchronized(timeFormat) { timeFormat.format(Date()) } + " " + line
        _log.update { (it + stamped).takeLast(LOG_LINES) }
    }
}
