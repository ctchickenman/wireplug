package org.metalmind.wireplug.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import org.metalmind.wireplug.R
import org.metalmind.wireplug.data.EngineStatus
import org.metalmind.wireplug.data.Overall
import org.metalmind.wireplug.data.overallState
import org.metalmind.wireplug.ui.MainActivity

/** The ongoing notification: mode, tunnel state and a Stop action. */
class StatusNotification(private val context: Context) {
    private val nm = context.getSystemService(NotificationManager::class.java)
    private var lastKey: String? = null

    init {
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.channel_status), NotificationManager.IMPORTANCE_LOW)
        ch.description = context.getString(R.string.channel_status_desc)
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    fun build(s: EngineStatus, detail: Boolean): Notification {
        val overall = overallState(true, s)
        val title = when (overall) {
            Overall.HOME -> "Home · direct"
            Overall.AWAY_UP -> "Away · tunnel up"
            Overall.AWAY_CONNECTING -> "Away · connecting"
            Overall.AWAY_IDLE -> "Away · tunnel idle"
            Overall.OFFLINE -> "Offline"
            Overall.ERROR -> "Away · tunnel error"
            Overall.OFF -> "Off"
        }
        val open = s.openConnections
        val conns = "$open connection${if (open == 1) "" else "s"} open"
        val text = when (overall) {
            Overall.AWAY_UP -> "Tunnel up · $conns"
            Overall.ERROR -> s.tunnel.error.ifEmpty { "Tunnel error" }
            Overall.AWAY_CONNECTING -> "Handshake in progress · $conns"
            else -> "Tunnel down · $conns"
        }
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            context, 1, Intent(context, WirePlugService::class.java).setAction(WirePlugService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val b = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, context.getString(R.string.stop), stopIntent)
        if (detail) {
            b.setContentText(text)
            // A chronometer shows the handshake age without waking the app.
            if (overall == Overall.AWAY_UP && s.tunnel.lastHandshake > 0) {
                b.setWhen(s.tunnel.lastHandshake * 1000).setShowWhen(true).setUsesChronometer(true)
                b.setSubText("handshake")
            } else {
                b.setShowWhen(false)
            }
        } else {
            b.setShowWhen(false)
        }
        return b.build()
    }

    /** Re-posts the notification when what it shows has changed. */
    fun update(s: EngineStatus, detail: Boolean) {
        val key = listOf(overallState(true, s), s.openConnections, s.tunnel.lastHandshake, s.tunnel.error, detail).toString()
        if (key == lastKey) return
        lastKey = key
        runCatching { nm.notify(ID, build(s, detail)) }
    }

    companion object {
        const val ID = 1
        const val CHANNEL = "status"
    }
}
