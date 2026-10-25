package com.nocturne.transport

import com.nocturne.protocol.Commands
import com.nocturne.protocol.EkosEvent
import com.nocturne.protocol.EkosEventCodec
import com.nocturne.protocol.MediaFrame
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient
import java.time.Duration
import kotlin.math.min

private const val SETTINGS_RETRY_MS = 2_000L
private const val PROBE_INTERVAL_MS = 10_000L

/**
 * Owns the Message and Media channels and their shared reconnect logic. The server only accepts
 * connections, so reconnect/backoff is entirely client-side. Media reconnects in lockstep with
 * Message; a Media-only drop while Message stays healthy is not detected.
 */
class EkosRemoteClient(
    val host: String,
    port: Int = 9000,
    private val scope: CoroutineScope,
) {
    private val okHttpClient = OkHttpClient.Builder()
        // The server doesn't answer WS pings. Dead sockets are detected only through OkHttp's
        // onFailure/onClosed; there is no app-level staleness timer.
        .pingInterval(Duration.ZERO)
        .build()

    private val messageChannel = MessageChannel(okHttpClient, "ws://$host:$port/message/ekos")
    private val mediaChannel = MediaChannel(okHttpClient, "ws://$host:$port/media/ekos")

    /** Read by the Home health card; see [LinkStats]. */
    val link = LinkStats()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus(ConnectionState.DISCONNECTED, host))
    val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _events = MutableSharedFlow<EkosEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EkosEvent> = _events.asSharedFlow()

    /** Latest-frame-wins media stream — [MediaChannel] already drops stale intermediates. */
    val mediaFrames: SharedFlow<MediaFrame> = mediaChannel.frames

    private var reconnectJob: Job? = null
    private var backoffAttempt = 0
    // Set by disconnect(), cleared by connect(). onClosed/onFailure look the same for a requested
    // close and a dropped connection, so without this disconnect()'s own close() would schedule
    // a reconnect.
    private var userDisconnected = false
    // Send time of the `get_connection` probe still unanswered, 0 when none.
    private var probeSentAtMs = 0L

    /**
     * Module `*_get_all_settings` requests still awaiting a reply this online session, command →
     * last send time (ms). The fetch on `online` races module creation: the server drops requests
     * for a module that isn't up yet ("Ignoring command ... as guide module is not available")
     * and never answers. [retryPendingSettings] re-requests on that module's state pushes.
     */
    private val pendingSettings = mutableMapOf<String, Long>()

    init {
        // Collectors live as long as the client; reconnects reopen the same channels.
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
            messageChannel.inbound.collect { text ->
                link.lastMessageAtMs = System.currentTimeMillis()
                onEvent(EkosEventCodec.decode(text))
            }
        }
        scope.launch {
            mediaChannel.frames.collect { link.lastMediaAtMs = System.currentTimeMillis() }
        }
        scope.launch {
            // `get_profiles` is answered even while Ekos is stopped and is not a `new_connection_state`,
            // whose every `online: true` re-fetches the rig's state. Its round trip is the link's latency.
            // Not sent while the last probe is unanswered: a busy KStars (Ekos starting) would queue them.
            while (true) {
                delay(PROBE_INTERVAL_MS)
                val state = _connectionStatus.value.state
                if (userDisconnected || probeSentAtMs != 0L) continue
                if (state != ConnectionState.ONLINE && state != ConnectionState.SOCKET_OPEN) continue
                probeSentAtMs = System.currentTimeMillis()
                sendCommand(Commands.GET_PROFILES)
            }
        }
        scope.launch {
            mediaChannel.socketEvents.collect { event ->
                link.mediaOpen = event is MediaChannel.SocketEvent.Open
                // set_blobs does not persist across a reconnect; re-send it on every open.
                if (event is MediaChannel.SocketEvent.Open) {
                    mediaChannel.send(EkosEventCodec.encode(Commands.SET_BLOBS, JsonPrimitive(true)))
                }
            }
        }
    }

    /** Fresh, user-initiated connect attempt — resets backoff. */
    fun connect() {
        reconnectJob?.cancel()
        backoffAttempt = 0
        userDisconnected = false
        _connectionStatus.update { it.copy(state = ConnectionState.CONNECTING, lastError = null) }
        messageChannel.open()
        mediaChannel.open()
    }

    fun disconnect() {
        userDisconnected = true
        reconnectJob?.cancel()
        sendCommand(Commands.SET_CLIENT_STATE, buildJsonObject { put("state", false) })
        messageChannel.close()
        mediaChannel.close()
        _connectionStatus.update { it.copy(state = ConnectionState.DISCONNECTED) }
    }

    fun sendCommand(type: String, payload: JsonElement = JsonObject(emptyMap())) {
        messageChannel.send(EkosEventCodec.encode(type, payload))
    }

    private fun onSocketOpen() {
        // An open already in flight when disconnect() ran can still land here. Close it rather
        // than revive the session.
        if (userDisconnected) {
            messageChannel.close()
            mediaChannel.close()
            return
        }
        backoffAttempt = 0
        probeSentAtMs = 0L // a probe sent on the dead socket will never be answered
        _connectionStatus.update { it.copy(state = ConnectionState.SOCKET_OPEN, lastError = null) }
        sendCommand(Commands.SET_CLIENT_STATE, buildJsonObject { put("state", true) })
        sendCommand(Commands.GET_CONNECTION)
        // The fork's buffered log; answered even with Ekos stopped, so it lands before any module log push.
        sendCommand(Commands.GET_LOGS)
        // Scopes, profiles and the driver catalog are answered while Ekos is stopped, so fetch
        // them on socket open: the profile picker and wizard need them before Start Ekos.
        sendCommand(Commands.GET_SCOPES)
        sendCommand(Commands.GET_PROFILES)
        sendCommand(Commands.GET_DRIVERS)
    }

    private fun onEvent(event: EkosEvent) {
        if (event is EkosEvent.NewConnectionState) {
            _connectionStatus.update {
                it.copy(state = if (event.online) ConnectionState.ONLINE else ConnectionState.SOCKET_OPEN)
            }
            // Every `online: true` push re-fetches, on purpose: devices come up one by one while
            // Ekos starts (the filter wheel after the focuser, say) and the first fetch can't see them.
            if (event.online) {
                val now = System.currentTimeMillis()
                pendingSettings.clear()
                listOf(
                    Commands.MOUNT_GET_ALL_SETTINGS, Commands.CAPTURE_GET_ALL_SETTINGS,
                    Commands.ALIGN_GET_ALL_SETTINGS, Commands.GUIDE_GET_ALL_SETTINGS,
                    Commands.FOCUS_GET_ALL_SETTINGS,
                ).forEach { pendingSettings[it] = now }
                sendCommand(Commands.GET_STATES)
                sendCommand(Commands.GET_DEVICES)
                // Module-backed data only exists once Ekos has started. Fetched eagerly so cards
                // show a summary before their sheets are opened.
                sendCommand(Commands.TRAIN_GET_ALL)
                // Module→train assignment; resolved against the trains once both replies arrive.
                sendCommand(Commands.TRAIN_GET_PROFILES)
                sendCommand(Commands.MOUNT_GET_ALL_SETTINGS)
                sendCommand(Commands.CAPTURE_GET_ALL_SETTINGS)
                sendCommand(Commands.ALIGN_GET_ALL_SETTINGS)
                sendCommand(Commands.GUIDE_GET_ALL_SETTINGS)
                sendCommand(Commands.FOCUS_GET_ALL_SETTINGS)
                // Dusk/dawn and site timezone; stable for the night, so fetched once.
                sendCommand(Commands.ASTRO_GET_ALMANAC)
                sendCommand(Commands.ASTRO_GET_LOCATION)
                // Read-only mirror of the Scheduler queue; nothing here changes the Scheduler.
                sendCommand(Commands.SCHEDULER_GET_JOBS)
                sendCommand(Commands.SCHEDULER_GET_ALL_SETTINGS)
                // option_get takes an array: the notification gate/sound prefs, and
                // astrometryUseRotator, the source of truth for rotator auto-control.
                sendCommand(Commands.OPTION_GET, buildJsonObject {
                    putJsonArray("options") {
                        addJsonObject { put("name", "ekosRemoteNotifications") }
                        addJsonObject { put("name", "ekosRemoteSound") }
                        addJsonObject { put("name", "astrometryUseRotator") }
                    }
                })
            }
        }
        if (event is EkosEvent.Profiles && probeSentAtMs != 0L) {
            val now = System.currentTimeMillis()
            link.rttMs = now - probeSentAtMs
            link.rttAtMs = now
            probeSentAtMs = 0L
        }
        retryPendingSettings(event)
        _events.tryEmit(event)
    }

    private fun retryPendingSettings(event: EkosEvent) {
        when (event) {
            is EkosEvent.MountSettings -> pendingSettings.remove(Commands.MOUNT_GET_ALL_SETTINGS)
            is EkosEvent.CaptureSettings -> pendingSettings.remove(Commands.CAPTURE_GET_ALL_SETTINGS)
            is EkosEvent.AlignSettings -> pendingSettings.remove(Commands.ALIGN_GET_ALL_SETTINGS)
            is EkosEvent.GuideSettings -> pendingSettings.remove(Commands.GUIDE_GET_ALL_SETTINGS)
            is EkosEvent.FocusSettings -> pendingSettings.remove(Commands.FOCUS_GET_ALL_SETTINGS)
            else -> {
                val command = when (event) {
                    is EkosEvent.NewMountState -> Commands.MOUNT_GET_ALL_SETTINGS
                    is EkosEvent.NewCaptureState -> Commands.CAPTURE_GET_ALL_SETTINGS
                    is EkosEvent.NewAlignState -> Commands.ALIGN_GET_ALL_SETTINGS
                    is EkosEvent.NewGuideState -> Commands.GUIDE_GET_ALL_SETTINGS
                    is EkosEvent.NewFocusState -> Commands.FOCUS_GET_ALL_SETTINGS
                    else -> return
                }
                val sentAt = pendingSettings[command] ?: return
                val now = System.currentTimeMillis()
                // Mount state pushes are frequent — at most one retry per 2 s per module.
                if (now - sentAt < SETTINGS_RETRY_MS) return
                pendingSettings[command] = now
                sendCommand(command)
            }
        }
    }

    private fun onSocketClosedOrFailed(reason: String?) {
        if (userDisconnected) return
        _connectionStatus.update { it.copy(state = ConnectionState.CONNECTING, lastError = reason) }
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        reconnectJob?.cancel()
        // 1s, 2s, 4s, 8s, 16s, capped at 30s. Only connect() or a successful open resets it.
        val delayMs = min(30_000L, 1_000L * (1L shl backoffAttempt.coerceAtMost(5)))
        backoffAttempt++
        reconnectJob = scope.launch {
            delay(delayMs)
            // cancel() can lose the race with delay() resuming, so recheck the flag.
            if (userDisconnected) return@launch
            messageChannel.open()
            mediaChannel.open()
        }
    }
}
