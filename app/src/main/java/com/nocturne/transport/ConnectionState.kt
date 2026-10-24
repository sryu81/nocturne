package com.nocturne.transport

/**
 * Socket-level and Ekos-level connection progress. `new_connection_state` carries
 * `connected` (true whenever pushes arrive at all) and `online` (Ekos has finished starting);
 * [SOCKET_OPEN] vs [ONLINE] keeps that distinction.
 */
enum class ConnectionState {
    /** No socket, not attempting one (fresh, explicit disconnect, or between backoff attempts). */
    DISCONNECTED,

    /** Dial in progress, or waiting on a backoff timer before the next attempt. */
    CONNECTING,

    /** WebSocket open, `set_client_state`/`get_connection` sent, but Ekos hasn't reported `online` yet. */
    SOCKET_OPEN,

    /** `new_connection_state.online == true` observed; module data has been requested. */
    ONLINE,
}

/** Richer than a bare enum so the connect screen can show *why* an attempt failed. */
data class ConnectionStatus(
    val state: ConnectionState,
    val host: String? = null,
    val lastError: String? = null,
)
