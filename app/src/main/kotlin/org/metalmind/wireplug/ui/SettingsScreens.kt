package org.metalmind.wireplug.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.metalmind.wireplug.data.AppState
import org.metalmind.wireplug.data.ConfigStore
import org.metalmind.wireplug.data.Forward
import org.metalmind.wireplug.data.HomeSettings
import org.metalmind.wireplug.data.RecordType
import org.metalmind.wireplug.data.TunnelSettings
import org.metalmind.wireplug.data.generatePassword
import org.metalmind.wireplug.go.engine.Engine
import org.metalmind.wireplug.net.LocalNetwork
import org.metalmind.wireplug.service.WirePlugService
import java.net.InetAddress
import java.net.ServerSocket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// --- Settings menu ---

@Composable
fun SettingsScreen(store: ConfigStore, go: (Screen) -> Unit, back: () -> Unit) {
    val cfg by store.config.collectAsState()
    ScreenScaffold("Settings", back) {
        NavRow("Tunnel", cfg.tunnel.endpoint.ifEmpty { "Not configured" }) { go(Screen.Tunnel) }
        HorizontalDivider()
        NavRow(
            "Home network",
            if (cfg.home.isConfigured) "${cfg.home.hostName} · ${cfg.home.recordType} record" else "Not configured",
        ) { go(Screen.Home) }
        HorizontalDivider()
        val n = cfg.forwards.size
        NavRow("Forwards", "$n forward${if (n == 1) "" else "s"}, ${cfg.forwards.count { it.enabled }} enabled") { go(Screen.Forwards) }
        HorizontalDivider()
        NavRow("HTTP proxy", if (cfg.proxy.enabled) "On · port ${cfg.proxy.port}" else "Off") { go(Screen.Proxy) }
        HorizontalDivider()
        NavRow("Behaviour", "Start at boot, notification") { go(Screen.Behaviour) }
    }
}

// --- Tunnel ---

private fun intOr(s: String, default: Int) = s.trim().toIntOrNull() ?: default

@Composable
fun TunnelScreen(store: ConfigStore, back: () -> Unit) {
    val ctx = LocalContext.current
    val saved = store.current.tunnel
    var privateKey by rememberSaveable { mutableStateOf(saved.privateKey) }
    var replacingKey by rememberSaveable { mutableStateOf(saved.privateKey.isEmpty()) }
    var newKey by rememberSaveable { mutableStateOf("") }
    var peer by rememberSaveable { mutableStateOf(saved.peerPublicKey) }
    var psk by rememberSaveable { mutableStateOf(saved.presharedKey) }
    var endpoint by rememberSaveable { mutableStateOf(saved.endpoint) }
    var allowed by rememberSaveable { mutableStateOf(saved.allowedIPs) }
    var address by rememberSaveable { mutableStateOf(saved.address) }
    var dns by rememberSaveable { mutableStateOf(saved.dns) }
    var mtu by rememberSaveable { mutableStateOf(saved.mtu) }
    var keepalive by rememberSaveable { mutableStateOf(saved.keepalive.toString()) }
    var idle by rememberSaveable { mutableStateOf(saved.idleTimeout.toString()) }
    var keepUp by rememberSaveable { mutableStateOf(saved.keepUp) }
    var error by remember { mutableStateOf<String?>(null) }
    var importWarnings by remember { mutableStateOf<List<String>?>(null) }

    fun applyImport(text: String) {
        try {
            val res = JSONObject(Engine.parseConf(text))
            val t = TunnelSettings.fromImport(res.getJSONObject("config"), TunnelSettings(privateKey = privateKey))
            if (t.privateKey.isNotEmpty()) { privateKey = t.privateKey; replacingKey = false; newKey = "" }
            peer = t.peerPublicKey; psk = t.presharedKey; endpoint = t.endpoint; allowed = t.allowedIPs
            address = t.address; dns = t.dns; mtu = t.mtu; keepalive = t.keepalive.toString()
            val w = res.optJSONArray("warnings")
            val list = (0 until (w?.length() ?: 0)).map { w!!.getString(it) }
            importWarnings = list.ifEmpty { listOf("Imported. Review the fields and tap Save.") }
            error = null
        } catch (e: Exception) {
            error = "Import failed: ${e.message}"
        }
    }

    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val text = runCatching {
                ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().take(64 * 1024).toByteArray().toString(Charsets.UTF_8) }
            }.getOrNull()
            if (text == null) error = "Cannot read the file" else applyImport(text)
        }
    }

    fun build(): TunnelSettings {
        val key = if (replacingKey && newKey.isNotBlank()) newKey.trim() else privateKey
        return TunnelSettings(
            privateKey = key,
            peerPublicKey = peer.trim(),
            presharedKey = psk.trim(),
            endpoint = endpoint.trim(),
            allowedIPs = allowed.trim(),
            address = address.trim(),
            dns = dns.trim(),
            mtu = mtu,
            keepalive = intOr(keepalive, 0),
            idleTimeout = intOr(idle, 60).coerceIn(5, 86400),
            keepUp = keepUp,
        )
    }

    ScreenScaffold("Tunnel", back, actions = {
        SaveButton {
            val t = build()
            val err = runCatching { Engine.validateConfig(t.toEngineJson()) }.exceptionOrNull()
            if (err != null) {
                error = err.message
            } else {
                store.update { it.copy(tunnel = t) }
                AppState.log("tunnel settings saved")
                back()
            }
        }
    }) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { openFile.launch(arrayOf("*/*")) }) { Text("Import .conf") }
            OutlinedButton(onClick = {
                val clip = ctx.getSystemService(ClipboardManager::class.java).primaryClip
                val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                // The clipboard text is parsed but never stored unless saved (S7).
                if (text.isNullOrBlank()) error = "The clipboard is empty" else applyImport(text)
            }) { Text("Paste clipboard") }
        }
        error?.let { ErrorNote(it) }

        SectionLabel("Peer")
        Text("Private key (never shown in full)", style = MaterialTheme.typography.bodySmall)
        if (!replacingKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("••••••••••••••••••••", fontFamily = FontFamily.Monospace)
                    val pub = remember(privateKey) { runCatching { Engine.publicKey(privateKey) }.getOrNull() }
                    if (pub != null) Text("Public key: $pub", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                TextButton(onClick = { replacingKey = true }) { Text("Replace") }
            }
        } else {
            Field("New private key", newKey, { newKey = it }, mono = true, visual = PasswordVisualTransformation(),
                supporting = "Base64 WireGuard private key")
        }
        Field("Peer public key", peer, { peer = it }, mono = true)
        Field("Preshared key (optional)", psk, { psk = it }, mono = true, visual = PasswordVisualTransformation())
        Field("Endpoint (hostname or IP)", endpoint, { endpoint = it }, placeholder = "vpn.example.org:51820")
        Field("Allowed IPs", allowed, { allowed = it }, placeholder = "192.168.1.0/24")
        Field("Tunnel address", address, { address = it }, placeholder = "10.8.0.2/32")
        Field("DNS server", dns, { dns = it }, placeholder = "192.168.1.1")
        Field("Keepalive (s)", keepalive, { keepalive = it.filter(Char::isDigit) }, number = true, supporting = "0 turns it off; 25 is usual")
        Field("Idle timeout (s)", idle, { idle = it.filter(Char::isDigit) }, number = true,
            supporting = "The tunnel closes after this long with no open connections")

        SectionLabel("On demand")
        SwitchRow("Keep tunnel up", "Otherwise it starts on first connection", keepUp) { keepUp = it }
    }

    importWarnings?.let { list ->
        AlertDialog(
            onDismissRequest = { importWarnings = null },
            confirmButton = { TextButton(onClick = { importWarnings = null }) { Text("OK") } },
            title = { Text("Import") },
            text = { Text(list.joinToString("\n")) },
        )
    }
}

// --- Home network ---

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

@Composable
fun HomeNetworkScreen(store: ConfigStore, back: () -> Unit) {
    val saved = store.current.home
    val check by AppState.homeCheck.collectAsState()
    var host by rememberSaveable { mutableStateOf(saved.hostName) }
    var type by rememberSaveable { mutableStateOf(saved.recordType) }
    var expected by rememberSaveable { mutableStateOf(saved.expected) }
    var probeHost by rememberSaveable { mutableStateOf(saved.probeHost) }
    var probePort by rememberSaveable { mutableStateOf(if (saved.probePort > 0) saved.probePort.toString() else "") }
    var error by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val lanLauncher = rememberLanPermissionLauncher { back() }

    ScreenScaffold("Home network", back, actions = {
        SaveButton {
            val h = host.trim()
            error = when {
                h.isNotEmpty() && !h.matches(Regex("^[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*\\.?$")) -> "Host name is not valid"
                h.isNotEmpty() && expected.isBlank() -> "Enter the expected value"
                type == RecordType.A && expected.isNotBlank() && !isIpLiteral(expected) -> "Expected address must be an IP address"
                probeHost.isNotBlank() && !isIpLiteral(probeHost) -> "Probe host must be a LAN IP address"
                probeHost.isNotBlank() && intOr(probePort, 0) !in 1..65535 -> "Probe port must be 1 to 65535"
                else -> null
            }
            if (error == null) {
                store.update {
                    it.copy(home = HomeSettings(h, type, expected.trim(), probeHost.trim(), if (probeHost.isBlank()) 0 else intOr(probePort, 0)))
                }
                // Home mode connects to the LAN directly, which Android 17
                // allows only with the local network permission.
                if (h.isNotEmpty() && !LocalNetwork.isAllowed(ctx)) lanLauncher() else back()
            }
        }
    }) {
        InfoNote("Home mode is active when this host name resolves with the expected value on the current network. Use a name only your home DNS server answers.")
        Field("Host name", host, { host = it }, placeholder = "internal.domain")
        Text("Record type", style = MaterialTheme.typography.bodyMedium)
        for (t in RecordType.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .selectable(selected = type == t, onClick = { type = t })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = type == t, onClick = { type = t })
                Text(if (t == RecordType.TXT) "TXT (preferred)" else "A record")
            }
        }
        Field(
            if (type == RecordType.TXT) "Expected TXT value" else "Expected address",
            expected, { expected = it },
            placeholder = if (type == RecordType.TXT) "True" else "10.11.12.13",
        )
        error?.let { ErrorNote(it) }
        SectionCard("Latest lookup") {
            if (check.time == 0L) {
                Text("No lookup yet (runs while the service is running)", style = MaterialTheme.typography.bodyMedium)
            } else {
                Text("${check.summary} · ${clock.format(Date(check.time))}", style = MaterialTheme.typography.bodyMedium)
                if (check.probe.isNotEmpty()) Text(check.probe, style = MaterialTheme.typography.bodySmall)
                Text(
                    when (check.mode) { "home" -> "Home"; "away" -> "Away"; else -> "Offline" } +
                        (if (check.networkName.isNotEmpty()) " · ${check.networkName}" else ""),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        SectionLabel("Reachability probe (optional)")
        Text("Not required. If set, Home also needs a TCP connect to this LAN address to succeed.", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("Probe host", probeHost, { probeHost = it }, Modifier.weight(2f), placeholder = "Not set")
            Field("Port", probePort, { probePort = it.filter(Char::isDigit) }, Modifier.weight(1f), number = true)
        }
    }
}

// --- Forwards ---

@Composable
fun ForwardsScreen(store: ConfigStore, go: (Screen) -> Unit, back: () -> Unit) {
    val cfg by store.config.collectAsState()
    ScreenScaffold("Forwards", back) {
        // S2: say plainly who can use a forward.
        InfoNote("Any app on this phone can connect to an enabled forward.")
        cfg.forwards.forEachIndexed { i, f ->
            if (i > 0) HorizontalDivider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { go(Screen.EditForward(f.id)) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.titleMedium)
                    Text(":${f.localPort}  →  ${f.remoteAddr}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                }
                Switch(checked = f.enabled, onCheckedChange = { on ->
                    store.update { c -> c.copy(forwards = c.forwards.map { if (it.id == f.id) it.copy(enabled = on) else it }) }
                })
            }
        }
        if (cfg.forwards.isNotEmpty()) {
            Text("Tap a row to edit or delete it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = { go(Screen.EditForward(null)) }, modifier = Modifier.fillMaxWidth()) { Text("+  Add forward") }
    }
}

/** Checks a local port against other forwards, the proxy and the system (F13). */
private fun portProblem(store: ConfigStore, id: String?, port: Int): String? {
    val cfg = store.current
    cfg.forwards.find { it.id != id && it.localPort == port }?.let { return "Port $port is already used by forward \"${it.name}\"" }
    if (cfg.proxy.enabled && cfg.proxy.port == port) return "Port $port is already used by the HTTP proxy"
    val ours = cfg.forwards.find { it.id == id }?.let { it.enabled && it.localPort == port && WirePlugService.current != null } ?: false
    if (!ours) {
        try {
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
        } catch (e: Exception) {
            return "Port $port cannot be used: ${e.message}"
        }
    }
    return null
}

@Composable
fun EditForwardScreen(store: ConfigStore, id: String?, back: () -> Unit) {
    val existing = remember(id) { store.current.forwards.find { it.id == id } }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var local by rememberSaveable { mutableStateOf(existing?.localPort?.toString() ?: "") }
    var ip by rememberSaveable { mutableStateOf(existing?.remoteIp ?: "") }
    var port by rememberSaveable { mutableStateOf(existing?.remotePort?.toString() ?: "") }
    var enabled by rememberSaveable { mutableStateOf(existing?.enabled ?: true) }
    var error by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun validate(): String? = when {
        name.isBlank() -> "Enter a name"
        intOr(local, 0) !in 1024..65535 -> "Local port must be 1024 to 65535"
        !isIpLiteral(ip) -> "Enter an IP address, not a host name"
        intOr(port, 0) !in 1..65535 -> "Remote port must be 1 to 65535"
        else -> null
    }

    ScreenScaffold(if (existing == null) "Add forward" else "Edit forward", back, actions = {
        SaveButton {
            error = validate()
            if (error != null) return@SaveButton
            scope.launch {
                val lp = intOr(local, 0)
                val problem = withContext(Dispatchers.IO) { portProblem(store, existing?.id, lp) }
                if (problem != null) {
                    error = problem
                    return@launch
                }
                val f = (existing ?: Forward()).copy(name = name.trim(), localPort = lp, remoteIp = ip.trim(), remotePort = intOr(port, 0), enabled = enabled)
                store.update { c ->
                    c.copy(forwards = if (existing == null) c.forwards + f else c.forwards.map { if (it.id == f.id) f else it })
                }
                back()
            }
        }
    }) {
        Field("Name", name, { name = it }, placeholder = "Home Assistant")
        Field("Local port (on 127.0.0.1)", local, { local = it.filter(Char::isDigit) }, number = true)
        SectionLabel("Remote target")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Field("IP address", ip, { ip = it.trim() }, Modifier.weight(2f), placeholder = "192.168.1.10")
            Field("Port", port, { port = it.filter(Char::isDigit) }, Modifier.weight(1f), number = true)
        }
        Text("An IP address, not a host name. The same address is used at home and through the tunnel.", style = MaterialTheme.typography.bodySmall)
        SwitchRow("Enabled", null, enabled) { enabled = it }
        error?.let { ErrorNote(it) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !testing, onClick = {
                val v = validate()
                val engine = WirePlugService.current
                when {
                    v != null -> testResult = v
                    engine == null -> testResult = "Turn WirePlug on to test"
                    else -> {
                        testing = true
                        testResult = "Testing…"
                        val target = Forward(remoteIp = ip.trim(), remotePort = intOr(port, 0)).remoteAddr
                        scope.launch {
                            val res = withContext(Dispatchers.IO) { JSONObject(engine.testRoute(target)) }
                            val route = when (res.optString("route")) { "direct" -> "directly"; "tunnel" -> "through the tunnel"; else -> "" }
                            testResult = if (res.optBoolean("ok")) "Connected $route in ${res.optLong("ms")} ms"
                            else "Failed $route: ${res.optString("error")}"
                            testing = false
                        }
                    }
                }
            }) { Text("Test") }
            testResult?.let { Text(it, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium) }
        }

        if (existing != null) {
            TextButton(onClick = { confirmDelete = true }) {
                Text("Delete forward", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (confirmDelete && existing != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${existing.name}?") },
            text = { Text("Open connections through this forward are closed.") },
            confirmButton = {
                TextButton(onClick = {
                    store.update { c -> c.copy(forwards = c.forwards.filterNot { it.id == existing.id }) }
                    confirmDelete = false
                    back()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

// --- Proxy ---

@Composable
fun ProxyScreen(store: ConfigStore, back: () -> Unit) {
    val ctx = LocalContext.current
    val saved = store.current.proxy
    var enabled by rememberSaveable { mutableStateOf(saved.enabled) }
    var port by rememberSaveable { mutableStateOf(saved.port.toString()) }
    var user by rememberSaveable { mutableStateOf(saved.username) }
    var password by rememberSaveable { mutableStateOf(saved.password) }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    ScreenScaffold("HTTP proxy", back, actions = {
        SaveButton {
            val p = intOr(port, 0)
            error = when {
                p !in 1024..65535 -> "Port must be 1024 to 65535"
                user.isBlank() -> "Enter a username"
                password.length < 16 -> "The password must be at least 16 characters"
                else -> null
            }
            if (error != null) return@SaveButton
            scope.launch {
                val cfg = store.current
                val clash = cfg.forwards.find { it.localPort == p }
                val problem = when {
                    clash != null -> "Port $p is already used by forward \"${clash.name}\""
                    enabled && !(cfg.proxy.enabled && cfg.proxy.port == p && WirePlugService.current != null) ->
                        withContext(Dispatchers.IO) {
                            runCatching { ServerSocket(p, 1, InetAddress.getByName("127.0.0.1")).close() }
                                .exceptionOrNull()?.let { "Port $p cannot be used: ${it.message}" }
                        }
                    else -> null
                }
                if (problem != null) {
                    error = problem
                    return@launch
                }
                store.update { it.copy(proxy = it.proxy.copy(enabled = enabled, port = p, username = user.trim(), password = password)) }
                back()
            }
        }
    }) {
        SwitchRow("Enable proxy", "HTTP and CONNECT on 127.0.0.1", enabled) { enabled = it }
        Field("Local port", port, { port = it.filter(Char::isDigit) }, number = true)
        Field("Username", user, { user = it })
        Field("Password (generated)", password, { password = it }, mono = true,
            visual = if (show) androidx.compose.ui.text.input.VisualTransformation.None else PasswordVisualTransformation())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") }
            OutlinedButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("WirePlug proxy password", password).apply {
                    if (Build.VERSION.SDK_INT >= 24) description.extras = android.os.PersistableBundle().apply {
                        putBoolean("android.content.extra.IS_SENSITIVE", true)
                    }
                })
            }) { Text("Copy") }
            OutlinedButton(onClick = { password = generatePassword() }) { Text("New") }
        }
        error?.let { ErrorNote(it) }
        InfoNote("Reaches only destinations inside the tunnel's allowed IPs, in Home and Away mode alike. Other destinations get 502.")
    }
}

// --- Behaviour ---

@Composable
fun BehaviourScreen(store: ConfigStore, back: () -> Unit) {
    val ctx = LocalContext.current
    val cfg by store.config.collectAsState()
    ScreenScaffold("Behaviour", back) {
        SwitchRow("Start at boot", "Only if the main switch was on", cfg.behaviour.startAtBoot) { on ->
            store.update { it.copy(behaviour = it.behaviour.copy(startAtBoot = on)) }
        }
        HorizontalDivider()
        SwitchRow("Detailed notification", "Show tunnel state, connection count and handshake age", cfg.behaviour.notificationDetail) { on ->
            store.update { it.copy(behaviour = it.behaviour.copy(notificationDetail = on)) }
        }
        HorizontalDivider()
        NavRow("Notification settings", "Opens the system settings for WirePlug's notifications") {
            ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
        }
        HorizontalDivider()
        NavRow("Battery settings", "Opens the system battery settings") {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                .onFailure { openAppSettings(ctx) }
        }
        InfoNote("WirePlug does not need a battery exemption. It makes no periodic wake-ups while the tunnel is down.")
    }
}
