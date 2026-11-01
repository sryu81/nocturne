package com.nocturne.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What a piece split out of [EkosRemoteController] needs from it: a way to send a command, whether the
 * socket is open, the coroutine scope, and read and write access to the shared [AppState]. Pieces hold no
 * controller or client reference, so they stay small, cannot reach the controller's other members, and can
 * be built in a test with a recording `send`.
 */
internal class ControllerContext(
    private val sender: (type: String, payload: JsonElement) -> Unit,
    private val link: () -> Boolean,
    val scope: CoroutineScope,
    val state: () -> AppState,
    val update: ((AppState) -> AppState) -> Unit,
) {
    fun send(type: String, payload: JsonElement = JsonObject(emptyMap())) = sender(type, payload)

    /** The socket is open, with or without Ekos running. */
    fun linkUp(): Boolean = link()
}
