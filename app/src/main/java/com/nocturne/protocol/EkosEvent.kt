package com.nocturne.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Typed inbound pushes M2 understands, decoded from an [Envelope]'s
 * `payload` once dispatched on `type` by [EkosEventCodec]. Not a polymorphic
 * hierarchy (no `@Serializable` on the sealed interface itself) — dispatch is
 * manual, keyed by the wire's `type` string, since the wire has no
 * discriminator field of its own.
 *
 * Only the pushes M2's bootstrap/Session-tab-live-pushes scope needs are
 * modeled here (see EkosRemote-Client-Guide.md "get_states" burst). Anything
 * else — `get_profiles`/`get_devices` (rich ~20-field shapes, M3's job to
 * model), `capture_get_sequences`, align settings payloads, the full
 * ~230-command set — falls through to [Raw] rather than being over-modeled
 * before it's needed.
 */
sealed interface EkosEvent {
    @Serializable
    data class NewConnectionState(val connected: Boolean, val online: Boolean) : EkosEvent

    @Serializable
    data class NewCaptureState(val status: String) : EkosEvent

    @Serializable
    data class NewMountState(
        val status: String,
        val target: String,
        val slewRate: Int,
        val pierSide: Int,
    ) : EkosEvent

    @Serializable
    data class NewFocusState(val status: String) : EkosEvent

    @Serializable
    data class NewGuideState(val status: String) : EkosEvent

    @Serializable
    data class NewAlignState(val status: String) : EkosEvent

    @Serializable
    data class NewPolarState(val stage: String, val enabled: Boolean, val message: String) : EkosEvent

    /** Anything not decoded above — unrecognized `type`, or a shape that failed to parse. */
    data class Raw(val type: String, val payload: JsonElement) : EkosEvent
}
