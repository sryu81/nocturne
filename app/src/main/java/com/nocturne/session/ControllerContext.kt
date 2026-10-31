package com.nocturne.session

import com.nocturne.transport.ConnectionState
import com.nocturne.transport.EkosRemoteClient
import kotlinx.coroutines.CoroutineScope

/**
 * What a piece split out of [EkosRemoteController] needs from it: the connection, the coroutine scope,
 * and read and write access to the shared [AppState]. Pieces hold no controller reference, so they
 * stay small and cannot reach its other members.
 */
internal class ControllerContext(
    val client: EkosRemoteClient,
    val scope: CoroutineScope,
    val state: () -> AppState,
    val update: ((AppState) -> AppState) -> Unit,
) {
    /** The socket is open, with or without Ekos running. */
    fun linkUp(): Boolean = client.connectionStatus.value.state.let {
        it == ConnectionState.SOCKET_OPEN || it == ConnectionState.ONLINE
    }
}
