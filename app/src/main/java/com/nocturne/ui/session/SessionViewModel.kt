package com.nocturne.ui.session

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nocturne.data.ConnectionRepository
import com.nocturne.data.SequenceRepository
import com.nocturne.session.EkosRemoteController
import com.nocturne.session.SessionController
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
    data class Connected(val status: ConnectionStatus) : ConnectionMode
}

/**
 * Owns the real [EkosRemoteController] connection. Real-rig-only (the simulator was removed
 * 2026-08-22 — see docs/simulator-removal-plan.md); [ctrl] is only ever set by [connect], never
 * before — [NocturneApp] only composes anything that reads it once [connectionMode] has left
 * [ConnectionMode.NeedsConnect], which [connect] guarantees happens after [ctrl] is assigned.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConnectionRepository(app.applicationContext)
    private val sequenceRepo = SequenceRepository(app.applicationContext)

    lateinit var ctrl: SessionController
        private set

    private var client: EkosRemoteClient? = null
    private var reachedOnline = false

    private val _connectionMode = MutableStateFlow<ConnectionMode>(ConnectionMode.NeedsConnect)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    // Compose State, not a plain var — ConnectScreen composes off ConnectionMode alone (which
    // is already NeedsConnect at construction, before repo.current() resolves), so a plain var
    // set later inside init's coroutine would never trigger a recomposition to pick it up.
    var savedHost: String? by mutableStateOf(null)
        private set
    var savedPort: Int by mutableStateOf(9000)
        private set

    init {
        viewModelScope.launch {
            val settings = repo.current()
            savedHost = settings.host
            savedPort = settings.port
            // A saved host only pre-fills ConnectScreen's fields (via savedHost/savedPort
            // above) — it must never auto-dial on its own, a saved host is not a confirmation
            // to connect, it's just what was typed last time.
            _connectionMode.value = ConnectionMode.NeedsConnect
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
        ctrl = EkosRemoteController(newClient, viewModelScope, repo, sequenceRepo)

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
