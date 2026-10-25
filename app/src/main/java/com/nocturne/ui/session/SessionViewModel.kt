package com.nocturne.ui.session

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nocturne.data.ConnectionRepository
import com.nocturne.data.FrameRepository
import com.nocturne.data.SequenceRepository
import com.nocturne.data.TargetRepository
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
 * What [NocturneApp] shows at the top level. Once [Connected], later status churn (a dropped
 * socket) stays in [Connected] with an updated [ConnectionStatus]; [Connecting] only precedes
 * the first `ONLINE`. Only an explicit disconnect returns to [NeedsConnect].
 */
sealed interface ConnectionMode {
    data object NeedsConnect : ConnectionMode
    data class Connecting(val status: ConnectionStatus) : ConnectionMode
    data class Connected(val status: ConnectionStatus) : ConnectionMode
}

/** Result of a reboot/shutdown sent from the Connect screen, where there is no controller yet. */
sealed interface RigPowerUi {
    data object Idle : RigPowerUi
    data class Sending(val action: RigPowerAction) : RigPowerUi
    data class Sent(val action: RigPowerAction) : RigPowerUi
    data class Failed(val action: RigPowerAction, val message: String) : RigPowerUi
}

enum class RigPowerAction { REBOOT, SHUTDOWN }

/**
 * Owns the [EkosRemoteController] connection. [ctrl] is set only by [connect], before
 * [connectionMode] leaves [ConnectionMode.NeedsConnect], which is when [NocturneApp] starts
 * reading it.
 */
class SessionViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = ConnectionRepository(app.applicationContext)
    private val sequenceRepo = SequenceRepository(app.applicationContext)
    private val frameRepo = FrameRepository(app.applicationContext)
    private val skyAtlas = com.nocturne.hips.SkyAtlas(app.applicationContext)
    private val targetRepo = TargetRepository(app.applicationContext)
    /** The phone's log; shared by every connection so history outlives reconnects. */
    val logRepo = com.nocturne.data.LogRepository(app.applicationContext)

    lateinit var ctrl: SessionController
        private set

    private var client: EkosRemoteClient? = null
    /** Link probes of the current connection, for Home's health card; null before [connect]. */
    val link: com.nocturne.transport.LinkStats? get() = client?.link
    val host: String? get() = client?.host
    private var reachedOnline = false

    private val _connectionMode = MutableStateFlow<ConnectionMode>(ConnectionMode.NeedsConnect)
    val connectionMode: StateFlow<ConnectionMode> = _connectionMode.asStateFlow()

    // Compose State so ConnectScreen recomposes when init's coroutine loads the saved values.
    var savedHost: String? by mutableStateOf(null)
        private set
    var savedPort: Int by mutableStateOf(9000)
        private set
    var savedRebootPort: Int by mutableStateOf(9001)
        private set
    /** Reboot/shutdown requested from ConnectScreen; works with Ekos down because it only needs the daemon. */
    var rigPower: RigPowerUi by mutableStateOf(RigPowerUi.Idle)
        private set
    /** One-line note for ConnectScreen after a rig reboot sent us back there; cleared on [connect]. */
    var connectNotice: String? by mutableStateOf(null)
        private set

    init {
        viewModelScope.launch {
            val settings = repo.current()
            savedHost = settings.host
            savedPort = settings.port
            savedRebootPort = settings.rebootPort
            // A saved host only pre-fills ConnectScreen; it never auto-dials.
            _connectionMode.value = ConnectionMode.NeedsConnect
        }
    }

    fun connect(host: String, port: Int) {
        client?.disconnect()
        reachedOnline = false
        connectNotice = null
        rigPower = RigPowerUi.Idle
        viewModelScope.launch { repo.save(host, port) }

        val newClient = EkosRemoteClient(host, port, viewModelScope)
        client = newClient
        // Set ctrl before connectionMode flips to Connecting/Connected — NocturneShell
        // only composes once connectionMode says so, and must see the final ctrl then.
        ctrl = EkosRemoteController(
            newClient, viewModelScope, repo, sequenceRepo, frameRepo, targetRepo, skyAtlas,
            allowOptionalInternet = { com.nocturne.transport.RigNetwork.allowOptionalInternet(getApplication(), host) },
            logRepo = logRepo,
        )

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

    /** Explicit user action; the only path back to [ConnectionMode.NeedsConnect]. */
    fun disconnect() {
        client?.disconnect()
        client = null
        reachedOnline = false
        _connectionMode.value = ConnectionMode.NeedsConnect
    }

    /** After an accepted reboot, return to ConnectScreen rather than leave the shell up over
     *  stale state. No auto-dial; the user reconnects once the beacon shows the Pi again. */
    fun disconnectAfterReboot() {
        disconnect()
        connectNotice = "Rig is rebooting — reconnect once it shows up again (about a minute)."
    }

    /** After an accepted shutdown: back to ConnectScreen, saying the Pi won't return by itself. */
    fun disconnectAfterShutdown() {
        disconnect()
        connectNotice = "Rig is shutting down — it stays off until its power is cycled."
    }

    /**
     * Reboot or power off the Pi at [host] through the maintenance daemon (port from the last
     * saved setting), without an Ekos connection: the reason to use it is often that Ekos or
     * KStars is down, which also leaves the Gear tab unreachable.
     */
    fun powerRig(action: RigPowerAction, host: String) {
        if (rigPower is RigPowerUi.Sending) return
        rigPower = RigPowerUi.Sending(action)
        viewModelScope.launch {
            val client = com.nocturne.transport.RigRebootClient(host, savedRebootPort)
            val result = when (action) {
                RigPowerAction.REBOOT -> client.reboot()
                RigPowerAction.SHUTDOWN -> client.shutdown()
            }
            rigPower = result.fold(
                onSuccess = { RigPowerUi.Sent(action) },
                onFailure = { RigPowerUi.Failed(action, it.message ?: "request failed") },
            )
        }
    }

    override fun onCleared() {
        client?.disconnect()
    }
}
