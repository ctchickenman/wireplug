package org.metalmind.wireplug.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.metalmind.wireplug.WirePlugApp
import org.metalmind.wireplug.data.AppState

/** Starts the service at boot when the option and the main switch are on (F18). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val cfg = (context.applicationContext as WirePlugApp).config.current
        if (cfg.behaviour.startAtBoot && cfg.shouldRun) {
            AppState.log("starting at boot")
            WirePlugService.start(context)
        }
    }
}
