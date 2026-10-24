package com.nocturne.transport

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Wraps one OkHttp WebSocket on `/message/ekos` (JSON commands and pushes) as flows. OkHttp calls
 * the listener on its own thread, so emission uses non-suspending `tryEmit`.
 *
 * The server keeps one socket per path (`ekosremote/node.cpp`, `Node::adoptSocket`): any new
 * `/message/ekos` connection, such as a diagnostic script, silently evicts this one and triggers
 * a reconnect. Don't trust reads from a second concurrent connection.
 */
class MessageChannel(private val okHttpClient: OkHttpClient, private val url: String) {

    sealed interface SocketEvent {
        data object Open : SocketEvent
        data class Closed(val code: Int, val reason: String) : SocketEvent
        data class Failure(val throwable: Throwable) : SocketEvent
    }

    // Dropping an intermediate push is fine (the next supersedes it); blocking OkHttp's callback
    // thread on backpressure is not.
    private val _inbound = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val inbound: SharedFlow<String> = _inbound.asSharedFlow()

    private val _socketEvents = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val socketEvents: SharedFlow<SocketEvent> = _socketEvents.asSharedFlow()

    private var webSocket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _socketEvents.tryEmit(SocketEvent.Open)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _inbound.tryEmit(text)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            _socketEvents.tryEmit(SocketEvent.Closed(code, reason))
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            _socketEvents.tryEmit(SocketEvent.Failure(t))
        }
    }

    fun open() {
        webSocket = okHttpClient.newWebSocket(Request.Builder().url(url).build(), listener)
    }

    fun send(text: String): Boolean = webSocket?.send(text) ?: false

    fun close() {
        webSocket?.close(1000, "client disconnect")
        webSocket = null
    }
}
