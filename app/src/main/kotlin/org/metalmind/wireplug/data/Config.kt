package org.metalmind.wireplug.data

import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

data class TunnelSettings(
    val privateKey: String = "",
    val peerPublicKey: String = "",
    val presharedKey: String = "",
    val endpoint: String = "",
    val allowedIPs: String = "",
    val address: String = "",
    val dns: String = "",
    val mtu: Int = 0,
    val keepalive: Int = 25,
    val idleTimeout: Int = 60,
    val keepUp: Boolean = false,
) {
    val isComplete: Boolean
        get() = privateKey.isNotBlank() && peerPublicKey.isNotBlank() && endpoint.isNotBlank() &&
            allowedIPs.isNotBlank() && address.isNotBlank()

    /** The JSON the Go engine's Configure takes. */
    fun toEngineJson(): String = JSONObject().apply {
        put("privateKey", privateKey)
        put("peerPublicKey", peerPublicKey)
        put("presharedKey", presharedKey)
        put("endpoint", endpoint)
        put("allowedIPs", allowedIPs)
        put("address", address)
        put("dns", dns)
        put("mtu", mtu)
        put("keepalive", keepalive)
        put("idleTimeout", idleTimeout)
        put("keepUp", keepUp)
    }.toString()

    /** Everything but the secrets, for plain storage. */
    fun toPublicJson(): JSONObject = JSONObject().apply {
        put("peerPublicKey", peerPublicKey)
        put("endpoint", endpoint)
        put("allowedIPs", allowedIPs)
        put("address", address)
        put("dns", dns)
        put("mtu", mtu)
        put("keepalive", keepalive)
        put("idleTimeout", idleTimeout)
        put("keepUp", keepUp)
    }

    companion object {
        fun fromJson(o: JSONObject?, privateKey: String, presharedKey: String): TunnelSettings {
            if (o == null) return TunnelSettings(privateKey = privateKey, presharedKey = presharedKey)
            return TunnelSettings(
                privateKey = privateKey,
                peerPublicKey = o.optString("peerPublicKey"),
                presharedKey = presharedKey,
                endpoint = o.optString("endpoint"),
                allowedIPs = o.optString("allowedIPs"),
                address = o.optString("address"),
                dns = o.optString("dns"),
                mtu = o.optInt("mtu", 0),
                keepalive = o.optInt("keepalive", 25),
                idleTimeout = o.optInt("idleTimeout", 60),
                keepUp = o.optBoolean("keepUp", false),
            )
        }

        /** Builds settings from the Go ParseConf result's "config" object. */
        fun fromImport(o: JSONObject, current: TunnelSettings): TunnelSettings = current.copy(
            privateKey = o.optString("privateKey").ifBlank { current.privateKey },
            peerPublicKey = o.optString("peerPublicKey"),
            presharedKey = o.optString("presharedKey"),
            endpoint = o.optString("endpoint"),
            allowedIPs = o.optString("allowedIPs"),
            address = o.optString("address"),
            dns = o.optString("dns"),
            mtu = o.optInt("mtu", 0),
            keepalive = o.optInt("keepalive", 0),
        )
    }
}

enum class RecordType { TXT, A }

data class HomeSettings(
    val hostName: String = "",
    val recordType: RecordType = RecordType.TXT,
    val expected: String = "",
    val probeHost: String = "",
    val probePort: Int = 0,
) {
    val isConfigured: Boolean get() = hostName.isNotBlank() && expected.isNotBlank()
    val probeEnabled: Boolean get() = probeHost.isNotBlank() && probePort in 1..65535

    fun toJson(): JSONObject = JSONObject().apply {
        put("hostName", hostName)
        put("recordType", recordType.name)
        put("expected", expected)
        put("probeHost", probeHost)
        put("probePort", probePort)
    }

    companion object {
        fun fromJson(o: JSONObject?): HomeSettings {
            if (o == null) return HomeSettings()
            return HomeSettings(
                hostName = o.optString("hostName"),
                recordType = runCatching { RecordType.valueOf(o.optString("recordType")) }.getOrDefault(RecordType.TXT),
                expected = o.optString("expected"),
                probeHost = o.optString("probeHost"),
                probePort = o.optInt("probePort", 0),
            )
        }
    }
}

data class Forward(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val localPort: Int = 0,
    val remoteIp: String = "",
    val remotePort: Int = 0,
    val enabled: Boolean = true,
) {
    val localAddr: String get() = "127.0.0.1:$localPort"
    val remoteAddr: String get() = if (remoteIp.contains(':')) "[$remoteIp]:$remotePort" else "$remoteIp:$remotePort"

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("localPort", localPort)
        put("remoteIp", remoteIp)
        put("remotePort", remotePort)
        put("enabled", enabled)
    }

    companion object {
        fun fromJson(o: JSONObject) = Forward(
            id = o.getString("id"),
            name = o.optString("name"),
            localPort = o.optInt("localPort"),
            remoteIp = o.optString("remoteIp"),
            remotePort = o.optInt("remotePort"),
            enabled = o.optBoolean("enabled", true),
        )
    }
}

data class ProxySettings(
    val enabled: Boolean = false,
    val port: Int = 8888,
    val username: String = "wireplug",
    val password: String = generatePassword(),
) {
    val localAddr: String get() = "127.0.0.1:$port"
}

data class Behaviour(
    val mainSwitch: Boolean = false,
    val startAtBoot: Boolean = false,
    val notificationDetail: Boolean = true,
)

data class AppConfig(
    val tunnel: TunnelSettings = TunnelSettings(),
    val home: HomeSettings = HomeSettings(),
    val forwards: List<Forward> = emptyList(),
    val proxy: ProxySettings = ProxySettings(),
    val behaviour: Behaviour = Behaviour(),
) {
    /** F16: the service runs only with the main switch on and something enabled. */
    val shouldRun: Boolean
        get() = behaviour.mainSwitch && (forwards.any { it.enabled } || proxy.enabled)

    fun forwardsToJson(): JSONArray = JSONArray().apply { forwards.forEach { put(it.toJson()) } }
}

private const val PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"

/** A random proxy password, 20 characters (S3 requires at least 16). */
fun generatePassword(length: Int = 20): String {
    val rnd = SecureRandom()
    return buildString { repeat(length) { append(PASSWORD_CHARS[rnd.nextInt(PASSWORD_CHARS.length)]) } }
}
