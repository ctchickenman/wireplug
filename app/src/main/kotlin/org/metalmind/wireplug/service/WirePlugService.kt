package org.metalmind.wireplug.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.metalmind.wireplug.WirePlugApp
import org.metalmind.wireplug.data.AppConfig
import org.metalmind.wireplug.data.AppState
import org.metalmind.wireplug.data.Forward
import org.metalmind.wireplug.data.HomeSettings
import org.metalmind.wireplug.data.ProxySettings
import org.metalmind.wireplug.data.TunnelSettings
import org.metalmind.wireplug.go.engine.Engine
import org.metalmind.wireplug.go.engine.Engine_
import org.metalmind.wireplug.net.ModeDetector

/**
 * Foreground service that owns the Go engine while the main switch is on
 * and something is enabled (F16). It is sticky and rebuilds everything from
 * the stored configuration after process death or a reboot (P3).
 */
class WirePlugService : Service() {

    @OptIn(ExperimentalCoroutinesApi::class)
    private val applyDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var engine: Engine_
    private lateinit var detector: ModeDetector
    private lateinit var notifications: StatusNotification

    @Volatile private var homeNetwork: Network? = null

    private var appliedTunnel: TunnelSettings? = null
    private var appliedHome: HomeSettings? = null
    private val appliedForwards = mutableMapOf<String, Forward>()
    private var appliedProxy: ProxySettings? = null
    private val applyErrors = mutableMapOf<String, String>()

    private val store get() = (application as WirePlugApp).config

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = StatusNotification(this)
        goForeground()
        AppState.log("service started")
        engine = Engine.newEngine(
            EngineHost(
                homeNetwork = { homeNetwork },
                defaultNetwork = { detector.currentNetwork },
                onStatus = { AppState.setStatus(it) },
            )
        )
        current = engine
        recheckHook = { detector.recheck() }
        AppState.setServiceRunning(true)
        detector = ModeDetector(this, scope, { store.current.home }) { check, network, changed ->
            AppState.setHomeCheck(check)
            homeNetwork = if (check.mode == "home") network else null
            if (engine.mode() != check.mode) {
                AppState.log("mode=${check.mode} (${check.networkName.ifEmpty { "no network" }}: ${check.summary})")
            }
            runCatching { engine.setMode(check.mode) }
            if (changed && check.mode != "offline") engine.networkChanged()
        }
        scope.launch {
            store.config.collect { cfg -> launch(applyDispatcher) { apply(cfg) } }
        }
        detector.start()
        scope.launch {
            combine(AppState.status, store.config.map { it.behaviour.notificationDetail }.distinctUntilChanged()) { s, d -> s to d }
                .collect { (s, detail) -> notifications.update(s, detail) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        if (intent?.action == ACTION_STOP) {
            AppState.log("stopped from notification")
            store.update { it.copy(behaviour = it.behaviour.copy(mainSwitch = false)) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (!store.current.shouldRun) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun goForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, StatusNotification.ID, notifications.build(AppState.status.value, true), type)
    }

    /** Applies configuration changes without restarting (F12). */
    private fun apply(cfg: AppConfig) {
        if (!cfg.shouldRun) {
            stopSelf()
            return
        }
        if (cfg.tunnel != appliedTunnel) {
            appliedTunnel = cfg.tunnel
            if (cfg.tunnel.isComplete) {
                runCatching { engine.configure(cfg.tunnel.toEngineJson()) }
                    .onFailure { AppState.log("tunnel settings rejected: ${it.message}") }
            }
        }
        if (cfg.home != appliedHome) {
            if (appliedHome != null) detector.recheck()
            appliedHome = cfg.home
        }
        val wanted = cfg.forwards.filter { it.enabled }.associateBy { it.id }
        for (id in appliedForwards.keys - wanted.keys) {
            runCatching { engine.removeForward(id) }
            appliedForwards.remove(id)
            applyErrors.remove(id)
        }
        for (f in wanted.values) {
            if (appliedForwards[f.id] == f && f.id !in applyErrors) continue
            runCatching { engine.addForward(f.id, f.name, f.localAddr, f.remoteAddr) }
                .onSuccess { appliedForwards[f.id] = f; applyErrors.remove(f.id) }
                .onFailure {
                    appliedForwards.remove(f.id)
                    applyErrors[f.id] = it.message ?: "failed"
                    AppState.log("${f.name}: ${it.message}")
                }
        }
        if (cfg.proxy != appliedProxy || PROXY in applyErrors) {
            appliedProxy = cfg.proxy
            applyErrors.remove(PROXY)
            if (cfg.proxy.enabled) {
                runCatching { engine.setProxy(cfg.proxy.localAddr, cfg.proxy.username, cfg.proxy.password) }
                    .onFailure {
                        applyErrors[PROXY] = it.message ?: "failed"
                        AppState.log("proxy: ${it.message}")
                    }
            } else {
                engine.disableProxy()
            }
        }
        AppState.setApplyErrors(applyErrors.toMap())
    }

    override fun onDestroy() {
        AppState.log("service stopped")
        detector.stop()
        scope.cancel()
        current = null
        recheckHook = null
        engine.stop()
        AppState.setApplyErrors(emptyMap())
        AppState.setServiceRunning(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "org.metalmind.wireplug.STOP"
        private const val PROXY = "proxy"

        /** The running engine, for the UI's Test and Retry actions. */
        @Volatile var current: Engine_? = null
            private set

        @Volatile private var recheckHook: (() -> Unit)? = null

        /** Re-runs the home check, e.g. after a permission is granted. */
        fun recheck() {
            recheckHook?.invoke()
        }

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, WirePlugService::class.java))
            } catch (e: Exception) {
                // Android 12+ refuses foreground starts from the background.
                AppState.log("cannot start service: ${e.message}")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WirePlugService::class.java))
        }
    }
}
