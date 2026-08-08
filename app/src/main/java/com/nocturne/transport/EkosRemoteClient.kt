package com.nocturne.transport

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.EkosEventCodec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.time.Duration
import kotlin.math.min

/**
 * Owns the Message channel + reconnect state machine. Client owns 100% of
 * reconnect/backoff — the server "just accepts whatever connects to the two
 * paths" (EkosRemote-Client-Guide.md §"Practical notes"). Media channel is
 * not opened here in M2 (see [MediaChannel]).
 */
class EkosRemoteClient(
    val host: String,
    port: Int = 9000,
    private val scope: CoroutineScope,
) {
    private val okHttpClient = OkHttpClient.Builder()
        // The server doesn't speak WS ping/pong — don't add traffic it won't
        // answer. Dead-socket detection relies solely on OkHttp's own
        // onFailure/onClosed callbacks; no app-level staleness timer in M2
        // (accepted gap, see README §8).
        .pingInterval(Duration.ZERO)
        .build()

    private val messageChannel = MessageChannel(okHttpClient, "ws://$host:$port/message/ekos")

    private val _connectionStatus = MutableStateFlow(ConnectionStatus(ConnectionState.DISCONNECTED, host))
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _events = MutableSharedFlow<EkosEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EkosEvent> = _events.asSharedFlow()

    private var reconnectJob: Job? = null
    private var backoffAttempt = 0

    init {
        // Persistent collectors for the lifetime of this client — reconnects
        // just reopen the same MessageChannel, no need to relaunch these.
        scope.launch {
            messageChannel.socketEvents.collect { event ->
                when (event) {
                    is MessageChannel.SocketEvent.Open -> onSocketOpen()
                    is MessageChannel.SocketEvent.Closed -> onSocketClosedOrFailed(event.reason)
                    is MessageChannel.SocketEvent.Failure -> onSocketClosedOrFailed(event.throwable.message)
                }
            }
        }
        scope.launch {
            messageChannel.inbound.collect { text -> onEvent(EkosEventCodec.decode(text)) }
        }
    }

    /** Fresh, user-initiated connect attempt — resets backoff. */
    fun connect() {
        reconnectJob?.cancel()
        backoffAttempt = 0
        _connectionStatus.update { it.copy(state = ConnectionState.CONNECTING, lastError = null) }
        messageChannel.open()
    }

    fun disconnect() {
        reconnectJob?.cancel()
        sendCommand(Commands.SET_CLIENT_STATE, buildJsonObject { put("state", false) })
        messageChannel.close()
        _connectionStatus.update { it.copy(state = ConnectionState.DISCONNECTED) }
    }

    fun sendCommand(type: String, payload: JsonElement = JsonObject(emptyMap())) {
        messageChannel.send(EkosEventCodec.encode(type, payload))
    }

    private fun onSocketOpen() {
        backoffAttempt = 0
        _connectionStatus.update { it.copy(state = ConnectionState.SOCKET_OPEN, lastError = null) }
        sendCommand(Commands.SET_CLIENT_STATE, buildJsonObject { put("state", true) })
        sendCommand(Commands.GET_CONNECTION)
        // Scopes catalog — separate from Optical Trains (message.cpp:204), answered even while
        // Ekos itself is stopped (confirmed live) — sent eagerly here, not gated behind `online`
        // like GET_DEVICES/TRAIN_GET_ALL below, so the Scopes card shows real data before the
        // user ever taps Start Ekos.
        sendCommand(Commands.GET_SCOPES)
        // Rig profiles — same pre-online availability as GET_SCOPES above (this is exactly what
        // the Gear tab's profile picker needs before Start Ekos is even tappable). Previously
        // this was only sent inside the `online` branch below, so a fresh connect to a Pi with
        // Ekos not yet running left SimState.profiles at its SimState() default (DEFAULT_PROFILES
        // fixture — "Field · 550 mm" etc., never overwritten) with no way to trigger a real fetch
        // short of starting Ekos first. Documented bootstrap order (README §4.1) always had this
        // eager — the actual sendCommand call had just drifted into the online-only block.
        sendCommand(Commands.GET_PROFILES)
    }

    private fun onEvent(event: EkosEvent) {
        if (event is EkosEvent.NewConnectionState) {
            _connectionStatus.update {
                it.copy(state = if (event.online) ConnectionState.ONLINE else ConnectionState.SOCKET_OPEN)
            }
            if (event.online) {
                sendCommand(Commands.GET_STATES)
                sendCommand(Commands.GET_DEVICES)
                // OpticalTrainManager is a real Ekos module — only meaningful once Ekos has
                // actually started, unlike profiles/scopes which the server happily reports
                // pre-online too (both now requested eagerly in onSocketOpen). Gated the same
                // way GET_DEVICES is.
                sendCommand(Commands.TRAIN_GET_ALL)
                // Per-active-profile module→train assignment (ProfileSettings) — needs the
                // trains above to resolve IDs to names, but no ordering dependency in sending;
                // the reply is applied once both have arrived (see EkosRemoteController).
                sendCommand(Commands.TRAIN_GET_PROFILES)
                // Mount module settings (M3.3, curated subset) — same online-only gating as
                // TRAIN_GET_ALL above (the Mount module instance is null pre-start server-side).
                // Sent eagerly (not lazily on sheet-open) so the Gear-tab card can show a real
                // summary before the user ever opens the settings sheet.
                sendCommand(Commands.MOUNT_GET_ALL_SETTINGS)
            }
        }
        _events.tryEmit(event)
    }

    private fun onSocketClosedOrFailed(reason: String?) {
        _connectionStatus.update { it.copy(state = ConnectionState.CONNECTING, lastError = reason) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        // 1s, 2s, 4s, 8s, 16s, capped at 30s — doesn't reset backoffAttempt
        // itself; only a fresh connect() or a successful open does that.
        val delayMs = min(30_000L, 1_000L * (1L shl backoffAttempt.coerceAtMost(5)))
        backoffAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            messageChannel.open()
        }
    }
}
