package org.metalmind.wireplug.service

import android.net.Network
import android.os.ParcelFileDescriptor
import org.metalmind.wireplug.data.AppState
import org.metalmind.wireplug.data.EngineStatus
import org.metalmind.wireplug.go.engine.Host

/**
 * The Go engine's view of Android (L6). Sockets are bound to a chosen
 * Network so direct traffic goes to the home network and tunnel traffic to
 * the current default network, whatever the default route is (P2).
 */
class EngineHost(
    private val homeNetwork: () -> Network?,
    private val defaultNetwork: () -> Network?,
    private val onStatus: (EngineStatus) -> Unit,
) : Host {

    private fun bind(network: Network?, fd: Long, what: String) {
        val n = network ?: throw IllegalStateException("no $what network")
        // fromFd duplicates the descriptor; the dup refers to the same
        // socket, so binding it binds Go's socket. Close only the dup.
        ParcelFileDescriptor.fromFd(fd.toInt()).use { n.bindSocket(it.fileDescriptor) }
    }

    override fun bindDirectSocket(fd: Long) = bind(homeNetwork(), fd, "home")

    override fun bindTunnelSocket(fd: Long) {
        // Leaving the socket unbound falls back to the default route (L8).
        defaultNetwork()?.let { bind(it, fd, "default") }
    }

    override fun resolve(hostname: String): String {
        val n = homeNetwork() ?: defaultNetwork() ?: throw IllegalStateException("no network")
        return n.getAllByName(hostname).joinToString(",") { it.hostAddress ?: "" }
    }

    override fun log(line: String) = AppState.log(line)

    override fun onStatusChanged(status: String) {
        runCatching { EngineStatus.parse(status) }.onSuccess(onStatus)
    }
}
