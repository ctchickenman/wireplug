package org.metalmind.wireplug.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import org.metalmind.wireplug.data.AppState
import org.metalmind.wireplug.data.ConfigStore
import org.metalmind.wireplug.data.Overall
import org.metalmind.wireplug.data.formatAge
import org.metalmind.wireplug.data.formatBytes
import org.metalmind.wireplug.data.overallState
import org.metalmind.wireplug.service.WirePlugService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class Warning(val text: String, val fix: String, val action: () -> Unit)

@Composable
fun StatusScreen(store: ConfigStore, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val cfg by store.config.collectAsState()
    val status by AppState.status.collectAsState()
    val running by AppState.serviceRunning.collectAsState()
    val home by AppState.homeCheck.collectAsState()
    val errors by AppState.applyErrors.collectAsState()
    val log by AppState.log.collectAsState()

    // Clock for "x s ago" texts, ticking only while the screen is visible.
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    // Re-read permission and battery state when the screen resumes.
    var resumes by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            resumes++
            while (true) {
                now = System.currentTimeMillis() / 1000
                delay(1000)
            }
        }
    }

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { resumes++ }

    val overall = overallState(running, status)
    val warnings = remember(cfg, errors, resumes, running) {
        buildList {
            if (!cfg.tunnel.isComplete) add(Warning("Tunnel is not configured", "Fix") { go(Screen.Tunnel) })
            if (!cfg.home.isConfigured) add(Warning("No home DNS name set: Home mode cannot be detected", "Fix") { go(Screen.Home) })
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) add(Warning("Notification permission denied", "Fix") { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) })
            if (Build.VERSION.SDK_INT >= 28 && ctx.getSystemService(ActivityManager::class.java).isBackgroundRestricted) {
                add(Warning("Battery restriction may stop the service", "Fix") { openAppSettings(ctx) })
            }
            if (cfg.behaviour.mainSwitch && !cfg.shouldRun) {
                add(Warning("No forward or proxy is enabled, so the service is not running", "Fix") { go(Screen.Forwards) })
            }
            for ((id, msg) in errors) {
                val name = if (id == "proxy") "HTTP proxy" else cfg.forwards.find { it.id == id }?.name ?: "Forward"
                add(Warning("$name: $msg", "Edit") { go(if (id == "proxy") Screen.Proxy else Screen.EditForward(id)) })
            }
        }
    }

    ScreenScaffold(
        title = "WirePlug",
        onBack = null,
        actions = {
            IconButton(onClick = { go(Screen.Settings) }) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
        },
    ) {
        // Header: main switch, overall state and detail.
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = headerColor(overall))) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(overall.label, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(headerDetail(overall, cfg.behaviour.mainSwitch, running, status, home, now), style = MaterialTheme.typography.bodyMedium)
                }
                Switch(
                    checked = cfg.behaviour.mainSwitch,
                    onCheckedChange = { on -> store.update { it.copy(behaviour = it.behaviour.copy(mainSwitch = on)) } },
                )
            }
        }

        for (w in warnings) {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                    Spacer(Modifier.width(8.dp))
                    Text(w.text, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = w.action) { Text(w.fix) }
                }
            }
        }

        if (running) {
            SectionCard("Tunnel") {
                val t = status.tunnel
                KeyValue("State", when (t.state) {
                    "up" -> "Up"
                    "connecting" -> "Connecting"
                    "error" -> "Error"
                    else -> if (status.mode == "away") "Idle" else "Down"
                })
                when (t.state) {
                    "up", "connecting" -> {
                        KeyValue("Last handshake", if (t.lastHandshake > 0) formatAge(now - t.lastHandshake) else "none yet")
                        KeyValue("Sent / received", "${formatBytes(t.tx)} / ${formatBytes(t.rx)}")
                        KeyValue("Endpoint", t.endpoint)
                        if (t.state == "connecting") KeyValue("Elapsed", "${(now - t.since).coerceAtLeast(0)} s")
                    }
                    "error" -> {
                        Text(t.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { Thread { WirePlugService.current?.retryTunnel() }.start() }) { Text("Retry") }
                    }
                    else -> {
                        KeyValue("Endpoint", cfg.tunnel.endpoint.ifEmpty { "not set" })
                        if (status.mode == "away") {
                            KeyValue("Idle timeout", if (t.keepUp) "kept up" else "${t.idleTimeout} s")
                        }
                    }
                }
            }
        }

        SectionCard("Forwards") {
            if (cfg.forwards.isEmpty()) {
                Text("No forwards yet.", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { go(Screen.EditForward(null)) }) { Text("Add forward") }
            }
            cfg.forwards.forEachIndexed { i, f ->
                if (i > 0) HorizontalDivider()
                val fs = status.forwards.find { it.id == f.id }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.titleSmall)
                        Text(":${f.localPort}  →  ${f.remoteAddr}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                        val state = when {
                            !f.enabled -> "Disabled"
                            errors.containsKey(f.id) -> "Error: ${errors[f.id]}"
                            !running -> "Not running"
                            fs != null -> "Listening · ${fs.connections} open"
                            else -> "Starting"
                        }
                        Text(state, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (running && f.enabled && fs != null) {
                        AssistChip(onClick = {}, label = { Text(routeLabel(fs.route)) })
                    }
                }
            }
        }

        if (cfg.proxy.enabled) {
            SectionCard("HTTP proxy") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val p = status.proxy
                    val state = when {
                        errors.containsKey("proxy") -> "Error: ${errors["proxy"]}"
                        !running -> "Not running"
                        p.enabled -> "Listening · ${p.connections} open"
                        else -> "Starting"
                    }
                    Text("${cfg.proxy.localAddr} · $state", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (running && status.proxy.enabled) AssistChip(onClick = {}, label = { Text(routeLabel(if (status.mode == "home") "direct" else if (status.mode == "away") "tunnel" else "none")) })
                }
            }
        }

        SectionCard("Log", action = {
            TextButton(onClick = {
                val cm = ctx.getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("WirePlug log", log.joinToString("\n")))
            }) { Text("Copy") }
        }) {
            if (log.isEmpty()) Text("Empty", style = MaterialTheme.typography.bodySmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (line in log.takeLast(200).asReversed()) {
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun headerColor(o: Overall) = when (o) {
    Overall.HOME, Overall.AWAY_UP -> MaterialTheme.colorScheme.primaryContainer
    Overall.ERROR -> MaterialTheme.colorScheme.errorContainer
    else -> MaterialTheme.colorScheme.surfaceVariant
}

private fun routeLabel(route: String) = when (route) {
    "direct" -> "Direct"
    "tunnel" -> "Tunnel"
    else -> "No route"
}

private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun headerDetail(
    o: Overall,
    mainSwitch: Boolean,
    running: Boolean,
    s: org.metalmind.wireplug.data.EngineStatus,
    home: org.metalmind.wireplug.data.HomeCheck,
    now: Long,
): String = when (o) {
    Overall.OFF -> if (mainSwitch && !running) "Starting…" else "Nothing running"
    Overall.HOME -> buildString {
        append("Home DNS name matched on ${home.networkName.ifEmpty { "this network" }}")
        if (home.probe.isNotEmpty()) append(" · ${home.probe}")
    }
    Overall.AWAY_IDLE -> "On another network · tunnel starts on first connection"
    Overall.AWAY_CONNECTING -> "Handshake with ${s.tunnel.endpoint} · ${(now - s.tunnel.since).coerceAtLeast(0)} s"
    Overall.AWAY_UP -> "On another network · service running"
    Overall.OFFLINE -> "No usable network · listeners up, connections fail fast"
    Overall.ERROR -> s.tunnel.error
} + if (o != Overall.OFF && home.time > 0) "\nHome check ${clock.format(Date(home.time))}: ${home.summary}" else ""

fun openAppSettings(ctx: Context) {
    ctx.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
