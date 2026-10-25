package com.nocturne.monitor

import com.nocturne.session.AppState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.transport.LinkStats
import com.nocturne.ui.home.RigHealthSnapshot
import kotlinx.coroutines.flow.StateFlow

/**
 * What [RigMonitorService] watches: the live connection's state, link figures and health polls.
 * The connection itself still lives in the view model; the service only keeps the process alive
 * and speaks up, so it ends with the connection.
 */
class MonitorSource(
    val host: String,
    val state: StateFlow<AppState>,
    val status: StateFlow<ConnectionStatus?>,
    val link: LinkStats?,
    val health: StateFlow<RigHealthSnapshot>?,
)

/** Process-wide hand-over from the view model to the service (a service can't take constructor arguments). */
object MonitorBridge {
    @Volatile var source: MonitorSource? = null
}
