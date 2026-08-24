package com.nocturne.transport

import com.nocturne.protocol.MediaFrame
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * Binary frame channel (`/media/ekos`) — mirrors [MessageChannel]'s shape
 * (same reconnect/backoff ownership lives in [EkosRemoteClient], not here)
 * but the payload is binary: a 512-byte JSON header (see [MediaFrame]) then
 * raw JPEG bytes, no length prefix. This channel also carries a handful of
 * JSON *text* commands (`set_blobs`, `astro_get_objects_image`,
 * `astro_get_skypoint_image` — EkosRemote-Client-Guide.md), hence [send]/
 * [inboundText] alongside the binary [frames] flow.
 *
 * ⚠️ Any binary frame *sent* on this socket is treated by the real server as
 * an align-solve image upload (`Media::onBinaryReceived` →
 * `Align::loadAndSlew`). This client never sends binary here — [send] is
 * text-only, deliberately, so nothing here can trigger that by accident.
 */
class MediaChannel(private val okHttpClient: OkHttpClient, private val url: String) {

    sealed interface SocketEvent {
        data object Open : SocketEvent
        data class Closed(val code: Int, val reason: String) : SocketEvent
        data class Failure(val throwable: Throwable) : SocketEvent
    }

    // A dropped intermediate frame is fine — the next push supersedes it, same rationale as
    // MessageChannel's inbound flow.
    private val _frames = MutableSharedFlow<MediaFrame>(extraBufferCapacity = 4, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val frames: SharedFlow<MediaFrame> = _frames.asSharedFlow()

    private val _inboundText = MutableSharedFlow<String>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val inboundText: SharedFlow<String> = _inboundText.asSharedFlow()

    private val _socketEvents = MutableSharedFlow<SocketEvent>(extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val socketEvents: SharedFlow<SocketEvent> = _socketEvents.asSharedFlow()

    private var webSocket: WebSocket? = null

    private val listener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            _socketEvents.tryEmit(SocketEvent.Open)
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            MediaFrame.parse(bytes.toByteArray())?.let { _frames.tryEmit(it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            _inboundText.tryEmit(text)
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
