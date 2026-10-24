package com.nocturne.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Wire envelope, both directions: `{"type": "<command>", "payload": ...}` (docs/PROTOCOL.md).
 * `payload` stays a raw [JsonElement] because its shape varies per command
 * (object/array/string/bool); decode it after dispatching on [type].
 */
@Serializable
data class Envelope(val type: String, val payload: JsonElement)

/** Shared codec. `ignoreUnknownKeys` is required: models are curated subsets of what Ekos sends. */
val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
