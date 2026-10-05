package org.metalmind.wireplug.data

/** The overall states of the status screen table. */
enum class Overall(val label: String) {
    OFF("Off"),
    HOME("Home, direct"),
    AWAY_IDLE("Away, tunnel idle"),
    AWAY_CONNECTING("Away, connecting"),
    AWAY_UP("Away, tunnel up"),
    OFFLINE("Offline"),
    ERROR("Error"),
}

fun overallState(running: Boolean, s: EngineStatus): Overall = when {
    !running -> Overall.OFF
    s.mode == "home" -> Overall.HOME
    s.mode == "offline" -> Overall.OFFLINE
    s.tunnel.state == "up" -> Overall.AWAY_UP
    s.tunnel.state == "connecting" -> Overall.AWAY_CONNECTING
    s.tunnel.state == "error" -> Overall.ERROR
    else -> Overall.AWAY_IDLE
}

fun formatBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f KB", n / 1024.0)
    n < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", n / (1024.0 * 1024))
    else -> String.format(java.util.Locale.US, "%.2f GB", n / (1024.0 * 1024 * 1024))
}

fun formatAge(seconds: Long): String = when {
    seconds < 0 -> "just now"
    seconds < 60 -> "$seconds s ago"
    seconds < 3600 -> "${seconds / 60} min ago"
    else -> "${seconds / 3600} h ago"
}
