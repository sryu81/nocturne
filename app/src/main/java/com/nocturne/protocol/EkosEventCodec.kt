package com.nocturne.protocol

import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/** Decodes/encodes the wire [Envelope] format, dispatching to typed [EkosEvent]s. */
object EkosEventCodec {

    /**
     * Parses one inbound text frame into a typed [EkosEvent]. Never throws —
     * an unrecognized `type`, or a recognized `type` whose payload doesn't
     * match the expected shape, both fall back to [EkosEvent.Raw] rather than
     * crashing the socket's read loop. The wire has no formal error envelope
     * to lean on (EkosRemote-Command-Reference.md §5), so decode failures
     * must degrade gracefully, not propagate.
     */
    fun decode(text: String): EkosEvent {
        val envelope = try {
            protocolJson.decodeFromString(Envelope.serializer(), text)
        } catch (e: SerializationException) {
            return EkosEvent.Raw(type = "<unparsable>", payload = JsonObject(emptyMap()))
        }
        return try {
            when (envelope.type) {
                "new_connection_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewConnectionState>(envelope.payload)
                "new_capture_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewCaptureState>(envelope.payload)
                "new_mount_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewMountState>(envelope.payload)
                "new_focus_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewFocusState>(envelope.payload)
                "new_guide_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewGuideState>(envelope.payload)
                "new_align_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewAlignState>(envelope.payload)
                "new_polar_state" -> protocolJson.decodeFromJsonElement<EkosEvent.NewPolarState>(envelope.payload)
                else -> EkosEvent.Raw(envelope.type, envelope.payload)
            }
        } catch (e: SerializationException) {
            EkosEvent.Raw(envelope.type, envelope.payload)
        }
    }

    /** Builds one outbound text frame for [type]/[payload] (defaults to an empty object). */
    fun encode(type: String, payload: JsonElement = JsonObject(emptyMap())): String =
        protocolJson.encodeToString(Envelope(type, payload))
}
