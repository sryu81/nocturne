package com.nocturne.transport

/**
 * Socket-level + Ekos-level connection progress. Two different booleans live
 * on the wire ([EkosRemote-Client-Guide.md]'s `new_connection_state`
 * `connected`/`online`) — `connected` is trivially true once you're receiving
 * pushes at all, `online` is whether Ekos itself has finished starting.
 * [SOCKET_OPEN] vs [ONLINE] keeps that distinction visible to the connect
 * screen instead of collapsing it into a single "connected" flag.
 */
enum class ConnectionState {
    /** No socket, not attempting one (fresh, explicit disconnect, or between backoff attempts). */
    DISCONNECTED,

    /** Dial in progress, or waiting on a backoff timer before the next attempt. */
    CONNECTING,

    /** WebSocket open, `set_client_state`/`get_connection` sent, but Ekos hasn't reported `online` yet. */
    SOCKET_OPEN,

    /** `new_connection_state.online == true` observed — bootstrap fired, real pushes flowing. */
    ONLINE,
}

/** Richer than a bare enum so the connect screen can show *why* an attempt failed. */
data class ConnectionStatus(
    val state: ConnectionState,
    val host: String? = null,
    val lastError: String? = null,
)
