package com.nocturne.ui.session

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nocturne.data.ConnectionRepository
import com.nocturne.session.EkosRemoteController
import com.nocturne.session.SessionController
import com.nocturne.session.SimulatedController
import com.nocturne.transport.ConnectionState
import com.nocturne.transport.ConnectionStatus
import com.nocturne.transport.EkosRemoteClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What [NocturneApp] should show at the top level. Once [Connected] is
 * reached for a client, later status churn (a dropped socket mid-session)
 * stays in [Connected] with an updated [ConnectionStatus] — [connect] only
 * ever reports [Connecting] *before* the first `ONLINE`. Falling back to
 * [NeedsConnect] happens solely via an explicit user disconnect, never as a
 * side effect of a reconnect attempt — see plan §7's mid-session-loss note.
 */
sealed interface ConnectionMode {
    data object NeedsConnect : ConnectionMode
    data class Connecting(val status: ConnectionStatus) : ConnectionMode
    data object Simulated : ConnectionMode
    data class Connected(val status: ConnectionStatus) : ConnectionMode
}

/** Owns the connection-mode switch: [SimulatedController] vs a real [EkosRemoteController]. */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConnectionRepository(app.applicationContext)

    var ctrl: SessionController = SimulatedController(viewModelScope)
        private set

    private var client: EkosRemoteClient? = null
    private var reachedOnline = false

    private val _connectionMode = MutableStateFlow<ConnectionMode>(ConnectionMode.NeedsConnect)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    var savedHost: String? = null
        private set
    var savedPort: Int = 9000
        private set

    init {
        viewModelScope.launch {
            val settings = repo.current()
            savedHost = settings.host
            savedPort = settings.port
            // A saved host only pre-fills ConnectScreen's fields (via savedHost/savedPort
            // above) — it must never auto-dial on its own. Only useSimulator is an explicit
            // past opt-in and is the sole case that skips the screen automatically; a saved
            // host is not a confirmation to connect, it's just what was typed last time.
            if (settings.useSimulator) useSimulator() else _connectionMode.value = ConnectionMode.NeedsConnect
        }
    }

    fun connect(host: String, port: Int) {
        client?.disconnect()
        reachedOnline = false
        viewModelScope.launch { repo.save(host, port) }

        val newClient = EkosRemoteClient(host, port, viewModelScope)
        client = newClient
        // Set ctrl before connectionMode flips to Connecting/Connected — NocturneShell
        // only composes once connectionMode says so, and must see the final ctrl then.
        ctrl = EkosRemoteController(newClient, viewModelScope)

        viewModelScope.launch {
            newClient.connectionStatus.collect { status ->
                if (status.state == ConnectionState.ONLINE) {
                    if (!reachedOnline) viewModelScope.launch { repo.markConnectedNow() }
                    reachedOnline = true
                }
                _connectionMode.value = if (reachedOnline) {
                    ConnectionMode.Connected(status)
                } else {
                    ConnectionMode.Connecting(status)
                }
            }
        }
    }

    fun useSimulator() {
        client?.disconnect()
        client = null
        reachedOnline = false
        ctrl = SimulatedController(viewModelScope)
        viewModelScope.launch { repo.setUseSimulator(true) }
        _connectionMode.value = ConnectionMode.Simulated
    }

    /** Explicit user action only — the sole path back to [ConnectionMode.NeedsConnect]
     *  once a session has ever reached [ConnectionMode.Connected]. */
    fun disconnect() {
        client?.disconnect()
        client = null
        reachedOnline = false
        _connectionMode.value = ConnectionMode.NeedsConnect
    }

    override fun onCleared() {
        client?.disconnect()
    }
}
