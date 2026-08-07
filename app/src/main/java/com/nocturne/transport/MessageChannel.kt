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
 * Thin wrapper over one OkHttp WebSocket (`/message/ekos` — JSON commands and
 * pushes). Translates OkHttp's callback-based `WebSocketListener` into flows;
 * OkHttp invokes these callbacks on its own dispatcher thread, not a
 * suspend context, so emission uses non-suspending `tryEmit` throughout.
 *
 * **One client at a time, confirmed from the real server source**
 * (`kstars/ekos/ekosremote/node.cpp`, `Node::adoptSocket`): the real
 * EkosRemote server holds exactly one `QWebSocket*` per path — a *new*
 * connection to `/message/ekos` disconnects and deletes whatever was
 * previously connected there, silently. This isn't a Nocturne bug to work
 * around; it's a hard constraint of the real protocol. Concretely: a second
 * Nocturne instance, a raw diagnostic script, or anything else opening its
 * own `/message/ekos` connection while this one is live will evict it
 * (triggering this client's own reconnect/backoff) — confirmed live while
 * debugging what looked like a flaky Mount-settings write (M3.3): every
 * "independent verification" probe opened during that investigation was
 * itself the thing kicking the real connection off the wire and returning
 * stale reads, not a write actually failing. Keep this in mind before ever
 * trusting a second concurrent connection's read as ground truth.
 */
class MessageChannel(private val okHttpClient: OkHttpClient, private val url: String) {

    sealed interface SocketEvent {
        data object Open : SocketEvent
        data class Closed(val code: Int, val reason: String) : SocketEvent
        data class Failure(val throwable: Throwable) : SocketEvent
    }

    // A dropped intermediate status push is fine — the next one supersedes it.
    // Blocking OkHttp's callback thread on backpressure (the SUSPEND default) is not.
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
