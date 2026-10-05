package org.metalmind.wireplug

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.metalmind.wireplug.data.ConfigStore
import org.metalmind.wireplug.service.WirePlugService

class WirePlugApp : Application() {
    lateinit var config: ConfigStore
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        config = ConfigStore(this)
        // Start or stop the service when the switch or the enabled set
        // changes (F16). The initial value is skipped so a process start for
        // a boot broadcast does not start the service unless F18 allows it.
        scope.launch {
            config.config.map { it.shouldRun }.distinctUntilChanged().drop(1).collect { run ->
                if (run) WirePlugService.start(this@WirePlugApp) else WirePlugService.stop(this@WirePlugApp)
            }
        }
    }

    /** Called when the UI is shown, so a stopped service is restarted. */
    fun syncService() {
        if (config.current.shouldRun && WirePlugService.current == null) WirePlugService.start(this)
    }
}
