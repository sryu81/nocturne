package com.nocturne.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Wire envelope, both directions — `{"type": "<command>", "payload": {...}}`
 * (EkosRemote-Client-Guide.md §"Envelope"). `payload` stays a raw [JsonElement]
 * since its shape varies per command (object/array/string/bool) — decode it
 * against a type-specific shape only after dispatching on [type].
 */
@Serializable
data class Envelope(val type: String, val payload: JsonElement)

/**
 * Shared codec instance. `ignoreUnknownKeys` is essential — M2 only models a
 * handful of the ~230 real commands/pushes, and every push carries fields
 * this app doesn't read yet.
 */
val protocolJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}
